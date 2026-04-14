package de.vvwt.tm.infrastructure.score.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /api/score/submit} — final set result submission (E06S06, AC5, AC6, AC7, AC8).
 *
 * <p>Sent by the scoring tablet when the scorekeeper confirms a set result via the
 * confirmation dialog. The server validates the device token (AC10, AC12), passes the
 * result through {@link de.vvwt.tm.domain.rules.SetValidationRule} (AC6), and invokes
 * {@link de.vvwt.tm.domain.CascadeRecomputeService#registerMatchResult} (AC7).
 * The audit log entry records {@code source_type = 'TABLET'} and {@code source_device_id}
 * from the device UUID (AC8).
 *
 * @param matchId      UUID of the match whose set result is being submitted (NOT NULL)
 * @param setIndex     0-based index of the completed set (&ge; 0)
 * @param team1Points  final points for team 1 (0–99)
 * @param team2Points  final points for team 2 (0–99)
 * @param deviceToken  opaque device token identifying the scoring tablet (NOT NULL, NOT BLANK)
 */
public record SetSubmitRequest(
        @NotNull UUID matchId,
        @Min(0) int setIndex,
        @Min(0) @Max(99) int team1Points,
        @Min(0) @Max(99) int team2Points,
        @NotBlank String deviceToken
) {
}
