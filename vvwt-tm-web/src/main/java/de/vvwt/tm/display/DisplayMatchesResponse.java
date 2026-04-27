package de.vvwt.tm.display;

import java.util.List;
import java.util.UUID;

/**
 * Bounded-context-owned query-shape record for the display matches response (E25S01).
 *
 * <p>Resides at {@code de.vvwt.tm.display.*} (root, public) per DEC-40 §2026-04-27 Clarification
 * (Pattern A — bounded-context-owned query-shape records). JSON wire shape preserved verbatim per
 * Brief v2.2 C-8 + D-9 and C-3 signature-preservation.
 *
 * <p>Nested records {@code MatchEntry} and {@code SetResultEntry} are co-located per Java records
 * canon.
 *
 * @param phaseId UUID of the phase these matches belong to
 * @param lap lap number for the returned matches
 * @param matches ordered list of match entries for this lap
 * @see DisplayOverviewService
 * @see E25S01
 */
public record DisplayMatchesResponse(UUID phaseId, int lap, List<MatchEntry> matches) {

    /**
     * A single match in the display matches list.
     *
     * @param matchId UUID of the match
     * @param fieldNumber playing field number (nullable if not yet assigned)
     * @param teamAName display name for team A (resolved via TeamAvatar → Team)
     * @param teamBName display name for team B (resolved via TeamAvatar → Team)
     * @param setResults set results in play order (0-based set index)
     * @param matchStatus display-mapped status string ({@code "PENDING"}, {@code "IN_PROGRESS"},
     *     {@code "COMPLETED"})
     * @param refereeTeamName name of the referee team (may be {@code null} if none)
     */
    public record MatchEntry(
            UUID matchId,
            Integer fieldNumber,
            String teamAName,
            String teamBName,
            List<SetResultEntry> setResults,
            String matchStatus,
            String refereeTeamName) {}

    /**
     * The score of a single set within a match.
     *
     * @param setIndex 0-based index of this set within the match
     * @param scoreA points scored by team A in this set
     * @param scoreB points scored by team B in this set
     */
    public record SetResultEntry(int setIndex, int scoreA, int scoreB) {}
}
