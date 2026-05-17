// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Phase.PhaseStatus;
import de.vvwt.tm.tournament.exceptions.ConflictException;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * RED-first integration tests for the verb-encoded phase transition-table + activation-guard
 * introduced by E51S05 (DEC-55 D-4 + D-6).
 *
 * <p>These tests cover:
 *
 * <ul>
 *   <li>AC-TEST-TRANSITION-TABLE-ALLOWED-PATHS-RED — every ALLOWED (source, target, verb) triple
 *       succeeds.
 *   <li>AC-TEST-TRANSITION-TABLE-DISALLOWED-REJECTED-RED — disallowed triples throw {@link
 *       IllegalStateException} naming source + target + verb.
 *   <li>AC-TEST-VERB-DISCRIMINATES-EDGES-RED — "complete" and "force-complete" both succeed from
 *       ACTIVE→COMPLETED; a phantom verb fails.
 *   <li>AC-TEST-ACTIVATION-GUARD-OPTIMIZE-FALSE-PASSES-RED — optimize=false bypasses the guard.
 *   <li>AC-TEST-ACTIVATION-GUARD-OPTIMIZED-TRUE-PASSES-RED — optimize=true + optimized=true passes.
 *   <li>AC-TEST-ACTIVATION-GUARD-FAIL-RED — optimize=true + optimized=false throws {@link
 *       ConflictException}.
 *   <li>AC-ERROR-HANDLING-NULL-VERB-REJECTED — null verb → {@link IllegalArgumentException}.
 *   <li>AC-ERROR-HANDLING-PHASE-NOT-FOUND — unknown phaseId → {@link IllegalArgumentException}.
 * </ul>
 *
 * <p>Regression coverage (GREEN path):
 *
 * <ul>
 *   <li>AC-TEST-PHASE-STATUS-CHANGED-EVENT-EMITTED-GREEN — {@link
 *       de.vvwt.tm.tournament.events.PhaseStatusChangedEvent} emitted on every successful
 *       transition.
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 — All 6 tests above are RED-first: {@link PhaseLifecycleService#transition(UUID,
 *       PhaseStatus, String)} does not exist at test-write time.
 *   <li>DEC-36 — Cross-package: this class is in {@code de.vvwt.tm.tournament} (public API
 *       package), injecting {@link PhaseLifecycleService} (the interface), never the impl class.
 *   <li>DEC-37 Clause B — verified implicitly: the lock is part of the service impl contract; IT
 *       tests exercise the full Spring TX stack.
 *   <li>DEC-44 — Service-only IT: {@code @SpringBootTest(NONE)} (same pattern as {@link
 *       TournamentLifecycleServiceIT}).
 * </ul>
 *
 * @see PhaseLifecycleService
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseLifecycleService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-55">DEC-55 D-4 + D-6 — ASSIGNED status + transition-table +
 *     activation-guard</a>
 * @see <a href="E51S05">E51S05 — story implementing this feature</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phasetransitionit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@RecordApplicationEvents
@DisplayName("PhaseTransitionIT — E51S05 RED-first transition-table + activation-guard")
class PhaseTransitionIT {

    /**
     * Subject under test — injected via the public interface per DEC-36 cross-package rule.
     *
     * <p>DEC-36: this class is in {@code de.vvwt.tm.tournament} (different from {@code
     * de.vvwt.tm.tournament.internal}), so it MUST reference {@link PhaseLifecycleService}, never
     * {@code DefaultPhaseLifecycleService}.
     */
    @Autowired private PhaseLifecycleService phaseLifecycleService;

    @Autowired private PhaseRepository phaseRepository;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private ApplicationEvents applicationEvents;

    private UUID tournamentId;
    private UUID phaseId;
    private UUID locationId;

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
                "Transition IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "Transition IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                4,
                true /* optimize=true by default */);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Vorrunde",
                "PENDING",
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
    // AC-TEST-TRANSITION-TABLE-ALLOWED-PATHS-RED
    // =========================================================================

    @Test
    @DisplayName("transition() — PENDING→PREPARED 'match-gen-done' succeeds (E51S05)")
    void transition_pendingToPrepared_matchGenDone_succeeds() {
        phaseLifecycleService.transition(phaseId, PhaseStatus.PREPARED, "match-gen-done");

        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("PREPARED");
    }

    @Test
    @DisplayName("transition() — PREPARED→ASSIGNED 'assign' succeeds (E51S05)")
    void transition_preparedToAssigned_assign_succeeds() {
        setPhaseStatus("PREPARED");

        phaseLifecycleService.transition(phaseId, PhaseStatus.ASSIGNED, "assign");

        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName("transition() — ASSIGNED→ASSIGNED 're-assign' self-loop succeeds (E51S05)")
    void transition_assignedToAssigned_reassign_succeeds() {
        setPhaseStatus("ASSIGNED");

        phaseLifecycleService.transition(phaseId, PhaseStatus.ASSIGNED, "re-assign");

        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ASSIGNED");
    }

    @Test
    @DisplayName(
            "transition() — ACTIVE→COMPLETED 'complete' succeeds (E51S05 allowed-paths coverage)")
    void transition_activeToCompleted_complete_succeeds() {
        setPhaseStatus("ACTIVE");

        phaseLifecycleService.transition(phaseId, PhaseStatus.COMPLETED, "complete");

        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName(
            "transition() — ACTIVE→COMPLETED 'force-complete' succeeds (E51S05 allowed-paths"
                    + " coverage)")
    void transition_activeToCompleted_forceComplete_succeeds() {
        setPhaseStatus("ACTIVE");

        phaseLifecycleService.transition(phaseId, PhaseStatus.COMPLETED, "force-complete");

        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
    }

    // =========================================================================
    // AC-TEST-TRANSITION-TABLE-DISALLOWED-REJECTED-RED
    // =========================================================================

    @Test
    @DisplayName("transition() — PENDING→ACTIVE 'start' is disallowed → IllegalStateException")
    void transition_disallowed_pendingToActive_throwsIllegalStateException() {
        assertThatThrownBy(
                        () ->
                                phaseLifecycleService.transition(
                                        phaseId, PhaseStatus.ACTIVE, "start"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("start");
    }

    @Test
    @DisplayName(
            "transition() — ASSIGNED→PENDING with any verb is disallowed → IllegalStateException")
    void transition_disallowed_assignedToPending_throwsIllegalStateException() {
        setPhaseStatus("ASSIGNED");

        assertThatThrownBy(
                        () ->
                                phaseLifecycleService.transition(
                                        phaseId, PhaseStatus.PENDING, "any-verb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ASSIGNED")
                .hasMessageContaining("PENDING")
                .hasMessageContaining("any-verb");
    }

    @Test
    @DisplayName(
            "transition() — ACTIVE→PREPARED with any verb is disallowed → IllegalStateException")
    void transition_disallowed_activeToPrepared_throwsIllegalStateException() {
        setPhaseStatus("ACTIVE");

        assertThatThrownBy(
                        () ->
                                phaseLifecycleService.transition(
                                        phaseId, PhaseStatus.PREPARED, "any-verb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("PREPARED")
                .hasMessageContaining("any-verb");
    }

    // =========================================================================
    // AC-TEST-VERB-DISCRIMINATES-EDGES-RED
    // =========================================================================

    @Test
    @DisplayName(
            "transition() — 'complete' and 'force-complete' both succeed from ACTIVE (verb"
                    + " discrimination)")
    void transition_verbDiscrimination_completeAndForceCompleteFromActive() {
        // Test 'complete' verb
        setPhaseStatus("ACTIVE");
        phaseLifecycleService.transition(phaseId, PhaseStatus.COMPLETED, "complete");
        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");

        // Reset for second assertion
        setPhaseStatus("ACTIVE");
        phaseLifecycleService.transition(phaseId, PhaseStatus.COMPLETED, "force-complete");
        updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName(
            "transition() — 'phantom-verb' fails ACTIVE→COMPLETED because verb not in table"
                    + " (AC-TEST-VERB-DISCRIMINATES-EDGES-RED)")
    void transition_verbDiscrimination_phantomVerbFails() {
        setPhaseStatus("ACTIVE");

        assertThatThrownBy(
                        () ->
                                phaseLifecycleService.transition(
                                        phaseId, PhaseStatus.COMPLETED, "phantom-verb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("COMPLETED")
                .hasMessageContaining("phantom-verb");
    }

    // =========================================================================
    // AC-TEST-ACTIVATION-GUARD-OPTIMIZE-FALSE-PASSES-RED
    // =========================================================================

    @Test
    @DisplayName(
            "transition() — optimize=false + optimized=false + ASSIGNED→ACTIVE 'start' succeeds"
                    + " (guard bypass)")
    void transition_activationGuard_optimizeFalse_passes() {
        setTournamentOptimize(false);
        setPhaseStatusAndOptimized("ASSIGNED", false);

        phaseLifecycleService.transition(phaseId, PhaseStatus.ACTIVE, "start");

        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-ACTIVATION-GUARD-OPTIMIZED-TRUE-PASSES-RED
    // =========================================================================

    @Test
    @DisplayName("transition() — optimize=true + optimized=true + ASSIGNED→ACTIVE 'start' succeeds")
    void transition_activationGuard_optimizeTrue_optimizedTrue_passes() {
        setTournamentOptimize(true);
        setPhaseStatusAndOptimized("ASSIGNED", true);

        phaseLifecycleService.transition(phaseId, PhaseStatus.ACTIVE, "start");

        Phase updated = phaseRepository.findById(phaseId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-ACTIVATION-GUARD-FAIL-RED
    // =========================================================================

    @Test
    @DisplayName(
            "transition() — optimize=true + optimized=false + ASSIGNED→ACTIVE 'start' → "
                    + "ConflictException with operator-actionable message (E51S05)")
    void transition_activationGuard_optimizeTrue_optimizedFalse_throwsConflictException() {
        setTournamentOptimize(true);
        setPhaseStatusAndOptimized("ASSIGNED", false);

        assertThatThrownBy(
                        () ->
                                phaseLifecycleService.transition(
                                        phaseId, PhaseStatus.ACTIVE, "start"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(phaseId.toString())
                .hasMessageContaining("optimize")
                .hasMessageContaining("optimized");
    }

    // =========================================================================
    // AC-ERROR-HANDLING-NULL-VERB-REJECTED
    // =========================================================================

    @Test
    @DisplayName(
            "transition() — null verb → IllegalArgumentException (AC-ERROR-HANDLING-NULL-VERB)")
    void transition_nullVerb_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () -> phaseLifecycleService.transition(phaseId, PhaseStatus.PREPARED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // AC-ERROR-HANDLING-PHASE-NOT-FOUND
    // =========================================================================

    @Test
    @DisplayName(
            "transition() — unknown phaseId → IllegalArgumentException before guard/table lookup"
                    + " (AC-ERROR-HANDLING-PHASE-NOT-FOUND)")
    void transition_unknownPhaseId_throwsIllegalArgumentException() {
        UUID unknownId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                phaseLifecycleService.transition(
                                        unknownId, PhaseStatus.PREPARED, "match-gen-done"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(unknownId.toString());
    }

    // =========================================================================
    // AC-TEST-PHASE-STATUS-CHANGED-EVENT-EMITTED-GREEN (regression)
    // =========================================================================

    @Test
    @DisplayName(
            "transition() — publishes PhaseStatusChangedEvent on every successful transition"
                    + " (AC-TEST-PHASE-STATUS-CHANGED-EVENT-EMITTED-GREEN)")
    void transition_success_publishesPhaseStatusChangedEvent() {
        phaseLifecycleService.transition(phaseId, PhaseStatus.PREPARED, "match-gen-done");

        long eventCount =
                applicationEvents.stream(de.vvwt.tm.tournament.events.PhaseStatusChangedEvent.class)
                        .count();
        assertThat(eventCount).isGreaterThanOrEqualTo(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void setPhaseStatus(String status) {
        jdbcTemplate.update("UPDATE phase SET status = ? WHERE id = ?", status, phaseId);
    }

    private void setPhaseStatusAndOptimized(String status, boolean optimized) {
        jdbcTemplate.update(
                "UPDATE phase SET status = ?, optimized = ? WHERE id = ?",
                status,
                optimized,
                phaseId);
    }

    private void setTournamentOptimize(boolean optimize) {
        jdbcTemplate.update(
                "UPDATE tournament SET optimize = ? WHERE id = ?", optimize, tournamentId);
    }
}
