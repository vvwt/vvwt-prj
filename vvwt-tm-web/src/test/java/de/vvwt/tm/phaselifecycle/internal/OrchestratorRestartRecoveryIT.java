package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.phaselifecycle.WorkerRegistry;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
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
 * RED-first IT for DEC-64 D-7 restart-recovery — AC-TEST-RESTART-RECOVERY-RED.
 *
 * <p>Contract under test: {@link WorkerRegistry#initOnStartup(String)} resets a stale RUNNING row
 * (claimed_by != currentJvmId) back to PENDING, spawns a worker for the tournament, and the drain
 * hint causes the job to complete.
 *
 * <p>Boundary: a row with {@code claimed_by = currentJvmId} is NOT reset (this JVM's own job).
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE), DEC-64 D-7 (restart-recovery),
 * AC-TEST-RESTART-RECOVERY-RED (E55S07).
 *
 * @since E55S07
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:restartrecoveryit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorRestartRecoveryIT.SlotOptConfig.class})
@DisplayName("OrchestratorRestartRecoveryIT — AC-TEST-RESTART-RECOVERY-RED (E55S07)")
class OrchestratorRestartRecoveryIT {

    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }
    }

    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private WorkerRegistry workerRegistry;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

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
                "RestartRecovery IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "RestartRecovery IT Tournament",
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
            tenantBinder.unbind();
        }
    }

    /**
     * AC-TEST-RESTART-RECOVERY-RED: a stale RUNNING row (claimed_by = 'dead-jvm') is reset to
     * PENDING by initOnStartup(), and the spawned worker completes the job.
     *
     * <p>Setup: insert a job row directly in RUNNING state with {@code claimed_by='dead-jvm'}. Call
     * {@code initOnStartup("this-jvm-id")} → step 2 resets it to PENDING. Step 3 finds the
     * tournament with a PENDING job, spawns a worker, submits drain hint. The worker completes the
     * job → final status = COMPLETED.
     */
    @Test
    @DisplayName("initOnStartup resets stale RUNNING row to PENDING and worker completes the job")
    void staleRunningRowIsResetAndJobCompletes() throws InterruptedException {
        // Insert job directly as RUNNING with a foreign claimed_by (dead JVM)
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled,"
                        + " claimed_by, claimed_at)"
                        + " VALUES (?, ?, ?, 'roundRobin', 1, 'RUNNING', FALSE, 'dead-jvm',"
                        + " CURRENT_TIMESTAMP)",
                jobId,
                tournamentId,
                phaseId);

        // Trigger restart-recovery with a JVM ID that does NOT match 'dead-jvm'
        workerRegistry.initOnStartup("this-jvm-id");

        // Allow time for the drain hint to execute and the job to complete
        // Poll up to 10s, checking every 200ms
        String finalStatus = null;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(200);
            finalStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase_lifecycle_job WHERE id = ?",
                            String.class,
                            jobId);
            if ("COMPLETED".equals(finalStatus)) {
                break;
            }
        }

        assertThat(finalStatus)
                .as("stale RUNNING job must be reclaimed and completed by restart-recovery")
                .isEqualTo("COMPLETED");
    }

    /**
     * AC-TEST-RESTART-RECOVERY-OWN-JVM-BOUNDARY: a RUNNING row with {@code claimed_by =
     * currentJvmId} is NOT reset — this JVM's own running job survives initOnStartup().
     *
     * <p>Setup: insert a job row directly in RUNNING state with {@code claimed_by='this-jvm-id'}.
     * Call {@code initOnStartup("this-jvm-id")} → step 2 MUST NOT reset this row. Assert: status is
     * still RUNNING after initOnStartup returns.
     */
    @Test
    @DisplayName("initOnStartup does NOT reset own-JVM RUNNING row (boundary)")
    void ownJvmRunningRowIsNotReset() throws InterruptedException {
        String currentJvmId = "this-jvm-id";

        // Insert job directly as RUNNING with claimed_by = currentJvmId
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled,"
                        + " claimed_by, claimed_at)"
                        + " VALUES (?, ?, ?, 'roundRobin', 1, 'RUNNING', FALSE, ?,"
                        + " CURRENT_TIMESTAMP)",
                jobId,
                tournamentId,
                phaseId,
                currentJvmId);

        // Trigger restart-recovery with the same JVM ID
        workerRegistry.initOnStartup(currentJvmId);

        // Small wait to let any async side-effects settle
        Thread.sleep(200);

        // The row should NOT have been reset — it stays RUNNING (own JVM's job)
        String statusAfter =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE id = ?", String.class, jobId);
        assertThat(statusAfter)
                .as("own-JVM RUNNING row must NOT be reset to PENDING by initOnStartup")
                .isEqualTo("RUNNING");
    }
}
