package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.CancelFlagRegistry;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * IT for AC-TEST-CANCEL-CLEAR-ON-COMPLETION (E55S05).
 *
 * <p>Verifies that after a cancelled job completes (BSF applied), {@link
 * CancelFlagRegistry#isCancelled(UUID)} returns {@code false} for the same tournament, so a
 * subsequent job is not falsely cancelled.
 *
 * <p>Strategy:
 *
 * <ol>
 *   <li>Set cancel flag before job1 drain (simulates pre-cancel).
 *   <li>Drain job1 — completes with BSF; orchestrator step-B calls {@link
 *       CancelFlagRegistry#clear(UUID)}.
 *   <li>Assert: {@code isCancelled(tournamentId)} = {@code false}.
 *   <li>Enqueue job2, drain job2 — must complete normally (not cancelled).
 *   <li>Assert job2 status = COMPLETED.
 * </ol>
 *
 * <p>Uses no-op SlotOptimizationClient for Leg 1 (exhaustive-max-n default=10; 4-team roundRobin
 * has 3 laps ≤ 10 → Leg 1 inline exhaustive → no cancel flag polling in permutation loop for Leg
 * 1). We verify the clear() is called by checking isCancelled() is false after job1.
 *
 * <p>Note: For this test, the cancel flag is set in-memory BEFORE drain. Since Leg 1 does NOT poll
 * {@link CancelFlagRegistry} (it has its own pre-cancel check via the CancellationToken path), this
 * test focuses purely on the orchestrator step-B calling clear() after completion. The cancel flag
 * is set pre-drain; the orchestrator should clear it after step-B regardless of whether L3 ran.
 *
 * <p>Authorizing decisions: DEC-22 (TDD), DEC-64 D-10 (cooperative cancel flag), E55S05
 * AC-TEST-CANCEL-CLEAR-ON-COMPLETION.
 *
 * @since E55S05
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cancelclearoncompletion;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, CancelClearOnCompletionIT.SlotOptConfig.class})
@DisplayName("CancelClearOnCompletionIT — AC-TEST-CANCEL-CLEAR-ON-COMPLETION (E55S05)")
class CancelClearOnCompletionIT {

    /** No-op slot-opt: Leg 1 always runs (exhaustive-max-n=10, 4 teams = 3 laps ≤ 10). */
    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // no-op — L2 already set lap+field
            };
        }
    }

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private CancelFlagRegistry cancelFlagRegistry;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId1;
    private UUID phaseId2;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "CancelClear IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "CancelClear IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);

        phaseId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId1,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                0,
                false);

        phaseId2 = UUID.randomUUID();
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

        for (int i = 1; i <= 4; i++) {
            UUID teamId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i,
                    "Team " + i,
                    true,
                    LocalDateTime.now());
            for (UUID pid : new UUID[] {phaseId1, phaseId2}) {
                UUID avatarId = UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                                + " group_position, team_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        avatarId,
                        tournamentId,
                        pid,
                        1,
                        i,
                        teamId);
            }
        }
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
     * After job1 completes (with or without cancel), the cancel flag must be cleared so job2 runs
     * to natural completion.
     */
    @Test
    @DisplayName(
            "cancel flag cleared after job1 completes — job2 runs naturally without false cancel")
    void cancelFlagClearedAfterJob1_job2RunsNormally() {
        // Enqueue job1
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId1, "roundRobin", 1));

        // Pre-set cancel flag (simulating cancel arriving before or during job1)
        cancelFlagRegistry.requestCancel(tournamentId);

        // Drain job1 — completes; step-B must call cancelFlagRegistry.clear(tournamentId)
        jobDrainService.drainNext(tournamentId);

        // Assert: cancel flag cleared after job1 completes
        assertThat(cancelFlagRegistry.isCancelled(tournamentId))
                .as(
                        "CancelFlagRegistry must be cleared after job1 completes (step-B calls"
                                + " clear())")
                .isFalse();

        // Enqueue job2
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId2, "roundRobin", 2));

        // Drain job2 — must complete normally (no cancel)
        jobDrainService.drainNext(tournamentId);

        // Assert: job2 COMPLETED + phase2.optimized=TRUE (not falsely cancelled)
        String job2Status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE phase_id = ?",
                        String.class,
                        phaseId2);
        assertThat(job2Status).as("job2 must be COMPLETED").isEqualTo("COMPLETED");

        Boolean cancelled2 =
                jdbcTemplate.queryForObject(
                        "SELECT cancelled FROM phase_lifecycle_job WHERE phase_id = ?",
                        Boolean.class,
                        phaseId2);
        assertThat(cancelled2).as("job2 must NOT be cancelled").isFalse();

        Boolean optimized2 =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId2);
        assertThat(optimized2).as("phase2.optimized must be TRUE (natural completion)").isTrue();
    }
}
