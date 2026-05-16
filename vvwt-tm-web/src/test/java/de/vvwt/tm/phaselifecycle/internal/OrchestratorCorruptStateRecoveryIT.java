// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
 * RED-first IT for corrupt RUNNING-row recovery —
 * AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE.
 *
 * <p>Contract under test: {@link WorkerRegistry#initOnStartup(String)} (which delegates to {@link
 * PhaseLifecycleJobRepository#handleCorruptRunningRows()}) detects rows in {@code status=RUNNING}
 * with {@code claimed_by IS NULL} (corrupt state — not reachable through normal flow), marks them
 * {@code FAILED}, and logs a WARN. No exception is thrown.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE), DEC-64 D-7
 * (AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE chosen contract: option b — FAILED+WARN).
 *
 * @since E55S07
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:corruptrecoveryit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorCorruptStateRecoveryIT.SlotOptConfig.class})
@DisplayName(
        "OrchestratorCorruptStateRecoveryIT — AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE"
                + " (E55S07)")
class OrchestratorCorruptStateRecoveryIT {

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
                "CorruptState IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "CorruptState IT Tournament",
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
     * AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE: a corrupt row (status=RUNNING,
     * claimed_by=NULL) is marked FAILED by initOnStartup(). No exception is thrown.
     *
     * <p>Setup: insert a job row directly in RUNNING state with {@code claimed_by=NULL}. Call
     * {@code initOnStartup("this-jvm-id")} → step 1 detects and marks it FAILED with WARN. Assert:
     * no exception; final status = FAILED.
     */
    @Test
    @DisplayName("initOnStartup marks corrupt RUNNING+NULL row FAILED and does not throw")
    void corruptRunningNullRowIsMarkedFailed() {
        // Insert a corrupt row: status=RUNNING, claimed_by=NULL
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled)"
                        + " VALUES (?, ?, ?, 'roundRobin', 1, 'RUNNING', FALSE)",
                jobId,
                tournamentId,
                phaseId);

        // Verify the corrupt row was inserted correctly
        String initialStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE id = ?", String.class, jobId);
        assertThat(initialStatus)
                .as("corrupt row must be RUNNING before recovery")
                .isEqualTo("RUNNING");

        String initialClaimedBy =
                jdbcTemplate.queryForObject(
                        "SELECT claimed_by FROM phase_lifecycle_job WHERE id = ?",
                        String.class,
                        jobId);
        assertThat(initialClaimedBy).as("corrupt row must have NULL claimed_by").isNull();

        // Call initOnStartup — must NOT throw
        assertThatCode(() -> workerRegistry.initOnStartup("this-jvm-id"))
                .as("initOnStartup must not throw even when corrupt rows exist")
                .doesNotThrowAnyException();

        // The corrupt row must be marked FAILED
        String finalStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE id = ?", String.class, jobId);
        assertThat(finalStatus)
                .as(
                        "corrupt RUNNING+NULL row must be marked FAILED by"
                                + " AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE")
                .isEqualTo("FAILED");
    }

    /**
     * AC-ERROR-HANDLING-RECOVERY-STALE-CLAIM-CORRUPT-STATE — idempotency: calling
     * handleCorruptRunningRows() twice on the same row is a no-op on the second call (the row is
     * already FAILED after the first call). No exception thrown.
     */
    @Test
    @DisplayName("handleCorruptRunningRows is idempotent (second call no-op)")
    void handleCorruptRunningRowsIsIdempotent() {
        // Insert a corrupt row
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled)"
                        + " VALUES (?, ?, ?, 'roundRobin', 1, 'RUNNING', FALSE)",
                jobId,
                tournamentId,
                phaseId);

        // First call: marks corrupt row FAILED
        int firstCount = jobRepository.handleCorruptRunningRows();
        assertThat(firstCount).as("first call must mark 1 corrupt row").isEqualTo(1);

        // Second call: no corrupt rows remain (already FAILED)
        int secondCount = jobRepository.handleCorruptRunningRows();
        assertThat(secondCount).as("second call must be a no-op (0 corrupt rows)").isEqualTo(0);

        // Row is still FAILED
        String status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE id = ?", String.class, jobId);
        assertThat(status).as("corrupt row must remain FAILED").isEqualTo("FAILED");
    }
}
