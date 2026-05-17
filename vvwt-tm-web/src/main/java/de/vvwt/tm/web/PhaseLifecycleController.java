// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for phase lifecycle status transitions (DEC-40, E48S06).
 *
 * <p>Primary-adapter-isolation: lives in {@code de.vvwt.tm.web} per DEC-40 Clause A. Delegates all
 * business logic to {@link PhaseLifecycleService}.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/phases/{id}/prepare — PENDING → PREPARED (E48S17)
 *   <li>POST /api/phases/{id}/start — PREPARED → ACTIVE (E48S17 refactor; predecessor must be
 *       COMPLETED)
 *   <li>POST /api/phases/{id}/complete — ACTIVE → COMPLETED (only when all matches finished)
 *   <li>POST /api/phases/{id}/force-complete — ACTIVE → COMPLETED + void unfinished matches
 * </ul>
 *
 * <p>Invalid transitions throw {@link de.vvwt.tm.tournament.exceptions.ConflictException}, which is
 * mapped to HTTP 409 by {@code GlobalExceptionHandler}.
 *
 * <h2>Security</h2>
 *
 * <p>All endpoints require authenticated Admin per AC-SECURITY-PHASE-LIFECYCLE-AUTH. Secured via
 * global {@code SecurityFilterChain} (Spring Security HTTP Basic). Anonymous requests → 401.
 *
 * @see PhaseLifecycleService
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S06">E48S06 — Phase-Lifecycle Service + Controller</a>
 */
@RestController("tmPhaseLifecycleController")
@RequestMapping("/api/phases")
public class PhaseLifecycleController {

    private final PhaseLifecycleService phaseLifecycleService;

    public PhaseLifecycleController(
            @Qualifier("tmPhaseLifecycleService") PhaseLifecycleService phaseLifecycleService) {
        this.phaseLifecycleService = phaseLifecycleService;
    }

    // -------------------------------------------------------------------------
    // POST /api/phases/{id}/prepare — PENDING → PREPARED (E48S17)
    // (AC-IMPL-PHASE-LIFECYCLE-CONTROLLER-PREPARE-ENDPOINT, AC-SECURITY-PHASE-LIFECYCLE-AUTH)
    // -------------------------------------------------------------------------

    /**
     * Transitions the phase from {@code PENDING} to {@code PREPARED} (E48S17 / E51S06 rollback of
     * E48S21).
     *
     * <p>Pure status flip only (PENDING → PREPARED). Avatar persistence and match generation are
     * handled by separate pipeline steps (E51S02 DraftConfig-Apply + E51S03 match-gen background
     * job). The slot payload is no longer accepted here — this endpoint ignores any request body.
     *
     * <p>Idempotent: returns 200 OK with the current phase if already PREPARED.
     *
     * @param id the phase UUID (from path)
     * @return 200 OK with the updated phase; 409 if invalid transition
     */
    @PostMapping("/{id}/prepare")
    public ResponseEntity<Phase> prepare(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(phaseLifecycleService.prepare(id));
    }

    // -------------------------------------------------------------------------
    // POST /api/phases/{id}/start — PREPARED → ACTIVE (E48S17 refactor)
    // (AC-IMPL-PHASE-LIFECYCLE-CONTROLLER, AC-SECURITY-PHASE-LIFECYCLE-AUTH)
    // -------------------------------------------------------------------------

    /**
     * Transitions the phase from {@code PREPARED} to {@code ACTIVE} (E48S17 refactor).
     *
     * <p>Requires predecessor phase (if any) to be COMPLETED.
     *
     * @param id the phase UUID (from path)
     * @return 200 OK with the updated phase; 404 if not found; 409 if invalid transition
     */
    @PostMapping("/{id}/start")
    public ResponseEntity<Phase> start(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(phaseLifecycleService.start(id));
    }

    // -------------------------------------------------------------------------
    // POST /api/phases/{id}/complete — ACTIVE → COMPLETED (all matches must be finished)
    // -------------------------------------------------------------------------

    /**
     * Transitions the phase from {@code ACTIVE} to {@code COMPLETED}.
     *
     * <p>Returns HTTP 409 if unfinished matches remain (operator-actionable message).
     *
     * @param id the phase UUID (from path)
     * @return 200 OK with the updated phase; 404 if not found; 409 if invalid transition or
     *     unfinished matches
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<Phase> complete(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(phaseLifecycleService.complete(id));
    }

    // -------------------------------------------------------------------------
    // POST /api/phases/{id}/force-complete — ACTIVE → COMPLETED + void unfinished matches
    // -------------------------------------------------------------------------

    /**
     * Force-completes the phase (Notabschluss): transitions {@code ACTIVE} to {@code COMPLETED} and
     * voids all unfinished matches.
     *
     * <p>Admin-only operation. All unfinished matches in the tournament are cancelled.
     *
     * @param id the phase UUID (from path)
     * @return 200 OK with the updated phase; 404 if not found; 409 if invalid transition
     */
    @PostMapping("/{id}/force-complete")
    public ResponseEntity<Phase> forceComplete(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(phaseLifecycleService.forceComplete(id));
    }
}
