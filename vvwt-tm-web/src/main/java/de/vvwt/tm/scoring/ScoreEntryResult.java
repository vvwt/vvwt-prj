// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import java.util.UUID;

/**
 * Scoring-public DTO carrying the display data for a scoring tablet session (E22S06,
 * AC-PUBLIC-DTOS-CREATED).
 *
 * <p>Placed in {@code de.vvwt.tm.scoring} (public package) per Q-11 transitive-exposure rule: this
 * record is the return type of the public {@link ScoreEntryService} interface method {@link
 * ScoreEntryService#getMatchForField(int, String)}, so it MUST be public-package-visible.
 *
 * <p>Field shapes reconstructed from legacy {@code
 * de.vvwt.tm.infrastructure.score.dto.MatchScoreResponse} (field-mapping table in impl-report):
 *
 * <ul>
 *   <li>{@code matchId} ← {@code MatchScoreResponse.matchId}
 *   <li>{@code fieldNumber} ← {@code MatchScoreResponse.fieldNumber}
 *   <li>{@code lapNumber} ← {@code MatchScoreResponse.lapNumber}
 *   <li>{@code setIndex} ← {@code MatchScoreResponse.setIndex}
 *   <li>{@code team1Name} ← {@code MatchScoreResponse.team1Name}
 *   <li>{@code team2Name} ← {@code MatchScoreResponse.team2Name}
 *   <li>{@code refereeTeamName} ← {@code MatchScoreResponse.refereeTeamName}
 *   <li>{@code team1Points} ← {@code MatchScoreResponse.team1Points}
 *   <li>{@code team2Points} ← {@code MatchScoreResponse.team2Points}
 * </ul>
 *
 * <p>The legacy {@code MatchScoreResponse} remains in {@code infrastructure.score.dto.*} until the
 * E22S11 cutover. This record is the scoring-domain equivalent — a pure-domain record with no
 * REST/Jackson coupling.
 *
 * @param matchId UUID of the active match on this field
 * @param fieldNumber court field number (1-based, matches the device's assignedField)
 * @param lapNumber current lap number (1-based)
 * @param setIndex 0-based index of the current open set
 * @param team1Name display name of team 1
 * @param team2Name display name of team 2
 * @param refereeTeamName display name of the referee team; {@code null} if not assigned
 * @param team1Points current points for team 1 in the open set
 * @param team2Points current points for team 2 in the open set
 * @since E22S06
 * @see ScoreEntryService
 */
public record ScoreEntryResult(
        UUID matchId,
        int fieldNumber,
        int lapNumber,
        int setIndex,
        String team1Name,
        String team2Name,
        String refereeTeamName,
        int team1Points,
        int team2Points) {}
