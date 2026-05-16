// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.UUID;

/**
 * Read-only port for tournament phase overview queries (E48S05, DEC-35).
 *
 * <p>Exposes the bounded context's public read-side API. Naming convention: no {@code I} prefix per
 * DEC-35. Implementation is {@link de.vvwt.tm.tournament.internal.DefaultPhaseQueryService}.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseQueryService
 * @see PhaseOverviewResponse
 * @see <a href="DEC-35">DEC-35 — service interface in public package</a>
 * @see <a href="E48S05">E48S05 — AC-IMPL-PHASE-LIST-ENDPOINT</a>
 */
public interface PhaseQueryService {

    /**
     * Returns phase overview DTOs for all phases belonging to the given tournament, ordered by
     * {@code sequenceNumber} ascending.
     *
     * <p>Each DTO includes:
     *
     * <ul>
     *   <li>{@code id} — phase UUID
     *   <li>{@code sequenceNumber} — 1-indexed ordering
     *   <li>{@code description} — human-readable label
     *   <li>{@code status} — lifecycle status name
     *   <li>{@code gameMode} — derived from {@code tournament.draft_json} section matching this
     *       phase's {@code sequenceNumber}; {@code null} when {@code draft_json} is absent,
     *       unparseable, or no matching section (AC-PHASE-LIST-DEFENSIVE)
     *   <li>{@code currentLapNumber} — active lap index
     *   <li>{@code matchCountsByState} — match counts per state name; single aggregation query (no
     *       N+1)
     * </ul>
     *
     * @param tournamentId the tournament to query
     * @return non-null list, ordered by sequenceNumber; empty if tournament has no phases
     */
    List<PhaseOverviewResponse> listPhasesWithCounts(UUID tournamentId);

    /**
     * Returns whether a tournament with the given id exists (scoped to current tenant).
     *
     * <p>Used by the controller to distinguish 404 (tournament not found) from 200 with empty list.
     *
     * @param tournamentId the tournament UUID to check
     * @return {@code true} if the tournament exists; {@code false} otherwise
     */
    boolean tournamentExists(UUID tournamentId);
}
