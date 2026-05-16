// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Spring WebSocket + STOMP configuration for the {@code tournament} bounded context (E21S10,
 * AC-TDD-WebSocketConfig, AC-PKG-WebSocketConfig, AC-WEBSOCKET-CONFIG-INTEGRATION, inventory row
 * 462).
 *
 * <h2>DEC-21 package discipline</h2>
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.internal.web.*}: its effects (WebSocket broker) are
 * global, but the class is tournament-internal implementation per D-8.
 *
 * <h2>Configuration choices — MUST match legacy WebSocketConfig exactly
 * (AC-WEBSOCKET-CONFIG-INTEGRATION)</h2>
 *
 * <ul>
 *   <li><b>Endpoint:</b> {@code /ws} + SockJS — matches legacy exactly
 *   <li><b>Topic prefix:</b> {@code /topic} — existing clients subscribe to {@code /topic/events}
 *       and {@code /topic/display/{tenantId}/events}
 *   <li><b>App destination prefix:</b> {@code /app} — server-to-client only in V1; registered for
 *       future extensibility
 * </ul>
 *
 * <h2>Bean identity</h2>
 *
 * <p>Named {@code "tmWebSocketConfig"} to avoid colliding with the legacy {@code
 * de.vvwt.tm.infrastructure.web.WebSocketConfig} during the parallel phase.
 *
 * @see DomainEventBridge
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, internal vs public package</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, @Configuration TDD pattern</a>
 * @see <a href="E21S10">E21S10 — inventory row 462</a>
 */
@Configuration("tmWebSocketConfig")
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Registers the STOMP WebSocket endpoint at {@code /ws} (AC-WEBSOCKET-CONFIG-INTEGRATION).
     *
     * <p>Endpoint path and SockJS configuration match the legacy {@code
     * de.vvwt.tm.infrastructure.web.WebSocketConfig} exactly. Allowed origins: {@code *} for V1 LAN
     * deployment (admin UI is same-origin; no separate-origin attack surface in V1).
     *
     * @param registry the STOMP endpoint registry
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    /**
     * Configures the in-memory message broker (AC-WEBSOCKET-CONFIG-INTEGRATION).
     *
     * <ul>
     *   <li>{@code /topic} — server-to-client broadcasts
     *   <li>{@code /app} — client-to-server (unused in V1, registered for extensibility)
     * </ul>
     *
     * @param registry the message broker registry
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
