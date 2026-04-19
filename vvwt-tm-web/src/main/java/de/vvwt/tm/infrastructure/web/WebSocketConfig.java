package de.vvwt.tm.infrastructure.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Spring WebSocket + STOMP configuration for Tournament Manager V1.
 *
 * <h2>Design decisions (E05S03)</h2>
 *
 * <ul>
 *   <li><b>Transport:</b> STOMP over SockJS. STOMP provides topic-based pub/sub ({@code
 *       /topic/events}), which makes adding new event types trivial without changing WebSocket
 *       infrastructure. SockJS provides transparent long-polling fallback for restricted networks.
 *   <li><b>Endpoint:</b> {@code /ws} — clients connect to {@code ws://host/ws} or {@code
 *       http://host/ws} (SockJS fallback).
 *   <li><b>Message broker:</b> In-memory simple broker with {@code /topic} prefix. Sufficient for
 *       V1 single-tenant single-tournament model. Replace with a full broker (RabbitMQ, ActiveMQ)
 *       in a future story if needed.
 *   <li><b>Application destination prefix:</b> {@code /app} — messages sent FROM clients TO the
 *       server are routed to {@code @MessageMapping} methods. Not used in V1 (server-to-client
 *       only) but registered for future extensibility.
 * </ul>
 *
 * <h2>Security (AC6)</h2>
 *
 * <p>Authentication on the STOMP CONNECT frame is enforced by {@link WebSocketSecurityConfig}. The
 * HTTP-level {@code /ws/**} path is permitted in {@link de.vvwt.tm.auth.internal.SecurityConfig} to
 * allow the upgrade handshake through — Spring WebSocket then enforces auth at the STOMP protocol
 * level.
 *
 * <h2>Extensibility (AC5 notes)</h2>
 *
 * <p>Later stories add new event types by:
 *
 * <ol>
 *   <li>Declaring a new Spring ApplicationEvent subclass.
 *   <li>Adding an {@code @TransactionalEventListener} in {@link DomainEventBridge}.
 * </ol>
 *
 * No change to this config class is required.
 *
 * @see WebSocketSecurityConfig
 * @see DomainEventBridge
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story
 *     E05S03</a>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Registers the STOMP WebSocket endpoint at {@code /ws}.
     *
     * <p>SockJS fallback is enabled so clients on restricted networks can use long-polling or
     * server-sent events transparently.
     *
     * <p>Allowed origins: {@code *} in V1 single-tenant LAN deployment. The admin UI is always
     * same-origin (served from the same Spring Boot instance), so this wildcard does not open a
     * cross-origin attack surface in V1. If a V2 story introduces separate-origin deployments,
     * restrict this to an explicit list.
     *
     * @param registry the STOMP endpoint registry
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }

    /**
     * Configures the in-memory message broker.
     *
     * <ul>
     *   <li>{@code /topic} — server-to-client broadcasts (e.g., {@code /topic/events})
     *   <li>{@code /app} — client-to-server messages routed to {@code @MessageMapping} methods
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
