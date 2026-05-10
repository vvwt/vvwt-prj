package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
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
 * RED-first integration tests for atomic tournament-status auto-promotion on phase lifecycle
 * transitions (E48S24).
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-AUTO-ACTIVATE-FIRST-PHASE-START-RED — {@code start(phaseId)}: PLANNED→ACTIVE
 *       auto-promote when tournament.status=="PLANNED"
 *   <li>AC-TEST-AUTO-COMPLETE-LAST-PHASE-COMPLETE-RED — {@code complete(phaseId)}: ACTIVE→COMPLETED
 *       auto-promote when tournament.status=="ACTIVE" + isLastPhase
 *   <li>AC-TEST-AUTO-COMPLETE-NON-LAST-PHASE-NOOP-RED — intermediate phase complete: tournament
 *       stays ACTIVE
 *   <li>AC-TEST-FORCECOMPLETE-LAST-PHASE-NO-AUTO-COMPLETE-RED — {@code forceComplete(phaseId)}:
 *       tournament stays ACTIVE (Notabschluss excluded per T-8)
 *   <li>AC-TEST-IDEMPOTENT-AUTO-ACTIVATE-RED — tournament already ACTIVE: start() does not
 *       re-promote
 *   <li>AC-TEST-IDEMPOTENT-AUTO-COMPLETE-RED — tournament already COMPLETED: complete() does not
 *       re-promote
 *   <li>AC-TEST-MANUAL-ACTIVATE-REGRESSION-GREEN — manual POST /activate retained additive
 *   <li>AC-TEST-MANUAL-COMPLETE-REGRESSION-GREEN — manual POST /complete retained additive
 *   <li>AC-TEST-MANUAL-COMPLETE-AFTER-FORCECOMPLETE-LAST-PHASE-GREEN — escalation-pfad recovery
 * </ul>
 *
 * <h2>DEC-22 Iron Law (RED-first)</h2>
 *
 * <p>Tests AC-TEST-AUTO-ACTIVATE-*, AC-TEST-AUTO-COMPLETE-LAST-*, AC-TEST-AUTO-COMPLETE-NON-LAST-*,
 * AC-TEST-FORCECOMPLETE-*, AC-TEST-IDEMPOTENT-AUTO-ACTIVATE-*, AC-TEST-IDEMPOTENT-AUTO-COMPLETE-*
 * are RED-first: committed failing BEFORE the corresponding production code changes in {@link
 * de.vvwt.tm.tournament.internal.DefaultPhaseLifecycleService}.
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This class is in {@code de.vvwt.tm.tournament} (the public API package). All injected service
 * references use public interfaces ({@link PhaseLifecycleService}, {@link
 * TournamentLifecycleService}).
 *
 * <h2>DEC-38 + DEC-44</h2>
 *
 * <p>These are bounded-context ITs (not web-module ITs), so {@code @SpringBootTest(NONE)} is the
 * correct annotation per {@link PhaseTransitionIT} / {@link TournamentLifecycleServiceIT} pattern.
 * DEC-44 Sub-Clause-3 web-module carve-out does NOT apply.
 *
 * @see PhaseLifecycleService
 * @see TournamentLifecycleService
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseLifecycleService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-38">DEC-38 — @SpringBootTest canon for bounded-context ITs</a>
 * @see <a href="DEC-44">DEC-44 — web-module carve-out (NOT applicable here)</a>
 * @see <a href="DEC-59">DEC-59 Clause E — Siegerehrung as ceremonial tournament-completion
 *     gesture</a>
 * @see <a href="E48S24">E48S24 — Atomic tournament-status auto-promote story</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:autopromote_it;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("PhaseAutoPromoteIT — E48S24 RED-first auto-promote on phase start/complete")
class PhaseAutoPromoteIT {

    /** Inject via public interface per DEC-36 cross-package test typing rule. */
    @Autowired private PhaseLifecycleService phaseLifecycleService;

    /** Inject via public interface per DEC-36. Used for manual-endpoint regression tests. */
    @Autowired private TournamentLifecycleService tournamentLifecycleService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phase1Id; // sequenceNumber=1 (first/only in single-phase scenarios)
    private UUID phase2Id; // sequenceNumber=2 (last phase in multi-phase scenarios)

    // =========================================================================
    // Setup / Teardown
    // =========================================================================

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "AutoPromote IT Location");

        tournamentId = UUID.randomUUID();
        // Default tournament state: PLANNED, optimize=false (avoid slot-opt guard complexity)
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "AutoPromote IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                4,
                false /* optimize=false: bypass activation-guard (E51S05 AC) */);

        phase1Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phase1Id,
                tournamentId,
                1,
                "Vorrunde",
                "ASSIGNED",
                0,
                LocalDateTime.now(),
                false);

        phase2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phase2Id,
                tournamentId,
                2,
                "Siegerehrung",
                "ASSIGNED",
                0,
                LocalDateTime.now(),
                false);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-AUTO-ACTIVATE-FIRST-PHASE-START-RED
    // =========================================================================

    @Test
    @DisplayName(
            "start(): tournament PLANNED → auto-promotes to ACTIVE when first phase starts"
                    + " [AC-TEST-AUTO-ACTIVATE-FIRST-PHASE-START-RED]")
    void start_tournamentPlanned_autoPromotesToActive() {
        // Pre-condition: tournament is PLANNED
        assertThat(tournamentStatus()).isEqualTo("PLANNED");

        phaseLifecycleService.start(phase1Id);

        // (a) phase.status == ACTIVE
        assertThat(phaseStatus(phase1Id)).isEqualTo("ACTIVE");

        // (b) tournament.status == ACTIVE (auto-promoted)
        assertThat(tournamentStatus())
                .as("tournament should be auto-promoted to ACTIVE when first phase starts")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-AUTO-COMPLETE-LAST-PHASE-COMPLETE-RED
    // =========================================================================

    @Test
    @DisplayName(
            "complete(): tournament ACTIVE + last phase → auto-promotes tournament to COMPLETED"
                    + " [AC-TEST-AUTO-COMPLETE-LAST-PHASE-COMPLETE-RED]")
    void complete_lastPhase_tournamentActive_autoPromotesToCompleted() {
        // Set up: tournament ACTIVE, phase1 COMPLETED (predecessor done), phase2 ACTIVE (last)
        setTournamentStatus("ACTIVE");
        setPhaseStatus(phase1Id, "COMPLETED");
        setPhaseStatus(phase2Id, "ACTIVE");

        phaseLifecycleService.complete(phase2Id);

        // (a) phase.status == COMPLETED
        assertThat(phaseStatus(phase2Id)).isEqualTo("COMPLETED");

        // (b) tournament.status == COMPLETED (auto-promoted)
        assertThat(tournamentStatus())
                .as("tournament should be auto-promoted to COMPLETED when last phase completes")
                .isEqualTo("COMPLETED");
    }

    // =========================================================================
    // AC-TEST-AUTO-COMPLETE-NON-LAST-PHASE-NOOP-RED
    // =========================================================================

    @Test
    @DisplayName(
            "complete(): non-last phase complete → tournament stays ACTIVE"
                    + " [AC-TEST-AUTO-COMPLETE-NON-LAST-PHASE-NOOP-RED]")
    void complete_nonLastPhase_tournamentRemainsActive() {
        // Set up: tournament ACTIVE, phase1 ACTIVE (NOT the last), phase2 still ASSIGNED
        setTournamentStatus("ACTIVE");
        setPhaseStatus(phase1Id, "ACTIVE");
        // phase2 remains ASSIGNED (is the last phase by sequenceNumber=2)

        phaseLifecycleService.complete(phase1Id);

        // (a) phase.status == COMPLETED
        assertThat(phaseStatus(phase1Id)).isEqualTo("COMPLETED");

        // (b) tournament.status == ACTIVE (unchanged — NOT the last phase)
        assertThat(tournamentStatus())
                .as("tournament should remain ACTIVE when a non-last phase completes")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-FORCECOMPLETE-LAST-PHASE-NO-AUTO-COMPLETE-RED
    // =========================================================================

    @Test
    @DisplayName(
            "forceComplete(): last phase → tournament stays ACTIVE (Notabschluss excluded, T-8)"
                    + " [AC-TEST-FORCECOMPLETE-LAST-PHASE-NO-AUTO-COMPLETE-RED]")
    void forceComplete_lastPhase_tournamentRemainsActive() {
        // Set up: tournament ACTIVE, phase2 ACTIVE (last phase)
        setTournamentStatus("ACTIVE");
        setPhaseStatus(phase1Id, "COMPLETED");
        setPhaseStatus(phase2Id, "ACTIVE");

        phaseLifecycleService.forceComplete(phase2Id);

        // (a) phase.status == COMPLETED
        assertThat(phaseStatus(phase2Id)).isEqualTo("COMPLETED");

        // (b) tournament.status == ACTIVE (NOT auto-promoted — forceComplete excluded per T-8)
        assertThat(tournamentStatus())
                .as("tournament should NOT be auto-promoted on forceComplete (Notabschluss, T-8)")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-IDEMPOTENT-AUTO-ACTIVATE-RED
    // =========================================================================

    @Test
    @DisplayName(
            "start(): tournament already ACTIVE → start() succeeds, tournament status unchanged"
                    + " [AC-TEST-IDEMPOTENT-AUTO-ACTIVATE-RED]")
    void start_tournamentAlreadyActive_statusUnchanged() {
        // Tournament already ACTIVE (e.g., operator activated manually first)
        setTournamentStatus("ACTIVE");

        phaseLifecycleService.start(phase1Id);

        // phase ACTIVE
        assertThat(phaseStatus(phase1Id)).isEqualTo("ACTIVE");

        // tournament stays ACTIVE (no double-promote)
        assertThat(tournamentStatus())
                .as("tournament status should be unchanged when already ACTIVE")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-IDEMPOTENT-AUTO-COMPLETE-RED
    // =========================================================================

    @Test
    @DisplayName(
            "complete(): tournament already COMPLETED → complete() succeeds, status unchanged"
                    + " [AC-TEST-IDEMPOTENT-AUTO-COMPLETE-RED]")
    void complete_tournamentAlreadyCompleted_statusUnchanged() {
        // Tournament already COMPLETED (e.g., operator completed manually first)
        setTournamentStatus("COMPLETED");
        setPhaseStatus(phase1Id, "COMPLETED");
        setPhaseStatus(phase2Id, "ACTIVE");

        phaseLifecycleService.complete(phase2Id);

        assertThat(phaseStatus(phase2Id)).isEqualTo("COMPLETED");

        // tournament status stays COMPLETED (no error, no re-promote)
        assertThat(tournamentStatus())
                .as("tournament status should remain COMPLETED when already COMPLETED")
                .isEqualTo("COMPLETED");
    }

    // =========================================================================
    // AC-TEST-MANUAL-ACTIVATE-REGRESSION-GREEN (confirmatory — must pass before + after impl)
    // =========================================================================

    @Test
    @DisplayName(
            "TournamentLifecycleService.activate(): manual activation retained additive"
                    + " [AC-TEST-MANUAL-ACTIVATE-REGRESSION-GREEN]")
    void manualActivate_tournamentPlanned_setsStatusActive() {
        // Manual activation endpoint retained (D-2, Q-1)
        Tournament result = tournamentLifecycleService.activate(tournamentId);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(tournamentStatus()).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-MANUAL-COMPLETE-REGRESSION-GREEN (confirmatory)
    // =========================================================================

    @Test
    @DisplayName(
            "TournamentLifecycleService.complete(): manual complete retained additive"
                    + " [AC-TEST-MANUAL-COMPLETE-REGRESSION-GREEN]")
    void manualComplete_tournamentActive_setsStatusCompleted() {
        setTournamentStatus("ACTIVE");

        Tournament result = tournamentLifecycleService.complete(tournamentId);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(tournamentStatus()).isEqualTo("COMPLETED");
    }

    // =========================================================================
    // AC-TEST-MANUAL-COMPLETE-AFTER-FORCECOMPLETE-LAST-PHASE-GREEN (escalation-pfad)
    // =========================================================================

    @Test
    @DisplayName(
            "Manual complete after forceComplete last phase: escalation-pfad recovery works"
                    + " [AC-TEST-MANUAL-COMPLETE-AFTER-FORCECOMPLETE-LAST-PHASE-GREEN]")
    void manualComplete_afterForceCompletLastPhase_succeeds() {
        // End state of AC-TEST-FORCECOMPLETE-LAST-PHASE-NO-AUTO-COMPLETE-RED:
        // tournament ACTIVE, last phase COMPLETED via forceComplete
        setTournamentStatus("ACTIVE");
        setPhaseStatus(phase1Id, "COMPLETED");
        setPhaseStatus(phase2Id, "COMPLETED"); // already force-completed

        // Operator manually invokes POST /api/tournaments/{id}/complete
        Tournament result = tournamentLifecycleService.complete(tournamentId);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(tournamentStatus()).isEqualTo("COMPLETED");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String tournamentStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
    }

    private String phaseStatus(UUID phaseId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
    }

    private void setTournamentStatus(String status) {
        jdbcTemplate.update("UPDATE tournament SET status = ? WHERE id = ?", status, tournamentId);
    }

    private void setPhaseStatus(UUID phaseId, String status) {
        jdbcTemplate.update("UPDATE phase SET status = ? WHERE id = ?", status, phaseId);
    }
}
