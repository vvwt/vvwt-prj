package de.vvwt.tm.infrastructure.score.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/score/partial} — partial score update (E06S06, AC4).
 *
 * <p>Sent by the scoring tablet on every button press or direct input change. The server validates
 * the device token, verifies field ownership (AC12), and broadcasts the partial update via
 * WebSocket to admin UI clients.
 *
 * <p>No set result is persisted — this is a live score broadcast only. The final set result is
 * submitted via {@link SetSubmitRequest} when the scorekeeper confirms the set.
 *
 * @param matchId UUID of the match being scored (NOT NULL)
 * @param setIndex 0-based index of the current set (&ge; 0)
 * @param team1Points current points for team 1 (0–99)
 * @param team2Points current points for team 2 (0–99)
 * @param deviceToken opaque device token identifying the scoring tablet (NOT NULL, NOT BLANK)
 */
public record PartialScoreRequest(
        @NotNull UUID matchId,
        @Min(0) int setIndex,
        @Min(0) @Max(99) int team1Points,
        @Min(0) @Max(99) int team2Points,
        @NotBlank String deviceToken) {}
