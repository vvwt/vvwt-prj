// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Regression IT — E65S08 AC6 — PhaseStatusChangedEvent tenantId-resolution defect.
 *
 * <p>Asserts that the STOMP broadcast destination for each phase-status REST transition equals
 * {@code /topic/display/{tenantId}/events} (not {@code /topic/display/null/events}).
 *
 * <p>Test was committed RED: before the fix, {@link DefaultPhaseLifecycleService} passed {@code
 * null} as {@code tenantId}, causing {@link DomainEventBridge} to broadcast to {@code
 * /topic/display/null/events}. After the fix all publish sites use {@code TenantContext#current()}.
 *
 * <p>Coverage: prepare (PENDING→PREPARED), start (ASSIGNED→ACTIVE), complete (ACTIVE→COMPLETED),
 * force-complete (ACTIVE→COMPLETED). Uses {@code @SpringBootTest(RANDOM_PORT)} per DEC-44 D1. JDBC
 * fixture (DEC-26 Rule 3).
 *
 * @see DomainEventBridge
 * @see DefaultPhaseLifecycleService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-44">DEC-44 D1 — web-module ITs</a>
 * @see <a href="E65S08">E65S08 — tenantId defect fix</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e65s08broadcastdb"
                    + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({
    WebModuleTestConfig.class,
    PhaseStatusChangedBroadcastIT.TestAdminCredentials.class,
    TenantContextTestSupport.class
})
@SuppressWarnings({"deprecation", "removal"})
// MappingJackson2MessageConverter deprecated-for-removal in SB 4.x (E42S01)
@DisplayName("E65S08 AC6 — PhaseStatusChangedEvent broadcast lands on correct tenant topic")
class PhaseStatusChangedBroadcastIT {

    static final String ADMIN_PASS = "E65S08BroadcastIT01";

    @LocalServerPort private int port;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID defaultTenantId;
    private UUID locationId;
    private UUID tournamentId;

    // -------------------------------------------------------------------------
    // Fixture lifecycle
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        defaultTenantId = tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E65S08 BroadcastIT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E65S08 Broadcast Fixture",
                "BEST_OF_1",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                1,
                2,
                "{\"sections\":[{\"sectionNumber\":1,\"sortType\":\"team_number\","
                        + "\"groupCount\":1,\"gameMode\":\"roundRobin\","
                        + "\"lapBreakTimeMinutes\":0,\"sectionBreakTimeMinutes\":0,"
                        + "\"lapTimeMinutes\":15,\"setQuantity\":1,\"breaks\":[],"
                        + "\"distributionMode\":\"ROUND_ROBIN\"},"
                        + "{\"sectionNumber\":2,\"sortType\":\"team_number\","
                        + "\"groupCount\":1,\"gameMode\":\"roundRobin\","
                        + "\"lapBreakTimeMinutes\":0,\"sectionBreakTimeMinutes\":0,"
                        + "\"lapTimeMinutes\":15,\"setQuantity\":1,\"breaks\":[],"
                        + "\"distributionMode\":\"ROUND_ROBIN\"},"
                        + "{\"sectionNumber\":3,\"sortType\":\"team_number\","
                        + "\"groupCount\":1,\"gameMode\":\"roundRobin\","
                        + "\"lapBreakTimeMinutes\":0,\"sectionBreakTimeMinutes\":0,"
                        + "\"lapTimeMinutes\":15,\"setQuantity\":1,\"breaks\":[],"
                        + "\"distributionMode\":\"ROUND_ROBIN\"},"
                        + "{\"sectionNumber\":4,\"sortType\":\"team_number\","
                        + "\"groupCount\":1,\"gameMode\":\"roundRobin\","
                        + "\"lapBreakTimeMinutes\":0,\"sectionBreakTimeMinutes\":0,"
                        + "\"lapTimeMinutes\":15,\"setQuantity\":1,\"breaks\":[],"
                        + "\"distributionMode\":\"ROUND_ROBIN\"}]}");

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update(
                "DELETE FROM team_avatar WHERE phase_id IN "
                        + "(SELECT id FROM phase WHERE tournament_id = ?)",
                tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // AC6-PREPARE — PENDING → PREPARED
    // -------------------------------------------------------------------------

    /**
     * AC6 — {@code POST /api/phases/{id}/prepare} (PENDING→PREPARED) broadcasts
     * PHASE_STATUS_CHANGED to {@code /topic/display/{realTenantId}/events}.
     *
     * <p>RED before fix: broadcast lands on {@code /topic/display/null/events}, subscriber on the
     * real tenant's topic receives nothing → timeout → assertion fails.
     */
    @Test
    @DisplayName(
            "AC6-PREPARE: PENDING→PREPARED broadcast lands on /topic/display/{tenantId}/events"
                    + " (NOT /null/)")
    void prepare_broadcastsToCorrectTenantDisplayTopic() throws Exception {
        UUID phaseId = UUID.randomUUID();
        tenantBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                    phaseId,
                    tournamentId,
                    1,
                    "E65S08 Prepare Phase",
                    "PENDING",
                    0);
        } finally {
            tenantBinder.unbind();
        }

        String correctTopic = DomainEventBridge.displayTopic(defaultTenantId);
        BlockingQueue<Map<?, ?>> received =
                subscribeAndTrigger(
                        correctTopic, () -> restPost("/api/phases/" + phaseId + "/prepare"));

        assertThat(received.poll(2, TimeUnit.SECONDS))
                .as(
                        "AC6-PREPARE: PHASE_STATUS_CHANGED must arrive on /topic/display/"
                                + defaultTenantId
                                + "/events within 2 s (not on /null/)")
                .isNotNull()
                .extracting(m -> m.get("eventType"))
                .isEqualTo(DomainEventBridge.EVENT_TYPE_PHASE_STATUS_CHANGED);
    }

    // -------------------------------------------------------------------------
    // AC6-START — ASSIGNED → ACTIVE
    // -------------------------------------------------------------------------

    /**
     * AC6 — {@code POST /api/phases/{id}/start} (ASSIGNED→ACTIVE) broadcasts PHASE_STATUS_CHANGED
     * to {@code /topic/display/{realTenantId}/events}.
     */
    @Test
    @DisplayName(
            "AC6-START: ASSIGNED→ACTIVE broadcast lands on /topic/display/{tenantId}/events"
                    + " (NOT /null/)")
    void start_broadcastsToCorrectTenantDisplayTopic() throws Exception {
        UUID phaseId = UUID.randomUUID();
        tenantBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                    phaseId,
                    tournamentId,
                    2,
                    "E65S08 Start Phase",
                    "ASSIGNED",
                    0);
        } finally {
            tenantBinder.unbind();
        }

        String correctTopic = DomainEventBridge.displayTopic(defaultTenantId);
        BlockingQueue<Map<?, ?>> received =
                subscribeAndTrigger(
                        correctTopic, () -> restPost("/api/phases/" + phaseId + "/start"));

        assertThat(received.poll(2, TimeUnit.SECONDS))
                .as(
                        "AC6-START: PHASE_STATUS_CHANGED must arrive on /topic/display/"
                                + defaultTenantId
                                + "/events within 2 s (not on /null/)")
                .isNotNull()
                .extracting(m -> m.get("eventType"))
                .isEqualTo(DomainEventBridge.EVENT_TYPE_PHASE_STATUS_CHANGED);
    }

    // -------------------------------------------------------------------------
    // AC6-COMPLETE — ACTIVE → COMPLETED (all matches finished)
    // -------------------------------------------------------------------------

    /**
     * AC6 — {@code POST /api/phases/{id}/complete} (ACTIVE→COMPLETED, all matches finished)
     * broadcasts PHASE_STATUS_CHANGED to {@code /topic/display/{realTenantId}/events}.
     */
    @Test
    @DisplayName(
            "AC6-COMPLETE: ACTIVE→COMPLETED broadcast lands on /topic/display/{tenantId}/events"
                    + " (NOT /null/)")
    void complete_broadcastsToCorrectTenantDisplayTopic() throws Exception {
        UUID phaseId = UUID.randomUUID();
        tenantBinder.bindDefaultTenant();
        try {
            // Phase with no unfinished matches → complete() succeeds
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                    phaseId,
                    tournamentId,
                    3,
                    "E65S08 Complete Phase",
                    "ACTIVE",
                    0);
        } finally {
            tenantBinder.unbind();
        }

        String correctTopic = DomainEventBridge.displayTopic(defaultTenantId);
        BlockingQueue<Map<?, ?>> received =
                subscribeAndTrigger(
                        correctTopic, () -> restPost("/api/phases/" + phaseId + "/complete"));

        assertThat(received.poll(2, TimeUnit.SECONDS))
                .as(
                        "AC6-COMPLETE: PHASE_STATUS_CHANGED must arrive on /topic/display/"
                                + defaultTenantId
                                + "/events within 2 s (not on /null/)")
                .isNotNull()
                .extracting(m -> m.get("eventType"))
                .isEqualTo(DomainEventBridge.EVENT_TYPE_PHASE_STATUS_CHANGED);
    }

    // -------------------------------------------------------------------------
    // AC6-FORCE-COMPLETE — ACTIVE → COMPLETED (Notabschluss)
    // -------------------------------------------------------------------------

    /**
     * AC6 — {@code POST /api/phases/{id}/force-complete} (ACTIVE→COMPLETED, Notabschluss)
     * broadcasts PHASE_STATUS_CHANGED to {@code /topic/display/{realTenantId}/events}.
     */
    @Test
    @DisplayName(
            "AC6-FORCE-COMPLETE: ACTIVE→COMPLETED (Notabschluss) broadcast lands on"
                    + " /topic/display/{tenantId}/events (NOT /null/)")
    void forceComplete_broadcastsToCorrectTenantDisplayTopic() throws Exception {
        UUID phaseId = UUID.randomUUID();
        tenantBinder.bindDefaultTenant();
        try {
            jdbcTemplate.update(
                    "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                            + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                    phaseId,
                    tournamentId,
                    4,
                    "E65S08 ForceComplete Phase",
                    "ACTIVE",
                    0);
        } finally {
            tenantBinder.unbind();
        }

        String correctTopic = DomainEventBridge.displayTopic(defaultTenantId);
        BlockingQueue<Map<?, ?>> received =
                subscribeAndTrigger(
                        correctTopic, () -> restPost("/api/phases/" + phaseId + "/force-complete"));

        assertThat(received.poll(2, TimeUnit.SECONDS))
                .as(
                        "AC6-FORCE-COMPLETE: PHASE_STATUS_CHANGED must arrive on /topic/display/"
                                + defaultTenantId
                                + "/events within 2 s (not on /null/)")
                .isNotNull()
                .extracting(m -> m.get("eventType"))
                .isEqualTo(DomainEventBridge.EVENT_TYPE_PHASE_STATUS_CHANGED);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Subscribes a STOMP client to {@code topic} as an admin session, waits for subscription
     * registration, then executes {@code trigger} (which fires a REST endpoint), and returns the
     * blocking queue that receives incoming STOMP frames.
     */
    private BlockingQueue<Map<?, ?>> subscribeAndTrigger(
            String topic, RunnableWithException trigger) throws Exception {
        String wsUrl = "http://localhost:" + port + "/ws";
        BlockingQueue<Map<?, ?>> received = new LinkedBlockingQueue<>();

        SockJsClient sockJsClient =
                new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", basicAuth("admin", ADMIN_PASS));

        StompSession session =
                stompClient
                        .connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                connectHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        session.subscribe(
                topic,
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return Map.class;
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public void handleFrame(StompHeaders headers, Object payload) {
                        received.add((Map<?, ?>) payload);
                    }
                });

        // Wait for subscription to be registered on the broker
        Thread.sleep(300);

        try {
            trigger.run();
        } finally {
            // Disconnect after message collection
            new Thread(
                            () -> {
                                try {
                                    Thread.sleep(3000);
                                    session.disconnect();
                                } catch (InterruptedException ignored) {
                                    Thread.currentThread().interrupt();
                                }
                            })
                    .start();
        }

        return received;
    }

    @FunctionalInterface
    private interface RunnableWithException {
        void run() throws Exception;
    }

    /**
     * Performs an authenticated REST POST to {@code path} using Basic auth with the test admin
     * credentials. The TenantContext must be bound before the REST call reaches the service; the
     * web interceptor ({@code TenantContextResolver}) handles this for HTTP requests — the tenant
     * binding is set by Spring Security on the request thread.
     *
     * <p>Uses {@code java.net.http.HttpClient} for simplicity (no Spring test-RestTemplate
     * dependency on this inner helper).
     */
    private void restPost(String path) throws Exception {
        String url = "http://localhost:" + port + path;
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest request =
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url))
                        .header("Authorization", basicAuth("admin", ADMIN_PASS))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                        .build();
        client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private String basicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Per-IT admin credentials
    // -------------------------------------------------------------------------

    /**
     * Per-IT {@code @Primary AdminCredentialsProvider} per DEC-44 D2 pattern (E39S01 precedent).
     */
    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("e65s08BroadcastItAdminCredentials")
        @Primary
        public AdminCredentialsProvider adminCredentialsProvider(PasswordEncoder passwordEncoder) {
            return new AdminCredentialsProvider() {
                @Override
                public String getPasswordHash() {
                    return passwordEncoder.encode(ADMIN_PASS);
                }
            };
        }
    }
}
