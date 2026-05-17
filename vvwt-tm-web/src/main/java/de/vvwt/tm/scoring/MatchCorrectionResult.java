// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.MatchState;

/**
 * Result of a {@link MatchCorrectionService#correctMatchSets(MatchCorrectionInput)} operation
 * (E48S25, AC-DOMAIN-MATCH-CORRECTION-RESULT).
 *
 * <p>Bounded-context-owned result record of the {@code scoring} module.
 *
 * @param newMatchState the derived match state after the correction cascade; for audit-only
 *     (CANCELED) corrections, this reflects the match's current persisted state (unchanged)
 * @param auditOnly {@code true} if the correction was applied in audit-only mode (CANCELED match);
 *     {@code false} for full cascade corrections
 * @see MatchCorrectionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public record MatchCorrectionResult(MatchState newMatchState, boolean auditOnly) {}
