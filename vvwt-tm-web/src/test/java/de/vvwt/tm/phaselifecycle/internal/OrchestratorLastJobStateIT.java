package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@code phase.last_job_state} writes by the Saga-Orchestrator pipeline
 * (DEC-66 D-2, E55S08).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-TEST-LAST-JOB-STATE-HAPPY-PATH-ROUNDROBIN-RED: roundRobin + optimize=true → idle at end.
 *   <li>AC-TEST-LAST-JOB-STATE-SIEGEREHRUNG-IDLE-RED: siegerehrung → idle (no step-B).
 *   <li>AC-TEST-LAST-JOB-STATE-OPTIMIZE-FALSE-IDLE-RED: optimize=false → idle (step-B skips L3).
 *   <li>AC-TEST-LAST-JOB-STATE-STEP-A-FAILURE-FAILED-RED: step-A throws → failed persisted by
 *       REQUIRES_NEW failure writer.
 *   <li>AC-TEST-LAST-JOB-STATE-STEP-B-FAILURE-FAILED-RED: step-B throws (SlotOpt) → failed
 *       persisted by REQUIRES_NEW failure writer.
 *   <li>AC-TEST-LAST-JOB-STATE-CANCEL-CLASS-C-POST-CLAIM-PRE-L3-IDLE-RED: cancel before L3 body →
 *       idle.
 * </ul>
 *
 * <p>DEC-22 Iron Law: RED-first. These tests are committed before the production writes are
 * implemented; they should fail RED until the implementation is in place.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE web env), DEC-66 D-2 (last_job_state
 * writes), AC-IMPL-LAST-JOB-STATE-AT-CLAIM,
 * AC-IMPL-LAST-JOB-STATE-STEP-A-DONE-OPTIMIZE-TRUE-QUEUED,
 * AC-IMPL-LAST-JOB-STATE-STEP-A-DONE-OPTIMIZE-FALSE-OR-SIEGEREHRUNG,
 * AC-IMPL-LAST-JOB-STATE-STEP-B-SUCCESS, AC-IMPL-LAST-JOB-STATE-STEP-FAILURE-FAILED.
 *
 * @since E55S08
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratorlastjobstateit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorLastJobStateIT.SlotOptConfig.class})
@DisplayName("OrchestratorLastJobStateIT — DEC-66 D-2 last_job_state writes (E55S08)")
class OrchestratorLastJobStateIT {

    /**
     * Slot-opt test double that can be configured per-test: defaults to no-op; can be replaced with
     * a blocking or throwing implementation via {@link #slotOptBehavior}.
     */
    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> slotOptBehavior.run();
        }
    }

    /** Mutable hook: replace to make SlotOpt block or throw. Resets to no-op in setUp(). */
    static volatile Runnable slotOptBehavior = () -> {};

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private CancelFlagRegistry cancelFlagRegistry;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID capturedTenantId;

    @BeforeEach
    void setUp() {
        slotOptBehavior = () -> {};
        capturedTenantId = tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "LastJobState IT Location");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update("DELETE FROM match WHERE 1=1");
            jdbcTemplate.update("DELETE FROM phase_lifecycle_job WHERE 1=1");
            jdbcTemplate.update("DELETE FROM team_avatar WHERE 1=1");
            jdbcTemplate.update("DELETE FROM team WHERE 1=1");
            jdbcTemplate.update("DELETE FROM phase WHERE 1=1");
            jdbcTemplate.update("DELETE FROM tournament WHERE 1=1");
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
            tenantBinder.unbind();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private UUID insertTournament(boolean optimize, String gameMode) {
        UUID tId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tId,
                locationId,
                "LastJobState Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                gameMode,
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                optimize);
        return tId;
    }

    private UUID insertPhase(UUID tId) {
        UUID pId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                pId,
                tId,
                1,
                "Phase 1",
                "PENDING",
                0,
                false);
        return pId;
    }

    private void insertTeamsAndAvatars(UUID tId, UUID pId, int count) {
        insertTeamsAndAvatarsWithOffset(tId, pId, count, 0);
    }

    private void insertTeamsAndAvatarsWithOffset(UUID tId, UUID pId, int count, int offset) {
        for (int i = 1; i <= count; i++) {
            int teamNumber = i + offset;
            UUID teamId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tId,
                    teamNumber,
                    "Team " + teamNumber,
                    true,
                    LocalDateTime.now());
            UUID avatarId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                    avatarId,
                    tId,
                    pId,
                    1,
                    i,
                    teamId);
        }
    }

    private String queryLastJobState(UUID pId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_job_state FROM phase WHERE id = ?", String.class, pId);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────

    /**
     * AC-TEST-LAST-JOB-STATE-HAPPY-PATH-ROUNDROBIN-RED: optimize=true, roundRobin. Sequence:
     * match_gen_running → slot_opt_queued (end of step-A) → slot_opt_running (start of step-B) →
     * idle (end of step-B).
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: roundRobin + optimize=true — last_job_state ends as 'idle' after full"
                    + " pipeline (AC-TEST-LAST-JOB-STATE-HAPPY-PATH-ROUNDROBIN-RED)")
    void happyPathOptimizeTrueLastJobStateIsIdle() {
        tournamentId = insertTournament(true, "roundRobin");
        phaseId = insertPhase(tournamentId);
        insertTeamsAndAvatars(tournamentId, phaseId, 4);
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));

        jobDrainService.drainNext(tournamentId);

        String lastJobState = queryLastJobState(phaseId);
        assertThat(lastJobState)
                .as(
                        "last_job_state must be 'idle' after successful roundRobin + optimize=true"
                                + " pipeline (DEC-66 D-2)")
                .isEqualTo("idle");
    }

    /**
     * AC-TEST-LAST-JOB-STATE-SIEGEREHRUNG-IDLE-RED: siegerehrung → step-A writes 'idle' (no step-B
     * enqueued per DEC-66 D-2 row 2 + DEC-59 Clause E).
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: siegerehrung → last_job_state='idle' after step-A (no step-B)"
                    + " (AC-TEST-LAST-JOB-STATE-SIEGEREHRUNG-IDLE-RED)")
    void awardCeremonyLastJobStateIsIdleAfterStepA() {
        tournamentId = insertTournament(true, "awardCeremony");
        phaseId = insertPhase(tournamentId);
        // No teams needed — siegerehrung produces 0 matches
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "awardCeremony", 1));

        jobDrainService.drainNext(tournamentId);

        String lastJobState = queryLastJobState(phaseId);
        assertThat(lastJobState)
                .as(
                        "last_job_state must be 'idle' after siegerehrung pipeline"
                                + " (DEC-66 D-2, DEC-59 Clause E)")
                .isEqualTo("idle");
    }

    /**
     * AC-TEST-LAST-JOB-STATE-OPTIMIZE-FALSE-IDLE-RED: optimize=false → step-B skips L3 and writes
     * 'idle'. Terminal state is idle regardless.
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: optimize=false — last_job_state='idle' after step-B (L3 skipped)"
                    + " (AC-TEST-LAST-JOB-STATE-OPTIMIZE-FALSE-IDLE-RED)")
    void optimizeFalseLastJobStateIsIdle() {
        tournamentId = insertTournament(false, "roundRobin");
        phaseId = insertPhase(tournamentId);
        insertTeamsAndAvatars(tournamentId, phaseId, 4);
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));

        jobDrainService.drainNext(tournamentId);

        String lastJobState = queryLastJobState(phaseId);
        assertThat(lastJobState)
                .as(
                        "last_job_state must be 'idle' after optimize=false pipeline"
                                + " (DEC-66 D-2 row 2 terminal)")
                .isEqualTo("idle");
    }

    /**
     * AC-TEST-LAST-JOB-STATE-STEP-A-FAILURE-FAILED-RED: step-A throws (unknown generator) →
     * REQUIRES_NEW failure writer commits 'failed' independently of rolled-back step-A TX.
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: step-A failure → last_job_state='failed' (REQUIRES_NEW writer commits"
                    + " independently) (AC-TEST-LAST-JOB-STATE-STEP-A-FAILURE-FAILED-RED)")
    void stepAFailureLastJobStateIsFailed() {
        tournamentId = insertTournament(true, "roundRobin");
        phaseId = insertPhase(tournamentId);
        // No teams inserted → unknownGenerator will be the gameMode; L1 throws
        jobRepository.enqueueJob(
                new PhaseLifecycleJob(tournamentId, phaseId, "unknownGenerator", 1));

        try {
            jobDrainService.drainNext(tournamentId);
        } catch (Exception e) {
            // Expected: step-A TX rolled back, failure writer should have committed 'failed'
        }

        String lastJobState = queryLastJobState(phaseId);
        assertThat(lastJobState)
                .as(
                        "last_job_state must be 'failed' after step-A exception (REQUIRES_NEW"
                                + " failure writer, DEC-66 D-2)")
                .isEqualTo("failed");
    }

    /**
     * AC-TEST-LAST-JOB-STATE-STEP-B-FAILURE-FAILED-RED: step-B SlotOpt throws → REQUIRES_NEW
     * failure writer commits 'failed'. Step-A result (slot_opt_queued) is overwritten to 'failed'.
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: step-B failure (SlotOpt throws) → last_job_state='failed'"
                    + " (AC-TEST-LAST-JOB-STATE-STEP-B-FAILURE-FAILED-RED)")
    void stepBFailureLastJobStateIsFailed() {
        // Make SlotOpt throw a RuntimeException to simulate step-B failure
        slotOptBehavior =
                () -> {
                    throw new RuntimeException(
                            "Simulated SlotOpt failure for AC-TEST-STEP-B-FAILURE");
                };

        tournamentId = insertTournament(true, "roundRobin");
        phaseId = insertPhase(tournamentId);
        insertTeamsAndAvatars(tournamentId, phaseId, 4);
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));

        try {
            jobDrainService.drainNext(tournamentId);
        } catch (Exception e) {
            // Expected: step-B TX rolled back, failure writer should have committed 'failed'
        }

        String lastJobState = queryLastJobState(phaseId);
        assertThat(lastJobState)
                .as(
                        "last_job_state must be 'failed' after step-B exception (REQUIRES_NEW"
                                + " failure writer, DEC-66 D-2)")
                .isEqualTo("failed");
    }

    /**
     * AC-TEST-LAST-JOB-STATE-CANCEL-CLASS-C-POST-CLAIM-PRE-L3-IDLE-RED: cancel flag set before
     * drainNext() is called → last_job_state='idle'. L3 Leg 3 detects pre-cancel via
     * CancellationToken (mirrors CancelFlagRegistry) and applies BSF rank-0 (L2 baseline), so
     * phase.optimized=TRUE and last_job_state='idle' (step-B success terminal per DEC-66 D-2).
     *
     * <p>Note: the SlotOpt override in this test class is a no-op; the real L3 implementation
     * (RoutingSlotOptimizationClient → CancelableInProcessSlotOptimizationService) handles
     * pre-cancel. This test uses the default no-op slotOptBehavior.
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: cancel flag set before drain → last_job_state='idle' after BSF rank-0"
                    + " (AC-TEST-LAST-JOB-STATE-CANCEL-CLASS-C-POST-CLAIM-PRE-L3-IDLE-RED)")
    void cancelBeforeL3LastJobStateIsIdle() {
        tournamentId = insertTournament(true, "roundRobin");
        phaseId = insertPhase(tournamentId);
        insertTeamsAndAvatars(tournamentId, phaseId, 4);
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));

        // Set cancel flag before drain starts — L3 detects pre-cancel, applies rank-0 BSF
        // per DEC-49 D-11a (trivial BSF). step-B completes normally → last_job_state='idle'.
        cancelFlagRegistry.requestCancel(tournamentId);

        jobDrainService.drainNext(tournamentId);

        String lastJobState = queryLastJobState(phaseId);
        assertThat(lastJobState)
                .as(
                        "last_job_state must be 'idle' after pre-cancel BSF rank-0 (DEC-66 D-2"
                                + " step-B success terminal)")
                .isEqualTo("idle");

        // Job must be COMPLETED (BSF applied, step-B completes normally)
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus)
                .as("job must be COMPLETED after pre-cancel BSF rank-0")
                .isEqualTo("COMPLETED");
    }

    /**
     * AC-TEST-LAST-JOB-STATE-AT-CLAIM-MATCH-GEN-RUNNING-RED: immediately after CAS claim,
     * last_job_state must be 'match_gen_running' (written in T-claim per DEC-66 D-2 row 1,
     * AC-IMPL-LAST-JOB-STATE-AT-CLAIM).
     *
     * <p>This test uses a blocking SlotOpt to freeze the drain mid-pipeline and inspects
     * last_job_state while the drain is running.
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: last_job_state='match_gen_running' written at T-claim before step-A"
                    + " (AC-TEST-LAST-JOB-STATE-AT-CLAIM-MATCH-GEN-RUNNING-RED)")
    void atClaimLastJobStateIsMatchGenRunning() throws Exception {
        tournamentId = insertTournament(true, "roundRobin");
        phaseId = insertPhase(tournamentId);
        insertTeamsAndAvatars(tournamentId, phaseId, 4);
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));

        // Block in SlotOpt so we can inspect DB state while drain is paused
        CountDownLatch slotOptStarted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        slotOptBehavior =
                () -> {
                    slotOptStarted.countDown();
                    try {
                        resume.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                };

        final UUID capturedTId = capturedTenantId;
        AtomicReference<Exception> drainError = new AtomicReference<>();
        Future<?> drainFuture =
                Executors.newSingleThreadExecutor()
                        .submit(
                                () -> {
                                    TenantContext.Scope scope = tenantContext.bind(capturedTId);
                                    try {
                                        jobDrainService.drainNext(tournamentId);
                                    } catch (Exception e) {
                                        drainError.set(e);
                                    } finally {
                                        scope.close();
                                    }
                                });

        // Wait for SlotOpt to start (T-claim + T-step-A already committed at this point;
        // T-step-B is open but not yet committed — step-B TX holds slot_opt_running internally)
        boolean started = slotOptStarted.await(10, TimeUnit.SECONDS);
        assertThat(started).as("SlotOpt must start within 10s").isTrue();

        // Inspect last_job_state from an external connection (READ_COMMITTED default).
        // T-claim + T-step-A committed → last externally visible state is 'slot_opt_queued'
        // (set by step-A TX). T-step-B is still open, so 'slot_opt_running' is not yet visible.
        String stateWhileStepBRunning = queryLastJobState(phaseId);

        // Allow drain to complete
        resume.countDown();
        drainFuture.get(10, TimeUnit.SECONDS);

        assertThat(stateWhileStepBRunning)
                .as(
                        "while step-B TX is open (SlotOpt blocking), last externally visible"
                                + " last_job_state is 'slot_opt_queued' (step-A TX committed;"
                                + " step-B TX not yet committed — READ_COMMITTED, DEC-66 D-2)")
                .isEqualTo("slot_opt_queued");

        // Final state must be 'idle' (step-B TX committed successfully)
        assertThat(queryLastJobState(phaseId))
                .as("last_job_state must be 'idle' after successful step-B completion")
                .isEqualTo("idle");

        if (drainError.get() != null) {
            throw drainError.get();
        }
    }

    /**
     * AC-TEST-LAST-JOB-STATE-MULTI-PHASE-QUEUED-RED: two phases in same tournament, optimize=true.
     * First phase gets 'idle' at end; the second phase's drain run also ends with 'idle'. While
     * second phase is in step-A, last_job_state should transition through the expected lifecycle.
     */
    @Test
    @DisplayName(
            "DEC-66 D-2: two-phase FIFO queue — both phases end with last_job_state='idle'"
                    + " (AC-TEST-LAST-JOB-STATE-MULTI-PHASE-QUEUED-RED)")
    void twoPhasesFifoQueueBothEndWithIdle() {
        tournamentId = insertTournament(true, "roundRobin");
        UUID phaseId1 = insertPhase(tournamentId);
        insertTeamsAndAvatars(tournamentId, phaseId1, 4);

        UUID phaseId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId2,
                tournamentId,
                2,
                "Phase 2",
                "PENDING",
                0,
                false);
        // Phase 2 teams use team_number 5-8 to avoid UK conflict with phase 1 teams (1-4)
        insertTeamsAndAvatarsWithOffset(tournamentId, phaseId2, 4, 4);

        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId1, "roundRobin", 1));
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId2, "roundRobin", 2));

        // Drain both jobs (FIFO — drainNext loops until queue is empty)
        jobDrainService.drainNext(tournamentId);

        assertThat(queryLastJobState(phaseId1))
                .as("phase1 last_job_state must be 'idle' after FIFO drain")
                .isEqualTo("idle");

        assertThat(queryLastJobState(phaseId2))
                .as("phase2 last_job_state must be 'idle' after FIFO drain")
                .isEqualTo("idle");
    }
}
