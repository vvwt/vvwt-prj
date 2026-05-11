package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
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
import de.vvwt.tm.slotopt.SlotOptimizationClient;

/**
 * RED-first IT for T-step-A failure rollback — AC-TEST-STEP-A-FAILURE-ROLLBACK-RED.
 *
 * <p>DEC-22 Iron Law: RED before GREEN. At RED time, {@code drainNext()} throws
 * {@code UnsupportedOperationException}. GREEN state: when MatchGen throws (zero teams,
 * so the generator has no avatars to work with), step-A TX rolls back.
 * The job status stays 'RUNNING' (claimed in T-claim) but phase.status is unchanged (PENDING).
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44, DEC-64 D-12 (T-claim separate from
 * T-step-A; step-A failure leaves job RUNNING).
 *
 * @since E55S04
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratorstepafillit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({
    TenantContextTestSupport.class,
    OrchestratorStepAFailureRollbackIT.SlotOptConfig.class
})
@DisplayName("OrchestratorStepAFailureRollbackIT — AC-TEST-STEP-A-FAILURE-ROLLBACK-RED (E55S04)")
class OrchestratorStepAFailureRollbackIT {

    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }
    }

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;
    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "StepAFail IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "StepAFail IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                0,  // team_count=0
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

        // NOTE: intentionally NO teams and NO team_avatars inserted.
        // This causes MatchGenerator (L1) to generate 0 matches, and
        // PhasePreparationService.generateMatches() may throw or return empty.
        // The test verifies step-A TX rolls back.

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
            jdbcTemplate.update("DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
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
     * AC-TEST-STEP-A-FAILURE-ROLLBACK-RED: when MatchGen fails (no participating teams),
     * step-A TX rolls back. Job stays RUNNING (claim was committed in T-claim).
     * Phase status stays PENDING (step-A did not complete PENDING→PREPARED transition).
     */
    @Test
    @DisplayName("step-A failure: job stays RUNNING, phase stays PENDING, step-A TX rolled back")
    void stepAFailureRollsBack() {
        // When: drainNext() — step-A will encounter no teams/avatars
        // The orchestrator must propagate the exception (or handle it gracefully) and
        // leave job status as RUNNING (T-claim committed separately).
        // The exact exception type is an implementation detail; we just verify the DB state.
        try {
            jobDrainService.drainNext(tournamentId);
        } catch (Exception e) {
            // Any exception from step-A is acceptable — step-A TX rolls back
        }

        // Then: job is RUNNING (claim committed in T-claim, step-A failed and rolled back)
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus)
                .as("job must stay RUNNING after step-A failure (T-claim committed, step-A rolled back)")
                .isEqualTo("RUNNING");

        // Then: phase stays PENDING (step-A transition PENDING→PREPARED did not commit)
        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?",
                        String.class,
                        phaseId);
        assertThat(phaseStatus)
                .as("phase must stay PENDING when step-A rolls back")
                .isEqualTo("PENDING");

        // Then: no matches (step-A rolled back any partial match inserts)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?",
                        Integer.class,
                        phaseId);
        assertThat(matchCount)
                .as("no matches must be present after step-A rollback")
                .isEqualTo(0);
    }
}
