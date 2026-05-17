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
 * RED-first happy-path IT for {@link
 * de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleOrchestrator} —
 * AC-TEST-HAPPY-PATH-IT-RED.
 *
 * <p>DEC-22 Iron Law: RED before GREEN. At RED time, {@code drainNext()} throws {@code
 * UnsupportedOperationException}. GREEN state: job completes, phase is PREPARED with
 * optimized=TRUE, matches have non-null lap+field from L2.
 *
 * <p>Uses {@code @SpringBootTest(NONE)} with full Spring context (real transactional infrastructure
 * required) + a no-op {@link SlotOptimizationClient} override to avoid calling the real routing
 * client.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE web environment), DEC-64 D-12 (T-claim
 * → T-step-A → T-step-B), DEC-56 D-1 (L1+L2 always run).
 *
 * @since E55S04
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratorhappypathit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorHappyPathIT.SlotOptConfig.class})
@DisplayName("OrchestratorHappyPathIT — AC-TEST-HAPPY-PATH-IT-RED (E55S04)")
class OrchestratorHappyPathIT {

    /** No-op slot-opt override: returns immediately, sets lap+field to 1 via L2 baseline. */
    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // No-op: L2 already set lap+field; this is a no-op for testing purposes
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
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "HappyPath IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "HappyPath IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2, // fieldCount = 2
                4, // 4 teams → 6 matches, 3 laps (round-robin with 4 teams on 2 courts)
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

        // Insert 4 participating teams
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
            // Insert team_avatar for each team (required by MatchGenerator)
            UUID avatarId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    avatarId,
                    tournamentId,
                    phaseId,
                    1, // group 1
                    i, // position i
                    teamId);
        }

        // Enqueue a job for the phase
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
     * AC-TEST-HAPPY-PATH-IT-RED: after drainNext(), the job is COMPLETED, phase is PREPARED with
     * optimized=TRUE, matches have non-null lap+field.
     *
     * <p>RED: throws UnsupportedOperationException before orchestrator is implemented. GREEN: all
     * assertions pass.
     */
    @Test
    @DisplayName(
            "drainNext() completes job, sets phase PREPARED + optimized=TRUE, matches have"
                    + " lap+field")
    void happyPathJobCompletesWithMatchesLapAndField() {
        // When
        jobDrainService.drainNext(tournamentId);

        // Then: job is COMPLETED
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus).as("job must be COMPLETED").isEqualTo("COMPLETED");

        String completedAt =
                jdbcTemplate.queryForObject(
                        "SELECT CAST(completed_at AS VARCHAR) FROM phase_lifecycle_job"
                                + " WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(completedAt).as("completed_at must be set").isNotNull();

        // Then: phase is PREPARED + optimized=TRUE
        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus).as("phase must be PREPARED").isEqualTo("PREPARED");

        Boolean optimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized).as("phase must be optimized=true").isTrue();

        // Then: matches exist with non-null lap+field (L2 assigned coordinates)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(matchCount).as("matches must exist after L1").isGreaterThan(0);

        Integer nullLapCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND lap_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullLapCount).as("all matches must have lap_number from L2").isEqualTo(0);

        Integer nullFieldCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND field_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullFieldCount).as("all matches must have field_number from L2").isEqualTo(0);
    }
}
