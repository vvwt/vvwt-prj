package de.vvwt.info.reader.internal;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * Thread-safe registry mapping tournament IDs to their active WebSocket reader sessions.
 *
 * <p>Lifecycle:
 *
 * <ul>
 *   <li>{@link #register(String, WebSocketSession)}: called on WS connect after HMAC validation.
 *   <li>{@link #deregister(String, WebSocketSession)}: called on WS close/error (AC13 — server does
 *       not retain state beyond the active socket).
 *   <li>{@link #sessionsFor(String)}: used by the broadcaster to fan-out delta frames.
 * </ul>
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC13</a>
 */
@Component
public class ReaderSessionRegistry {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByTournament =
            new ConcurrentHashMap<>();

    /**
     * Registers a WebSocket session for the given tournament.
     *
     * @param tournamentId the tournament the client is subscribing to
     * @param session the newly opened WebSocket session
     */
    public void register(String tournamentId, WebSocketSession session) {
        sessionsByTournament
                .computeIfAbsent(
                        tournamentId,
                        ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(session);
    }

    /**
     * Removes a WebSocket session from the registry.
     *
     * @param tournamentId the tournament the session was subscribed to
     * @param session the session that closed
     */
    public void deregister(String tournamentId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByTournament.get(tournamentId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByTournament.remove(tournamentId, sessions);
            }
        }
    }

    /**
     * Returns a snapshot of all active sessions for the given tournament.
     *
     * @param tournamentId the tournament to look up
     * @return unmodifiable set of active sessions; empty if none
     */
    public Set<WebSocketSession> sessionsFor(String tournamentId) {
        Set<WebSocketSession> sessions = sessionsByTournament.get(tournamentId);
        return sessions != null ? Set.copyOf(sessions) : Set.of();
    }
}
