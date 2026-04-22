package de.vvwt.tm.scoring;

import java.util.UUID;

/**
 * Scoring-public DTO for partial (in-progress) score updates (E22S06, AC-PUBLIC-DTOS-CREATED).
 *
 * <p>Placed in {@code de.vvwt.tm.scoring} (public package) per Q-11 transitive-exposure rule: this
 * record is a parameter type of the public {@link ScoreEntryService} interface method {@link
 * ScoreEntryService#handlePartialScore(PartialScoreInput)}, so it MUST be public-package-visible.
 *
 * <p>Field shapes reconstructed from legacy {@code
 * de.vvwt.tm.infrastructure.score.dto.PartialScoreRequest} (field-mapping table in impl-report):
 *
 * <ul>
 *   <li>{@code matchId} ← {@code PartialScoreRequest.matchId}
 *   <li>{@code setIndex} ← {@code PartialScoreRequest.setIndex}
 *   <li>{@code team1Points} ← {@code PartialScoreRequest.team1Points}
 *   <li>{@code team2Points} ← {@code PartialScoreRequest.team2Points}
 *   <li>{@code deviceToken} ← {@code PartialScoreRequest.deviceToken}
 * </ul>
 *
 * <p>The legacy DTOs remain in {@code infrastructure.score.dto.*} until the E22S11 cutover
 * (AC-LEGACY-UNTOUCHED applies to production files only, not to this new record).
 *
 * @param matchId UUID of the match being scored (NOT NULL)
 * @param setIndex 0-based index of the current set (&ge; 0)
 * @param team1Points current points for team 1 (0–99)
 * @param team2Points current points for team 2 (0–99)
 * @param deviceToken opaque device token identifying the scoring tablet (NOT NULL, NOT BLANK)
 * @since E22S06
 * @see ScoreEntryService
 */
public record PartialScoreInput(
        UUID matchId, int setIndex, int team1Points, int team2Points, String deviceToken) {}
