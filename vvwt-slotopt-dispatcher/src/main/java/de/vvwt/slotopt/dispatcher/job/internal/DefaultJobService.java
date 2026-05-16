// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.dispatcher.audit.AuditService;
import de.vvwt.slotopt.dispatcher.cache.CachedResult;
import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.job.JobService;
import de.vvwt.slotopt.dispatcher.job.SubmitJobRequest;
import de.vvwt.slotopt.dispatcher.job.SubmitJobResponse;
import de.vvwt.slotopt.dispatcher.packet.PacketDecomposerService;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link JobService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code job.internal}. All consumers reference {@link
 * JobService} (the public interface), never this class directly (DEC-36).
 *
 * <p>Named {@code DefaultJobService} per DEC-35 naming canon (no {@code I}-prefix on the interface;
 * {@code Default*} prefix on the implementation).
 *
 * <p>Behavior per AC-JOB-SERVICE:
 *
 * <ol>
 *   <li>Validates phase non-null and rowCount ∈ [1, 15] (Phase 1 N-cap per spec section (b))
 *   <li>Computes structural fingerprint via {@link StructuralFingerprint#transform(RawPhaseDef)}
 *   <li>Serializes {@link de.vvwt.slotopt.worker.types.JobDef} to JSON
 *   <li>Generates a {@code jobId} UUID
 *   <li>Persists a {@link JobRecord} with status {@code RECEIVED}
 *   <li>Decomposes the job into packets via {@link PacketDecomposerService} and persists them via
 *       {@link PacketRepository}; advances {@code JobRecord.status} to {@code DECOMPOSED}. The
 *       decompose + save-packets + status-update is atomic within a single transaction: if
 *       decomposition or packet persistence fails, no partial state is left (E60S01 /
 *       AC-ERR-DECOMPOSE-FAILURE-DETERMINISTIC).
 *   <li>Returns a {@link SubmitJobResponse}
 * </ol>
 *
 * <p>Cache-hit short-circuit (AC-CACHE-READ-SHORT-CIRCUIT, E37S10 retrofit): if a cached result
 * exists for this fingerprint + V1 game mode, return immediately without persisting a JobRecord or
 * decomposing into packets.
 *
 * <p>Audit: records {@code JOB_SUBMITTED} via {@link AuditService} after successful persistence.
 * Audit failures do NOT propagate — absorbed per AC-AUDIT-FAILURE-MODE (consistent with E37S06
 * identity package audit pattern).
 *
 * <p>Story: E37S07 + E37S10 (AC-CACHE-READ-SHORT-CIRCUIT retrofit) + E60S01 (decomposition wiring);
 * AC-JOB-SERVICE; DEC-9, DEC-35, DEC-36
 */
@Service
public class DefaultJobService implements JobService {

    private static final Logger LOG = Logger.getLogger(DefaultJobService.class.getName());
    private static final int PHASE1_N_CAP = 15;

    /**
     * V1 game-mode discriminator for cache lookups per DEC-9 / AC-CACHE-READ-SHORT-CIRCUIT. Must
     * match the constant used by {@link
     * de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService}.
     */
    private static final String V1_GAME_MODE = "default";

    private final JobRepository jobRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final ResultsCacheService resultsCacheService;
    private final PacketDecomposerService packetDecomposerService;
    private final PacketRepository packetRepository;

    public DefaultJobService(
            JobRepository jobRepository,
            AuditService auditService,
            ObjectMapper objectMapper,
            ResultsCacheService resultsCacheService,
            PacketDecomposerService packetDecomposerService,
            PacketRepository packetRepository) {
        this.jobRepository = jobRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.resultsCacheService = resultsCacheService;
        this.packetDecomposerService = packetDecomposerService;
        this.packetRepository = packetRepository;
    }

    @Override
    @Transactional
    public SubmitJobResponse submitJob(SubmitJobRequest request) {
        // (1) Validate phase
        RawPhaseDef phase = request.phase();
        if (phase == null) {
            throw new IllegalArgumentException("request.phase must not be null");
        }
        if (phase.rowCount() < 1 || phase.rowCount() > PHASE1_N_CAP) {
            throw new IllegalArgumentException(
                    "N-cap exceeded: rowCount "
                            + phase.rowCount()
                            + " is outside the Phase 1 range [1, "
                            + PHASE1_N_CAP
                            + "]. rowCount ∈ {16, 17} requires Phase 2 (not yet shipped).");
        }

        // (2) Compute structural fingerprint (ensures we validate the phase topology)
        byte[] fingerprint = StructuralFingerprint.transform(phase).fingerprint();

        // (2a) Cache-read short-circuit (AC-CACHE-READ-SHORT-CIRCUIT, E37S10 retrofit):
        // If a cached result exists for this fingerprint + V1 game mode, return immediately
        // without persisting a JobRecord or decomposing into packets.
        // Cache lookup failure is treated as a cache miss (absorbed exception) per the AC.
        try {
            Optional<CachedResult> cacheHit = resultsCacheService.lookup(fingerprint, V1_GAME_MODE);
            if (cacheHit.isPresent()) {
                UUID syntheticJobId = UUID.randomUUID();
                return new SubmitJobResponse(syntheticJobId, null, true);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Cache lookup failed — treating as miss: {0}", e.getMessage());
        }

        // (3) Serialize JobDef to JSON
        String jobDefJson;
        try {
            jobDefJson = objectMapper.writeValueAsString(request.jobDef());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Failed to serialize JobDef to JSON: " + exception.getMessage(), exception);
        }

        // (4) Generate jobId
        UUID jobId = UUID.randomUUID();
        Instant submittedAt = Instant.now();

        // (5) Persist JobRecord with RECEIVED status
        JobRecord record = new JobRecord();
        record.setJobId(jobId);
        record.setSubmittedAt(submittedAt);
        record.setJobDefJson(jobDefJson);
        record.setStatus("RECEIVED");
        jobRepository.save(record);

        // (5a) Decompose into packets and persist; advance status to DECOMPOSED.
        // Wrapped by @Transactional — any failure here rolls back both packet saves and the
        // initial RECEIVED row, leaving no partial state (AC-ERR-DECOMPOSE-FAILURE-DETERMINISTIC).
        List<PacketRecord> packets = packetDecomposerService.decompose(record);
        packetRepository.saveAll(packets);
        record.setStatus("DECOMPOSED");
        jobRepository.save(record);

        // Audit JOB_SUBMITTED — failure absorbed per AC-AUDIT-FAILURE-MODE
        try {
            auditService.recordEvent(
                    "JOB_SUBMITTED",
                    null, // no worker UUID in this context (submitter auth is future scope)
                    "internal", // source IP not available at service layer; controller-level audit
                    // is
                    // future scope per spec (b) — audit logs at controller level per E37S08+
                    "{\"jobId\":\"" + jobId + "\",\"status\":\"DECOMPOSED\"}");
        } catch (Exception exception) {
            // Audit failure must not block the job submission response
        }

        // (6) Return response
        return new SubmitJobResponse(jobId, submittedAt);
    }
}
