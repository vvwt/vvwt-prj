// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import java.util.UUID;

/**
 * Builds a {@link TournamentSnapshot} from TM domain data for publication to the Info-Portal.
 *
 * <p>The snapshot captures the tournament's current public state — teams and schedule — at a given
 * sequence number. It is used by E62S02 (initial snapshot on opt-in registration) and E62S03
 * ({@code 409 FULL_RESYNC} resync).
 *
 * <p>The builder is pure read + map — no side effects, deterministic output for a given tournament
 * state.
 *
 * <h2>Snapshot scope (AC3 / reviewer F-9)</h2>
 *
 * <p>The {@link TournamentSnapshot} contract carries {@code tournamentId}, {@code tenantId}, {@code
 * sequenceNumber}, {@code scheduleEntries}, {@code teams}, and {@code tournamentEnded}. It has
 * <strong>no finished-match-results field</strong> — match scores flow only as {@code DomainEvent}
 * deltas (E62S03 scope).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 + DEC-58: public interface in bounded-context root package.
 *   <li>DEC-42 D2: {@code vvwt-tm-web → vvwt-info-dto} is the only cross-subsystem dependency.
 *   <li>DEC-21: {@code allowedDependencies} expanded to {@code {tournament, tournament::events}}.
 * </ul>
 *
 * @see de.vvwt.tm.infoportal.internal.DefaultTournamentSnapshotBuilder
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E62S01.story.md">
 *     E62S01 AC1–AC9</a>
 * @since E62S01
 */
public interface TournamentSnapshotBuilder {

    /**
     * Builds a {@link TournamentSnapshot} for the given tournament.
     *
     * <p>Loads teams, phases, matches, and phase breaks from the tournament domain and assembles
     * them into the {@code vvwt-info-dto} contract types. The snapshot contains no match result
     * data (scores are out of scope — see AC3 / reviewer F-9).
     *
     * @param tournamentId the tournament to snapshot (must exist for the active tenant)
     * @param tenantId the tenant identifier to embed in the snapshot (opaque string)
     * @param sequenceNumber the sequence number to embed in the snapshot (caller-managed)
     * @return a fully assembled {@link TournamentSnapshot}; never {@code null}
     * @throws java.util.NoSuchElementException if the tournament is not found for the active tenant
     */
    TournamentSnapshot build(UUID tournamentId, String tenantId, long sequenceNumber);
}
