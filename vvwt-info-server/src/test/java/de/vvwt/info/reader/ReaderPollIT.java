package de.vvwt.info.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.info.dto.envelope.Envelope;
import de.vvwt.info.dto.snapshot.ScheduleEntry;
import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.info.persistence.testsupport.InfoDaoTestSupport;
import de.vvwt.info.reader.internal.HmacTokenValidator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for the HTTP poll fallback endpoint (E38S06 AC3, AC4, AC9, AC10, AC11).
 *
 * <p>Uses {@code @SpringBootTest} (self-host profile, H2). Test seam: direct DB inserts via {@link
 * InfoDaoTestSupport#insertDirectly} per AC2 note and DEC-26/DEC-46.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("self-host")
class ReaderPollIT {

    @Autowired private MockMvc mockMvc;
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

    /**
     * Builds a {@link LinkedHashMap} from alternating key/value pairs. Unlike {@link Map#of}, this
     * helper accepts {@code null} values, which is required for nullable DB columns such as {@code
     * superseded_at}.
     */
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

    private byte[] randomSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    private String tokenFor(byte[] secret, String teamId) {
        byte[] hmac = HmacTokenValidator.computeHmac(secret, teamId);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
    }

    private String buildStateJson(
            String tournamentId, String tenantId, long seq, List<TeamEntry> teams)
            throws Exception {
        TournamentSnapshot snap =
                new TournamentSnapshot(tournamentId, tenantId, seq, List.of(), teams);
        Envelope<TournamentSnapshot> env = new Envelope<>(Envelope.SCHEMA_VERSION, snap);
        return objectMapper.writeValueAsString(env);
    }

    private void insertTenant(String tenantId) {
        // Use a minimal tenant row; public_key column is VARBINARY — pass zero bytes
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

    private TournamentFixture insertActiveTournament(List<TeamEntry> additionalTeams)
            throws Exception {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        String tournamentToken = UUID.randomUUID().toString();
        byte[] secret = randomSecret();
        String teamId = UUID.randomUUID().toString();
        String teamToken = tokenFor(secret, teamId);

        List<TeamEntry> teams =
                new java.util.ArrayList<>(List.of(new TeamEntry(teamId, "Team Alpha", 1)));
        teams.addAll(additionalTeams);

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

    // -------------------------------------------------------------------------
    // AC3 — empty delta list when since == last_applied_seq
    // -------------------------------------------------------------------------

    @Test
    void poll_sinceEqualsCurrentSeq_returnsEmptyDeltas() throws Exception {
        var t = insertActiveTournament(List.of());

        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/poll/{tt}/{tk}", t.tournamentToken(), t.teamToken())
                                        .param("since", "0"))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // payload should be PollResponse with empty deltas
        var tree = objectMapper.readTree(body);
        assertThat(tree.get("payload").get("deltas").isEmpty()).isTrue();
        assertThat(tree.get("payload").get("current_seq").asLong()).isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // AC3 — since < 0 → full snapshot
    // -------------------------------------------------------------------------

    @Test
    void poll_sinceNegative_returnsFullSnapshot() throws Exception {
        var t = insertActiveTournament(List.of());

        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/poll/{tt}/{tk}", t.tournamentToken(), t.teamToken())
                                        .param("since", "-1"))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var tree = objectMapper.readTree(body);
        // Full snapshot has 'tournamentId' field (not 'deltas')
        assertThat(tree.get("payload").has("tournamentId")).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC3 — absent since param → full snapshot (treated as since=-1)
    // -------------------------------------------------------------------------

    @Test
    void poll_noSinceParam_returnsFullSnapshot() throws Exception {
        var t = insertActiveTournament(List.of());

        MvcResult result =
                mockMvc.perform(get("/api/v1/poll/{tt}/{tk}", t.tournamentToken(), t.teamToken()))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var tree = objectMapper.readTree(body);
        assertThat(tree.get("payload").has("tournamentId")).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC9/AC10 — unknown tournament_token → 410
    // -------------------------------------------------------------------------

    @Test
    void poll_unknownTournamentToken_returns410() throws Exception {
        mockMvc.perform(
                        get(
                                "/api/v1/poll/{tt}/{tk}",
                                "unknown-tournament-token",
                                "some-team-token-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .andExpect(status().isGone());
    }

    // -------------------------------------------------------------------------
    // AC9 — malformed team token (wrong length) → 410
    // -------------------------------------------------------------------------

    @Test
    void poll_malformedTeamToken_returns410() throws Exception {
        var t = insertActiveTournament(List.of());

        mockMvc.perform(get("/api/v1/poll/{tt}/{tk}", t.tournamentToken(), "short"))
                .andExpect(status().isGone());
    }

    // -------------------------------------------------------------------------
    // AC10 — valid tournament token + HMAC mismatch → 410
    // -------------------------------------------------------------------------

    @Test
    void poll_hmacMismatch_returns410() throws Exception {
        var t = insertActiveTournament(List.of());

        // Compute HMAC for a team UUID not in the list
        byte[] otherSecret = randomSecret();
        String wrongToken = tokenFor(otherSecret, "some-other-team-uuid");

        mockMvc.perform(get("/api/v1/poll/{tt}/{tk}", t.tournamentToken(), wrongToken))
                .andExpect(status().isGone());
    }

    // -------------------------------------------------------------------------
    // AC11 — superseded beyond 24h grace → 410
    // -------------------------------------------------------------------------

    @Test
    void poll_supersededBeyond24h_returns410() throws Exception {
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

        // superseded 25 hours ago (past 24h grace)
        LocalDateTime supersededAt = LocalDateTime.now().minusHours(25);

        InfoDaoTestSupport.insertDirectly(
                dataSource,
                "tournament",
                Map.of(
                        "tournament_id", tournamentId,
                        "tenant_id", tenantId,
                        "location_id", "loc-1",
                        "tournament_token", tournamentToken,
                        "per_tournament_secret", secret,
                        "state", stateJson,
                        "last_applied_seq", 0L,
                        "registered_at", LocalDateTime.now().minusHours(30),
                        "superseded_at", supersededAt));

        mockMvc.perform(get("/api/v1/poll/{tt}/{tk}", tournamentToken, teamToken))
                .andExpect(status().isGone());
    }

    // -------------------------------------------------------------------------
    // AC5/AC11 — superseded within 24h grace → returns frozen snapshot (200)
    // -------------------------------------------------------------------------

    @Test
    void poll_supersededWithin24hGrace_returnsFrozenSnapshot() throws Exception {
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

        // superseded 1 hour ago (within 24h grace)
        LocalDateTime supersededAt = LocalDateTime.now().minusHours(1);

        InfoDaoTestSupport.insertDirectly(
                dataSource,
                "tournament",
                Map.of(
                        "tournament_id", tournamentId,
                        "tenant_id", tenantId,
                        "location_id", "loc-1",
                        "tournament_token", tournamentToken,
                        "per_tournament_secret", secret,
                        "state", stateJson,
                        "last_applied_seq", 5L,
                        "registered_at", LocalDateTime.now().minusHours(5),
                        "superseded_at", supersededAt));

        MvcResult result =
                mockMvc.perform(get("/api/v1/poll/{tt}/{tk}", tournamentToken, teamToken))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var tree = objectMapper.readTree(body);
        // Returns snapshot (frozen state)
        assertThat(tree.get("payload").has("tournamentId")).isTrue();
        assertThat(tree.get("payload").get("tournamentId").asText()).isEqualTo(tournamentId);
    }

    // -------------------------------------------------------------------------
    // AC12 — per-team view projection: team sees own match, not other team's match
    // -------------------------------------------------------------------------

    @Test
    void poll_teamView_filtersOtherTeamMatches() throws Exception {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        String tournamentToken = UUID.randomUUID().toString();
        byte[] secret = randomSecret();
        String teamAId = UUID.randomUUID().toString();
        String teamBId = UUID.randomUUID().toString();
        String teamAToken = tokenFor(secret, teamAId);

        TeamEntry teamA = new TeamEntry(teamAId, "Team Alpha", 1);
        TeamEntry teamB = new TeamEntry(teamBId, "Team Beta", 2);

        TournamentSnapshot snap =
                new TournamentSnapshot(
                        tournamentId,
                        tenantId,
                        1L,
                        List.of(
                                new ScheduleEntry.Match("m1", "Team Alpha", "Team Beta", 1),
                                new ScheduleEntry.Match("m2", "Team Beta", "Team Gamma", 2)),
                        List.of(teamA, teamB));
        String stateJson =
                objectMapper.writeValueAsString(new Envelope<>(Envelope.SCHEMA_VERSION, snap));

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
                        "last_applied_seq", 1L,
                        "registered_at", LocalDateTime.now(),
                        "superseded_at", null));

        MvcResult result =
                mockMvc.perform(get("/api/v1/poll/{tt}/{tk}", tournamentToken, teamAToken))
                        .andExpect(status().isOk())
                        .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var tree = objectMapper.readTree(body);
        var entries = tree.get("payload").get("scheduleEntries");
        // Team Alpha is in match m1 (home), not in match m2
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("id").asText()).isEqualTo("m1");
    }
}
