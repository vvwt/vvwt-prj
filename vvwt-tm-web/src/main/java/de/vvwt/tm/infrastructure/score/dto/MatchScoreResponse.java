package de.vvwt.tm.infrastructure.score.dto;

import java.util.UUID;

/**
 * Response DTO returned by {@code GET /api/score/match?field={n}&token={t}} (E06S06, AC1).
 *
 * <p>Carries the current match state for the given court field in the active lap.
 * Used by the Mustache scoring page's inline ES5 script to populate the match display
 * and score entry area on load and after each WebSocket notification.
 *
 * <p>All text fields are data values (team names, referee name) — i18n labels for buttons
 * and headings are rendered server-side via the Mustache template / messages.properties (AC13).
 *
 * @param matchId         UUID of the active match on this field
 * @param fieldNumber     court field number (1-based, matches the device's assignedField)
 * @param lapNumber       current lap number (1-based)
 * @param setIndex        0-based index of the current open set
 * @param team1Name       display name of team 1
 * @param team2Name       display name of team 2
 * @param refereeTeamName display name of the referee team; {@code null} if not assigned
 * @param team1Points     current points for team 1 in the open set (from last partial update or 0)
 * @param team2Points     current points for team 2 in the open set (from last partial update or 0)
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
        int team2Points
) {
}
