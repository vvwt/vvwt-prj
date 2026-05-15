package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.web.internal.dto.MatchSummaryResponse;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for the extended {@code GET /api/phases/{phaseId}/matches} endpoint (E48S26).
 *
 * <p>Verifies that {@link MatchSummaryResponse} includes team names (resolved via
 * Match→TeamAvatar→Team.description multi-join) and per-set scores (from set_result rows ordered by
 * setIndex), in addition to the existing fields (matchId, state, lapNumber, fieldNumber).
 *
 * <h2>DEC-44 module annotation</h2>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)} per
 * DEC-44 D1.
 *
 * <h2>DEC-22 Iron Law — RED-first</h2>
 *
 * <p>This test class was written before the production extension of {@link MatchSummaryResponse}
 * and {@link PhaseTransitionController#listPhaseMatches} (E48S26). Tests fail RED on staging HEAD
 * (team1Name/team2Name/setScores fields absent from response).
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-MATCHLIST-ENDPOINT-EXTENDED-RED: team names + per-set scores in response
 *   <li>AC-TEST-MATCHLIST-NO-SETS-RED: match with no set_result rows → empty setScores
 *   <li>AC-ERR-TEAM-NAME-FALLBACK: null teamId or blank Team.description → fallback label
 *   <li>AC-ERR-MATCHLIST-UNKNOWN-PHASE: unknown phaseId → empty list
 *   <li>AC-SEC-MATCHLIST-ADMIN-AUTH: unauthenticated → 401
 *   <li>AC-SEC-MATCHLIST-TENANT-SCOPED: team names from correct tenant only
 * </ul>
 *
 * <h2>DEC-26 DAO test governance</h2>
 *
 * <p>Fixture data inserted via direct JDBC. FK-ordered seeding: tenant → location → tournament →
 * team → team_avatar → phase → match → set_result. Column lists mirror {@code
 * db/migration/tournament/V1__initial_schema.sql}.
 *
 * @see PhaseTransitionController
 * @see MatchSummaryResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S26">E48S26 — Match-overview entry point + correction form pre-load</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, PhaseMatchSummaryControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("PhaseMatchSummaryControllerIT — E48S26 team-name + set-score extension")
class PhaseMatchSummaryControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E48S26PhaseMatchSummaryControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Fixture IDs — allocated fresh per test class
    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID matchId;
    private UUID avatar1Id;
    private UUID avatar2Id;
    private UUID team1Id;
    private UUID team2Id;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);

        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        matchId = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        team1Id = UUID.randomUUID();
        team2Id = UUID.randomUUID();

        // DEC-26 FK-ordered seeding: location → tournament → team → phase → team_avatar → match
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "MatchSummaryIT Location");

        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "MatchSummaryIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                4);

        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description) VALUES"
                        + " (?, ?, ?, ?)",
                team1Id,
                tournamentId,
                1,
                "Team Alpha");
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description) VALUES"
                        + " (?, ?, ?, ?)",
                team2Id,
                tournamentId,
                2,
                "Team Beta");

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM set_result WHERE match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match WHERE id = ?", matchId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE phase_id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM phase WHERE id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // Security: unauthenticated GET → 401 (AC-SEC-MATCHLIST-ADMIN-AUTH)
    // =========================================================================

    @Test
    @DisplayName("AC-SEC-MATCHLIST-ADMIN-AUTH: unauthenticated GET returns 401")
    void unauthenticatedGet_phaseMatches_returns401() throws Exception {
        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/phases/" + phaseId + "/matches"),
                        HttpMethod.GET,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated GET must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC-TEST-MATCHLIST-ENDPOINT-EXTENDED-RED: team names + per-set scores
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-MATCHLIST-ENDPOINT-EXTENDED-RED: GET /api/phases/{phaseId}/matches includes"
                    + " team1Name, team2Name, and setScores ordered by setIndex")
    void getPhaseMatches_withTeamsAndSetResults_returnsExtendedMatchSummary() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithMatchAndSetResults();
        tenantBinder.unbind();

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/phases/" + phaseId + "/matches"),
                        HttpMethod.GET,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("GET phase matches must return 200")
                .isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        assertThat(body).isNotNull();

        List<MatchSummaryResponse> matches =
                objectMapper.readValue(body, new TypeReference<List<MatchSummaryResponse>>() {});

        assertThat(matches).hasSize(1);

        MatchSummaryResponse summary = matches.get(0);
        assertThat(summary.matchId()).as("matchId must match the seeded match").isEqualTo(matchId);

        // AC-TEST-MATCHLIST-ENDPOINT-EXTENDED-RED: team names resolved via Join
        assertThat(summary.team1Name())
                .as("team1Name must be resolved from Team.description for avatar1")
                .isEqualTo("Team Alpha");
        assertThat(summary.team2Name())
                .as("team2Name must be resolved from Team.description for avatar2")
                .isEqualTo("Team Beta");

        // AC-TEST-MATCHLIST-ENDPOINT-EXTENDED-RED: per-set scores ordered by setIndex
        assertThat(summary.setScores())
                .as("setScores must contain 2 entries for the seeded set_result rows")
                .isNotNull()
                .hasSize(2);

        MatchSummaryResponse.SetScoreDto set0 =
                summary.setScores().stream()
                        .filter(s -> s.setIndex() == 0)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("setIndex 0 not found"));
        assertThat(set0.team1Points()).as("set0 team1Points").isEqualTo(25);
        assertThat(set0.team2Points()).as("set0 team2Points").isEqualTo(10);

        MatchSummaryResponse.SetScoreDto set1 =
                summary.setScores().stream()
                        .filter(s -> s.setIndex() == 1)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("setIndex 1 not found"));
        assertThat(set1.team1Points()).as("set1 team1Points").isEqualTo(25);
        assertThat(set1.team2Points()).as("set1 team2Points").isEqualTo(15);
    }

    // =========================================================================
    // AC-TEST-MATCHLIST-NO-SETS-RED: match with no set_result rows → empty setScores
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-MATCHLIST-NO-SETS-RED: match with no set_result rows returns empty"
                    + " setScores list")
    void getPhaseMatches_matchWithNoSetResults_returnsEmptySetScores() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithMatchNoSetResults();
        tenantBinder.unbind();

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/phases/" + phaseId + "/matches"),
                        HttpMethod.GET,
                        null,
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        List<MatchSummaryResponse> matches =
                objectMapper.readValue(body, new TypeReference<List<MatchSummaryResponse>>() {});

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).setScores())
                .as("setScores must be empty (not null) for match with no set_result rows")
                .isNotNull()
                .isEmpty();
    }

    // =========================================================================
    // AC-ERR-MATCHLIST-UNKNOWN-PHASE: unknown phaseId → empty list
    // =========================================================================

    @Test
    @DisplayName(
            "AC-ERR-MATCHLIST-UNKNOWN-PHASE: unknown phaseId returns 200 with empty list"
                    + " (consistent with matchRepository.findByPhaseId contract)")
    void getPhaseMatches_unknownPhaseId_returnsEmptyList() throws Exception {
        UUID unknownPhaseId = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/phases/" + unknownPhaseId + "/matches"),
                        HttpMethod.GET,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("unknown phaseId must return 200 (empty list)")
                .isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        List<MatchSummaryResponse> matches =
                objectMapper.readValue(body, new TypeReference<List<MatchSummaryResponse>>() {});
        assertThat(matches).as("empty list expected for unknown phaseId").isEmpty();
    }

    // =========================================================================
    // AC-ERR-TEAM-NAME-FALLBACK: avatar with null teamId → non-empty fallback name
    // =========================================================================

    @Test
    @DisplayName(
            "AC-ERR-TEAM-NAME-FALLBACK: avatar with null teamId returns non-empty fallback"
                    + " label (not null, not empty string)")
    void getPhaseMatches_avatarWithNullTeamId_returnsFallbackName() throws Exception {
        tenantBinder.bindDefaultTenant();
        seedActivePhaseWithMatchNullTeamId();
        tenantBinder.unbind();

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/phases/" + phaseId + "/matches"),
                        HttpMethod.GET,
                        null,
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        List<MatchSummaryResponse> matches =
                objectMapper.readValue(body, new TypeReference<List<MatchSummaryResponse>>() {});

        assertThat(matches).hasSize(1);
        MatchSummaryResponse summary = matches.get(0);
        assertThat(summary.team1Name())
                .as("team1Name fallback must be non-null and non-empty when teamId is null")
                .isNotNull()
                .isNotEmpty();
        assertThat(summary.team2Name())
                .as("team2Name fallback must be non-null and non-empty when teamId is null")
                .isNotNull()
                .isNotEmpty();
    }

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    /** Seeds an ACTIVE phase + match with 2 set_result rows + team_avatar rows with teamId. */
    private void seedActivePhaseWithMatchAndSetResults() {
        // DEC-26 FK-ordered seeding: phase → team_avatar → match → set_result
        // Column list mirrors V1__initial_schema.sql
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "MatchSummaryIT Phase",
                "ACTIVE",
                1,
                LocalDateTime.now());

        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                1,
                1,
                team1Id);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                1,
                2,
                team2Id);

        // match with lap_number=1, field_number=1 (1-based per DEC-60)
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, lap_number, field_number,"
                        + " created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                51, // FINISHED_WINNER1 legacy code (MatchState enum: 51)
                3,
                1,
                1,
                LocalDateTime.now());

        // set_result rows: set_index 0 and 1 (Column list per V1__initial_schema.sql)
        // set_state=1 (FINISHED per SetState enum)
        jdbcTemplate.update(
                "INSERT INTO set_result (match_id, set_index, phase_id, team1_points,"
                        + " team2_points, set_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                matchId,
                0,
                phaseId,
                25,
                10,
                1);
        jdbcTemplate.update(
                "INSERT INTO set_result (match_id, set_index, phase_id, team1_points,"
                        + " team2_points, set_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                matchId,
                1,
                phaseId,
                25,
                15,
                1);
    }

    /** Seeds a phase + match with team_avatar rows (with teamId) but NO set_result rows. */
    private void seedActivePhaseWithMatchNoSetResults() {
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "MatchSummaryIT Phase NoSets",
                "ACTIVE",
                0,
                LocalDateTime.now());

        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                1,
                1,
                team1Id);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                1,
                2,
                team2Id);

        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                0, // OPEN legacy code (MatchState enum: 0)
                3,
                LocalDateTime.now());
        // No set_result rows inserted
    }

    /**
     * Seeds a phase + match with team_avatar rows whose teamId is NULL (edge case per
     * AC-ERR-TEAM-NAME-FALLBACK).
     */
    private void seedActivePhaseWithMatchNullTeamId() {
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "MatchSummaryIT Phase NullTeam",
                "ACTIVE",
                0,
                LocalDateTime.now());

        // team_avatar rows with teamId=NULL (structural placeholder avatars, pre-team-assignment)
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position) VALUES (?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                1,
                1);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position) VALUES (?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                1,
                2);

        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                51, // FINISHED_WINNER1 legacy code (MatchState enum: 51)
                3,
                LocalDateTime.now());
    }

    // =========================================================================
    // Test-local AdminCredentials
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
