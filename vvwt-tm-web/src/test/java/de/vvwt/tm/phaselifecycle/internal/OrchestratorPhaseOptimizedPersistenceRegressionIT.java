// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
 * Regression IT for M-1 (phase.optimized persistence race) —
 * AC-TEST-M-1-PHASE-OPTIMIZED-PERSISTENCE-RED.
 *
 * <h2>M-1 regression background</h2>
 *
 * <p>Under the E1 (events-only) architecture, {@code @TransactionalEventListener(AFTER_COMMIT)}
 * caused orchestration to run in a separate transaction that was silently dropped if the listener
 * thread raced with commit of the parent TX. This produced {@code phase.optimized=FALSE} in
 * production-like load (observed 2026-05-11T22:06:47 per Session Brief §M-1).
 *
 * <p>The E3 orchestrator (DEC-64) is imperative: {@code DefaultJobDrainService.drainNext()} runs
 * the full pipeline synchronously in the worker thread; {@code optimized=TRUE} is set inside the
 * same TX as phase status. The AFTER_COMMIT race surface is structurally absent.
 *
 * <p>This IT exercises 100 consecutive iterations (shared Spring context, DB state reset between
 * iterations) — any residual non-determinism would cause {@code optimized=FALSE} in at least one
 * run.
 *
 * <p>Authorizing decisions: DEC-22 (E3 arch eliminates race — test passes GREEN on E3), DEC-44
 * (NONE), DEC-64 D-3 (per-tournament single-thread worker),
 * AC-TEST-M-1-PHASE-OPTIMIZED-PERSISTENCE-RED (E55S07).
 *
 * @since E55S07
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:m1regressionit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({
    TenantContextTestSupport.class,
    OrchestratorPhaseOptimizedPersistenceRegressionIT.SlotOptConfig.class
})
@DisplayName(
        "OrchestratorPhaseOptimizedPersistenceRegressionIT —"
                + " AC-TEST-M-1-PHASE-OPTIMIZED-PERSISTENCE-RED (E55S07)")
class OrchestratorPhaseOptimizedPersistenceRegressionIT {

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

    private UUID locationId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "M1 Regression IT Location");
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

    /**
     * AC-TEST-M-1-PHASE-OPTIMIZED-PERSISTENCE-RED: 100 consecutive runs with 2 phases (slow + fast)
     * — both must have {@code optimized=TRUE} in every run.
     *
     * <p>Under E3 (imperative orchestrator), phase.optimized is written inside the orchestrator TX
     * — no AFTER_COMMIT race possible. All 100/100 runs must pass.
     */
    @Test
    @DisplayName(
            "M-1 regression: phase.optimized=TRUE in 100/100 consecutive runs (E3 arch eliminates"
                    + " AFTER_COMMIT race)")
    void phaseOptimizedPersistsCorrectlyIn100ConsecutiveRuns() {
        int totalRuns = 100;

        for (int run = 0; run < totalRuns; run++) {
            UUID tournamentId = UUID.randomUUID();
            UUID phaseId = UUID.randomUUID();

            // Insert tournament + phase + teams
            jdbcTemplate.update(
                    "INSERT INTO tournament (id, location_id, description, match_format,"
                            + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                            + " status, created_at, field_count, team_count, optimize)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tournamentId,
                    locationId,
                    "M1 Regression Run " + run,
                    "BEST_OF_3",
                    "setPoints",
                    "standardVolleyball",
                    "roundRobin",
                    "DRAFT",
                    LocalDateTime.now(),
                    2,
                    4,
                    true);

            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number, optimized)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    phaseId,
                    tournamentId,
                    1,
                    "Phase " + run,
                    "PENDING",
                    0,
                    false);

            for (int i = 1; i <= 4; i++) {
                UUID teamId = UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO team (id, tournament_id, team_number, description,"
                                + " participate, created_at) VALUES (?, ?, ?, ?, ?, ?)",
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

            // Enqueue and drain
            jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));
            jobDrainService.drainNext(tournamentId);

            // Assert: job COMPLETED, phase.optimized=TRUE
            String jobStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                            String.class,
                            tournamentId);
            assertThat(jobStatus).as("run %d: job must be COMPLETED", run).isEqualTo("COMPLETED");

            Boolean optimized =
                    jdbcTemplate.queryForObject(
                            "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
            assertThat(optimized)
                    .as(
                            "run %d: phase.optimized must be TRUE (M-1 regression check — E3 arch"
                                    + " eliminates AFTER_COMMIT race)",
                            run)
                    .isTrue();

            // E55S08 / AC-TEST-M-1-IT-EXTENDED-WITH-LAST-JOB-STATE: last_job_state must be 'idle'
            // after full pipeline (DEC-66 D-2 terminal state for optimize=true roundRobin).
            String lastJobState =
                    jdbcTemplate.queryForObject(
                            "SELECT last_job_state FROM phase WHERE id = ?", String.class, phaseId);
            assertThat(lastJobState)
                    .as(
                            "run %d: phase.last_job_state must be 'idle' after full pipeline"
                                    + " (DEC-66 D-2, AC-TEST-M-1-IT-EXTENDED-WITH-LAST-JOB-STATE)",
                            run)
                    .isEqualTo("idle");

            // Clean up this run's rows before the next iteration
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
            jdbcTemplate.update("DELETE FROM match WHERE phase_id = ?", phaseId);
            jdbcTemplate.update(
                    "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
