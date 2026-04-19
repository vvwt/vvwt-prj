package de.vvwt.dispatcher.result;

import de.vvwt.dispatcher.cache.ResultsCacheService;
import de.vvwt.dispatcher.crypto.Ed25519Verifier;
import de.vvwt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.dispatcher.identity.KeyRegistration;
import de.vvwt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.dispatcher.job.JobRecord;
import de.vvwt.dispatcher.job.JobRepository;
import de.vvwt.dispatcher.packet.PacketRecord;
import de.vvwt.dispatcher.packet.PacketRepository;
import de.vvwt.worker.score.VarietyScorer;
import de.vvwt.worker.types.StructuralFingerprint;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core business logic for {@code POST /submit-result} (E01S08 AC1–AC12).
 *
 * <h2>Protocol Summary</h2>
 *
 * <ol>
 *   <li>Validate request fields (AC1, AC11).
 *   <li>Parse {@code bestScore} from decimal string (AC1).
 *   <li>Look up packet and worker key; enforce assignment check (AC6) and deadline (AC7).
 *   <li>Verify Ed25519 signature over 72-byte canonical bytes (AC2).
 *   <li>If packet is already {@code "done"}: log late result (AC4, AC5), return late response.
 *   <li>If packet is {@code "assigned"} to this worker: accept first result (AC3).
 *   <li>After first result: check whether this was the last packet; if so, finalize the job (AC9,
 *       AC10).
 * </ol>
 *
 * <p>See Story E01S08 and DEC-6.
 */
@Service
public class SubmitResultService {

    private static final Logger log = LoggerFactory.getLogger(SubmitResultService.class);

    private final PacketRepository packetRepository;
    private final JobRepository jobRepository;
    private final KeyRegistrationRepository keyRepository;
    private final LateResultRepository lateResultRepository;
    private final ResultsCacheService cacheService;

    public SubmitResultService(
            PacketRepository packetRepository,
            JobRepository jobRepository,
            KeyRegistrationRepository keyRepository,
            LateResultRepository lateResultRepository,
            ResultsCacheService cacheService) {
        this.packetRepository = packetRepository;
        this.jobRepository = jobRepository;
        this.keyRepository = keyRepository;
        this.lateResultRepository = lateResultRepository;
        this.cacheService = cacheService;
    }

    // -------------------------------------------------------------------------
    // Exception types for HTTP status mapping
    // -------------------------------------------------------------------------

    /** 400 Bad Request — malformed request. */
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    /** 401 Unauthorized — signature verification failed. */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    /** 404 Not Found — unknown packetId. */
    public static class PacketNotFoundException extends RuntimeException {
        public PacketNotFoundException(String message) {
            super(message);
        }
    }

    /** 409 Conflict — not assigned to this worker. */
    public static class NotAssignedException extends RuntimeException {
        public NotAssignedException(String message) {
            super(message);
        }
    }

    /** 410 Gone — deadline exceeded. */
    public static class DeadlineExceededException extends RuntimeException {
        private final Instant deadline;

        public DeadlineExceededException(String message, Instant deadline) {
            super(message);
            this.deadline = deadline;
        }

        public Instant getDeadline() {
            return deadline;
        }
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Processes a submit-result call.
     *
     * @param req the parsed request body
     * @return the outcome (first result, late result, or duplicate)
     * @throws BadRequestException if required fields are missing or invalid
     * @throws PacketNotFoundException if {@code packetId} is unknown
     * @throws UnauthorizedException if signature verification fails
     * @throws NotAssignedException if this worker was never assigned this packet
     * @throws DeadlineExceededException if the submission arrived after the deadline
     */
    @Transactional
    public SubmitResultResponse process(SubmitResultRequest req) {
        // --- Validate required fields (AC1, AC11) ---
        validateRequest(req);

        // Parse bestScore from decimal string (AC1)
        double bestScore = parseBestScore(req.bestScore());

        // --- Load packet (AC11: unknown → 404) ---
        PacketRecord packet =
                packetRepository
                        .findById(req.packetId())
                        .orElseThrow(
                                () ->
                                        new PacketNotFoundException(
                                                "Unknown packetId: " + req.packetId()));

        // --- Load worker key (AC2 signature, AC6 assignment check) ---
        KeyRegistration key =
                keyRepository
                        .findById(req.workerKeyId())
                        .orElseThrow(
                                () ->
                                        new UnauthorizedException(
                                                "Unknown workerKeyId: " + req.workerKeyId()));

        Instant now = Instant.now();

        // --- Signature verification (AC2) ---
        verifySignature(req, bestScore, key);

        // --- Late result: packet already done ---
        if ("done".equals(packet.getStatus())) {
            return handleLateResult(packet, req, bestScore, now);
        }

        // --- Assignment check (AC6): packet must be assigned to THIS worker ---
        if (!"assigned".equals(packet.getStatus())
                || !req.workerKeyId().equals(packet.getAssignedTo())) {
            throw new NotAssignedException(
                    "Packet " + req.packetId() + " is not assigned to worker " + req.workerKeyId());
        }

        // --- Deadline check (AC7) ---
        if (packet.getDeadline() != null && now.isAfter(packet.getDeadline())) {
            throw new DeadlineExceededException(
                    "Deadline exceeded for packet " + req.packetId(), packet.getDeadline());
        }

        // --- Accept first result (AC3) ---
        packet.acceptFirstResult(req.workerKeyId(), req.bestRank(), bestScore);
        packetRepository.save(packet);

        log.info(
                "submit-result: first result accepted packetId={} jobId={} workerKeyId={}"
                        + " bestRank={} bestScore={}",
                req.packetId(),
                req.jobId(),
                req.workerKeyId(),
                req.bestRank(),
                bestScore);

        // --- Job finalization check (AC9) ---
        checkAndFinalizeJob(packet.getJobId());

        return SubmitResultResponse.ofFirstResult();
    }

    // -------------------------------------------------------------------------
    // Signature verification (AC2) — 72-byte canonical layout
    // -------------------------------------------------------------------------

    /**
     * Verifies the Ed25519 signature over the 72-byte canonical byte layout (AC2).
     *
     * <p>Canonical bytes (72 total, all big-endian):
     *
     * <ol>
     *   <li>{@code packetId} — 16 bytes UUID big-endian
     *   <li>{@code jobId} — 16 bytes UUID big-endian
     *   <li>{@code bestRank} — 8 bytes long big-endian
     *   <li>{@code bestScoreBits} — 8 bytes from {@code Double.doubleToLongBits(bestScore)}
     *       big-endian
     *   <li>{@code permutationsScored} — 8 bytes long big-endian
     *   <li>{@code workerKeyId} — 16 bytes UUID big-endian
     * </ol>
     *
     * <p>Ed25519 signs these 72 bytes DIRECTLY per RFC 8032 — NO pre-hashing.
     */
    static byte[] buildCanonicalBytes72(
            UUID packetId,
            UUID jobId,
            long bestRank,
            double bestScore,
            long permutationsScored,
            UUID workerKeyId) {
        ByteBuffer buf = ByteBuffer.allocate(72).order(ByteOrder.BIG_ENDIAN);
        putUuid(buf, packetId); // 16 bytes
        putUuid(buf, jobId); // 16 bytes
        buf.putLong(bestRank); //  8 bytes
        buf.putLong(Double.doubleToLongBits(bestScore)); //  8 bytes
        buf.putLong(permutationsScored); //  8 bytes
        putUuid(buf, workerKeyId); // 16 bytes
        // Total: 72 bytes
        return buf.array();
    }

    private static void putUuid(ByteBuffer buf, UUID uuid) {
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }

    private void verifySignature(SubmitResultRequest req, double bestScore, KeyRegistration key) {
        byte[] canonical =
                buildCanonicalBytes72(
                        req.packetId(), req.jobId(),
                        req.bestRank(), bestScore,
                        req.permutationsScored(), req.workerKeyId());

        byte[] sigBytes;
        try {
            sigBytes = Base64.getDecoder().decode(req.signature());
        } catch (IllegalArgumentException badBase64) {
            throw new BadRequestException(
                    "signature is not valid Base64: " + badBase64.getMessage());
        }

        try {
            Ed25519Verifier.verify(key.getPublicKeyBytes(), canonical, sigBytes);
        } catch (InvalidSignatureException sigFail) {
            throw new UnauthorizedException(
                    "Signature verification failed: " + sigFail.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Late result handling (AC4, AC5)
    // -------------------------------------------------------------------------

    /**
     * Handles a submission for a packet that is already {@code "done"} (AC4, AC5).
     *
     * <p>Checks whether the late result matches the first result. If not, emits a WARN log (AC5
     * tamper-detection signal). Always logs to the {@code late_results} table. Returns a duplicate
     * response if the same worker submits the same result again.
     */
    private SubmitResultResponse handleLateResult(
            PacketRecord packet, SubmitResultRequest req, double bestScore, Instant now) {
        boolean rankMatches =
                packet.getFirstBestRank() != null && packet.getFirstBestRank() == req.bestRank();
        boolean scoreMatches =
                packet.getFirstBestScore() != null
                        && Double.compare(packet.getFirstBestScore(), bestScore) == 0;
        boolean workerMatches = req.workerKeyId().equals(packet.getFirstWorkerKeyId());
        boolean matchesFirst = rankMatches && scoreMatches;

        // Duplicate detection: same worker, same result (AC11)
        if (workerMatches && matchesFirst) {
            log.info(
                    "submit-result: duplicate from first worker packetId={} workerKeyId={}",
                    req.packetId(),
                    req.workerKeyId());
            return SubmitResultResponse.ofDuplicate();
        }

        // Tamper-detection signal (AC5): diverging (bestRank, bestScore)
        if (!matchesFirst) {
            log.warn(
                    "submit-result: TAMPER SIGNAL late result diverges from first — "
                            + "packetId={} jobId={} firstWorkerKeyId={} lateWorkerKeyId={} "
                            + "firstResult={{rank={}, score={}}} lateResult={{rank={}, score={}}}",
                    req.packetId(),
                    req.jobId(),
                    packet.getFirstWorkerKeyId(),
                    req.workerKeyId(),
                    packet.getFirstBestRank(),
                    packet.getFirstBestScore(),
                    req.bestRank(),
                    bestScore);
        } else {
            log.info(
                    "submit-result: late result matches first packetId={} workerKeyId={}",
                    req.packetId(),
                    req.workerKeyId());
        }

        // Persist late result (AC4)
        LateResult lateResult =
                new LateResult(
                        req.packetId(),
                        req.jobId(),
                        req.workerKeyId(),
                        req.bestRank(),
                        bestScore,
                        now,
                        matchesFirst);
        lateResultRepository.save(lateResult);

        return SubmitResultResponse.ofLateResult();
    }

    // -------------------------------------------------------------------------
    // Job finalization (AC9, AC10)
    // -------------------------------------------------------------------------

    /**
     * Checks whether all packets for the job are done; if so, finalizes the job.
     *
     * <p>Finalization is deterministic (AC10): lowest {@code bestScore} wins; on tie, lowest {@code
     * bestRank} wins (Brief D-3). The result is written to the results cache (E01S09 AC6).
     *
     * @param jobId the job to check
     */
    private void checkAndFinalizeJob(UUID jobId) {
        JobRecord job =
                jobRepository
                        .findById(jobId)
                        .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

        // Only finalize when every non-failed packet is done and no packets are pending/assigned
        long pendingCount = packetRepository.countByJobIdAndStatus(jobId, "pending");
        long assignedCount = packetRepository.countByJobIdAndStatus(jobId, "assigned");

        if (pendingCount > 0 || assignedCount > 0) {
            // Job is not complete yet
            return;
        }

        // All packets are either done or failed — attempt finalization
        List<PacketRecord> donePacketList = packetRepository.findDonePacketsByJobId(jobId);
        if (donePacketList.isEmpty()) {
            // All packets failed — job cannot be finalized with results
            log.warn("submit-result: job {} has no done packets — cannot finalize", jobId);
            return;
        }

        // Compute global best: lowest score wins; tie-break lowest rank (AC9, Brief D-3)
        PacketRecord globalBest =
                donePacketList.stream()
                        .filter(p -> p.getFirstBestRank() != null && p.getFirstBestScore() != null)
                        .min(
                                Comparator.comparingDouble(PacketRecord::getFirstBestScore)
                                        .thenComparingLong(PacketRecord::getFirstBestRank))
                        .orElse(null);

        if (globalBest == null) {
            log.warn("submit-result: job {} done packets have no first-result data", jobId);
            return;
        }

        // Write to cache (E01S09 AC6)
        // Version constants: SCORE_FN_VERSION and CANONICALIZATION_VERSION from worker-lib
        int scoreFnVersion = VarietyScorer.SCORE_FN_VERSION;
        int canonicalizationVersion = StructuralFingerprint.CANONICALIZATION_VERSION;

        cacheService.write(
                job.getFingerprint(),
                scoreFnVersion,
                canonicalizationVersion,
                globalBest.getFirstBestRank(),
                globalBest.getFirstBestScore(),
                /* n= */ extractN(job),
                jobId);

        // Transition job to done
        job.setStatus("done");
        jobRepository.save(job);

        log.info(
                "submit-result: job {} finalized — bestRank={} bestScore={}",
                jobId,
                globalBest.getFirstBestRank(),
                globalBest.getFirstBestScore());
    }

    /**
     * Extracts the number of avatars ({@code n}) from the job's canonical phase definition.
     *
     * <p>For Phase 1, {@code n} is the team count from the canonical form. This is used as the
     * {@code n} parameter in the cache write (E01S09). A simplified extraction is used here — reads
     * the row count from the job's canonical phaseDef JSON length signature. For correctness in
     * Phase 1, we obtain {@code n} from the packet count total.
     *
     * <p>Note: the canonical phase definition already contains {@code rowCount} / group count which
     * is exactly {@code n}. Since {@link JobRecord} stores the canonical JSON, we use the packet
     * count as a proxy: {@code packetCount} was set at decomposition time. The actual {@code n}
     * (avatar count) from the {@code CanonicalPhaseDef} would require parsing the JSON; to avoid a
     * JSON parse dependency here, we delegate to the stored job row.
     */
    private int extractN(JobRecord job) {
        // The rowCount field in the phaseDef encodes N (the optimizer's team count).
        // For Phase 1, the packetCount was computed from N! using Lehmer encoding.
        // We stored N in the CanonicalPhaseDef JSON. Parse a minimal field here.
        // Fallback: 0 (acceptable for cache key; n is informational, not part of the PK).
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(job.getCanonicalPhaseDefJson());
            com.fasterxml.jackson.databind.JsonNode rowCount = root.get("rowCount");
            return rowCount != null ? rowCount.asInt(0) : 0;
        } catch (Exception parseError) {
            log.warn(
                    "Could not parse rowCount from canonicalPhaseDefJson for job {}: {}",
                    job.getJobId(),
                    parseError.getMessage());
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    private void validateRequest(SubmitResultRequest req) {
        if (req.packetId() == null) throw new BadRequestException("packetId is required");
        if (req.jobId() == null) throw new BadRequestException("jobId is required");
        if (req.bestRank() == null) throw new BadRequestException("bestRank is required");
        if (req.bestScore() == null || req.bestScore().isBlank())
            throw new BadRequestException("bestScore is required");
        if (req.permutationsScored() == null)
            throw new BadRequestException("permutationsScored is required");
        if (req.wallClockNanos() == null)
            throw new BadRequestException("wallClockNanos is required");
        if (req.workerKeyId() == null) throw new BadRequestException("workerKeyId is required");
        if (req.signature() == null || req.signature().isBlank())
            throw new BadRequestException("signature is required");
    }

    static double parseBestScore(String bestScoreString) {
        try {
            return Double.parseDouble(bestScoreString);
        } catch (NumberFormatException parseError) {
            throw new SubmitResultService.BadRequestException(
                    "bestScore is not a valid double: " + bestScoreString);
        }
    }
}
