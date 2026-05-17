// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.reader.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.ratelimit.TournamentConcurrencyLimiter;
import de.vvwt.info.reader.ReaderService;
import de.vvwt.info.reader.ReaderSessionRegistry;
import de.vvwt.info.reader.config.ReaderProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Spring WebSocket configuration for the reader stream endpoint (E38S06 AC7).
 *
 * <p>Registers the WebSocket handler at {@code /api/v1/stream/{tournament_token}/{team_token}} with
 * the {@link ReaderHandshakeInterceptor} that performs token validation before the WS upgrade.
 *
 * <p>AC7: "Spring Boot 4 WebSocket handler; JSON envelope frames; no custom subprotocol." Low-level
 * {@link org.springframework.web.socket.handler.TextWebSocketHandler} is used — NOT STOMP (which
 * would be a custom subprotocol).
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S06.story.md">E38S06 AC7</a>
 */
@Configuration
@EnableWebSocket
public class ReaderWebSocketConfig implements WebSocketConfigurer {

    private final ReaderService readerService;
    private final ReaderSessionRegistry sessionRegistry;
    private final ReaderProperties readerProperties;
    private final ObjectMapper objectMapper;
    private final TournamentConcurrencyLimiter concurrencyLimiter;

    public ReaderWebSocketConfig(
            ReaderService readerService,
            ReaderSessionRegistry sessionRegistry,
            ReaderProperties readerProperties,
            ObjectMapper objectMapper,
            TournamentConcurrencyLimiter concurrencyLimiter) {
        this.readerService = readerService;
        this.sessionRegistry = sessionRegistry;
        this.readerProperties = readerProperties;
        this.objectMapper = objectMapper;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(
                        new ReaderWebSocketHandler(
                                readerService,
                                sessionRegistry,
                                readerProperties,
                                objectMapper,
                                concurrencyLimiter),
                        "/api/v1/stream/{tournament_token}/{team_token}")
                .addInterceptors(new ReaderHandshakeInterceptor(readerService))
                .setAllowedOrigins("*"); // AC9: no origin restriction for self-hosted QR URLs
    }
}
