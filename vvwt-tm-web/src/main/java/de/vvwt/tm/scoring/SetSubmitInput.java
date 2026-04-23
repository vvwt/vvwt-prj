package de.vvwt.tm.scoring;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Scoring-public DTO for final set result submissions (E22S06, AC-PUBLIC-DTOS-CREATED).
 *
 * <p>Placed in {@code de.vvwt.tm.scoring} (public package) per Q-11 transitive-exposure rule: this
 * record is a parameter type of the public {@link ScoreEntryService} interface method {@link
 * ScoreEntryService#submitSetResult(SetSubmitInput)}, so it MUST be public-package-visible.
 *
 * <p>Field shapes reconstructed from legacy {@code
 * de.vvwt.tm.infrastructure.score.dto.SetSubmitRequest} (field-mapping table in impl-report):
 *
 * <ul>
 *   <li>{@code matchId} ← {@code SetSubmitRequest.matchId}
 *   <li>{@code setIndex} ← {@code SetSubmitRequest.setIndex}
 *   <li>{@code team1Points} ← {@code SetSubmitRequest.team1Points}
 *   <li>{@code team2Points} ← {@code SetSubmitRequest.team2Points}
 *   <li>{@code deviceToken} ← {@code SetSubmitRequest.deviceToken}
 * </ul>
 *
 * <p>The legacy DTOs remain in {@code infrastructure.score.dto.*} until the E22S11 cutover.
 *
 * @param matchId UUID of the match whose set result is being submitted (NOT NULL)
 * @param setIndex 0-based index of the completed set (&ge; 0)
 * @param team1Points final points for team 1 (0–99)
 * @param team2Points final points for team 2 (0–99)
 * @param deviceToken opaque device token identifying the scoring tablet (NOT NULL, NOT BLANK)
 * @since E22S06
 * @see ScoreEntryService
 */
public record SetSubmitInput(
        @NotNull UUID matchId,
        @Min(0) int setIndex,
        @Min(0) @Max(99) int team1Points,
        @Min(0) @Max(99) int team2Points,
        @NotBlank String deviceToken) {}
