package de.vvwt.info.reader.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.event.DomainEvent;
import de.vvwt.info.reader.ReaderSessionRegistry;
import de.vvwt.info.reader.ReaderWebSocketBroadcaster;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Broadcasts delta frames to all subscribed WebSocket sessions when a delta is applied to a
 * tournament's state (E38S06 AC2).
 *
 * <p>Listens for {@link TournamentDeltaPublishedEvent} via Spring's {@link EventListener} mechanism
 * and fans out an {@code Envelope<DomainEvent>} JSON frame to every registered session for the
 * affected tournament.
 *
 * <p>Sessions that fail to receive the frame (closed between registry lookup and send) are silently
 * removed from the registry (AC13 — server does not retain state beyond the active socket).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC2</a>
 */
@Component
public class DefaultReaderWebSocketBroadcaster implements ReaderWebSocketBroadcaster {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultReaderWebSocketBroadcaster.class);

    private final ReaderSessionRegistry registry;
    private final ObjectMapper objectMapper;

    public DefaultReaderWebSocketBroadcaster(
            ReaderSessionRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles a {@link TournamentDeltaPublishedEvent}: serialises the delta as {@code
     * Envelope<DomainEvent>} and sends it to all subscribed sessions.
     *
     * @param event the published delta event
     */
    @Override
    @EventListener
    public void onDeltaPublished(TournamentDeltaPublishedEvent event) {
        Set<WebSocketSession> sessions = registry.sessionsFor(event.tournamentId());
        if (sessions.isEmpty()) {
            return;
        }

        DomainEvent domainEvent = event.event();
        String json;
        try {
            json =
                    objectMapper.writeValueAsString(
                            new Envelope<>(Envelope.SCHEMA_VERSION, domainEvent));
        } catch (JsonProcessingException e) {
            log.error(
                    "Failed to serialize delta event for tournament {}: {}",
                    event.tournamentId(),
                    e.getMessage());
            return;
        }

        TextMessage message = new TextMessage(json);
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                registry.deregister(event.tournamentId(), session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(message);
                }
            } catch (IOException e) {
                log.warn(
                        "Failed to send delta to session {} for tournament {}: {}",
                        session.getId(),
                        event.tournamentId(),
                        e.getMessage());
                registry.deregister(event.tournamentId(), session);
            }
        }
    }
}
