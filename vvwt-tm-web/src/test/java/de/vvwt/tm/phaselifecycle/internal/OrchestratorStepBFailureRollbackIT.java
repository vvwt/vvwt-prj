package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
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
 * RED-first IT for T-step-B failure rollback — AC-TEST-STEP-B-FAILURE-ROLLBACK-RED.
 *
 * <p>DEC-22 Iron Law: RED before GREEN. At RED time, {@code drainNext()} throws {@code
 * UnsupportedOperationException}. GREEN state: when SlotOpt throws during step-B, step-B TX rolls
 * back. Matches retain their L2 lap+field assignments (step-A committed). Job status stays RUNNING.
 * Phase.optimized stays FALSE.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44, DEC-64 D-12 (step-A separate TX from
 * step-B; step-B failure leaves job RUNNING, matches present from step-A).
 *
 * @since E55S04
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratorstepbfillit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorStepBFailureRollbackIT.SlotOptConfig.class})
@DisplayName("OrchestratorStepBFailureRollbackIT — AC-TEST-STEP-B-FAILURE-ROLLBACK-RED (E55S04)")
class OrchestratorStepBFailureRollbackIT {

    /** Controls whether SlotOpt throws (first call = throws; second call = succeeds). */
    static final AtomicBoolean SLOT_OPT_SHOULD_FAIL = new AtomicBoolean(true);

    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                if (SLOT_OPT_SHOULD_FAIL.get()) {
                    throw new RuntimeException(
                            "Simulated SlotOpt failure for step-B rollback test");
                }
            };
        }
    }

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        SLOT_OPT_SHOULD_FAIL.set(true);
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "StepBFail IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "StepBFail IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
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
                    "Team " + i,
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
     * AC-TEST-STEP-B-FAILURE-ROLLBACK-RED: SlotOpt throws → step-B rolls back. Matches still have
     * lap+field from step-A. Job stays RUNNING. Phase.optimized=FALSE. Next tick (with SlotOpt
     * fixed) completes successfully.
     */
    @Test
    @DisplayName(
            "step-B SlotOpt failure: matches retain lap+field, job RUNNING, optimized=FALSE; next"
                    + " tick succeeds")
    void stepBFailureRollsBackAndSecondTickSucceeds() {
        // Step 1: First tick — SlotOpt fails, step-B rolls back
        try {
            jobDrainService.drainNext(tournamentId);
        } catch (Exception e) {
            // Expected: step-B TX rolled back due to SlotOpt failure
        }

        // Then: job is RUNNING (T-claim committed, step-B rolled back)
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus)
                .as(
                        "job must stay RUNNING after step-B failure (T-claim committed, step-B"
                                + " rolled back)")
                .isEqualTo("RUNNING");

        // Then: matches present with lap+field from step-A (step-A committed before step-B started)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(matchCount).as("step-A matches must survive step-B rollback").isGreaterThan(0);

        Integer nullLapCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND lap_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullLapCount)
                .as("lap_number from step-A must be preserved through step-B rollback")
                .isEqualTo(0);

        // Then: optimized=FALSE (step-B rolled back before setting it)
        Boolean optimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized).as("optimized must stay FALSE after step-B rollback").isFalse();

        // Step 2: Second tick — SlotOpt now succeeds
        SLOT_OPT_SHOULD_FAIL.set(false);

        // Re-set job to PENDING to simulate recovery (in real recovery, the daemon resets RUNNING
        // rows on startup; here we manually reset to test the re-run path)
        jdbcTemplate.update(
                "UPDATE phase_lifecycle_job SET status = 'PENDING', claimed_by = NULL WHERE"
                        + " tournament_id = ?",
                tournamentId);
        // Phase must be re-set to PENDING as well for step-A to re-run
        // (in recovery, step-B re-runs from the PREPARED state — but this test verifies
        // the simpler path of full re-run for correctness)
        jdbcTemplate.update("UPDATE phase SET status = 'PENDING' WHERE id = ?", phaseId);
        jobDrainService.drainNext(tournamentId);

        // Then: second tick completes the job
        String jobStatus2 =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus2)
                .as("job must be COMPLETED after successful second tick")
                .isEqualTo("COMPLETED");

        Boolean optimized2 =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized2).as("phase optimized must be true after successful L3").isTrue();
    }
}
