package de.vvwt.tm.domain.activity;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable helper encapsulating the "busy" teams (playing or refereeing) per lap.
 *
 * <p>Used by {@link FirstFreeRoundAssigner} to determine which teams are free in a given lap
 * without exposing the raw map pair to the algorithm.
 *
 * <p>A team is considered busy in a lap if it appears in either the match schedule (playing) or the
 * referee schedule (refereeing). A free team is neither playing nor refereeing in that lap.
 *
 * @see FirstFreeRoundAssigner
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S04.story.md">Story
 *     E08S04 AC1, AC2</a>
 */
final class LapSchedule {

    /** Lap → set of team IDs playing in that lap. Never null; inner sets never null. */
    private final Map<Integer, Set<UUID>> matchSchedule;

    /** Lap → set of team IDs refereeing in that lap. Never null; inner sets never null. */
    private final Map<Integer, Set<UUID>> refereeSchedule;

    /**
     * Constructs a lap schedule from the raw input maps.
     *
     * @param matchSchedule lap → playing team IDs (NOT NULL)
     * @param refereeSchedule lap → refereeing team IDs (NOT NULL)
     */
    LapSchedule(Map<Integer, Set<UUID>> matchSchedule, Map<Integer, Set<UUID>> refereeSchedule) {
        if (matchSchedule == null) {
            throw new IllegalArgumentException("matchSchedule must not be null");
        }
        if (refereeSchedule == null) {
            throw new IllegalArgumentException("refereeSchedule must not be null");
        }
        this.matchSchedule = matchSchedule;
        this.refereeSchedule = refereeSchedule;
    }

    /**
     * Returns {@code true} if the given team is busy (playing or refereeing) in the given lap.
     *
     * @param lapNumber the lap to check (1-indexed)
     * @param teamId the team to check (NOT NULL)
     * @return true if the team is playing or refereeing in that lap
     */
    boolean isBusy(int lapNumber, UUID teamId) {
        Set<UUID> playing = matchSchedule.getOrDefault(lapNumber, Collections.emptySet());
        Set<UUID> refereeing = refereeSchedule.getOrDefault(lapNumber, Collections.emptySet());
        return playing.contains(teamId) || refereeing.contains(teamId);
    }
}
