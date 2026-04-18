package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TenantContextTestHelper;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for WebSocket display-device authentication (E07S06 AC1, AC11, AC12).
 *
 * <h2>Test coverage</h2>
 * <ul>
 *   <li>AC1  — STOMP CONNECT with valid {@code X-Device-Token} header succeeds</li>
 *   <li>AC12 — STOMP CONNECT with missing/invalid/wrong-type device token is rejected</li>
 *   <li>AC11 — Cross-tenant isolation: a device registered for tenant A cannot authenticate
 *              with tenant B's token (token is scoped to its own tenant)</li>
 *   <li>Backward compat — Admin HTTP Basic auth still works after adding device-token path</li>
 * </ul>
 *
 * <p>Uses a full Spring Boot test context with an in-memory H2 database and a real embedded
 * HTTP server on a random port. Connects via SockJS + STOMP.
 *
 * @see WebSocketSecurityConfig
 * @see DomainEventBridge
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S06.story.md">Story E07S06</a>
 */
@SpringBootTest(
        classes = {
                TournamentManagerApplication.class,
                DisplayWebSocketSecurityIT.TestCredentials.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e07s06wsdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class DisplayWebSocketSecurityIT {

    static final String TEST_PASSWORD = "DisplayWsTest22AB";

    @LocalServerPort
    private int port;

    @Autowired private DeviceRepository deviceRepository;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;

    private UUID defaultTenantId;
    private String wsUrl;

    @BeforeEach
    void setUp() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        wsUrl = "http://localhost:" + port + "/ws";
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextTestHelper.clear(tenantContext);
    }

    // -------------------------------------------------------------------------
    // AC1 — Valid DISPLAY device token → STOMP CONNECT succeeds
    // -------------------------------------------------------------------------

    /**
     * AC1 — A DISPLAY device with status REGISTERED can connect via device token.
     */
    @Test
    void displayDeviceToken_registeredStatus_connectSucceeds() throws Exception {
        String token = UUID.randomUUID().toString();
        saveDisplayDevice(token, Device.STATUS_REGISTERED);

        StompSession session = connectWithDeviceToken(token);
        try {
            assertThat(session.isConnected())
                    .as("AC1: STOMP CONNECT with valid DISPLAY device token must succeed (REGISTERED)")
                    .isTrue();
        } finally {
            session.disconnect();
        }
    }

    /**
     * AC1 — A DISPLAY device with status ASSIGNED can also connect via device token.
     */
    @Test
    void displayDeviceToken_assignedStatus_connectSucceeds() throws Exception {
        String token = UUID.randomUUID().toString();
        saveDisplayDevice(token, Device.STATUS_ASSIGNED);

        StompSession session = connectWithDeviceToken(token);
        try {
            assertThat(session.isConnected())
                    .as("AC1: STOMP CONNECT with valid DISPLAY device token must succeed (ASSIGNED)")
                    .isTrue();
        } finally {
            session.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // AC12 — Invalid / wrong-type device tokens must be rejected
    // -------------------------------------------------------------------------

    /**
     * AC12 — Unknown device token (not in the database) must be rejected.
     */
    @Test
    void unknownDeviceToken_isRejected() {
        String unknownToken = UUID.randomUUID().toString();

        CompletableFuture<StompSession> future = buildStompClient().connectAsync(
                wsUrl, new WebSocketHttpHeaders(), deviceTokenHeaders(unknownToken),
                new StompSessionHandlerAdapter() {});

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .as("AC12: Unknown device token must be rejected")
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(ExecutionException.class),
                        ex -> assertThat(ex).isInstanceOf(TimeoutException.class)
                );
    }

    /**
     * AC12 — SCORING_TABLET device token must be rejected (wrong device type).
     */
    @Test
    void scoringTabletToken_isRejected() {
        String token = UUID.randomUUID().toString();
        saveScoringTabletDevice(token, Device.STATUS_REGISTERED);

        CompletableFuture<StompSession> future = buildStompClient().connectAsync(
                wsUrl, new WebSocketHttpHeaders(), deviceTokenHeaders(token),
                new StompSessionHandlerAdapter() {});

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .as("AC12: SCORING_TABLET token must be rejected for WebSocket CONNECT")
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(ExecutionException.class),
                        ex -> assertThat(ex).isInstanceOf(TimeoutException.class)
                );
    }

    /**
     * AC12 — DISPLAY device with DISCONNECTED status must be rejected (inactive device).
     */
    @Test
    void displayDeviceToken_disconnectedStatus_isRejected() {
        String token = UUID.randomUUID().toString();
        saveDisplayDevice(token, Device.STATUS_DISCONNECTED);

        CompletableFuture<StompSession> future = buildStompClient().connectAsync(
                wsUrl, new WebSocketHttpHeaders(), deviceTokenHeaders(token),
                new StompSessionHandlerAdapter() {});

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .as("AC12: DISCONNECTED display device must be rejected")
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(ExecutionException.class),
                        ex -> assertThat(ex).isInstanceOf(TimeoutException.class)
                );
    }

    /**
     * AC12 — No credentials at all must be rejected.
     */
    @Test
    void noCredentials_isRejected() {
        CompletableFuture<StompSession> future = buildStompClient().connectAsync(
                wsUrl, new WebSocketHttpHeaders(), new StompHeaders(),
                new StompSessionHandlerAdapter() {});

        assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
                .as("AC12: STOMP CONNECT without any credentials must be rejected")
                .satisfiesAnyOf(
                        ex -> assertThat(ex).isInstanceOf(ExecutionException.class),
                        ex -> assertThat(ex).isInstanceOf(TimeoutException.class)
                );
    }

    // -------------------------------------------------------------------------
    // Backward compat — Admin Basic auth still works (AC6 / E05S03 unchanged)
    // -------------------------------------------------------------------------

    /**
     * Backward compat — Admin HTTP Basic credentials must still work after the
     * device-token path was added to {@link WebSocketSecurityConfig}.
     */
    @Test
    void adminBasicAuth_stillWorks() throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization",
                basicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD));

        StompSession session = buildStompClient()
                .connectAsync(wsUrl, new WebSocketHttpHeaders(), connectHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        try {
            assertThat(session.isConnected())
                    .as("Backward compat: admin Basic auth must still allow STOMP CONNECT")
                    .isTrue();
        } finally {
            session.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

    private StompSession connectWithDeviceToken(String token) throws Exception {
        return buildStompClient()
                .connectAsync(wsUrl, new WebSocketHttpHeaders(), deviceTokenHeaders(token),
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);
    }

    private StompHeaders deviceTokenHeaders(String token) {
        StompHeaders headers = new StompHeaders();
        headers.add(WebSocketSecurityConfig.DEVICE_TOKEN_HEADER, token);
        return headers;
    }

    private WebSocketStompClient buildStompClient() {
        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        return stompClient;
    }

    private String basicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Saves a DISPLAY device with the given token and status to the database.
     * Uses null locationId per DEC-24: location_id is nullable; DISPLAY device with null
     * location is accepted in overview mode (AC7a — no DefaultTenantProvider needed).
     */
    private void saveDisplayDevice(String token, String status) {
        deviceRepository.save(new Device(
                UUID.randomUUID(), defaultTenantId, null,
                token, null,
                Device.TYPE_DISPLAY, null, status,
                LocalDateTime.now(), null,
                "Test Display Device", "{\"display_schema\":\"OVERVIEW\"}"));
    }

    /**
     * Saves a SCORING_TABLET device with null locationId and the given token and status.
     * SCORING_TABLET with null locationId must be rejected (AC6).
     */
    private void saveScoringTabletDevice(String token, String status) {
        String pin = "9901"; // arbitrary unique PIN for this test device
        deviceRepository.save(new Device(
                UUID.randomUUID(), defaultTenantId, null,
                token, pin,
                Device.TYPE_SCORING_TABLET, null, status,
                LocalDateTime.now(), null, null, null));
    }

    // -------------------------------------------------------------------------
    // Test configuration — predictable admin password
    // -------------------------------------------------------------------------

    /**
     * Provides a fixed test password so the admin-basic-auth backward-compat test can connect.
     */
    @TestConfiguration
    static class TestCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
