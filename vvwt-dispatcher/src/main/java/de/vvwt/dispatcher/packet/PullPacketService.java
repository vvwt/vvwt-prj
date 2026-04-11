package de.vvwt.dispatcher.packet;

import de.vvwt.dispatcher.crypto.Ed25519Verifier;
import de.vvwt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.dispatcher.identity.KeyRegistration;
import de.vvwt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.dispatcher.job.JobRecord;
import de.vvwt.dispatcher.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Authenticates a worker and atomically claims the next available pending packet.
 *
 * <h2>Authentication (AC3, AC4)</h2>
 * <ol>
 *   <li>Parse {@code signedNonce} as an ISO-8601 {@link Instant}.</li>
 *   <li>Reject if nonce is older than 60 s or more than 5 s in the future → 401.</li>
 *   <li>Lookup {@code workerKeyId} in the key registry → 401 if not found.</li>
 *   <li>Reject if role is not {@code "worker"} → 403.</li>
 *   <li>Reject if key has expired (grace window passed) → 401.</li>
 *   <li>Build canonical bytes:
 *       {@code "pull-packet"} UTF-8 (11 bytes)
 *       + {@code workerKeyId} 16-byte big-endian UUID
 *       + {@code signedNonce} UTF-8 bytes of the ISO-8601 string.
 *       Ed25519 signs these bytes directly per RFC 8032 — NO pre-hashing (AC3).</li>
 *   <li>Verify Ed25519 signature → 401 on failure.</li>
 * </ol>
 *
 * <h2>Atomic claim (AC5, AC6, AC7)</h2>
 * <ol>
 *   <li>Call {@link PacketRepository#findAndLockNextPendingPacket()} —
 *       {@code SELECT FOR UPDATE SKIP LOCKED} prevents duplicate assignment.</li>
 *   <li>If no pending packet found → return {@link PullResult.NoWork} (204).</li>
 *   <li>Update packet: {@code status='assigned'}, {@code assignedTo}, {@code assignedAt}, {@code attempts++}.</li>
 *   <li>Return {@link PullResult.Assigned} with packet + job.</li>
 * </ol>
 *
 * <p>See Story E01S07 AC3–AC7 and DEC-6.
 */
@Service
public class PullPacketService {

    private static final Logger log = LoggerFactory.getLogger(PullPacketService.class);

    /** Canonical operation prefix — exactly 11 UTF-8 bytes (AC3). */
    private static final byte[] PULL_PACKET_PREFIX = "pull-packet".getBytes(StandardCharsets.UTF_8);

    /** Maximum age of a valid nonce in seconds (AC4). */
    private static final long NONCE_MAX_AGE_SECONDS = 60L;

    /** Maximum clock skew into the future in seconds (AC4). */
    private static final long NONCE_FUTURE_TOLERANCE_SECONDS = 5L;

    private final KeyRegistrationRepository keyRepository;
    private final PacketRepository packetRepository;
    private final JobRepository jobRepository;

    @Value("${dispatcher.packet.timeout:5}")
    private long packetTimeoutMinutes;

    public PullPacketService(KeyRegistrationRepository keyRepository,
                             PacketRepository packetRepository,
                             JobRepository jobRepository) {
        this.keyRepository = keyRepository;
        this.packetRepository = packetRepository;
        this.jobRepository = jobRepository;
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /** Sealed result type returned by {@link #execute(UUID, String, String)}. */
    public sealed interface PullResult
            permits PullResult.Assigned, PullResult.NoWork {

        /** A packet was successfully claimed. */
        record Assigned(PacketRecord packet, JobRecord job, Instant deadline) implements PullResult {}

        /** No pending packets available (204 No Content). */
        record NoWork() implements PullResult {}
    }

    // -------------------------------------------------------------------------
    // Exception types for HTTP status mapping in the controller
    // -------------------------------------------------------------------------

    /** Thrown on authentication failure: unknown key, expired key, bad signature, stale nonce. */
    public static class UnauthorizedException extends RuntimeException {
        private final String errorCode;

        public UnauthorizedException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        /** The machine-readable error code for the JSON response body (AC9). */
        public String getErrorCode() { return errorCode; }
    }

    /** Thrown when a non-worker key calls pull-packet (403). */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Authenticates the request and claims the next available pending packet in a single
     * transaction.
     *
     * @param workerKeyId  the worker's registered key UUID
     * @param base64Sig    Base64-encoded Ed25519 signature (AC3)
     * @param signedNonce  ISO-8601 instant string that was signed (AC3, AC4)
     * @return {@link PullResult.Assigned} or {@link PullResult.NoWork}
     * @throws IllegalArgumentException if the request is malformed (400)
     * @throws UnauthorizedException    if authentication fails (401)
     * @throws ForbiddenException       if the key role is not {@code "worker"} (403)
     */
    @Transactional
    public PullResult execute(UUID workerKeyId, String base64Sig, String signedNonce) {
        // --- Authenticate ---
        KeyRegistration key = authenticate(workerKeyId, base64Sig, signedNonce);

        // --- Claim next pending packet ---
        return packetRepository.findAndLockNextPendingPacket()
                .map(packet -> {
                    Instant now = Instant.now();
                    Instant deadline = now.plusSeconds(packetTimeoutMinutes * 60L);
                    packet.assign(workerKeyId, now, deadline);
                    packetRepository.save(packet);

                    JobRecord job = jobRepository.findById(packet.getJobId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Job not found for packet " + packet.getPacketId()
                                    + ": jobId=" + packet.getJobId()));

                    log.info("pull-packet: assigned packetId={} jobId={} workerKeyId={} deadline={}",
                            packet.getPacketId(), packet.getJobId(), workerKeyId, deadline);
                    return (PullResult) new PullResult.Assigned(packet, job, deadline);
                })
                .orElseGet(PullResult.NoWork::new);
    }

    // -------------------------------------------------------------------------
    // Authentication
    // -------------------------------------------------------------------------

    /**
     * Validates the nonce window, looks up the key, checks the role,
     * and verifies the Ed25519 signature (AC3, AC4).
     *
     * @param workerKeyId the claimed worker key UUID
     * @param base64Sig   Base64-encoded signature
     * @param signedNonce ISO-8601 nonce string
     * @return the valid {@link KeyRegistration}
     * @throws IllegalArgumentException if the payload is malformed
     * @throws UnauthorizedException    if authentication fails
     * @throws ForbiddenException       if the key role is not {@code "worker"}
     */
    KeyRegistration authenticate(UUID workerKeyId, String base64Sig, String signedNonce) {
        if (workerKeyId == null) {
            throw new IllegalArgumentException("workerKeyId is required");
        }
        if (base64Sig == null || base64Sig.isBlank()) {
            throw new IllegalArgumentException("signature is required");
        }
        if (signedNonce == null || signedNonce.isBlank()) {
            throw new IllegalArgumentException("signedNonce is required");
        }

        // 1. Parse nonce as ISO-8601 instant
        Instant nonce;
        try {
            nonce = Instant.parse(signedNonce);
        } catch (Exception parseError) {
            throw new IllegalArgumentException(
                    "signedNonce is not a valid ISO-8601 instant: " + signedNonce);
        }

        // 2. Nonce window check (AC4)
        Instant now = Instant.now();
        Instant earliest = now.minusSeconds(NONCE_MAX_AGE_SECONDS);
        Instant latest = now.plusSeconds(NONCE_FUTURE_TOLERANCE_SECONDS);
        if (nonce.isBefore(earliest) || nonce.isAfter(latest)) {
            throw new UnauthorizedException("stale-or-future-timestamp",
                    "signedNonce is outside the accepted window [now-60s, now+5s]: " + signedNonce);
        }

        // 3. Key lookup (AC4)
        KeyRegistration key = keyRepository.findById(workerKeyId)
                .orElseThrow(() -> new UnauthorizedException("unauthorized",
                        "Unknown workerKeyId: " + workerKeyId));

        // 4. Role check (AC4)
        if (!"worker".equals(key.getRole())) {
            throw new ForbiddenException(
                    "Key " + workerKeyId + " has role '" + key.getRole()
                    + "'; only 'worker' keys may call pull-packet");
        }

        // 5. Key validity (grace window check) (AC4)
        if (!key.isValidForVerificationAt(now)) {
            throw new UnauthorizedException("unauthorized",
                    "Key " + workerKeyId + " has expired (grace window passed)");
        }

        // 6. Build canonical bytes and verify signature (AC3)
        byte[] canonicalBytes = buildCanonicalBytes(workerKeyId, signedNonce);
        byte[] sigBytes = decodeBase64(base64Sig);
        try {
            Ed25519Verifier.verify(key.getPublicKeyBytes(), canonicalBytes, sigBytes);
        } catch (InvalidSignatureException signatureFail) {
            throw new UnauthorizedException("unauthorized",
                    "Signature verification failed for workerKeyId " + workerKeyId
                    + ": " + signatureFail.getMessage());
        }

        return key;
    }

    /**
     * Builds the canonical byte sequence for Ed25519 signature verification (AC3):
     * {@code "pull-packet"} (11 UTF-8 bytes) + {@code workerKeyId} (16 bytes UUID big-endian)
     * + {@code signedNonce} (UTF-8 bytes of the ISO-8601 string).
     *
     * <p>Ed25519 signs these bytes directly per RFC 8032 — no pre-hashing.
     */
    static byte[] buildCanonicalBytes(UUID workerKeyId, String signedNonce) {
        byte[] nonceBytes = signedNonce.getBytes(StandardCharsets.UTF_8);

        // UUID → 16 bytes big-endian (RFC 4122 wire format)
        ByteBuffer uuidBuffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
        uuidBuffer.putLong(workerKeyId.getMostSignificantBits());
        uuidBuffer.putLong(workerKeyId.getLeastSignificantBits());
        byte[] uuidBytes = uuidBuffer.array();

        // Concatenate: prefix (11) + uuid (16) + nonce (variable)
        byte[] result = new byte[PULL_PACKET_PREFIX.length + uuidBytes.length + nonceBytes.length];
        System.arraycopy(PULL_PACKET_PREFIX, 0, result, 0, PULL_PACKET_PREFIX.length);
        System.arraycopy(uuidBytes, 0, result, PULL_PACKET_PREFIX.length, uuidBytes.length);
        System.arraycopy(nonceBytes, 0, result, PULL_PACKET_PREFIX.length + uuidBytes.length, nonceBytes.length);
        return result;
    }

    private static byte[] decodeBase64(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("signature is not valid Base64: " + e.getMessage(), e);
        }
    }
}
