package de.vvwt.tm.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.scoring.ScoringService;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.SetResultInput;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.internal.web.DomainEventBridge;
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
 * End-to-end integration tests for display WebSocket event delivery (E07S06 AC2, AC3, AC4, AC5).
 *
 * <h2>Test scenarios</h2>
 *
 * <ol>
 *   <li>AC2/AC3 — Display device subscribes to its tenant topic, cascade registers match result,
 *       MATCH_RESULT_CHANGED event arrives within 2 seconds.
 *   <li>AC11 — Cross-tenant isolation: display device A (tenant A's topic) does NOT receive an
 *       event triggered in the context of a different tenant. <em>Note: In V1 single-tenant mode,
 *       all data is under the default tenant, so cross-tenant tests verify that an event with a
 *       different tenantId produces a different topic path.</em>
 * </ol>
 *
 * <p>This test does NOT use {@code @Transactional}:
 * {@code @TransactionalEventListener(AFTER_COMMIT)} only fires on real commits, not test-managed
 * rollbacks.
 *
 * @see DomainEventBridge
 * @see WebSocketSecurityConfig
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S06.story.md">Story
 *     E07S06</a>
 */
@SpringBootTest(
        classes = {
            TournamentManagerApplication.class,
            DisplayWebSocketEventsIT.TestCredentials.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e07s06eventsdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@SuppressWarnings({
    "deprecation",
    "removal"
}) // MappingJackson2MessageConverter deprecated-for-removal in SB 4.x (E42S01)
class DisplayWebSocketEventsIT {

    static final String TEST_PASSWORD = "DisplayEvtTest33ZZ";

    @LocalServerPort private int port;

    @Autowired private ScoringService scoringService;
    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private MatchRepository matchRepository;

    private UUID defaultTenantId;
    private UUID defaultLocationId;
    private UUID matchId;
    private UUID tournamentId;
    private String displayToken;

    @BeforeEach
    void setUp() {
        defaultTenantId = tenantContextBinder.bindDefaultTenant();
        defaultLocationId = tenantContextBinder.getDefaultLocationId();

        displayToken = UUID.randomUUID().toString();
        saveDisplayDevice(displayToken, Device.STATUS_REGISTERED);
        UUID[] ids = createMinimalFixture();
        tournamentId = ids[0];
        matchId = ids[1];
    }

    @AfterEach
    void tearDown() {
        tenantContextBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // AC2/AC3 — Display client receives MATCH_RESULT_CHANGED on tenant topic
    // -------------------------------------------------------------------------

    /**
     * AC2/AC3 — End-to-end: display device connects with device token, subscribes to the
     * tenant-scoped topic, cascade registers a result, event arrives within 2 seconds.
     */
    @Test
    void matchResultChanged_displayClientReceivesEventOnTenantTopic() throws Exception {
        String wsUrl = "http://localhost:" + port + "/ws";
        BlockingQueue<Map<?, ?>> receivedMessages = new LinkedBlockingQueue<>();

        WebSocketStompClient stompClient = buildStompClient();

        // Connect using display device token (AC1)
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(WebSocketSecurityConfig.DEVICE_TOKEN_HEADER, displayToken);

        StompSession session =
                stompClient
                        .connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                connectHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        try {
            assertThat(session.isConnected())
                    .as("Pre-condition: display device STOMP session must be connected")
                    .isTrue();

            // Subscribe to the tenant-scoped display topic (AC1 — display device topic)
            String displayTopic = DomainEventBridge.displayTopic(defaultTenantId);
            session.subscribe(
                    displayTopic,
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            @SuppressWarnings("unchecked")
                            Map<?, ?> message = (Map<?, ?>) payload;
                            receivedMessages.add(message);
                        }
                    });

            // Small pause to ensure subscription is registered before triggering the event
            Thread.sleep(200);

            // Trigger domain event via scoring service (E31S04 cutover — ScoringService interface)
            // tournamentId required by DefaultScoringService (DEC-37 Clause B lock-first contract)
            scoringService.registerMatchResult(
                    SetResultInput.withTournament(tournamentId, matchId, 0, 15, 10, null, null));

            // AC2/AC3: display client must receive MATCH_RESULT_CHANGED within 2 seconds
            Map<?, ?> received = receivedMessages.poll(2, TimeUnit.SECONDS);

            assertThat(received)
                    .as("AC2/AC3: display WebSocket client must receive event within 2 seconds")
                    .isNotNull();

            assertThat(received.get("eventType"))
                    .as("AC2/AC3: eventType must be MATCH_RESULT_CHANGED")
                    .isEqualTo(DomainEventBridge.EVENT_TYPE_MATCH_RESULT_CHANGED);

            assertThat(received.get("entityId"))
                    .as("AC2/AC3: entityId must be the match UUID")
                    .isEqualTo(matchId.toString());

            assertThat(received.get("timestamp"))
                    .as("AC2/AC3: timestamp must be present")
                    .isNotNull();

        } finally {
            session.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // AC11 — Cross-tenant isolation: different topic → no event
    // -------------------------------------------------------------------------

    /**
     * AC11 — A display device subscribed to its tenant topic does NOT receive events broadcast to a
     * DIFFERENT tenant's topic.
     *
     * <p>This test uses two concurrent sessions: one subscribes to the default tenant's display
     * topic (where the event is broadcast), the other subscribes to an arbitrary "other-tenant"
     * topic UUID. Only the first session should receive the event.
     */
    @Test
    void crossTenantIsolation_displayDeviceDoesNotReceiveOtherTenantEvents() throws Exception {
        String wsUrl = "http://localhost:" + port + "/ws";
        BlockingQueue<Map<?, ?>> correctTenantMessages = new LinkedBlockingQueue<>();
        BlockingQueue<Map<?, ?>> wrongTenantMessages = new LinkedBlockingQueue<>();

        WebSocketStompClient stompClient = buildStompClient();

        // Session 1: correct tenant display topic
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add(WebSocketSecurityConfig.DEVICE_TOKEN_HEADER, displayToken);
        StompSession correctSession =
                stompClient
                        .connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                connectHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        // Session 2: admin session, subscribe to a DIFFERENT (fake) tenant's display topic
        // (simulates a scenario where a rogue client tries to subscribe cross-tenant)
        String fakeOtherTenantId = UUID.randomUUID().toString();
        StompHeaders adminHeaders = new StompHeaders();
        adminHeaders.add(
                "Authorization", basicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD));
        StompSession wrongTenantSession =
                buildStompClient()
                        .connectAsync(
                                wsUrl,
                                new WebSocketHttpHeaders(),
                                adminHeaders,
                                new StompSessionHandlerAdapter() {})
                        .get(5, TimeUnit.SECONDS);

        try {
            // Subscribe correct tenant session to correct display topic
            String correctTopic = DomainEventBridge.displayTopic(defaultTenantId);
            correctSession.subscribe(
                    correctTopic,
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders h) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders h, Object payload) {
                            correctTenantMessages.add((Map<?, ?>) payload);
                        }
                    });

            // Subscribe "wrong tenant" session to a different (non-matching) display topic
            String wrongTopic =
                    String.format(
                            DomainEventBridge.DISPLAY_EVENTS_TOPIC_PATTERN, fakeOtherTenantId);
            wrongTenantSession.subscribe(
                    wrongTopic,
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders h) {
                            return Map.class;
                        }

                        @Override
                        public void handleFrame(StompHeaders h, Object payload) {
                            wrongTenantMessages.add((Map<?, ?>) payload);
                        }
                    });

            Thread.sleep(200);

            // Trigger event for the DEFAULT tenant
            scoringService.registerMatchResult(
                    SetResultInput.withTournament(tournamentId, matchId, 0, 15, 10, null, null));

            // AC11: correct tenant topic SHOULD receive the event
            Map<?, ?> correctReceived = correctTenantMessages.poll(2, TimeUnit.SECONDS);
            assertThat(correctReceived)
                    .as("AC11: correct tenant display topic must receive the event")
                    .isNotNull();
            assertThat(correctReceived.get("eventType"))
                    .isEqualTo(DomainEventBridge.EVENT_TYPE_MATCH_RESULT_CHANGED);

            // AC11: wrong (other-tenant) topic must NOT receive the event
            Map<?, ?> wrongReceived = wrongTenantMessages.poll(500, TimeUnit.MILLISECONDS);
            assertThat(wrongReceived)
                    .as("AC11: other-tenant display topic must NOT receive this tenant's event")
                    .isNull();

        } finally {
            correctSession.disconnect();
            wrongTenantSession.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
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

    private void saveDisplayDevice(String token, String status) {
        // DEC-24: location_id is nullable; DISPLAY device with null location → overview mode (AC7a)
        deviceRepository.save(
                new Device(
                        UUID.randomUUID(),
                        null,
                        token,
                        null,
                        Device.TYPE_DISPLAY,
                        null,
                        status,
                        LocalDateTime.now(),
                        null,
                        "Events Test Display",
                        "{\"display_schema\":\"OVERVIEW\"}"));
    }

    /**
     * Creates the minimal entity graph for triggering a {@link
     * de.vvwt.tm.tournament.events.MatchResultChangedEvent}: 1 tournament, 1 ACTIVE phase, 2 teams,
     * 2 avatars, 1 BEST_OF_1 match.
     *
     * @return array of [tournamentId, matchId] — tournamentId required for DEC-37 Clause B
     *     lock-first contract (E31S04 cutover)
     */
    private UUID[] createMinimalFixture() {
        UUID tournamentId = UUID.randomUUID();
        Tournament displayTournament =
                new Tournament(
                        tournamentId,
                        "Display Events Fixture " + tournamentId,
                        MatchFormat.BEST_OF_1.name(),
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        "DRAFT",
                        LocalDateTime.now());
        // E45S06: location_id NOT NULL (DEC-39 D2)
        displayTournament.setLocationId(defaultLocationId);
        tournamentRepository.save(displayTournament);

        UUID phaseId = UUID.randomUUID();
        phaseRepository.save(
                new Phase(
                        phaseId,
                        tournamentId,
                        1,
                        "Events Test Phase",
                        "ACTIVE",
                        0,
                        LocalDateTime.now()));

        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        teamRepository.save(
                new Team(
                        team1Id,
                        tournamentId,
                        1,
                        "Display Team A",
                        true,
                        false,
                        false,
                        LocalDateTime.now()));
        teamRepository.save(
                new Team(
                        team2Id,
                        tournamentId,
                        2,
                        "Display Team B",
                        true,
                        false,
                        false,
                        LocalDateTime.now()));

        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        teamAvatarRepository.save(
                new TeamAvatar(
                        av1, tournamentId, phaseId, 1, 1, team1Id, null, LocalDateTime.now()));
        teamAvatarRepository.save(
                new TeamAvatar(
                        av2, tournamentId, phaseId, 1, 2, team2Id, null, LocalDateTime.now()));

        UUID mId = UUID.randomUUID();
        matchRepository.save(
                new Match(
                        mId,
                        tournamentId,
                        phaseId,
                        av1,
                        av2,
                        MatchState.OPEN.getLegacyCode(),
                        MatchFormat.BEST_OF_1.getMaxSets(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.now()));

        return new UUID[] {tournamentId, mId};
    }

    // -------------------------------------------------------------------------
    // Test configuration
    // -------------------------------------------------------------------------

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
