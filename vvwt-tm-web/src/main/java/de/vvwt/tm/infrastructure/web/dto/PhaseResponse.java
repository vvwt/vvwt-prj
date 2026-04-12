package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;

import java.util.Map;
import java.util.UUID;

/**
 * REST response for phase detail and phase list entries (AC2, AC3 — E05S07; E05S08 adds sortType/groupCount).
 */
public record PhaseResponse(
        UUID id,
        UUID tournamentId,
        int  sequenceNumber,
        String description,
        String status,
        int currentLapNumber,
        int totalLapCount,
        String sortType,
        int groupCount,
        PhaseMatchCountsResponse matchCounts
) {
    /**
     * Builds the response from a {@link Phase} entity plus the derived fields.
     *
     * @param phase         the phase entity
     * @param totalLapCount total number of laps (derived from distinct lapNumbers on matches)
     * @param matchCounts   match counts by state
     * @return the REST response
     */
    public static PhaseResponse from(Phase phase, int totalLapCount, Map<MatchState, Long> matchCounts) {
        return new PhaseResponse(
                phase.getId(),
                phase.getTournamentId(),
                phase.getSequenceNumber(),
                phase.getDescription(),
                phase.getStatus(),
                phase.getCurrentLapNumber(),
                totalLapCount,
                phase.getSortType(),
                phase.getGroupCount(),
                PhaseMatchCountsResponse.from(matchCounts)
        );
    }
}
