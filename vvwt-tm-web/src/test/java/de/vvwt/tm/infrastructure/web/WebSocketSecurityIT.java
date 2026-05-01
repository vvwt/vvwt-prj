package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
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
 * Integration tests for WebSocket authentication (AC6, E05S03).
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>AC6: Unauthenticated STOMP CONNECT is rejected
 *   <li>AC6: Authenticated STOMP CONNECT with valid credentials succeeds
 * </ul>
 *
 * <p>Uses a full Spring Boot test context with a real embedded server on a random port. Connects
 * via SockJS + STOMP using {@link WebSocketStompClient}.
 *
 * @see WebSocketSecurityConfig
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story
 *     E05S03</a>
 */
@SpringBootTest(
        classes = {
            TournamentManagerApplication.class,
            WebSocketSecurityIT.TestAdminCredentials.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@SuppressWarnings({
    "deprecation",
    "removal"
}) // MappingJackson2MessageConverter deprecated-for-removal in SB 4.x (E42S01)
class WebSocketSecurityIT {

    static final String TEST_PASSWORD = "WsTestPass99XY";

    @LocalServerPort private int port;

    private String wsUrl;

    @BeforeEach
    void setUp() {
        wsUrl = "http://localhost:" + port + "/ws";
    }

    // -------------------------------------------------------------------------
    // AC6 — Unauthenticated CONNECT is rejected
    // -------------------------------------------------------------------------

    /**
     * AC6 — No Authorization header → STOMP CONNECT must be rejected.
     *
     * <p>The {@link WebSocketSecurityConfig} ChannelInterceptor throws {@link
     * org.springframework.security.access.AccessDeniedException} on missing credentials, which
     * Spring WebSocket translates to a connection error.
     */
    @Test
    void unauthenticatedConnect_isRejected() throws Exception {
        WebSocketStompClient client = buildStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        // No Authorization header — must be rejected

        CompletableFuture<StompSession> future =
                client.connectAsync(
                        wsUrl,
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {});

        // The connect should fail — either timeout or throw an execution exception
        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .as("AC6: STOMP CONNECT without credentials must be rejected")
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(ExecutionException.class),
                        ex -> assertThat(ex).isInstanceOf(TimeoutException.class));
    }

    // -------------------------------------------------------------------------
    // AC6 — Authenticated CONNECT succeeds
    // -------------------------------------------------------------------------

    /** AC6 — Valid credentials in Authorization header → STOMP CONNECT succeeds. */
    @Test
    void authenticatedConnect_succeeds() throws Exception {
        WebSocketStompClient client = buildStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(
                "Authorization", basicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD));

        StompSession session =
                client.connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                connectHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        try {
            assertThat(session.isConnected())
                    .as("AC6: STOMP session with valid credentials must be connected")
                    .isTrue();
        } finally {
            session.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // AC6 — Wrong password is rejected
    // -------------------------------------------------------------------------

    /** AC6 — Wrong password in Authorization header → STOMP CONNECT must be rejected. */
    @Test
    void wrongPasswordConnect_isRejected() throws Exception {
        WebSocketStompClient client = buildStompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(
                "Authorization",
                basicAuth(AdminCredentialsProvider.ADMIN_USERNAME, "wrongpassword"));

        CompletableFuture<StompSession> future =
                client.connectAsync(
                        wsUrl,
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {});

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .as("AC6: STOMP CONNECT with wrong password must be rejected")
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(ExecutionException.class),
                        ex -> assertThat(ex).isInstanceOf(TimeoutException.class));
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private WebSocketStompClient buildStompClient() {
        SockJsClient sockJsClient =
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        return stompClient;
    }

    private String basicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Test configuration — predictable admin password
    // -------------------------------------------------------------------------

    /**
     * Provides a fixed test password so WebSocket IT can authenticate. Mirrors the pattern from
     * {@link de.vvwt.tm.auth.SecurityConfigIT.TestAdminCredentials}.
     */
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
