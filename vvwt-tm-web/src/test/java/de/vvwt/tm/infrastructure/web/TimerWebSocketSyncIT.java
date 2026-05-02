package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.events.LapAdvancedEvent;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
import de.vvwt.tm.tournament.internal.web.DomainEventBridge;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

/**
 * Integration tests for timer WebSocket sync — E11S05.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>AC1 / D-7: Timer client connects without admin credentials using X-Timer-Connect header
 *   <li>AC1: Timer client without X-Timer-Connect header is rejected
 *   <li>AC2: LAP_ADVANCED event is received on the display topic
 *   <li>AC3: PHASE_STATUS_CHANGED event is received on the display topic
 * </ul>
 *
 * @see WebSocketSecurityConfig
 * @see DomainEventBridge
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S05.story.md">Story
 *     E11S05</a>
 */
@SpringBootTest(
        classes = {
            TournamentManagerApplication.class,
            TimerWebSocketSyncIT.TestAdminCredentials.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@SuppressWarnings({
    "deprecation",
    "removal"
}) // MappingJackson2MessageConverter deprecated-for-removal in SB 4.x (E42S01)
@DisplayName(
        "TimerWebSocketSyncIT — E11S05: WebSocket sync for lap advance and disconnect resilience")
class TimerWebSocketSyncIT {

    static final String TEST_PASSWORD = "TimerWsTestPass11S05";

    /** A fixed tenant UUID used for all timer client tests. */
    private static final UUID TEST_TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @LocalServerPort private int port;

    /**
     * {@link DomainEventBridge} — called directly in AC2/AC3 tests to broadcast events without
     * needing full transactional plumbing.
     *
     * <p>The bridge's {@code onLapAdvanced} / {@code onPhaseStatusChanged} methods annotated with
     * {@code @TransactionalEventListener} are called here directly (bypassing the Spring event
     * system) to avoid dependency on a real committed transaction. The STOMP broadcast itself is
     * synchronous and does not require a transaction context.
     */
    @Autowired private DomainEventBridge domainEventBridge;

    private String wsUrl;

    @BeforeEach
    void setUp() {
        wsUrl = "http://localhost:" + port + "/ws";
    }

    // =========================================================================
    // AC1 / D-7 — Timer client connects without admin credentials
    // =========================================================================

    @Test
    @DisplayName("AC1/D-7: timer client connects with X-Timer-Connect header (no admin auth)")
    void timerClient_connectsWithTimerHeader_noAdminAuth() throws Exception {
        WebSocketStompClient client = buildStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(WebSocketSecurityConfig.TIMER_CONNECT_HEADER, "true");
        connectHeaders.add(
                WebSocketSecurityConfig.TIMER_TENANT_ID_HEADER, TEST_TENANT_ID.toString());
        // Deliberately NO Authorization: Basic header

        StompSession session =
                client.connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                connectHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        try {
            assertThat(session.isConnected())
                    .as("AC1/D-7: timer client must connect without admin credentials")
                    .isTrue();
        } finally {
            session.disconnect();
        }
    }

    // =========================================================================
    // AC1 — Timer client without X-Timer-Connect header is rejected
    // =========================================================================

    @Test
    @DisplayName("AC1: STOMP CONNECT without X-Timer-Connect and without admin auth is rejected")
    void timerClient_withoutTimerHeader_isRejected() throws Exception {
        WebSocketStompClient client = buildStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        // No X-Timer-Connect, no Authorization, no X-Device-Token

        CompletableFuture<StompSession> future =
                client.connectAsync(
                        wsUrl,
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {});

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .as("AC1: CONNECT without any auth header must be rejected")
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(ExecutionException.class),
                        ex -> assertThat(ex).isInstanceOf(TimeoutException.class));
    }

    // =========================================================================
    // AC2 — LAP_ADVANCED event received on timer topic
    // =========================================================================

    @Test
    @DisplayName("AC2: timer receives LAP_ADVANCED event on /topic/display/{tenantId}/events")
    void timerClient_receivesLapAdvancedEvent() throws Exception {
        WebSocketStompClient client = buildStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(WebSocketSecurityConfig.TIMER_CONNECT_HEADER, "true");
        connectHeaders.add(
                WebSocketSecurityConfig.TIMER_TENANT_ID_HEADER, TEST_TENANT_ID.toString());

        CompletableFuture<Map<?, ?>> receivedMessage = new CompletableFuture<>();
        String topic = "/topic/display/" + TEST_TENANT_ID + "/events";

        StompSession session =
                client.connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                connectHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        try {
            session.subscribe(
                    topic,
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            if (payload instanceof Map<?, ?> msg) {
                                receivedMessage.complete(msg);
                            }
                        }
                    });

            // Wait for subscription to settle
            Thread.sleep(200);

            // AC2: call DomainEventBridge directly (bypasses @TransactionalEventListener
            // Spring machinery — the STOMP broadcast itself is not transactional).
            UUID phaseId = UUID.randomUUID();
            LapAdvancedEvent lapEvent =
                    new LapAdvancedEvent(
                            this,
                            TEST_TENANT_ID,
                            UUID.randomUUID(),
                            phaseId,
                            1,
                            2,
                            UUID.randomUUID());
            domainEventBridge.onLapAdvanced(lapEvent);

            Map<?, ?> received = receivedMessage.get(5, TimeUnit.SECONDS);

            assertThat(received.get("eventType"))
                    .as("AC2: event type must be LAP_ADVANCED")
                    .isEqualTo("LAP_ADVANCED");
            assertThat(received.get("entityId"))
                    .as("AC2: entityId must be the phaseId")
                    .isEqualTo(phaseId.toString());
        } finally {
            session.disconnect();
        }
    }

    // =========================================================================
    // AC3 — PHASE_STATUS_CHANGED event received on timer topic
    // =========================================================================

    @Test
    @DisplayName(
            "AC3: timer receives PHASE_STATUS_CHANGED event on /topic/display/{tenantId}/events")
    void timerClient_receivesPhaseStatusChangedEvent() throws Exception {
        WebSocketStompClient client = buildStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(WebSocketSecurityConfig.TIMER_CONNECT_HEADER, "true");
        connectHeaders.add(
                WebSocketSecurityConfig.TIMER_TENANT_ID_HEADER, TEST_TENANT_ID.toString());

        CompletableFuture<Map<?, ?>> receivedMessage = new CompletableFuture<>();
        String topic = "/topic/display/" + TEST_TENANT_ID + "/events";

        StompSession session =
                client.connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                connectHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        try {
            session.subscribe(
                    topic,
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            if (payload instanceof Map<?, ?> msg) {
                                receivedMessage.complete(msg);
                            }
                        }
                    });

            Thread.sleep(200);

            UUID phaseId = UUID.randomUUID();
            PhaseStatusChangedEvent phaseEvent =
                    new PhaseStatusChangedEvent(
                            this, TEST_TENANT_ID, UUID.randomUUID(), phaseId, "PENDING", "ACTIVE");
            domainEventBridge.onPhaseStatusChanged(phaseEvent);

            Map<?, ?> received = receivedMessage.get(5, TimeUnit.SECONDS);

            assertThat(received.get("eventType"))
                    .as("AC3: event type must be PHASE_STATUS_CHANGED")
                    .isEqualTo("PHASE_STATUS_CHANGED");
            assertThat(received.get("entityId"))
                    .as("AC3: entityId must be the phaseId")
                    .isEqualTo(phaseId.toString());
        } finally {
            session.disconnect();
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private WebSocketStompClient buildStompClient() {
        SockJsClient sockJsClient =
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        return stompClient;
    }

    // =========================================================================
    // Test configuration — predictable admin password
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
