// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
 * RED-first IT for restart-recovery with a cancelled job —
 * AC-TEST-RESTART-RECOVERY-CANCEL-RUNNING-RED.
 *
 * <p>Contract under test: when a stale-claimed RUNNING job also has {@code cancelled=TRUE}, the
 * restart-recovery path (via {@link WorkerRegistry#initOnStartup(String)}) resets the row to
 * PENDING, and the drain service processes it applying BSF semantics (because cancelled=TRUE).
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE), DEC-64 D-7 (restart-recovery),
 * DEC-49 D-11a + DEC-64 D-16 (BSF on cancel), AC-TEST-RESTART-RECOVERY-CANCEL-RUNNING-RED (E55S07).
 *
 * @since E55S07
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:restartrecoverycancelit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.exhaustive-max-n=2"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorRestartRecoveryCancelIT.SlotOptConfig.class})
@DisplayName(
        "OrchestratorRestartRecoveryCancelIT — AC-TEST-RESTART-RECOVERY-CANCEL-RUNNING-RED"
                + " (E55S07)")
class OrchestratorRestartRecoveryCancelIT {

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
                "RestartRecoveryCancel IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "RestartRecoveryCancel IT Tournament",
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
     * AC-TEST-RESTART-RECOVERY-CANCEL-RUNNING-RED: a stale RUNNING job with {@code cancelled=TRUE}
     * is reset to PENDING by initOnStartup(), then processed by the drain service, applying BSF
     * semantics (because cancelled=TRUE on reclaim).
     *
     * <p>Post-state contract: {@code phase_lifecycle_job.status='COMPLETED'}, {@code
     * cancelled=TRUE} retained, {@code phase.status='PREPARED'}, {@code phase.optimized=TRUE}.
     */
    @Test
    @DisplayName(
            "initOnStartup resets stale RUNNING+cancelled=TRUE job; drain completes it with"
                    + " BSF semantics")
    void staleRunningCancelledJobIsResetAndCompletedWithBsf() throws InterruptedException {
        // Insert a stale RUNNING job with cancelled=TRUE (simulates: job was cancelled before JVM
        // kill)
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled,"
                        + " claimed_by, claimed_at)"
                        + " VALUES (?, ?, ?, 'roundRobin', 1, 'RUNNING', TRUE, 'dead-jvm',"
                        + " CURRENT_TIMESTAMP)",
                jobId,
                tournamentId,
                phaseId);

        // Trigger restart-recovery
        workerRegistry.initOnStartup("this-jvm-id");

        // Poll up to 10s for the job to complete (drain runs asynchronously via worker submit)
        String finalJobStatus = null;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(200);
            finalJobStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase_lifecycle_job WHERE id = ?",
                            String.class,
                            jobId);
            if ("COMPLETED".equals(finalJobStatus)) {
                break;
            }
        }

        assertThat(finalJobStatus)
                .as(
                        "cancelled stale job must be reset to PENDING and completed by"
                                + " restart-recovery (AC-TEST-RESTART-RECOVERY-CANCEL-RUNNING-RED)")
                .isEqualTo("COMPLETED");

        // cancelled=TRUE must be retained on the completed row (DEC-64 D-16)
        Boolean cancelledFlagAfter =
                jdbcTemplate.queryForObject(
                        "SELECT cancelled FROM phase_lifecycle_job WHERE id = ?",
                        Boolean.class,
                        jobId);
        assertThat(cancelledFlagAfter)
                .as("cancelled flag must be retained as TRUE after completion")
                .isTrue();

        // phase.status must be PREPARED
        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus)
                .as("phase must be PREPARED after BSF-apply on cancel-restart")
                .isEqualTo("PREPARED");

        // phase.optimized must be TRUE (BSF applied per DEC-49 D-11a + DEC-64 D-16)
        Boolean optimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized)
                .as("phase.optimized must be TRUE (BSF applied on cancelled-restart job)")
                .isTrue();
    }
}
