package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link PhaseTransitionController} — E48S07
 * AC-TEST-CONTROLLER-IT-PER-METHOD-GREEN + AC-SECURITY-TRANSITION-ENDPOINTS-AUTH.
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>GET /api/phases/{phaseId}/transition-proposal — 200 with proposals; security 401
 *   <li>POST /api/phases/{phaseId}/transition-commit — 200 on success; security 401
 *   <li>AC-TEST-COMMIT-TRANSITION-WITH-MATCHES-RED: roundRobin phase → TeamAvatars persisted
 *   <li>AC-TEST-COMMIT-TRANSITION-SIEGEREHRUNG-RED: siegerehrung → TeamAvatars persisted, no
 *       matches
 * </ul>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 D1. Fixture data inserted via direct JDBC (DEC-26 Rule 3). Persistence verified via direct
 * JDBC after mutation (DEC-26 Rule 2).
 *
 * @see PhaseTransitionController
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S07">E48S07 — AC-TEST-CONTROLLER-IT-PER-METHOD-GREEN</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, PhaseTransitionControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("PhaseTransitionController IT — E48S07")
class PhaseTransitionControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E48S07PhaseTransitionControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private TestRestTemplate authed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Fixture IDs
    private UUID locationId;
    private UUID tournamentId;
    private UUID fromPhaseId;
    private UUID toPhaseRoundRobinId;
    private UUID toSiegerehrungPhaseId;
    private UUID teamId1;
    private UUID teamId2;
    private UUID avatarFromId1;
    private UUID avatarFromId2;

    /**
     * Draft JSON with 3 sections: - section 1: team_number, roundRobin - section 2: team_number,
     * roundRobin (toPhaseRoundRobinId) - section 3: team_number, siegerehrung
     * (toSiegerehrungPhaseId)
     */
    private static final String DRAFT_JSON =
            "{"
                    + "\"sections\": ["
                    + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                    + "  {\"sectionNumber\": 2, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                    + "  {\"sectionNumber\": 3, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"siegerehrung\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                    + "]"
                    + "}";

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E48S07 IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E48S07 IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                2,
                DRAFT_JSON);

        // fromPhase (sequenceNumber=1) — COMPLETED, has 2 avatars
        fromPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                fromPhaseId,
                tournamentId,
                1,
                "Vorrunde",
                "COMPLETED",
                1);

        // toPhase for roundRobin (sequenceNumber=2)
        toPhaseRoundRobinId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                toPhaseRoundRobinId,
                tournamentId,
                2,
                "Zwischenrunde",
                "PENDING",
                0);

        // toPhase for siegerehrung (sequenceNumber=3)
        toSiegerehrungPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                toSiegerehrungPhaseId,
                tournamentId,
                3,
                "Siegerehrung",
                "PENDING",
                0);

        // Teams
        teamId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId1,
                tournamentId,
                1,
                "E48S07 Team 1",
                LocalDateTime.now());
        teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId2,
                tournamentId,
                2,
                "E48S07 Team 2",
                LocalDateTime.now());

        // Avatars in fromPhase (sequenceNumber=1)
        avatarFromId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarFromId1,
                tournamentId,
                fromPhaseId,
                teamId1,
                1,
                1,
                LocalDateTime.now());
        avatarFromId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarFromId2,
                tournamentId,
                fromPhaseId,
                teamId2,
                1,
                2,
                LocalDateTime.now());

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

    // =========================================================================
    // GET /api/phases/{phaseId}/transition-proposal
    // =========================================================================

    @Test
    @DisplayName(
            "GET transition-proposal — authenticated → 200 with proposals (team_number sortType)")
    void getTransitionProposal_authenticated_returns200WithProposals() throws Exception {
        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/api/phases/"
                                        + toPhaseRoundRobinId
                                        + "/transition-proposal"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("GET transition-proposal must return 200 OK")
                .isEqualTo(HttpStatus.OK);

        // Parse response body
        List<TeamAvatarProposal> proposals =
                objectMapper.readValue(
                        response.getBody(), new TypeReference<List<TeamAvatarProposal>>() {});
        assertThat(proposals).as("Must return proposals for both teams").hasSize(2);

        // team_number + 1 group → both teams in group 1
        assertThat(proposals)
                .as("All proposals must have groupNumber=1 for 1-group sortType")
                .allMatch(p -> p.groupNumber() == 1);
        assertThat(proposals)
                .as("Proposals must have unique positions 1 and 2")
                .extracting(TeamAvatarProposal::groupPosition)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("GET transition-proposal — unauthenticated → 401")
    void getTransitionProposal_unauthenticated_returns401() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(
                                baseUrl
                                        + "/api/phases/"
                                        + toPhaseRoundRobinId
                                        + "/transition-proposal"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated GET transition-proposal must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // POST /api/phases/{phaseId}/transition-commit — roundRobin
    // (AC-TEST-COMMIT-TRANSITION-WITH-MATCHES-RED, AC-TEST-CONTROLLER-IT-PER-METHOD-GREEN)
    // =========================================================================

    @Test
    @DisplayName(
            "POST transition-commit — roundRobin phase → 200, TeamAvatars persisted (DEC-26 Rule"
                    + " 2)")
    void postTransitionCommit_roundRobin_returns200AndPersistsAvatars() throws Exception {
        // Given: assignment for toPhaseRoundRobinId (groupCount=1, 2 teams)
        String body =
                "["
                        + "{\"teamId\":\""
                        + teamId1
                        + "\",\"groupNumber\":1,\"groupPosition\":1},"
                        + "{\"teamId\":\""
                        + teamId2
                        + "\",\"groupNumber\":1,\"groupPosition\":2}"
                        + "]";

        RequestEntity<String> request =
                RequestEntity.post(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toPhaseRoundRobinId
                                                + "/transition-commit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<Void> response = authed.exchange(request, Void.class);

        assertThat(response.getStatusCode())
                .as("POST transition-commit (roundRobin) must return 200")
                .isEqualTo(HttpStatus.OK);

        // DEC-26 Rule 2: verify TeamAvatars persisted in DB via direct JDBC
        tenantBinder.bindDefaultTenant();
        try {
            int avatarCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                            Integer.class,
                            toPhaseRoundRobinId);
            assertThat(avatarCount)
                    .as("2 TeamAvatars must be persisted for toPhase (roundRobin)")
                    .isEqualTo(2);

            // DEC-26 Rule 2: verify group/position for team 1
            Integer t1Group =
                    jdbcTemplate.queryForObject(
                            "SELECT group_number FROM team_avatar WHERE phase_id = ? AND team_id ="
                                    + " ?",
                            Integer.class,
                            toPhaseRoundRobinId,
                            teamId1);
            assertThat(t1Group).as("Team 1 must be in group 1").isEqualTo(1);
        } finally {
            tenantBinder.unbind();
        }
    }

    @Test
    @DisplayName(
            "POST transition-commit — siegerehrung phase → 200, TeamAvatars persisted, no Matches"
                    + " (AC-TEST-COMMIT-TRANSITION-SIEGEREHRUNG-RED)")
    void postTransitionCommit_siegerehrung_returns200AndPersistsAvatarsButNoMatches()
            throws Exception {
        // Given: assignment for toSiegerehrungPhaseId (groupCount=1, 2 teams)
        String body =
                "["
                        + "{\"teamId\":\""
                        + teamId1
                        + "\",\"groupNumber\":1,\"groupPosition\":1},"
                        + "{\"teamId\":\""
                        + teamId2
                        + "\",\"groupNumber\":1,\"groupPosition\":2}"
                        + "]";

        RequestEntity<String> request =
                RequestEntity.post(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toSiegerehrungPhaseId
                                                + "/transition-commit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<Void> response = authed.exchange(request, Void.class);

        assertThat(response.getStatusCode())
                .as("POST transition-commit (siegerehrung) must return 200")
                .isEqualTo(HttpStatus.OK);

        // DEC-26 Rule 2: verify TeamAvatars persisted
        tenantBinder.bindDefaultTenant();
        try {
            int avatarCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                            Integer.class,
                            toSiegerehrungPhaseId);
            assertThat(avatarCount)
                    .as("2 TeamAvatars must be persisted for siegerehrung toPhase")
                    .isEqualTo(2);

            // AC-TEST-COMMIT-TRANSITION-SIEGEREHRUNG-RED: no matches for siegerehrung
            int matchCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM match WHERE phase_id = ?",
                            Integer.class,
                            toSiegerehrungPhaseId);
            assertThat(matchCount)
                    .as(
                            "No Matches must be created for siegerehrung phase"
                                    + " (E48S02 no-op generator)")
                    .isEqualTo(0);
        } finally {
            tenantBinder.unbind();
        }
    }

    @Test
    @DisplayName("POST transition-commit — unauthenticated → 401")
    void postTransitionCommit_unauthenticated_returns401() throws Exception {
        String body =
                "["
                        + "{\"teamId\":\""
                        + teamId1
                        + "\",\"groupNumber\":1,\"groupPosition\":1}"
                        + "]";

        RequestEntity<String> request =
                RequestEntity.post(
                                new URI(
                                        baseUrl
                                                + "/api/phases/"
                                                + toPhaseRoundRobinId
                                                + "/transition-commit"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

        ResponseEntity<Void> response = restTemplate.exchange(request, Void.class);

        assertThat(response.getStatusCode())
                .as("Unauthenticated POST transition-commit must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Inner TestConfiguration — per DEC-44 D2 auth substitute pattern
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Autowired private PasswordEncoder passwordEncoder;

        @Bean("testAdminCredentialsProvider")
        @Primary
        public AdminCredentialsProvider adminCredentialsProvider() {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
