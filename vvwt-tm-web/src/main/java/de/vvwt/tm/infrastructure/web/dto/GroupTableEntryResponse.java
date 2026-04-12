package de.vvwt.tm.infrastructure.web.dto;

import de.vvwt.tm.domain.LiveMonitoringService.GroupTableEntry;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST response for one entry in a group standings table (AC1, AC2 — E05S10).
 *
 * <p>Wraps {@link GroupTableEntry} from {@link de.vvwt.tm.domain.LiveMonitoringService}
 * for serialization. The {@code setQuotient} and {@code ballQuotient} fields use
 * {@code null} to represent the sentinel {@link Double#MAX_VALUE} (undefeated teams),
 * which the SPA renders as "∞" (AC8 — no infinity in JSON).
 *
 * @see de.vvwt.tm.infrastructure.web.MonitoringController
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S10.story.md">Story E05S10</a>
 */
public record GroupTableEntryResponse(
        int rank,
        UUID avatarId,
        String teamDescription,
        int groupNumber,
        int groupPosition,
        int matchCount,
        int points,
        int setsWon,
        int setsLost,
        /** null when undefeated (setsLost == 0); client renders as "∞" */
        Double setQuotient,
        int ballsWon,
        int ballsLost,
        /** null when undefeated (ballsLost == 0); client renders as "∞" */
        Double ballQuotient,
        boolean withoutAssessment
) {
    /**
     * Converts a domain group table entry to a REST response.
     *
     * <p>{@link Double#MAX_VALUE} sentinel quotients are mapped to {@code null}
     * so that JSON consumers receive a clearly typed "no-value" rather than the
     * sentinel magic number.
     *
     * @param entry the domain entry (must not be {@code null})
     * @return the REST response
     */
    public static GroupTableEntryResponse from(GroupTableEntry entry) {
        Double setQ = entry.setQuotient() == Double.MAX_VALUE ? null : entry.setQuotient();
        Double ballQ = entry.ballQuotient() == Double.MAX_VALUE ? null : entry.ballQuotient();
        return new GroupTableEntryResponse(
                entry.rank(),
                entry.avatarId(),
                entry.teamDescription(),
                entry.groupNumber(),
                entry.groupPosition(),
                entry.matchCount(),
                entry.points(),
                entry.setsWon(),
                entry.setsLost(),
                setQ,
                entry.ballsWon(),
                entry.ballsLost(),
                ballQ,
                entry.isWithoutAssessment()
        );
    }

    /**
     * Converts a list of domain group table entries to REST responses.
     *
     * @param entries the domain entries (must not be {@code null})
     * @return list of REST responses; never {@code null}
     */
    public static List<GroupTableEntryResponse> fromList(List<GroupTableEntry> entries) {
        return entries.stream()
                .map(GroupTableEntryResponse::from)
                .collect(Collectors.toList());
    }
}
