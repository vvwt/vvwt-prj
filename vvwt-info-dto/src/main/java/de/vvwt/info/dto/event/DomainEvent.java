package de.vvwt.info.dto.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed domain-event hierarchy for the Public Participant Info Service (AC2).
 *
 * <p>Phase 1 closed subtypes:
 *
 * <ul>
 *   <li>{@link ScoreUpdated} — live score update for a match
 *   <li>{@link RoundCompleted} — a tournament round has completed
 *   <li>{@link MatchResultFinalized} — final result recorded for a match
 *   <li>{@link ScheduleAdded} — a schedule entry was added
 *   <li>{@link ScheduleRemoved} — a schedule entry was removed
 * </ul>
 *
 * <p>Jackson polymorphic discrimination via {@code "type"} discriminator field. Unknown
 * discriminator values produce {@link com.fasterxml.jackson.databind.exc.InvalidTypeIdException}
 * (NOT silent fallback — AC2).
 *
 * <p>Consumers can {@code switch} over {@code DomainEvent} subtypes exhaustively (Java 21 sealed
 * types + pattern matching) without a default branch when all subtypes are handled.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S02.story.md">E38S02</a>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = DomainEvent.ScoreUpdated.class, name = "SCORE_UPDATED"),
    @JsonSubTypes.Type(value = DomainEvent.RoundCompleted.class, name = "ROUND_COMPLETED"),
    @JsonSubTypes.Type(
            value = DomainEvent.MatchResultFinalized.class,
            name = "MATCH_RESULT_FINALIZED"),
    @JsonSubTypes.Type(value = DomainEvent.ScheduleAdded.class, name = "SCHEDULE_ADDED"),
    @JsonSubTypes.Type(value = DomainEvent.ScheduleRemoved.class, name = "SCHEDULE_REMOVED")
})
public sealed interface DomainEvent
        permits DomainEvent.ScoreUpdated,
                DomainEvent.RoundCompleted,
                DomainEvent.MatchResultFinalized,
                DomainEvent.ScheduleAdded,
                DomainEvent.ScheduleRemoved {

    /**
     * Live score update for a match. {@code homeScore} / {@code awayScore} are current set counts.
     */
    record ScoreUpdated(String matchId, int homeScore, int awayScore) implements DomainEvent {}

    /** A tournament round has completed. */
    record RoundCompleted(int roundNumber) implements DomainEvent {}

    /**
     * Final result recorded for a match. Once published, this result is authoritative and will not
     * change (immutable domain event).
     */
    record MatchResultFinalized(String matchId, int homeScore, int awayScore)
            implements DomainEvent {}

    /** A new schedule entry was added to the tournament schedule. */
    record ScheduleAdded(String entryId) implements DomainEvent {}

    /** A schedule entry was removed from the tournament schedule. */
    record ScheduleRemoved(String entryId) implements DomainEvent {}
}
