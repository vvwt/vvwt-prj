package de.vvwt.tm.infrastructure.score.dto;

import java.util.UUID;

/**
 * Response DTO returned by {@code GET /api/score/match?field={n}&token={t}} (E06S06, E06S07).
 *
 * <p>Carries the current match state for the given court field in the active lap.
 * Used by the Mustache scoring page's inline ES5 script to populate the match display,
 * score entry area, multi-set progression, and side-swap logic.
 *
 * <p>All text fields are data values (team names, referee name) — i18n labels for buttons
 * and headings are rendered server-side via the Mustache template / messages.properties (AC13).
 *
 * <h2>E06S07 additions</h2>
 * <ul>
 *   <li>{@code matchFormat} — name of the {@link de.vvwt.tm.domain.MatchFormat} enum constant
 *       (e.g. {@code "BEST_OF_3"}) so the client can detect FIXED_2_SETS, BEST_OF_1, etc.</li>
 *   <li>{@code maxSets} — maximum number of sets for this match format (AC8: set counter)</li>
 *   <li>{@code tiebreakSwapThreshold} — point score at which teams swap sides in the deciding set
 *       (AC5); value comes from {@link de.vvwt.tm.infrastructure.score.ScoringConfig}</li>
 *   <li>{@code team1SetsWon} — sets won by team 1 so far (AC2: match-decided check, AC6: summary)</li>
 *   <li>{@code team2SetsWon} — sets won by team 2 so far (AC2: match-decided check, AC6: summary)</li>
 *   <li>{@code matchDecided} — {@code true} when the match is in a terminal state (AC2, AC6)</li>
 *   <li>{@code matchWinner} — {@code "TEAM1"}, {@code "TEAM2"}, {@code "STANDOFF"}, or {@code null}
 *       when not yet decided (AC6: match result summary)</li>
 * </ul>
 *
 * @param matchId                UUID of the active match on this field
 * @param fieldNumber            court field number (1-based, matches the device's assignedField)
 * @param lapNumber              current lap number (1-based)
 * @param setIndex               0-based index of the current open set
 * @param team1Name              display name of team 1
 * @param team2Name              display name of team 2
 * @param refereeTeamName        display name of the referee team; {@code null} if not assigned
 * @param team1Points            current points for team 1 in the open set
 * @param team2Points            current points for team 2 in the open set
 * @param matchFormat            match format name (e.g. {@code "BEST_OF_3"})
 * @param maxSets                maximum sets in this format
 * @param tiebreakSwapThreshold  point score for tie-break side swap (AC5)
 * @param team1SetsWon           sets won by team 1 so far
 * @param team2SetsWon           sets won by team 2 so far
 * @param matchDecided           true if the match has a terminal state
 * @param matchWinner            "TEAM1", "TEAM2", "STANDOFF", or null if ongoing
 */
public record MatchScoreResponse(
        UUID matchId,
        int fieldNumber,
        int lapNumber,
        int setIndex,
        String team1Name,
        String team2Name,
        String refereeTeamName,
        int team1Points,
        int team2Points,
        // E06S07 multi-set fields
        String matchFormat,
        int maxSets,
        int tiebreakSwapThreshold,
        int team1SetsWon,
        int team2SetsWon,
        boolean matchDecided,
        String matchWinner
) {
}
