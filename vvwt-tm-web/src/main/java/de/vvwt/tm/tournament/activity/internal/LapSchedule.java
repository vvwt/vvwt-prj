package de.vvwt.tm.tournament.activity.internal;

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
 * <p>This is an {@code internal} type — not accessible outside {@code
 * de.vvwt.tm.tournament.activity.internal.*}.
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code de.vvwt.tm.domain.activity.LapSchedule}.
 *
 * @see FirstFreeRoundAssigner
 */
final class LapSchedule {

    private final Map<Integer, Set<UUID>> matchSchedule;
    private final Map<Integer, Set<UUID>> refereeSchedule;

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

    boolean isBusy(int lapNumber, UUID teamId) {
        Set<UUID> playing = matchSchedule.getOrDefault(lapNumber, Collections.emptySet());
        Set<UUID> refereeing = refereeSchedule.getOrDefault(lapNumber, Collections.emptySet());
        return playing.contains(teamId) || refereeing.contains(teamId);
    }
}
