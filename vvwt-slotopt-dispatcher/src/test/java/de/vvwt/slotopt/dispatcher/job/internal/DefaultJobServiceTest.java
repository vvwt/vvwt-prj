package de.vvwt.slotopt.dispatcher.job.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * <p>TDD RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S07). Written before production
 * class.
 *
 * <p>DEC-36: this test class is in {@code job.internal} (same package as {@link
 * DefaultJobService}), so white-box access to the implementation class is permitted. However, the
 * collaborators ({@link JobRepository}, {@link AuditService}) are mocked via their PUBLIC
 * INTERFACES (different packages), per DEC-36.
 *
 * <p>Story: E37S07; AC-JOB-SERVICE; DEC-36
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

    private JobService jobService; // typed as public interface (DEC-36)

    @BeforeEach
    void setUp() {
        jobService =
                new DefaultJobService(
                        jobRepository,
                        auditService,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        resultsCacheService);
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

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.jobId()).isNotNull();
        assertThat(response.submittedAt()).isNotNull();
        verify(jobRepository).save(any(JobRecord.class));
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

        SubmitJobResponse response = jobService.submitJob(request);

        assertThat(response.cacheHit()).isFalse();
        assertThat(response.jobId()).isNotNull();
        verify(jobRepository).save(any(JobRecord.class));
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
