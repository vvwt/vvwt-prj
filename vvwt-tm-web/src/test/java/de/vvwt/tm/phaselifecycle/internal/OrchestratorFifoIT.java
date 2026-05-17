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
import java.util.List;
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
 * RED-first IT for FIFO job ordering within a tournament —
 * AC-TEST-FIFO-WITHIN-TOURNAMENT-VIA-ORCHESTRATOR-RED.
 *
 * <p>DEC-22 Iron Law: RED before GREEN. At RED time, {@code drainNext()} throws {@code
 * UnsupportedOperationException}. GREEN state: 3 jobs for the same tournament in sequence {1, 2, 3}
 * are processed in FIFO order — {@code completed_at} is monotonically increasing with {@code
 * sequence}.
 *
 * <p>The per-tournament single-thread worker (DEC-64 D-3) guarantees structural FIFO serialization
 * within a tournament. This test verifies the DB-visible ordering via {@code completed_at}.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44, DEC-64 D-4 (FIFO ordering via sequence),
 * AC-TEST-FIFO-WITHIN-TOURNAMENT-VIA-ORCHESTRATOR-RED.
 *
 * @since E55S04
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratorfifoiit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorFifoIT.SlotOptConfig.class})
@DisplayName("OrchestratorFifoIT — AC-TEST-FIFO-WITHIN-TOURNAMENT-VIA-ORCHESTRATOR-RED (E55S04)")
class OrchestratorFifoIT {

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
    private UUID tournamentId;
    private UUID phaseId1;
    private UUID phaseId2;
    private UUID phaseId3;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "FIFO IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "FIFO IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);

        // Create 3 phases with sequenceNumbers 1, 2, 3
        phaseId1 = createPhase(1);
        phaseId2 = createPhase(2);
        phaseId3 = createPhase(3);

        // Create teams and avatars for all 3 phases
        for (int seq = 1; seq <= 3; seq++) {
            UUID phId = seq == 1 ? phaseId1 : (seq == 2 ? phaseId2 : phaseId3);
            for (int i = 1; i <= 4; i++) {
                UUID teamId = UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO team (id, tournament_id, team_number, description,"
                                + " participate, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                        teamId,
                        tournamentId,
                        (seq - 1) * 4 + i,
                        "Team " + ((seq - 1) * 4 + i),
                        true,
                        LocalDateTime.now());
                UUID avatarId = UUID.randomUUID();
                jdbcTemplate.update(
                        "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                                + " group_position, team_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        avatarId,
                        tournamentId,
                        phId,
                        1,
                        i,
                        teamId);
            }
        }

        // Enqueue 3 jobs — the FIFO order should be determined by sequence values
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId1, "roundRobin", 1));
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId2, "roundRobin", 2));
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId3, "roundRobin", 3));
    }

    private UUID createPhase(int sequenceNumber) {
        UUID phId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phId,
                tournamentId,
                sequenceNumber,
                "Phase " + sequenceNumber,
                "PENDING",
                0,
                false);
        return phId;
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
     * AC-TEST-FIFO-WITHIN-TOURNAMENT-VIA-ORCHESTRATOR-RED: 3 jobs for same tournament with sequence
     * {1, 2, 3} complete in FIFO order — completed_at is monotonically increasing.
     */
    @Test
    @DisplayName("3 jobs drain in FIFO sequence order (completed_at monotonically increasing)")
    void threeJobsDrainInFifoOrder() {
        // Drain all 3 jobs sequentially (simulating a worker loop)
        jobDrainService.drainNext(tournamentId);
        jobDrainService.drainNext(tournamentId);
        jobDrainService.drainNext(tournamentId);

        // All 3 jobs must be COMPLETED
        Integer completedCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase_lifecycle_job WHERE tournament_id = ?"
                                + " AND status = 'COMPLETED'",
                        Integer.class,
                        tournamentId);
        assertThat(completedCount).as("all 3 jobs must be COMPLETED").isEqualTo(3);

        // completed_at must be monotonically increasing with sequence
        List<String> completedAtBySequence =
                jdbcTemplate.queryForList(
                        "SELECT CAST(completed_at AS VARCHAR) FROM phase_lifecycle_job"
                                + " WHERE tournament_id = ? ORDER BY sequence ASC",
                        String.class,
                        tournamentId);
        assertThat(completedAtBySequence).as("must have 3 completed_at values").hasSize(3);
        assertThat(completedAtBySequence.get(0))
                .as("seq=1 completed_at <= seq=2 completed_at")
                .isLessThanOrEqualTo(completedAtBySequence.get(1));
        assertThat(completedAtBySequence.get(1))
                .as("seq=2 completed_at <= seq=3 completed_at")
                .isLessThanOrEqualTo(completedAtBySequence.get(2));
    }
}
