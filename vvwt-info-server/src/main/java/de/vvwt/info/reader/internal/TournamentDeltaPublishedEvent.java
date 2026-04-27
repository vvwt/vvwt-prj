package de.vvwt.info.reader.internal;

import de.vvwt.info.dto.event.DomainEvent;

/**
 * Spring application event signaling that a delta was successfully applied to a tournament's state.
 *
 * <p>Published by the delta-application service (E38S05 scope) and consumed by {@link
 * ReaderWebSocketBroadcaster} to push the delta frame to all subscribed WS clients.
 *
 * <p>This event is the decoupling seam used by E38S06 tests: integration tests fire the event
 * directly via {@link org.springframework.context.ApplicationEventPublisher} after inserting a
 * delta row via {@link de.vvwt.info.persistence.testsupport.InfoDaoTestSupport#insertDirectly},
 * without depending on the E38S05 publisher service (AC2 test seam per story note).
 *
 * @param tournamentId the tournament that received the delta
 * @param seq the sequence number of the applied delta
 * @param event the deserialized domain event
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC2</a>
 */
public record TournamentDeltaPublishedEvent(String tournamentId, long seq, DomainEvent event) {}
