package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.domain.CascadeRecomputeService;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.SetResultInput;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.domain.repo.TenantContextTestHelper;
import de.vvwt.tm.domain.repo.TournamentRepository;
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
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the domain event WebSocket bridge (AC11, E05S03).
 *
 * <h2>Test scenario (AC11)</h2>
 * <ol>
 *   <li>Establish a STOMP WebSocket connection with valid admin credentials</li>
 *   <li>Subscribe to {@code /topic/events}</li>
 *   <li>Trigger a {@link de.vvwt.tm.domain.event.MatchResultChangedEvent} via
 *       {@link CascadeRecomputeService#registerMatchResult}</li>
 *   <li>Assert that the client receives an {@link EventMessage} with
 *       {@code eventType = "MATCH_RESULT_CHANGED"} and the correct match UUID
 *       <strong>within 2 seconds</strong></li>
 * </ol>
 *
 * <p>This test does NOT use {@code @Transactional} — the {@code @TransactionalEventListener(AFTER_COMMIT)}
 * only fires on real commits, not test-managed rollbacks.
 *
 * @see DomainEventBridge
 * @see WebSocketConfig
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S03.story.md">Story E05S03</a>
 */
@SpringBootTest(
        classes = {
                TournamentManagerApplication.class,
                WebSocketEventBridgeIT.TestAdminCredentials.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e05s03bridgedb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class WebSocketEventBridgeIT {

    static final String TEST_PASSWORD = "BridgeTestPass77ZZ";

    @LocalServerPort
    private int port;

    @Autowired private CascadeRecomputeService cascadeService;
    @Autowired private TenantContext tenantContext;
    @Autowired private DefaultTenantProvider defaultTenantProvider;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private MatchRepository matchRepository;

    private UUID defaultTenantId;
    private UUID matchId;

    @BeforeEach
    void setUp() {
        defaultTenantId = defaultTenantProvider.getDefaultTenantId();
        TenantContextTestHelper.set(tenantContext, defaultTenantId);
        matchId = createMinimalFixture();
    }

    @AfterEach
    void tearDown() {
        TenantContextTestHelper.clear(tenantContext);
    }

    // -------------------------------------------------------------------------
    // AC11 — Integration test: WS client receives event within 2 seconds
    // -------------------------------------------------------------------------

    /**
     * AC11 — End-to-end: connect, subscribe, trigger domain event, assert message received
     * within 2 seconds.
     */
    @Test
    void matchResultChanged_clientReceivesEventMessageWithin2Seconds() throws Exception {
        String wsUrl = "http://localhost:" + port + "/ws";
        BlockingQueue<Map<?, ?>> receivedMessages = new LinkedBlockingQueue<>();

        // Build STOMP client
        SockJsClient sockJsClient = new SockJsClient(
                List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        // Connect with valid credentials
        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", basicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD));

        StompSession session = stompClient.connectAsync(wsUrl, new WebSocketHttpHeaders(),
                connectHeaders, new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        try {
            assertThat(session.isConnected())
                    .as("Pre-condition: STOMP session must be connected before test")
                    .isTrue();

            // Subscribe to /topic/events
            session.subscribe(DomainEventBridge.EVENTS_TOPIC, new StompFrameHandler() {
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

            // Trigger domain event via service layer (real commit → AFTER_COMMIT listener fires)
            // BEST_OF_1: setIndex=0 is the deciding set, target=15 (standardVolleyball)
            cascadeService.registerMatchResult(SetResultInput.legacy(matchId, 0, 15, 10, null, null));

            // Assert: client receives EventMessage within 2 seconds (AC11)
            Map<?, ?> received = receivedMessages.poll(2, TimeUnit.SECONDS);

            assertThat(received)
                    .as("AC11: WebSocket client must receive an event message within 2 seconds")
                    .isNotNull();

            assertThat(received.get("eventType"))
                    .as("AC11: eventType must be MATCH_RESULT_CHANGED")
                    .isEqualTo(DomainEventBridge.EVENT_TYPE_MATCH_RESULT_CHANGED);

            assertThat(received.get("entityId"))
                    .as("AC11: entityId must be the match UUID")
                    .isEqualTo(matchId.toString());

            assertThat(received.get("timestamp"))
                    .as("AC11: timestamp must be present")
                    .isNotNull();

            // AC10: no password, no internal IDs other than the matchId (a public UUID)
            @SuppressWarnings("unchecked")
            java.util.Set<String> keys = (java.util.Set<String>) (java.util.Set<?>) received.keySet();
            assertThat(keys)
                    .as("AC10: WebSocket message must only contain eventType, entityId, timestamp")
                    .containsOnly("eventType", "entityId", "timestamp");

        } finally {
            session.disconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Test fixture
    // -------------------------------------------------------------------------

    /**
     * Creates the minimal set of entities needed to trigger a {@link de.vvwt.tm.domain.event.MatchResultChangedEvent}:
     * 1 tournament (DRAFT status to avoid DEC-5 active-tournament constraint),
     * 1 phase, 2 teams, 2 avatars, 1 BEST_OF_1 match.
     *
     * @return the UUID of the created match
     */
    private UUID createMinimalFixture() {
        UUID tournamentId = UUID.randomUUID();
        tournamentRepository.save(new Tournament(
                tournamentId, defaultTenantId, "Bridge Test Tournament " + tournamentId,
                MatchFormat.BEST_OF_1.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now()));

        UUID phaseId = UUID.randomUUID();
        phaseRepository.save(new Phase(phaseId, defaultTenantId, tournamentId, 1,
                "Test Phase", "ACTIVE", 0, LocalDateTime.now()));

        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        teamRepository.save(new Team(team1Id, defaultTenantId, tournamentId, 1,
                "Team WS-A", true, false, false, LocalDateTime.now()));
        teamRepository.save(new Team(team2Id, defaultTenantId, tournamentId, 2,
                "Team WS-B", true, false, false, LocalDateTime.now()));

        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        teamAvatarRepository.save(new TeamAvatar(av1, defaultTenantId, tournamentId, phaseId,
                1, 1, team1Id, null, LocalDateTime.now()));
        teamAvatarRepository.save(new TeamAvatar(av2, defaultTenantId, tournamentId, phaseId,
                1, 2, team2Id, null, LocalDateTime.now()));

        UUID mId = UUID.randomUUID();
        matchRepository.save(new Match(mId, defaultTenantId, tournamentId, phaseId,
                av1, av2, MatchState.OPEN.getLegacyCode(), MatchFormat.BEST_OF_1.getMaxSets(),
                null, null, null, null, null, LocalDateTime.now()));

        return mId;
    }

    private String basicAuth(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------------------
    // Test configuration
    // -------------------------------------------------------------------------

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
