// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

/**
 * Public service port for operator match-score corrections in the {@code scoring} bounded context
 * (DEC-35, DEC-58, E48S25).
 *
 * <p>Defines the correction-cascade contract. Eligible match states:
 *
 * <ul>
 *   <li>{@code OPEN}, {@code ENABLED} — Nacherfassung: operator enters score for a not-yet-started
 *       match; full cascade runs with a single DEC-37 Clause B lock
 *   <li>{@code FINISHED_WINNER1}, {@code FINISHED_WINNER2}, {@code FINISHED_STANDOFF} — correction
 *       of an already-finished match; full cascade runs
 *   <li>{@code CANCELED} — audit-only; no cascade, no lock; only {@code audit_log} rows are written
 * </ul>
 *
 * <p>Guard rules (throw before any DB write):
 *
 * <ul>
 *   <li>Phase must be {@code ACTIVE} — otherwise {@link
 *       de.vvwt.tm.tournament.exceptions.PhaseStateGuardException} (HTTP 409)
 *   <li>Match must not be {@code INPROGRESS} or {@code ONCHECK} — otherwise {@link
 *       de.vvwt.tm.tournament.exceptions.MatchStateGuardException} (HTTP 409)
 *   <li>Submitted scores must not derive {@code FINISHED_STANDOFF} on a non-tie-format match —
 *       otherwise {@link de.vvwt.tm.tournament.exceptions.StandoffFormatMismatchException} (HTTP
 *       422)
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 — interface in public package {@code de.vvwt.tm.scoring}; implementation in {@code
 *       de.vvwt.tm.scoring.internal}
 *   <li>DEC-37 Clause B — implementation acquires {@code findByIdForUpdate} FIRST for non-CANCELED
 *       matches; CANCELED path skips lock
 *   <li>DEC-58 — interface naming: {@code MatchCorrectionService} / {@code
 *       DefaultMatchCorrectionService}
 *   <li>DEC-65 — implementation MUST NOT touch {@code phase.currentLapNumber}
 *   <li>DEC-22 — all methods are RED-first; implementation follows test authoring
 * </ul>
 *
 * @since E48S25
 * @see de.vvwt.tm.scoring.internal.DefaultMatchCorrectionService
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-37">DEC-37 Clause B — pessimistic DB lock</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate</a>
 * @see <a href="DEC-65">DEC-65 — correction MUST NOT touch currentLapNumber</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public interface MatchCorrectionService {

    /**
     * Corrects match set scores.
     *
     * <p>For non-CANCELED eligible match states, acquires the DEC-37 Clause B per-tournament
     * pessimistic DB lock ONCE before iterating all sets. For CANCELED matches, runs in audit-only
     * mode (no lock, no cascade, no WebSocket event).
     *
     * @param input the correction input; must not be {@code null}
     * @return the correction result with the new match state and {@code auditOnly} flag
     * @throws de.vvwt.tm.tournament.exceptions.PhaseStateGuardException if the phase is not ACTIVE
     * @throws de.vvwt.tm.tournament.exceptions.MatchStateGuardException if the match is INPROGRESS
     *     or ONCHECK
     * @throws de.vvwt.tm.tournament.exceptions.StandoffFormatMismatchException if the submitted
     *     scores would derive FINISHED_STANDOFF on a non-tie-format match
     * @throws java.util.NoSuchElementException if the match is not found (tenant-scoped 404)
     */
    MatchCorrectionResult correctMatchSets(MatchCorrectionInput input);
}
