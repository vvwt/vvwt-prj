// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.reader.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.reader.StreamHello;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.info.persistence.tournament.TournamentRecord;
import de.vvwt.info.reader.ReaderService;
import de.vvwt.info.reader.ReaderSessionRegistry;
import de.vvwt.info.reader.config.ReaderProperties;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Low-level WebSocket handler for reader stream connections (E38S06 AC2, AC7, AC14).
 *
 * <p>Handles: connect (token validation, snapshot-on-connect, StreamHello), close (session
 * deregistration), and error (session deregistration). Client-to-server messages are ignored
 * (read-only stream).
 *
 * <p>On connect sequence (AC14):
 *
 * <ol>
 *   <li>Send {@code Envelope<StreamHello>} with {@code poll_cadence_seconds}.
 *   <li>Send {@code Envelope<TournamentSnapshot>} (per-team filtered view).
 *   <li>Register session for delta fan-out.
 * </ol>
 *
 * <p>The tournament ID is passed via a session attribute set by the {@link
 * ReaderHandshakeInterceptor} (stored as {@code "tournamentId"}) along with the resolved team entry
 * and tournament record.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC2, AC7,
 *     AC14</a>
 */
public class ReaderWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ReaderWebSocketHandler.class);

    static final String ATTR_TOURNAMENT_RECORD = "tournamentRecord";
    static final String ATTR_TEAM_ENTRY = "teamEntry";

    /**
     * Session attribute key for the raw tournament token from the URI (used for rate-limit slot
     * release, E38S07 AC13).
     */
    public static final String ATTR_TOURNAMENT_TOKEN = "tournamentToken";

    /**
     * Session attribute: {@code true} if the tournament is within the 24h supersede grace window
     * (E38S08 AC5).
     */
    static final String ATTR_WITHIN_GRACE = "withinGrace";

    private final ReaderService readerService;
    private final ReaderSessionRegistry sessionRegistry;
    private final ReaderProperties readerProperties;
    private final ObjectMapper objectMapper;
    private final de.vvwt.info.ratelimit.TournamentConcurrencyLimiter concurrencyLimiter;

    public ReaderWebSocketHandler(
            ReaderService readerService,
            ReaderSessionRegistry sessionRegistry,
            ReaderProperties readerProperties,
            ObjectMapper objectMapper,
            de.vvwt.info.ratelimit.TournamentConcurrencyLimiter concurrencyLimiter) {
        this.readerService = readerService;
        this.sessionRegistry = sessionRegistry;
        this.readerProperties = readerProperties;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        TournamentRecord tournament =
                (TournamentRecord) session.getAttributes().get(ATTR_TOURNAMENT_RECORD);
        de.vvwt.info.dto.snapshot.TeamEntry teamEntry =
                (de.vvwt.info.dto.snapshot.TeamEntry) session.getAttributes().get(ATTR_TEAM_ENTRY);
        boolean withinGrace = Boolean.TRUE.equals(session.getAttributes().get(ATTR_WITHIN_GRACE));

        if (tournament == null || teamEntry == null) {
            // Should not happen (interceptor guards); close defensively
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        // AC14: send StreamHello first
        StreamHello hello =
                new StreamHello(readerProperties.getPollCadenceSeconds(), Envelope.SCHEMA_VERSION);
        sendJson(session, new Envelope<>(Envelope.SCHEMA_VERSION, hello));

        // AC2: send per-team snapshot; E38S08 AC5: set tournamentEnded=withinGrace
        Optional<TournamentSnapshot> snapshot =
                readerService.getTeamSnapshot(tournament, teamEntry);
        if (snapshot.isPresent()) {
            TournamentSnapshot s = snapshot.get();
            // Re-wrap with tournamentEnded flag if withinGrace differs from stored value
            TournamentSnapshot withFlag =
                    withinGrace == s.tournamentEnded()
                            ? s
                            : new TournamentSnapshot(
                                    s.tournamentId(),
                                    s.tenantId(),
                                    s.sequenceNumber(),
                                    s.scheduleEntries(),
                                    s.teams(),
                                    withinGrace);
            sendJson(session, new Envelope<>(Envelope.SCHEMA_VERSION, withFlag));
        } else {
            // No state yet — send an empty snapshot wrapper
            sendJson(
                    session,
                    new Envelope<>(
                            Envelope.SCHEMA_VERSION,
                            new TournamentSnapshot(
                                    tournament.tournamentId(),
                                    tournament.tenantId(),
                                    tournament.lastAppliedSeq(),
                                    java.util.List.of(),
                                    java.util.List.of(),
                                    withinGrace)));
        }

        // Register for delta broadcasts after sending initial frames
        sessionRegistry.register(tournament.tournamentId(), session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        deregisterSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WS transport error on session {}: {}", session.getId(), exception.getMessage());
        deregisterSession(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Read-only stream — ignore client-to-server messages (AC7: no custom subprotocol)
    }

    private void deregisterSession(WebSocketSession session) {
        TournamentRecord tournament =
                (TournamentRecord) session.getAttributes().get(ATTR_TOURNAMENT_RECORD);
        if (tournament != null) {
            sessionRegistry.deregister(tournament.tournamentId(), session);
        }
        // E38S07 AC13: release per-tournament-token WS concurrency slot on close
        String tournamentToken = (String) session.getAttributes().get(ATTR_TOURNAMENT_TOKEN);
        if (tournamentToken != null) {
            concurrencyLimiter.releaseSlot(tournamentToken);
        }
    }

    private void sendJson(WebSocketSession session, Object payload) throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }
}
