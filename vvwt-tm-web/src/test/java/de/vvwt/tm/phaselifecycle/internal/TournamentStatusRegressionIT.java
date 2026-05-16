// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression IT for M-3 (tournament.status ACTIVE→PLANNED regression) —
 * AC-TEST-M-3-TOURNAMENT-STATUS-NO-REGRESSION-RED.
 *
 * <h2>M-3 regression background</h2>
 *
 * <p>Under the E1 (events-only) architecture, starting the first phase auto-promoted the tournament
 * from PLANNED→ACTIVE via {@link PhaseLifecycleService#transition} (E48S24 D-1a). However, a
 * concurrent {@code @TransactionalEventListener(AFTER_COMMIT)} listener issuing a separate DB write
 * in its own transaction could see a stale tournament snapshot and revert the status back to
 * PLANNED. This was observed 2026-05-10/11 as the "M-3 regression".
 *
 * <p>The E3 orchestrator (DEC-64) eliminates the event-listener cascade. In E3, the PLANNED→ACTIVE
 * auto-promote and all phase-status writes happen in the same transaction without concurrent
 * listener interference. This IT verifies that after starting Phase 1 (ASSIGNED→ACTIVE),
 * tournament.status remains ACTIVE.
 *
 * <p>Authorizing decisions: DEC-22 (GREEN-on-E3: regression surface structurally absent per Session
 * Brief §M-3, timestamp 2026-05-11), DEC-44 (NONE), AC-TEST-M-3-TOURNAMENT-STATUS-NO-REGRESSION-RED
 * (E55S07).
 *
 * @since E55S07
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:m3regressionit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName(
        "TournamentStatusRegressionIT — AC-TEST-M-3-TOURNAMENT-STATUS-NO-REGRESSION-RED (E55S07)")
class TournamentStatusRegressionIT {

    @Autowired private PhaseLifecycleService phaseLifecycleService;
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
                "M3 Regression IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "M3 Regression IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                4,
                false); // optimize=false so activation-guard does not block ASSIGNED→ACTIVE

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "ASSIGNED",
                0,
                true); // optimized=true → activation-guard allows start
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
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
     * AC-TEST-M-3-TOURNAMENT-STATUS-NO-REGRESSION-RED: starting Phase 1 auto-promotes tournament
     * PLANNED→ACTIVE (E48S24 D-1a); subsequent DB reads must show tournament.status=ACTIVE.
     *
     * <p>On E1 codebase: concurrent listener reverted tournament to PLANNED. On E3 codebase: no
     * listener interference — tournament.status remains ACTIVE. Test passes GREEN on E3.
     */
    @Test
    @DisplayName(
            "M-3 regression: tournament.status is ACTIVE after Phase 1 start; no reversion to"
                    + " PLANNED (E3 arch eliminates concurrent listener cascade)")
    void tournamentStatusRemainsActiveAfterPhaseStart() {
        // Verify tournament starts in PLANNED
        String initialStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(initialStatus)
                .as("tournament must start in PLANNED before phase start")
                .isEqualTo("PLANNED");

        // Start Phase 1: ASSIGNED → ACTIVE via start() — E48S24 D-1a auto-promotes tournament
        // PLANNED→ACTIVE. Must use start() (not transition()) because only start() contains the
        // auto-promote logic.
        phaseLifecycleService.start(phaseId);

        // Assert: tournament.status = ACTIVE (auto-promoted by E48S24 D-1a)
        String tournamentStatusAfterStart =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(tournamentStatusAfterStart)
                .as(
                        "M-3 regression check: tournament.status must be ACTIVE after first phase"
                                + " start (E48S24 D-1a auto-promote). On E1 codebase, concurrent"
                                + " listener reverted this to PLANNED (observed 2026-05-11).")
                .isEqualTo("ACTIVE");

        // Assert: phase.status = ACTIVE
        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus)
                .as("phase must be ACTIVE after start transition")
                .isEqualTo("ACTIVE");
    }

    /**
     * AC-TEST-M-3-TOURNAMENT-STATUS-NO-REGRESSION-REPEATED: 10 consecutive iterations of the
     * PLANNED→ACTIVE promotion — tournament.status must be ACTIVE in every iteration.
     *
     * <p>The 10-iteration loop surfaces any residual non-determinism that a single run might miss.
     * Under E3 (no listener cascade), all iterations pass deterministically.
     */
    @Test
    @DisplayName(
            "M-3 regression: tournament remains ACTIVE in 10/10 consecutive start iterations"
                    + " (no listener reversion)")
    void tournamentStatusRemainsActiveIn10ConsecutiveStartIterations() {
        for (int i = 0; i < 10; i++) {
            // Reset state: tournament PLANNED, phase ASSIGNED, optimized=true
            jdbcTemplate.update(
                    "UPDATE tournament SET status = 'PLANNED' WHERE id = ?", tournamentId);
            jdbcTemplate.update(
                    "UPDATE phase SET status = 'ASSIGNED', optimized = TRUE WHERE id = ?", phaseId);

            // Transition: ASSIGNED → ACTIVE via start() (auto-promotes tournament per E48S24 D-1a)
            phaseLifecycleService.start(phaseId);

            String tournamentStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM tournament WHERE id = ?",
                            String.class,
                            tournamentId);
            assertThat(tournamentStatus)
                    .as("iteration %d: tournament.status must be ACTIVE (M-3 regression check)", i)
                    .isEqualTo("ACTIVE");
        }
    }
}
