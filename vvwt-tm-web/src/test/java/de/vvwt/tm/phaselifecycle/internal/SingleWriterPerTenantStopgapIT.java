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
 * Structural IT for E55S10 Stair-2 H-1 stopgap — {@code tm.hikari.single-writer-per-tenant=true}.
 *
 * <p>Verifies that the happy-path job drain (step-A + step-B) completes successfully when the H-1
 * stopgap is active. The stopgap wraps the per-tenant raw {@code JdbcDataSource} in a {@link
 * com.zaxxer.hikari.HikariDataSource} with {@code maximumPoolSize=1}, serializing all per-tenant H2
 * connections.
 *
 * <p>This IT validates AC-ERROR-HANDLING-PRODUCER-NOT-FOUND-STAIR-STEP-ESCALATE Stair 2: the
 * stopgap must not break the existing orchestrator pipeline (no deadlock, no connection starvation,
 * no TX rollback on happy-path).
 *
 * <p>DEC-22 Iron Law: RED-first. GREEN state: job completes as COMPLETED, phase status PREPARED,
 * optimized=true (since optimize=true and not siegerehrung).
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE web environment), DEC-64 D-12, E55S10
 * AC-ERROR-HANDLING-PRODUCER-NOT-FOUND-STAIR-STEP-ESCALATE Stair 2.
 *
 * @since E55S10
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:singlewriterit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            // Enable H-1 stopgap: per-tenant HikariDataSource with maximumPoolSize=1
            "tm.hikari.single-writer-per-tenant=true"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, SingleWriterPerTenantStopgapIT.SlotOptConfig.class})
@DisplayName(
        "SingleWriterPerTenantStopgapIT — Stair-2 H-1 stopgap (single-writer-per-tenant) (E55S10)")
class SingleWriterPerTenantStopgapIT {

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
                "SingleWriter IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "SingleWriter IT Tournament",
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
        tenantBinder.unbind();
    }

    /**
     * Verifies that a full job drain completes successfully with {@code
     * tm.hikari.single-writer-per-tenant=true}: no deadlock, no TX rollback, job reaches COMPLETED,
     * phase optimized=true.
     *
     * <p>This validates Stair-2 of AC-ERROR-HANDLING-PRODUCER-NOT-FOUND-STAIR-STEP-ESCALATE: the
     * single-writer stopgap serializes per-tenant H2 connections (eliminates H-1b concurrent
     * connection race) without breaking the orchestrator pipeline.
     */
    @Test
    @DisplayName("drain completes successfully with single-writer-per-tenant enabled")
    void drainCompletesWithSingleWriterStopgap() {
        jobDrainService.drainNext(tournamentId);

        // Job must reach COMPLETED (no deadlock / connection starvation within maximumPoolSize=1)
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus)
                .as("job must be COMPLETED after drain with single-writer stopgap")
                .isEqualTo("COMPLETED");

        // Phase must be PREPARED + optimized=true (optimize=true, non-siegerehrung)
        Integer phaseOptimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Integer.class, phaseId);
        assertThat(phaseOptimized)
                .as("phase.optimized must be true after step-B with single-writer stopgap")
                .isEqualTo(1);

        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus)
                .as("phase.status must be PREPARED after step-A")
                .isEqualTo("PREPARED");
    }
}
