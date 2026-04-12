package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.MatchCorrectionService;

/**
 * A single set result entry in the match detail response (AC1, AC2, AC3 — E05S11).
 *
 * <p>Note: this DTO is distinct from {@link SetResultSummary} (E05S07 schedule response)
 * because it also exposes the {@code setState} field for display in the correction view.
 *
 * @param setIndex    0-based index of the set within the match
 * @param team1Points score for team 1 in this set
 * @param team2Points score for team 2 in this set
 * @param setState    set state name (e.g., {@code "WINNER1"}, {@code "OPEN"})
 */
public record SetResultDetailEntry(
        int setIndex,
        int team1Points,
        int team2Points,
        String setState
) {

    /**
     * Converts a domain {@link MatchCorrectionService.SetResultEntry} to a DTO.
     *
     * @param entry the domain record (must not be {@code null})
     * @return the DTO
     */
    public static SetResultDetailEntry from(MatchCorrectionService.SetResultEntry entry) {
        return new SetResultDetailEntry(
                entry.setIndex(),
                entry.team1Points(),
                entry.team2Points(),
                entry.setState()
        );
    }
}
