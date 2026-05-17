// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PositionTuple;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.RawRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultJobService}.
 *
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S07, E60S01). Written before
 * production class.
 *
 * <p>DEC-36: this test class is in {@code job.internal} (same package as {@link
 * DefaultJobService}), so white-box access to the implementation class is permitted. However, the
 * collaborators ({@link JobRepository}, {@link AuditService}, {@link PacketDecomposerService},
 * {@link PacketRepository}) are mocked via their PUBLIC INTERFACES (different packages), per
 * DEC-36.
 *
 * <p>Story: E37S07 (original); E60S01 (decomposition wiring + status transition tests); DEC-36
 */
@ExtendWith(MockitoExtension.class)
class DefaultJobServiceTest {

    // DEC-36: mock via PUBLIC INTERFACE (JobRepository is in de.vvwt.slotopt.dispatcher.job —
    // different from de.vvwt.slotopt.dispatcher.job.internal)
    @Mock private JobRepository jobRepository;

    // DEC-36: mock via PUBLIC INTERFACE (AuditService is in de.vvwt.slotopt.dispatcher.audit)
    @Mock private AuditService auditService;

    // DEC-36: mock via PUBLIC INTERFACE (ResultsCacheService is in
    // de.vvwt.slotopt.dispatcher.cache)
    @Mock private ResultsCacheService resultsCacheService;

    // DEC-36: mock via PUBLIC INTERFACE (PacketDecomposerService is in
    // de.vvwt.slotopt.dispatcher.packet)
    @Mock private PacketDecomposerService packetDecomposerService;

    // DEC-36: mock via PUBLIC INTERFACE (PacketRepository is in de.vvwt.slotopt.dispatcher.packet)
    @Mock private PacketRepository packetRepository;

    private JobService jobService; // typed as public interface (DEC-36)

    @BeforeEach
    void setUp() {
        jobService =
                new DefaultJobService(
                        jobRepository,
                        auditService,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        resultsCacheService,
                        packetDecomposerService,
                        packetRepository);
    }

    @Test
    void submitJobPersistsJobAndReturnsResponse() {
        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        // Stub repository.save to return entity with generated id
        when(jobRepository.save(any(JobRecord.class)))
                .thenAnswer(
                        inv -> {
                            JobRecord r = inv.getArgument(0);
                            r.setId(1L);
                            return r;
                        });
        when(packetDecomposerService.decompose(any(JobRecord.class))).thenReturn(List.of());

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.jobId()).isNotNull();
        assertThat(response.submittedAt()).isNotNull();
        // save is called twice: once for RECEIVED, once for DECOMPOSED
        verify(jobRepository, org.mockito.Mockito.times(2)).save(any(JobRecord.class));
    }

    @Test
    void submitJobRecordsAuditEvent() {
        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        when(jobRepository.save(any(JobRecord.class)))
                .thenAnswer(
                        inv -> {
                            JobRecord r = inv.getArgument(0);
                            r.setId(1L);
                            return r;
                        });
        when(packetDecomposerService.decompose(any(JobRecord.class))).thenReturn(List.of());

        jobService.submitJob(request);

        verify(auditService)
                .recordEvent(eq("JOB_SUBMITTED"), isNull(), eq("internal"), anyString());
    }

    @Test
    void submitJobRejectsNullPhase() {
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, null);

        assertThatThrownBy(() -> jobService.submitJob(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitJobRejectsRowCountExceedingNcap() {
        // rowCount 16 should be rejected (N-cap enforcement)
        // Build a phase with 16 rows (each row has 1 position to keep it minimal)
        List<RawRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            rows.add(new RawRow(List.of(new PositionTuple(i, 0))));
        }
        RawPhaseDef phase = new RawPhaseDef(1, 16, rows);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        assertThatThrownBy(() -> jobService.submitJob(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("N-cap");
    }

    // -------------------------------------------------------------------------
    // AC-CACHE-READ-SHORT-CIRCUIT (E37S10 retrofit)
    // -------------------------------------------------------------------------

    /**
     * Verifies that when {@link ResultsCacheService#lookup} returns a hit for the computed
     * structural fingerprint, {@link JobService#submitJob} short-circuits:
     *
     * <ul>
     *   <li>Returns {@code cacheHit=true} in the response
     *   <li>Does NOT persist a {@link JobRecord}
     *   <li>Does NOT emit audit events (cache hit is idempotent — no state change)
     * </ul>
     *
     * <p>RED-first per DEC-22 / AC-CACHE-READ-SHORT-CIRCUIT + AC-RETROFIT-EVIDENCE (E37S10).
     */
    @Test
    void submitJob_cacheHit_returnsShortCircuitResponse() {
        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        // Stub cache hit
        CachedResult cachedResult =
                new CachedResult(
                        new byte[32],
                        "default",
                        "{\"bestRank\":1}",
                        Instant.parse("2026-04-26T10:00:00Z"));
        when(resultsCacheService.lookup(any(byte[].class), eq("default")))
                .thenReturn(Optional.of(cachedResult));

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.cacheHit()).isTrue();
        assertThat(response.jobId()).isNotNull();
        // No JobRecord persisted
        org.mockito.Mockito.verify(jobRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void submitJob_cacheMiss_proceedsNormally() {
        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        when(resultsCacheService.lookup(any(byte[].class), any())).thenReturn(Optional.empty());
        when(jobRepository.save(any(JobRecord.class)))
                .thenAnswer(
                        inv -> {
                            JobRecord r = inv.getArgument(0);
                            r.setId(1L);
                            return r;
                        });
        when(packetDecomposerService.decompose(any(JobRecord.class))).thenReturn(List.of());

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.cacheHit()).isFalse();
        assertThat(response.jobId()).isNotNull();
        // save is called twice: once for RECEIVED, once for DECOMPOSED
        verify(jobRepository, org.mockito.Mockito.times(2)).save(any(JobRecord.class));
    }

    // -------------------------------------------------------------------------
    // AC-TEST-DECOMPOSE-INVOKED-ON-SUBMIT / AC-TEST-JOB-DECOMPOSED-TRANSITION (E60S01)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-DECOMPOSE-INVOKED-ON-SUBMIT (RED-first per DEC-22 / AC-GOV-RED-FIRST):
     *
     * <p>A non-cache-hit job submitted via {@code submitJob} MUST result in {@link
     * PacketDecomposerService#decompose(JobRecord)} being called and the returned packets being
     * persisted via {@link PacketRepository#saveAll(Iterable)}.
     *
     * <p>Story: E60S01; DEC-22, DEC-36
     */
    @Test
    void submitJob_nonCacheHit_invokesDecomposerAndPersistsPackets() {
        RawPhaseDef phase = buildSmallPhase(2);
        CanonicalPhaseDef canonical =
                new CanonicalPhaseDef(2, 2, List.of(List.of(0, 1), List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        when(resultsCacheService.lookup(any(byte[].class), any())).thenReturn(Optional.empty());
        when(jobRepository.save(any(JobRecord.class)))
                .thenAnswer(
                        inv -> {
                            JobRecord r = inv.getArgument(0);
                            r.setId(1L);
                            return r;
                        });
        PacketRecord fakePacket = new PacketRecord();
        fakePacket.setPacketId(UUID.randomUUID());
        when(packetDecomposerService.decompose(any(JobRecord.class)))
                .thenReturn(List.of(fakePacket));

        jobService.submitJob(request);

        verify(packetDecomposerService).decompose(any(JobRecord.class));
        verify(packetRepository).saveAll(any());
    }

    /**
     * AC-TEST-JOB-DECOMPOSED-TRANSITION (RED-first per DEC-22 / AC-GOV-RED-FIRST):
     *
     * <p>After decomposition completes, the {@link JobRecord} persisted by {@code submitJob} MUST
     * have {@code status == "DECOMPOSED"} (not {@code "RECEIVED"}).
     *
     * <p>Story: E60S01; DEC-22, DEC-36
     */
    @Test
    void submitJob_nonCacheHit_advancesJobStatusToDecomposed() {
        RawPhaseDef phase = buildSmallPhase(2);
        CanonicalPhaseDef canonical =
                new CanonicalPhaseDef(2, 2, List.of(List.of(0, 1), List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        when(resultsCacheService.lookup(any(byte[].class), any())).thenReturn(Optional.empty());

        // Capture the saved JobRecord to verify its final status
        java.util.concurrent.atomic.AtomicReference<JobRecord> savedRecord =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(jobRepository.save(any(JobRecord.class)))
                .thenAnswer(
                        inv -> {
                            JobRecord r = inv.getArgument(0);
                            r.setId(1L);
                            savedRecord.set(r);
                            return r;
                        });
        when(packetDecomposerService.decompose(any(JobRecord.class)))
                .thenReturn(List.of(new PacketRecord()));

        jobService.submitJob(request);

        // The job status must be DECOMPOSED after the full submitJob call completes
        assertThat(savedRecord.get()).isNotNull();
        assertThat(savedRecord.get().getStatus()).isEqualTo("DECOMPOSED");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-CACHE-HIT-RESPONSE-USABLE — E60S04 (RED-first)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-CACHE-HIT-RESPONSE-USABLE (RED-first per DEC-22):
     *
     * <p>When a cache hit occurs, the {@link SubmitJobResponse} MUST carry the cached optimum
     * inline — {@code cachedResult} is non-null with the correct {@code bestRank} / {@code
     * bestScore} from the cached payload.
     *
     * <p>Story: E60S04; AC-TEST-CACHE-HIT-RESPONSE-USABLE; DEC-22, DEC-9
     */
    @Test
    void submitJob_cacheHit_responseCachedResultContainsBestRankAndBestScore() {
        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        CachedResult cachedResult =
                new CachedResult(
                        new byte[32],
                        "default",
                        "{\"bestRank\":3,\"bestScore\":42.5}",
                        Instant.parse("2026-04-26T10:00:00Z"));
        when(resultsCacheService.lookup(any(byte[].class), eq("default")))
                .thenReturn(Optional.of(cachedResult));

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.cacheHit()).isTrue();
        // AC-TEST-CACHE-HIT-RESPONSE-USABLE: cached optimum reachable from response
        assertThat(response.cachedResult()).isNotNull();
        assertThat(response.cachedResult().bestRank()).isEqualTo(3);
        assertThat(response.cachedResult().bestScore()).isEqualTo(42.5);
    }

    /**
     * AC-ERR-CACHE-HIT-RESPONSE-CONSISTENT: malformed cached payload must yield null cachedResult
     * (not a broken reference) — the submitter is never given a bad reference.
     *
     * <p>Story: E60S04; AC-ERR-CACHE-HIT-RESPONSE-CONSISTENT; DEC-22
     */
    @Test
    void submitJob_cacheHit_malformedPayload_cachedResultIsNull() {
        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        // Payload is missing both bestRank and bestScore
        CachedResult cachedResult =
                new CachedResult(
                        new byte[32], "default", "{\"someOtherField\":true}", Instant.now());
        when(resultsCacheService.lookup(any(byte[].class), eq("default")))
                .thenReturn(Optional.of(cachedResult));

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.cacheHit()).isTrue();
        // Malformed payload → null cachedResult (not a broken reference)
        assertThat(response.cachedResult()).isNull();
    }

    /**
     * AC-TEST-CACHE-HIT-NOT-DECOMPOSED (regression guard — cache-hit short-circuit is unchanged):
     *
     * <p>A cache-hit job MUST NOT invoke the decomposer and MUST NOT persist any {@link
     * PacketRecord}.
     *
     * <p>Story: E60S01; DEC-22, DEC-36
     */
    @Test
    void submitJob_cacheHit_doesNotDecomposeOrPersistPackets() {
        RawPhaseDef phase = buildSmallPhase(1);
        CanonicalPhaseDef canonical = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        CachedResult cachedResult =
                new CachedResult(
                        new byte[32],
                        "default",
                        "{\"bestRank\":1}",
                        Instant.parse("2026-04-26T10:00:00Z"));
        when(resultsCacheService.lookup(any(byte[].class), eq("default")))
                .thenReturn(Optional.of(cachedResult));

        jobService.submitJob(request);

        verify(packetDecomposerService, never()).decompose(any());
        verify(packetRepository, never()).saveAll(any());
    }

    /**
     * AC-ERR-DECOMPOSE-FAILURE-DETERMINISTIC: if decomposition fails (e.g., malformed jobDefJson
     * that the decomposer cannot process), the job must not be left in a half-decomposed state. No
     * packets are persisted and the exception propagates (caller handles it).
     *
     * <p>Story: E60S01; AC-ERR-DECOMPOSE-FAILURE-DETERMINISTIC; DEC-22
     */
    @Test
    void submitJob_decompositionFailure_noPacketsPersisted() {
        RawPhaseDef phase = buildSmallPhase(2);
        CanonicalPhaseDef canonical =
                new CanonicalPhaseDef(2, 2, List.of(List.of(0, 1), List.of(0, 1)));
        JobDef jobDef = new JobDef(UUID.randomUUID(), 2, canonical);
        SubmitJobRequest request = new SubmitJobRequest(jobDef, phase);

        when(resultsCacheService.lookup(any(byte[].class), any())).thenReturn(Optional.empty());
        when(jobRepository.save(any(JobRecord.class)))
                .thenAnswer(
                        inv -> {
                            JobRecord r = inv.getArgument(0);
                            r.setId(1L);
                            return r;
                        });
        when(packetDecomposerService.decompose(any(JobRecord.class)))
                .thenThrow(new IllegalArgumentException("malformed jobDefJson"));

        assertThatThrownBy(() -> jobService.submitJob(request))
                .isInstanceOf(IllegalArgumentException.class);
        // No packets must have been saved
        verify(packetRepository, never()).saveAll(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static RawPhaseDef buildSmallPhase(int rowCount) {
        List<RawRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            rows.add(new RawRow(List.of(new PositionTuple(i, 0), new PositionTuple(i, 1))));
        }
        return new RawPhaseDef(1, rowCount, rows);
    }
}
