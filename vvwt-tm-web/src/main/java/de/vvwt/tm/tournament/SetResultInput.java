// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Input DTO for scoring-context cascade operations (DEC-21, DEC-22, E21S05).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.SetResultInput} (READ ONLY
 * reference; not imported). Carries the score for a single set within a match, plus optional audit
 * metadata. Immutable record.
 *
 * <p>Boundary-API per inventory line 183 — consumed by {@code scoring/ScoreEntryService}.
 *
 * <h2>E31S03 amendment — {@code tournamentId} field</h2>
 *
 * <p>The {@code tournamentId} field was added in E31S03 to support the DEC-37 Clause B lock-first
 * contract: {@code DefaultScoringService.registerMatchResult(...)} acquires a pessimistic DB
 * row-lock on the {@code tournament} table row as its FIRST action, before any other repository
 * read (including the match lookup). This requires the caller to supply the {@code tournamentId}
 * directly in the input.
 *
 * <p>The field is nullable ({@code null} is permitted) for backward compatibility with legacy
 * callers (e.g., {@code CascadeRecomputeService}, existing ITs) that create {@code SetResultInput}
 * without knowing the tournament context at the call site. When {@code tournamentId} is {@code
 * null}, the legacy code path (match lookup first, then tournament) remains valid. The new {@code
 * DefaultScoringService} requires a non-null {@code tournamentId} for the lock-first contract;
 * callers that supply {@code null} will receive an {@link IllegalArgumentException}.
 *
 * @param tournamentId UUID of the tournament being scored; required by {@code
 *     DefaultScoringService} for lock-first contract (DEC-37 Clause B); may be {@code null} for
 *     legacy callers that cannot supply it
 * @param matchId UUID of the match being scored (NOT NULL)
 * @param setIndex 0-based index of the set within the match (0..maxSets-1)
 * @param team1Points points scored by team 1 in this set (must be &ge; 0)
 * @param team2Points points scored by team 2 in this set (must be &ge; 0)
 * @param actorId identity of the actor submitting the result; {@code null} in LAN/no-login mode
 * @param reason organiser-provided correction reason; {@code null} for first entries
 * @param sourceType audit source type: {@code "TABLET"}, {@code "ADMIN"}, or {@code null} for
 *     legacy callers
 * @param sourceDeviceId device UUID string when {@code sourceType} is {@code "TABLET"}; {@code
 *     null} otherwise
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-37">DEC-37 Clause B — lock-first contract</a>
 * @see <a href="E21S05">E21S05 — inventory line 183</a>
 * @see <a href="E31S03">E31S03 — tournamentId field addition for lock-first contract</a>
 */
public record SetResultInput(
        UUID tournamentId,
        UUID matchId,
        int setIndex,
        int team1Points,
        int team2Points,
        String actorId,
        String reason,
        String sourceType,
        String sourceDeviceId) {

    /** Source type constant for scoring tablet entries (E06S06, AC8). */
    public static final String SOURCE_TYPE_TABLET = "TABLET";

    /** Source type constant for admin UI corrections (E05S11, AC8). */
    public static final String SOURCE_TYPE_ADMIN = "ADMIN";

    /**
     * Compact canonical constructor — validates required fields.
     *
     * @throws NullPointerException if {@code matchId} is {@code null}
     * @throws IllegalArgumentException if {@code team1Points} or {@code team2Points} is negative,
     *     or if {@code setIndex} is negative
     */
    public SetResultInput {
        if (matchId == null) {
            throw new NullPointerException("matchId must not be null");
        }
        if (setIndex < 0) {
            throw new IllegalArgumentException("setIndex must be >= 0, got: " + setIndex);
        }
        if (team1Points < 0) {
            throw new IllegalArgumentException("team1Points must be >= 0, got: " + team1Points);
        }
        if (team2Points < 0) {
            throw new IllegalArgumentException("team2Points must be >= 0, got: " + team2Points);
        }
    }

    /**
     * Convenience factory for legacy callers that do not supply source or tournament fields.
     *
     * <p>Sets {@code tournamentId}, {@code sourceType}, and {@code sourceDeviceId} all to {@code
     * null}. The {@code tournamentId = null} is intentional — legacy callers ({@code
     * CascadeRecomputeService} and existing ITs) derive the tournament from the match at runtime.
     * The new {@code DefaultScoringService} requires a non-null {@code tournamentId}; callers that
     * need the DEC-37 lock-first contract must supply it via the full constructor or the {@link
     * #withTournament(UUID, UUID, int, int, int, String, String)} factory.
     *
     * @param matchId UUID of the match being scored (NOT NULL)
     * @param setIndex 0-based index of the set within the match
     * @param team1Points points scored by team 1 (&ge; 0)
     * @param team2Points points scored by team 2 (&ge; 0)
     * @param actorId identity of the actor; {@code null} in LAN/no-login mode
     * @param reason correction reason; {@code null} for first entries
     * @return a {@code SetResultInput} with {@code tournamentId}, {@code sourceType}, and {@code
     *     sourceDeviceId} all {@code null}
     */
    public static SetResultInput legacy(
            UUID matchId,
            int setIndex,
            int team1Points,
            int team2Points,
            String actorId,
            String reason) {
        return new SetResultInput(
                null, matchId, setIndex, team1Points, team2Points, actorId, reason, null, null);
    }

    /**
     * Convenience factory for callers that supply tournament context (DEC-37 lock-first contract).
     *
     * <p>Use this factory when the calling code knows the {@code tournamentId} at the call site.
     * This is the correct factory for {@code DefaultScoringService} callers (e.g., the post-E31S04
     * {@code ScoreEntryService}).
     *
     * @param tournamentId UUID of the tournament being scored (NOT NULL for lock-first callers)
     * @param matchId UUID of the match being scored (NOT NULL)
     * @param setIndex 0-based index of the set within the match
     * @param team1Points points scored by team 1 (&ge; 0)
     * @param team2Points points scored by team 2 (&ge; 0)
     * @param actorId identity of the actor; {@code null} in LAN/no-login mode
     * @param reason correction reason; {@code null} for first entries
     * @return a {@code SetResultInput} with {@code sourceType} and {@code sourceDeviceId} both
     *     {@code null}
     */
    public static SetResultInput withTournament(
            UUID tournamentId,
            UUID matchId,
            int setIndex,
            int team1Points,
            int team2Points,
            String actorId,
            String reason) {
        return new SetResultInput(
                tournamentId,
                matchId,
                setIndex,
                team1Points,
                team2Points,
                actorId,
                reason,
                null,
                null);
    }
}
