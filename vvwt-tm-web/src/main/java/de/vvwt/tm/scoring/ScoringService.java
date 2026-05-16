// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.SetResultInput;

/**
 * Public service port for the {@code scoring} bounded context (DEC-35, E31S03).
 *
 * <p>Defines the contract for cascade-recompute operations in the {@code de.vvwt.tm.scoring}
 * Modulith module. The canonical implementation is {@link
 * de.vvwt.tm.scoring.internal.DefaultScoringService}.
 *
 * <p>Consumers of this service (e.g., {@code ScoreEntryService} post-E31S04) MUST inject this
 * interface type, never the concrete implementation class ({@code DefaultScoringService}), per
 * DEC-36 cross-package test typing rule and DEC-35 ports-and-adapters discipline.
 *
 * <h2>Authoring decisions</h2>
 *
 * <ul>
 *   <li>DEC-35 — service interface in public package, implementation in {@code .internal}
 *   <li>DEC-37 Clause B — implementation acquires per-tournament pessimistic DB lock as first
 *       action
 *   <li>DEC-22 — TDD Iron Law: {@code registerMatchResult} was preceded by a failing test before
 *       implementation was written
 * </ul>
 *
 * @since E31S03
 * @see de.vvwt.tm.scoring.internal.DefaultScoringService
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic DB lock</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 */
public interface ScoringService {

    /**
     * Executes the 13-step cascade recompute flow for a single set result.
     *
     * <p>Acquires a per-tournament pessimistic DB row-lock ({@code SELECT … FOR UPDATE} on the
     * {@code tournament} table row) as the FIRST action, serialising concurrent score submissions
     * for the same tournament. All 13 steps run inside a single {@code @Transactional} boundary.
     *
     * @param input the set result to register; must not be {@code null}
     * @throws de.vvwt.tm.tournament.exceptions.ValidationException if the set score fails
     *     validation
     * @throws IllegalArgumentException if the referenced match or tournament does not exist
     * @see <a href="DEC-37">DEC-37 Clause B — lock-acquisition contract</a>
     */
    void registerMatchResult(SetResultInput input);
}
