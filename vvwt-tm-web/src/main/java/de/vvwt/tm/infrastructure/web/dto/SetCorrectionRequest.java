package de.vvwt.tm.infrastructure.web.dto;

/**
 * Request body for set result correction and entry endpoints (AC2, AC3 — E05S11).
 *
 * <p>Used for:
 * <ul>
 *   <li>{@code PUT /api/matches/{matchId}/sets/{setIndex}} — correct existing set (AC2)</li>
 *   <li>{@code POST /api/matches/{matchId}/sets} — enter new set result (AC3)</li>
 * </ul>
 *
 * @param team1Points new score for team 1 (must be &ge; 0)
 * @param team2Points new score for team 2 (must be &ge; 0)
 */
public record SetCorrectionRequest(
        int team1Points,
        int team2Points
) {}
