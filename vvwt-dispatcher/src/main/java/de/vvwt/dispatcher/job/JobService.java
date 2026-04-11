package de.vvwt.dispatcher.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.dispatcher.cache.CachedResult;
import de.vvwt.dispatcher.cache.ResultsCacheService;
import de.vvwt.dispatcher.crypto.Ed25519Verifier;
import de.vvwt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.dispatcher.crypto.JcsCanonicalizer;
import de.vvwt.dispatcher.identity.KeyRegistration;
import de.vvwt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.worker.score.VarietyScorer;
import de.vvwt.worker.types.RawPhaseDef;
import de.vvwt.worker.types.StructuralFingerprint;
import de.vvwt.worker.types.TransformResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Business logic for {@code POST /submit-job}.
 *
 * <h2>Processing sequence (per ACs)</h2>
 * <ol>
 *   <li>Resolve {@code submitterKeyId} → {@link KeyRegistration} (AC5, AC7)</li>
 *   <li>Verify role = {@code "submitter"} (AC7)</li>
 *   <li>Check key validity (not superseded + grace expired) (AC6)</li>
 *   <li>Validate {@code phaseDef.rowCount}: 1–15 accepted; 16–17 → 422; &lt;1 or &gt;17 → 400 (AC8)</li>
 *   <li>JCS-canonicalize the {@code phaseDef} JSON field (AC6)</li>
 *   <li>Verify Ed25519 signature against canonical bytes (AC6)</li>
 *   <li>Parse {@code phaseDef} into {@link RawPhaseDef} and call
 *       {@link StructuralFingerprint#transform(RawPhaseDef)} (AC9, AC10)</li>
 *   <li>Cache lookup via {@link ResultsCacheService} (AC9)</li>
 *   <li>Cache hit → 202 cached (AC9); cache miss → create job row + 202 queued (AC10)</li>
 * </ol>
 *
 * <p>Database errors are surfaced as {@link DatabaseException} (transient → 503) or
 * {@link RuntimeException} (persistent → 500) per AC11.
 *
 * <p>See Story E01S06 AC5–AC12 and DEC-6, DEC-9.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** Phase 1 N-cap: maximum row count accepted without error (AC8). */
    private static final int N_CAP = 15;

    /** Maximum rowCount that triggers a "Phase 2 required" error (AC8). */
    private static final int N_CAP_PHASE2_MAX = 17;

    private final KeyRegistrationRepository keyRepository;
    private final JobRepository jobRepository;
    private final ResultsCacheService cacheService;
    private final ObjectMapper objectMapper;

    public JobService(KeyRegistrationRepository keyRepository,
                      JobRepository jobRepository,
                      ResultsCacheService cacheService,
                      ObjectMapper objectMapper) {
        this.keyRepository = keyRepository;
        this.jobRepository = jobRepository;
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
    }

    /**
     * Result type returned by {@link #submit} to allow the controller to choose
     * the appropriate HTTP status and audit outcome.
     */
    public sealed interface SubmitResult
            permits SubmitResult.Queued, SubmitResult.Cached {

        /** Job was accepted and queued for decomposition (AC10). */
        record Queued(UUID jobId) implements SubmitResult {}

        /** Job matched the cache — result returned immediately (AC9). */
        record Cached(UUID jobId, CachedResult cachedResult) implements SubmitResult {}
    }

    // -------------------------------------------------------------------------
    // Exception types for controller-level HTTP status mapping
    // -------------------------------------------------------------------------

    /** Thrown when the submitterKeyId is unknown or the key has expired (401). */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) { super(message); }
    }

    /** Thrown when the key's role is not 'submitter' (403). */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }

    /** Thrown when rowCount exceeds 15 but is ≤ 17 (422). */
    public static class NCapExceededException extends RuntimeException {
        private final int rowCount;
        public NCapExceededException(int rowCount) {
            super("N-cap exceeded: rowCount=" + rowCount);
            this.rowCount = rowCount;
        }
        public int getRowCount() { return rowCount; }
    }

    /** Thrown for a transient database error (503). */
    public static class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) { super(message, cause); }
    }

    // -------------------------------------------------------------------------
    // Main submission logic
    // -------------------------------------------------------------------------

    /**
     * Processes a {@code submit-job} request.
     *
     * @param request the parsed request
     * @return {@link SubmitResult.Queued} or {@link SubmitResult.Cached}
     * @throws UnauthorizedException  submitterKeyId unknown, key expired, or signature invalid (AC6)
     * @throws ForbiddenException     key role is not 'submitter' (AC7)
     * @throws IllegalArgumentException malformed request (AC5, AC8, AC11)
     * @throws NCapExceededException  rowCount 16–17 (AC8)
     */
    @Transactional
    public SubmitResult submit(SubmitJobRequest request) {
        // --- AC5: validate required fields ---
        if (request.submitterKeyId() == null) {
            throw new IllegalArgumentException("submitterKeyId is required");
        }
        if (request.phaseDef() == null || request.phaseDef().isBlank()) {
            throw new IllegalArgumentException("phaseDef is required");
        }
        if (request.signature() == null || request.signature().isBlank()) {
            throw new IllegalArgumentException("signature is required");
        }

        // --- AC7: resolve key and check role ---
        Instant now = Instant.now();
        Optional<KeyRegistration> keyOpt = keyRepository.findById(request.submitterKeyId());
        if (keyOpt.isEmpty()) {
            throw new UnauthorizedException(
                    "Unknown submitterKeyId: " + request.submitterKeyId());
        }
        KeyRegistration key = keyOpt.get();

        if (!"submitter".equals(key.getRole())) {
            throw new ForbiddenException(
                    "Key " + key.getKeyId() + " has role '" + key.getRole()
                    + "'; only 'submitter' keys may call submit-job (AC7)");
        }

        // --- AC6: check key validity (grace window) ---
        if (!key.isValidForVerificationAt(now)) {
            throw new UnauthorizedException(
                    "Key " + key.getKeyId() + " has been superseded and grace window has expired");
        }

        // --- AC8: parse and validate rowCount BEFORE signature check ---
        RawPhaseDef rawPhaseDef = parseRawPhaseDef(request.phaseDef());
        int rowCount = rawPhaseDef.rowCount();
        if (rowCount < 1 || rowCount > N_CAP_PHASE2_MAX) {
            throw new IllegalArgumentException(
                    "rowCount must be between 1 and " + N_CAP_PHASE2_MAX
                    + " (inclusive), got: " + rowCount);
        }
        if (rowCount > N_CAP) {
            // rowCount is 16 or 17
            throw new NCapExceededException(rowCount);
        }

        // --- AC6: JCS-canonicalize phaseDef and verify signature ---
        byte[] canonicalBytes = jcsCanonicalize(request.phaseDef());
        byte[] signatureBytes = decodeBase64Signature(request.signature());
        try {
            Ed25519Verifier.verify(key.getPublicKeyBytes(), canonicalBytes, signatureBytes);
        } catch (InvalidSignatureException signatureInvalid) {
            throw new UnauthorizedException(
                    "Signature verification failed for key " + key.getKeyId()
                    + ": " + signatureInvalid.getMessage());
        }

        // --- AC9/AC10: fingerprint + cache lookup ---
        TransformResult transform = StructuralFingerprint.transform(rawPhaseDef);
        byte[] fingerprint = transform.fingerprint();

        Optional<CachedResult> cacheHit = cacheService.lookup(
                fingerprint,
                VarietyScorer.SCORE_FN_VERSION,
                StructuralFingerprint.CANONICALIZATION_VERSION);

        if (cacheHit.isPresent()) {
            // AC9: cache hit — return immediately without creating a job row
            UUID jobId = UUID.randomUUID(); // synthetic jobId for cache-hit response (AC5)
            log.info("submit-job CACHE HIT for keyId={} phaseId={}", key.getKeyId(), rawPhaseDef.phaseId());
            return new SubmitResult.Cached(jobId, cacheHit.get());
        }

        // AC10: cache miss — create job row and queue
        UUID jobId = UUID.randomUUID();
        String canonicalPhaseDefJson = serializeCanonical(transform);
        JobRecord job = new JobRecord(
                jobId,
                key.getKeyId(),
                rawPhaseDef.phaseId(),
                request.phaseDef(),
                canonicalPhaseDefJson,
                fingerprint,
                "queued",
                now);
        jobRepository.save(job);
        log.info("submit-job QUEUED jobId={} for keyId={} phaseId={}",
                jobId, key.getKeyId(), rawPhaseDef.phaseId());
        return new SubmitResult.Queued(jobId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private RawPhaseDef parseRawPhaseDef(String phaseDefJson) {
        try {
            return objectMapper.readValue(phaseDefJson, RawPhaseDef.class);
        } catch (Exception parseError) {
            throw new IllegalArgumentException(
                    "phaseDef is not a valid RawPhaseDef: " + parseError.getMessage(), parseError);
        }
    }

    private byte[] jcsCanonicalize(String phaseDefJson) {
        try {
            return JcsCanonicalizer.canonicalize(phaseDefJson);
        } catch (IOException ioError) {
            throw new IllegalArgumentException(
                    "phaseDef is not valid JSON: " + ioError.getMessage(), ioError);
        }
    }

    private byte[] decodeBase64Signature(String base64Signature) {
        try {
            return Base64.getDecoder().decode(base64Signature);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "signature is not valid Base64: " + e.getMessage(), e);
        }
    }

    private String serializeCanonical(TransformResult transform) {
        try {
            return objectMapper.writeValueAsString(transform.canonical());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize CanonicalPhaseDef: " + e.getMessage(), e);
        }
    }
}
