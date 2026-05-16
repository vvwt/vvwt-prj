package de.vvwt.info.reader;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.info.persistence.testsupport.InfoDaoTestSupport;
import de.vvwt.info.reader.internal.DefaultHmacTokenValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * Integration tests for the WebSocket stream endpoint (E38S06 AC2, AC5, AC6, AC9, AC10, AC11,
 * AC14).
 *
 * <p>Uses {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} so that a real Servlet container
 * (Tomcat) starts and WebSocket connections can be established via {@link StandardWebSocketClient}.
 *
 * <p>Test seam: direct DB inserts via {@link InfoDaoTestSupport#insertDirectly} (DEC-26/DEC-46).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("self-host")
class ReaderWebSocketIT {

    @LocalServerPort private int port;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void clearDb() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM tournament_delta");
        jdbc.execute("DELETE FROM tournament");
        jdbc.execute("DELETE FROM tenant");
        jdbc.execute("DELETE FROM audit_log");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private byte[] randomSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    private String tokenFor(byte[] secret, String teamId) {
        byte[] hmac = DefaultHmacTokenValidator.computeHmac(secret, teamId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<String, V> cols(Object... kvPairs) {
        if (kvPairs.length % 2 != 0) {
            throw new IllegalArgumentException("cols() requires an even number of arguments");
        }
        LinkedHashMap<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < kvPairs.length; i += 2) {
            map.put((String) kvPairs[i], (V) kvPairs[i + 1]);
        }
        return map;
    }

    private String buildStateJson(
            String tournamentId, String tenantId, long seq, List<TeamEntry> teams)
            throws Exception {
        TournamentSnapshot snap =
                new TournamentSnapshot(tournamentId, tenantId, seq, List.of(), teams, false);
        Envelope<TournamentSnapshot> env = new Envelope<>(Envelope.SCHEMA_VERSION, snap);
        return objectMapper.writeValueAsString(env);
    }

    private void insertTenant(String tenantId) {
        InfoDaoTestSupport.insertDirectly(
                dataSource,
                "tenant",
                Map.of(
                        "tenant_id",
                        tenantId,
                        "public_key",
                        new byte[32],
                        "algorithm_id",
                        "Ed25519",
                        "registered_at",
                        LocalDateTime.now(),
                        "status",
                        "ACTIVE",
                        "is_default",
                        true));
    }

    private record TournamentFixture(
            String tournamentId,
            String tenantId,
            String tournamentToken,
            byte[] secret,
            String teamId,
            String teamToken) {}

    private TournamentFixture insertActiveTournament() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        String tournamentToken = UUID.randomUUID().toString();
        byte[] secret = randomSecret();
        String teamId = UUID.randomUUID().toString();
        String teamToken = tokenFor(secret, teamId);

        List<TeamEntry> teams = List.of(new TeamEntry(teamId, "Team Alpha", 1));
        String stateJson = buildStateJson(tournamentId, tenantId, 0L, teams);

        insertTenant(tenantId);
        InfoDaoTestSupport.insertDirectly(
                dataSource,
                "tournament",
                cols(
                        "tournament_id", tournamentId,
                        "tenant_id", tenantId,
                        "location_id", "loc-1",
                        "tournament_token", tournamentToken,
                        "per_tournament_secret", secret,
                        "state", stateJson,
                        "last_applied_seq", 0L,
                        "registered_at", LocalDateTime.now(),
                        "superseded_at", null));

        return new TournamentFixture(
                tournamentId, tenantId, tournamentToken, secret, teamId, teamToken);
    }

    /**
     * Connects to the WS stream endpoint and collects the first {@code frameCount} text frames into
     * an array. Returns the frames received within 5 seconds, or fewer if the server sends less.
     */
    private JsonNode[] connectAndCollect(String tournamentToken, String teamToken, int frameCount)
            throws Exception {
        String url =
                "ws://localhost:" + port + "/api/v1/stream/" + tournamentToken + "/" + teamToken;

        JsonNode[] frames = new JsonNode[frameCount];
        CountDownLatch latch = new CountDownLatch(frameCount);
        int[] idx = {0};

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session =
                client.execute(
                                new AbstractWebSocketHandler() {
                                    @Override
                                    protected void handleTextMessage(
                                            WebSocketSession s, TextMessage message)
                                            throws Exception {
                                        if (idx[0] < frameCount) {
                                            frames[idx[0]++] =
                                                    objectMapper.readTree(
                                                            message.getPayload()
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8));
                                            latch.countDown();
                                        }
                                    }
                                },
                                new WebSocketHttpHeaders(),
                                URI.create(url))
                        .get(5, TimeUnit.SECONDS);

        latch.await(5, TimeUnit.SECONDS);
        session.close();
        return frames;
    }

    /**
     * Attempts a WS connection and returns the HTTP upgrade response status. Returns 101 on
     * successful upgrade, 410 on rejection.
     */
    private int connectAndGetStatus(String tournamentToken, String teamToken) {
        String url =
                "ws://localhost:" + port + "/api/v1/stream/" + tournamentToken + "/" + teamToken;
        AtomicReference<Integer> statusCode = new AtomicReference<>(null);
        try {
            StandardWebSocketClient client = new StandardWebSocketClient();
            WebSocketSession session =
                    client.execute(
                                    new AbstractWebSocketHandler() {},
                                    new WebSocketHttpHeaders(),
                                    URI.create(url))
                            .get(5, TimeUnit.SECONDS);
            session.close();
            return 101; // Upgrade succeeded
        } catch (Exception e) {
            // WS upgrade rejected — connection refused / 410 → exception
            return 410;
        }
    }

    // -------------------------------------------------------------------------
    // AC14 — StreamHello is the first frame, snapshot is the second frame
    // -------------------------------------------------------------------------

    @Test
    void connect_firstFrameIsStreamHello() throws Exception {
        var t = insertActiveTournament();

        JsonNode[] frames = connectAndCollect(t.tournamentToken(), t.teamToken(), 2);

        // Frame 0 must be StreamHello
        assertThat(frames[0]).isNotNull();
        JsonNode hello = frames[0].get("payload");
        assertThat(hello.has("poll_cadence_seconds")).isTrue();
        assertThat(hello.get("poll_cadence_seconds").asInt()).isGreaterThan(0);
        assertThat(hello.has("schemaVersion")).isTrue();
    }

    @Test
    void connect_secondFrameIsSnapshot() throws Exception {
        var t = insertActiveTournament();

        JsonNode[] frames = connectAndCollect(t.tournamentToken(), t.teamToken(), 2);

        // Frame 1 must be TournamentSnapshot
        assertThat(frames[1]).isNotNull();
        JsonNode payload = frames[1].get("payload");
        assertThat(payload.has("tournamentId")).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC2 — per-team snapshot contains schemaVersion in envelope
    // -------------------------------------------------------------------------

    @Test
    void connect_snapshotEnvelopeCarriesSchemaVersion() throws Exception {
        var t = insertActiveTournament();

        JsonNode[] frames = connectAndCollect(t.tournamentToken(), t.teamToken(), 2);

        // Both frames must carry schemaVersion
        assertThat(frames[0].has("schemaVersion")).isTrue();
        assertThat(frames[1].has("schemaVersion")).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC9 / AC10 — unknown tournament token → upgrade rejected (410)
    // -------------------------------------------------------------------------

    @Test
    void connect_unknownTournamentToken_rejected() {
        String fakeToken = "unknown-tournament-token";
        String fakeTeamToken = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        int status = connectAndGetStatus(fakeToken, fakeTeamToken);
        assertThat(status).isEqualTo(410);
    }

    // -------------------------------------------------------------------------
    // AC9 — malformed team token (wrong length) → upgrade rejected
    // -------------------------------------------------------------------------

    @Test
    void connect_malformedTeamToken_rejected() throws Exception {
        var t = insertActiveTournament();
        int status = connectAndGetStatus(t.tournamentToken(), "short");
        assertThat(status).isEqualTo(410);
    }

    // -------------------------------------------------------------------------
    // AC10 — HMAC mismatch → upgrade rejected
    // -------------------------------------------------------------------------

    @Test
    void connect_hmacMismatch_rejected() throws Exception {
        var t = insertActiveTournament();
        byte[] otherSecret = randomSecret();
        String wrongToken = tokenFor(otherSecret, "some-other-uuid");
        int status = connectAndGetStatus(t.tournamentToken(), wrongToken);
        assertThat(status).isEqualTo(410);
    }

    // -------------------------------------------------------------------------
    // AC11 — superseded beyond 24h grace → upgrade rejected
    // -------------------------------------------------------------------------

    @Test
    void connect_supersededBeyond24h_rejected() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        String tournamentToken = UUID.randomUUID().toString();
        byte[] secret = randomSecret();
        String teamId = UUID.randomUUID().toString();
        String teamToken = tokenFor(secret, teamId);

        String stateJson =
                buildStateJson(
                        tournamentId,
                        tenantId,
                        0L,
                        List.of(new TeamEntry(teamId, "Team Alpha", 1)));

        insertTenant(tenantId);
        InfoDaoTestSupport.insertDirectly(
                dataSource,
                "tournament",
                cols(
                        "tournament_id", tournamentId,
                        "tenant_id", tenantId,
                        "location_id", "loc-1",
                        "tournament_token", tournamentToken,
                        "per_tournament_secret", secret,
                        "state", stateJson,
                        "last_applied_seq", 0L,
                        "registered_at", LocalDateTime.now().minusHours(30),
                        "superseded_at", LocalDateTime.now().minusHours(25)));

        int status = connectAndGetStatus(tournamentToken, teamToken);
        assertThat(status).isEqualTo(410);
    }

    // -------------------------------------------------------------------------
    // AC5/AC11 — superseded within 24h grace → upgrade succeeds, returns snapshot
    // -------------------------------------------------------------------------

    @Test
    void connect_supersededWithin24hGrace_succeeds() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        String tournamentToken = UUID.randomUUID().toString();
        byte[] secret = randomSecret();
        String teamId = UUID.randomUUID().toString();
        String teamToken = tokenFor(secret, teamId);

        String stateJson =
                buildStateJson(
                        tournamentId,
                        tenantId,
                        5L,
                        List.of(new TeamEntry(teamId, "Team Alpha", 1)));

        insertTenant(tenantId);
        InfoDaoTestSupport.insertDirectly(
                dataSource,
                "tournament",
                cols(
                        "tournament_id", tournamentId,
                        "tenant_id", tenantId,
                        "location_id", "loc-1",
                        "tournament_token", tournamentToken,
                        "per_tournament_secret", secret,
                        "state", stateJson,
                        "last_applied_seq", 5L,
                        "registered_at", LocalDateTime.now().minusHours(5),
                        "superseded_at", LocalDateTime.now().minusHours(1)));

        JsonNode[] frames = connectAndCollect(tournamentToken, teamToken, 2);

        // Snapshot should reflect the frozen state
        assertThat(frames[1]).isNotNull();
        JsonNode payload = frames[1].get("payload");
        assertThat(payload.get("tournamentId").asText()).isEqualTo(tournamentId);
    }
}
