package de.vvwt.info.reader;

import java.util.Set;
import org.springframework.web.socket.WebSocketSession;

/**
 * Thread-safe registry mapping tournament IDs to their active WebSocket reader sessions (E38S06
 * AC13).
 *
 * @see de.vvwt.info.reader.internal.DefaultReaderSessionRegistry
 * @see <a href="../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC13</a>
 */
public interface ReaderSessionRegistry {

    /**
     * Registers a WebSocket session for the given tournament.
     *
     * @param tournamentId the tournament the client is subscribing to
     * @param session the newly opened WebSocket session
     */
    void register(String tournamentId, WebSocketSession session);

    /**
     * Removes a WebSocket session from the registry.
     *
     * @param tournamentId the tournament the session was subscribed to
     * @param session the session that closed
     */
    void deregister(String tournamentId, WebSocketSession session);

    /**
     * Returns a snapshot of all active sessions for the given tournament.
     *
     * @param tournamentId the tournament to look up
     * @return unmodifiable set of active sessions; empty if none
     */
    Set<WebSocketSession> sessionsFor(String tournamentId);

    /**
     * Returns a snapshot of ALL sessions across all tournaments (for stale-slot sweeping).
     *
     * @return flat set of all currently-tracked WebSocket sessions
     */
    Set<WebSocketSession> allSessions();
}
