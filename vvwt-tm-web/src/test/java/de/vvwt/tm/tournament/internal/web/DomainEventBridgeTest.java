package de.vvwt.tm.tournament.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.tournament.events.MatchResultChangedEvent;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

/**
 * TDD tests for {@link DomainEventBridge} (E21S10, AC-TDD-DomainEventBridge,
 * AC-DOMAIN-EVENT-BRIDGE-INTEGRATION, AC-WEBSOCKET-BROKEN-CLIENT, inventory row 450).
 *
 * <p>This test was committed RED: {@link DomainEventBridge} at {@code
 * de.vvwt.tm.tournament.internal.web} did not exist at commit time — satisfying the DEC-22 Iron
 * Law.
 *
 * <h2>AC-DOMAIN-EVENT-BRIDGE-INTEGRATION</h2>
 *
 * <p>Wire-format round-trip: S09 event → bridge → WebSocket topic → subscriber. Published via
 * {@link ApplicationEventPublisher}; received as {@link EventMessage} on {@code /topic/events}. The
 * test uses {@link org.springframework.transaction.event.TransactionalEventListener} semantics —
 * the bridge uses {@code @EventListener} for the S09 events (no transaction boundary needed for
 * this AC; the bridge decides the listener type).
 *
 * <h2>AC-WEBSOCKET-BROKEN-CLIENT</h2>
 *
 * <p>If {@link SimpMessagingTemplate#convertAndSend} throws {@link MessagingException} (simulating
 * a disconnected WebSocket session), {@link DomainEventBridge} must NOT re-throw. Verified via unit
 * test with a mocked template.
 *
 * @see DomainEventBridge
 * @see EventMessage
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S10">E21S10 — inventory row 450</a>
 */
@DisplayName("DomainEventBridge — E21S10 AC-TDD-DomainEventBridge")
class DomainEventBridgeTest {

    // =========================================================================
    // Unit tests — AC-WEBSOCKET-BROKEN-CLIENT
    // =========================================================================

    /**
     * Unit-level tests that do not require a running Spring context. Verifies behaviour when the
     * {@link SimpMessagingTemplate} throws.
     */
    @Nested
    @DisplayName("Unit: AC-WEBSOCKET-BROKEN-CLIENT")
    class BrokenClientTest {

        @Test
        @DisplayName(
                "AC-WEBSOCKET-BROKEN-CLIENT: MessagingException during broadcast does NOT"
                        + " propagate")
        void broadcastFailure_doesNotPropagate() {
            SimpMessagingTemplate mockTemplate = mock(SimpMessagingTemplate.class);
            doThrow(new MessagingException("simulated disconnect"))
                    .when(mockTemplate)
                    .convertAndSend(any(String.class), any(Object.class));

            DomainEventBridge bridge = new DomainEventBridge(mockTemplate);

            UUID tenantId = UUID.randomUUID();
            UUID matchId = UUID.randomUUID();
            MatchResultChangedEvent event =
                    new MatchResultChangedEvent(
                            this,
                            tenantId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            matchId,
                            MatchState.OPEN,
                            MatchState.CLOSED,
                            null,
                            0,
                            0,
                            UUID.randomUUID());

            // Must not throw even though the template throws MessagingException
            assertThatCode(() -> bridge.onMatchResultChanged(event)).doesNotThrowAnyException();

            // Template was called (bridge attempted broadcast)
            verify(mockTemplate).convertAndSend(eq(DomainEventBridge.EVENTS_TOPIC), any());
        }
    }

    // =========================================================================
    // Integration test — AC-DOMAIN-EVENT-BRIDGE-INTEGRATION
    // =========================================================================

    /**
     * Integration test: full wire-format round-trip via Spring context + STOMP WebSocket. The test
     * publishes a {@link MatchResultChangedEvent} via {@link ApplicationEventPublisher} and asserts
     * that a subscribed STOMP client receives an {@link EventMessage} on {@code /topic/events}
     * within 2 seconds.
     */
    @SpringBootTest(
            webEnvironment = WebEnvironment.RANDOM_PORT,
            properties = {
                "spring.datasource.url=jdbc:h2:mem:domainevtbridgedb"
                        + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
            })
    @ActiveProfiles("test")
    @DisplayName("Integration: AC-DOMAIN-EVENT-BRIDGE-INTEGRATION")
    @Nested
    class IntegrationTest {

        @Autowired private ApplicationEventPublisher publisher;

        @org.springframework.boot.test.web.server.LocalServerPort private int port;

        @Test
        @DisplayName(
                "AC-DOMAIN-EVENT-BRIDGE-INTEGRATION: MatchResultChangedEvent → /topic/events"
                        + " round-trip within 2s")
        void matchResultChangedEvent_receivedOnEventsTopic() throws Exception {
            SockJsClient sockJsClient =
                    new SockJsClient(
                            List.of(new WebSocketTransport(new StandardWebSocketClient())));
            WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
            stompClient.setMessageConverter(new MappingJackson2MessageConverter());

            // Use timer-client auth (E11S05 D-7) — no DB credentials needed for this test
            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("X-Timer-Connect", "true");
            connectHeaders.add("X-Timer-Tenant-Id", UUID.randomUUID().toString());

            StompSession session =
                    stompClient
                            .connectAsync(
                                    "http://localhost:" + port + "/ws",
                                    new WebSocketHttpHeaders(),
                                    connectHeaders,
                                    new StompSessionHandlerAdapter() {})
                            .get(5, TimeUnit.SECONDS);

            CompletableFuture<Map<?, ?>> received = new CompletableFuture<>();

            session.subscribe(
                    DomainEventBridge.EVENTS_TOPIC,
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            received.complete((Map<?, ?>) payload);
                        }
                    });

            // Small pause to ensure subscription is registered before triggering the event
            Thread.sleep(200);

            UUID matchId = UUID.randomUUID();
            UUID tenantId = UUID.randomUUID();
            publisher.publishEvent(
                    new MatchResultChangedEvent(
                            this,
                            tenantId,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            matchId,
                            MatchState.OPEN,
                            MatchState.CLOSED,
                            null,
                            0,
                            0,
                            UUID.randomUUID()));

            Map<?, ?> message = received.get(2, TimeUnit.SECONDS);
            assertThat(message)
                    .as("EventMessage must have eventType MATCH_RESULT_CHANGED")
                    .containsEntry("eventType", "MATCH_RESULT_CHANGED");
            assertThat(message.get("entityId"))
                    .as("entityId must be the matchId")
                    .isEqualTo(matchId.toString());

            session.disconnect();
        }
    }
}
