// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
 * RED-first IT for AC-TEST-DEC-49-D-11-ISOLATION-RED (E55S05).
 *
 * <p>Verifies per-tournament cancel isolation: cancelling tournament A does NOT affect tournament
 * B's in-flight job (DEC-49 D-11 scope + DEC-64 D-10 per-tournament cancel flag).
 *
 * <p>Strategy:
 *
 * <ol>
 *   <li>Set cancel flag for tournament A.
 *   <li>Drain tournament A — completes (cancel observed, BSF applied).
 *   <li>Drain tournament B (no cancel flag set) — completes normally.
 *   <li>Assert B's job: {@code cancelled=FALSE}, {@code status='COMPLETED'}.
 * </ol>
 *
 * <p>Uses no-op SlotOptimizationClient (Leg 1) to keep the test deterministic.
 *
 * <p>Authorizing decisions: DEC-22 (TDD), DEC-49 D-11 (per-tournament cancel scope), DEC-64 D-10
 * (cooperative cancel flag — per-tournament UUID key).
 *
 * @since E55S05
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cancelisolationit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, CancelIsolationIT.SlotOptConfig.class})
@DisplayName("CancelIsolationIT — AC-TEST-DEC-49-D-11-ISOLATION-RED (E55S05)")
class CancelIsolationIT {

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
    @Autowired private CancelFlagRegistry cancelFlagRegistry;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentAId;
    private UUID phaseAId;
    private UUID tournamentBId;
    private UUID phaseBId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "CancelIsolation IT Location");

        tournamentAId = insertTournament("Tournament A");
        phaseAId = insertPhase(tournamentAId, 1, "Phase A");

        tournamentBId = insertTournament("Tournament B");
        phaseBId = insertPhase(tournamentBId, 1, "Phase B");

        insertTeamsAndAvatars(tournamentAId, phaseAId, 4);
        insertTeamsAndAvatars(tournamentBId, phaseBId, 4);

        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentAId, phaseAId, "roundRobin", 1));
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentBId, phaseBId, "roundRobin", 1));
    }

    @AfterEach
    void tearDown() {
        cancelFlagRegistry.clear(tournamentAId);
        cancelFlagRegistry.clear(tournamentBId);
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (UUID tid : new UUID[] {tournamentAId, tournamentBId}) {
                jdbcTemplate.update(
                        "DELETE FROM match WHERE phase_id IN"
                                + " (SELECT id FROM phase WHERE tournament_id = ?)",
                        tid);
                jdbcTemplate.update("DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tid);
                jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tid);
                jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tid);
                jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tid);
                jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tid);
            }
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-DEC-49-D-11-ISOLATION-RED: cancel A does NOT affect B.
     *
     * <p>Cancel flag set for A only; B drains without cancel. B's job must have {@code
     * cancelled=FALSE} and {@code optimized=TRUE}.
     */
    @Test
    @DisplayName("cancel tournament A — tournament B completes normally with cancelled=FALSE")
    void cancelA_doesNotAffectB() {
        // Cancel tournament A
        cancelFlagRegistry.requestCancel(tournamentAId);

        // Drain A — completes (with or without BSF, depending on Leg routing)
        jobDrainService.drainNext(tournamentAId);

        // Drain B — no cancel flag set for B
        jobDrainService.drainNext(tournamentBId);

        // Assert: B's job is COMPLETED + cancelled=FALSE
        String jobBStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentBId);
        assertThat(jobBStatus).as("B's job must be COMPLETED").isEqualTo("COMPLETED");

        Boolean cancelledB =
                jdbcTemplate.queryForObject(
                        "SELECT cancelled FROM phase_lifecycle_job WHERE tournament_id = ?",
                        Boolean.class,
                        tournamentBId);
        assertThat(cancelledB)
                .as("B's job must NOT be cancelled — cancel scope is per-tournament (DEC-49 D-11)")
                .isFalse();

        Boolean optimizedB =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseBId);
        assertThat(optimizedB).as("phase B must be optimized=TRUE (natural completion)").isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID insertTournament(String description) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                locationId,
                description,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);
        return id;
    }

    private UUID insertPhase(UUID tournamentId, int seq, String desc) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                tournamentId,
                seq,
                desc,
                "PENDING",
                0,
                false);
        return id;
    }

    private void insertTeamsAndAvatars(UUID tournamentId, UUID phaseId, int count) {
        for (int i = 1; i <= count; i++) {
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
}
