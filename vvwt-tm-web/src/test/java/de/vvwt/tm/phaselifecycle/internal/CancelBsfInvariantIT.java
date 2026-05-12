package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * RED-first IT for AC-TEST-M-4-CANCEL-BSF-INVARIANT-RED (E55S05).
 *
 * <p>DEC-22 Iron Law: RED commit precedes GREEN commit. At RED time, {@link
 * de.vvwt.tm.phaselifecycle.internal.DefaultCancelFlagRegistry} throws {@link
 * UnsupportedOperationException} — the cancel path wires to {@link CancelFlagRegistry} which
 * fails fast.
 *
 * <p>GREEN state (post E55S05 implementation): when operator cancels mid-L3-permutation:
 *
 * <ul>
 *   <li>{@code phase_lifecycle_job.cancelled=TRUE} (DB persistence)
 *   <li>{@code phase_lifecycle_job.status='COMPLETED'} (BSF applied + job finished)
 *   <li>{@code phase.optimized=TRUE} (BSF-apply-on-cancel per DEC-49 D-11a + DEC-64 D-16)
 *   <li>{@code phase.status='PREPARED'} (cancel does NOT regress phase)
 *   <li>All matches have non-null lap_number + field_number (BSF applied)
 * </ul>
 *
 * <p>Uses {@code tm.slotopt.exhaustive-max-n=2} so a 4-team roundRobin phase (3 laps) routes to
 * Leg 3 (cancelable in-process). The cancel signal is issued via {@link
 * SlotOptimizationCancelController} HTTP endpoint while drainNext() blocks in L3.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE web environment for non-HTTP drain),
 * DEC-49 D-11a (BSF semantics), DEC-64 D-10 (cooperative cancel flag), DEC-64 D-16
 * (cancel-completion invariant).
 *
 * @since E55S05
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cancelbsfinvariantit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@TestPropertySource(properties = {"tm.slotopt.exhaustive-max-n=2"})
@Import(TenantContextTestSupport.class)
@DisplayName("CancelBsfInvariantIT — AC-TEST-M-4-CANCEL-BSF-INVARIANT-RED (E55S05)")
class CancelBsfInvariantIT {

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private CancelFlagRegistry cancelFlagRegistry;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "CancelBSF IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "CancelBSF IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2, // 2 fields
                4, // 4 teams → 3 laps > exhaustive-max-n=2 → routes to Leg 3
                true);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                0,
                false);

        for (int i = 1; i <= 4; i++) {
            UUID teamId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i,
                    "CancelBSF Team " + i,
                    true,
                    LocalDateTime.now());
            UUID avatarId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    avatarId,
                    tournamentId,
                    phaseId,
                    1,
                    i,
                    teamId);
        }

        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));
    }

    @AfterEach
    void tearDown() {
        cancelFlagRegistry.clear(tournamentId);
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update(
                    "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-M-4-CANCEL-BSF-INVARIANT-RED: cancel mid-L3 applies BSF, sets phase.optimized=TRUE,
     * job COMPLETED, cancelled=TRUE, matches have lap+field.
     *
     * <p>Strategy: start drainNext() in a background thread. After a short delay (giving L3 time to
     * start), signal cancel via {@link CancelFlagRegistry#requestCancel(UUID)} and also set
     * {@code cancelled=TRUE} on the job row. The L3 loop observes the cancellation token and
     * applies BSF. The orchestrator step-B completes normally.
     */
    @Test
    @DisplayName(
            "cancel mid-L3 — BSF applied, phase.optimized=TRUE, job COMPLETED, cancelled=TRUE,"
                    + " matches have lap+field")
    void cancelMidL3_bsfApplied_phaseOptimized_jobCompleted() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch drainStarted = new CountDownLatch(1);

        // Start drain in background
        Future<?> drainFuture =
                executor.submit(
                        () -> {
                            drainStarted.countDown();
                            jobDrainService.drainNext(tournamentId);
                        });

        // Wait for drain to start, then cancel
        assertThat(drainStarted.await(5, TimeUnit.SECONDS))
                .as("drain must start within 5s")
                .isTrue();
        // Small delay to allow L3 to begin permutation loop
        Thread.sleep(50);

        // Signal cancel: set DB flag + in-memory flag
        jobRepository
                .findRunningJobIdForTournament(tournamentId)
                .ifPresent(jobId -> jobRepository.markCancelled(jobId));
        cancelFlagRegistry.requestCancel(tournamentId);

        // Wait for drain to complete (BSF should be applied quickly after cancel signal)
        drainFuture.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert: job is COMPLETED
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus).as("job must be COMPLETED after BSF apply").isEqualTo("COMPLETED");

        // Assert: cancelled=TRUE (audit flag retained)
        Boolean cancelled =
                jdbcTemplate.queryForObject(
                        "SELECT cancelled FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Boolean.class,
                        tournamentId);
        assertThat(cancelled).as("cancelled audit flag must remain TRUE").isTrue();

        // Assert: phase.optimized=TRUE (BSF applied per DEC-64 D-16)
        Boolean optimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized)
                .as("phase.optimized must be TRUE after BSF apply (DEC-64 D-16)")
                .isTrue();

        // Assert: phase.status=PREPARED (cancel does NOT regress phase)
        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus)
                .as("phase must be PREPARED — cancel does not regress phase")
                .isEqualTo("PREPARED");

        // Assert: matches have non-null lap+field (BSF rank applied)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(matchCount).as("matches must exist after L1+L2").isGreaterThan(0);

        Integer nullLapCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND lap_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullLapCount)
                .as("all matches must have lap_number (BSF applied)")
                .isEqualTo(0);

        Integer nullFieldCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND field_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullFieldCount)
                .as("all matches must have field_number (BSF applied)")
                .isEqualTo(0);
    }
}
