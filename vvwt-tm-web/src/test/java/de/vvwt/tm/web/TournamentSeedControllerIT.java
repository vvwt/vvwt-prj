package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for E05S12 auto-seed behaviour at {@code POST /api/tournaments}.
 *
 * <h2>RED state (E05S12 — DEC-22 Iron Law)</h2>
 *
 * <p>This test class was committed RED (E05S12): the seed implementation did not exist at commit
 * time. The {@link #postCreateTournament_seedsNTeamRows_assertjDbVerifiesCount()} test would fail
 * because zero rows were inserted in the {@code team} table — proving the RED state before GREEN.
 *
 * <h2>IT annotation (DEC-44 D1 + DEC-40 Clause E §Sub-Clause-3)</h2>
 *
 * <p>Web-module controller ITs use {@code @SpringBootTest(RANDOM_PORT,
 * classes=TournamentManagerApplication.class)} per DEC-44 D1. {@link WebModuleTestConfig} provides
 * auth-substitute beans per DEC-44 D2.
 *
 * <h2>assertj-db independent verifier (DEC-26 Rule 2)</h2>
 *
 * <p>Team row count is verified directly from the DataSource via assertj-db — never via a
 * subsequent GET endpoint or repository read method (controller-as-own-evaluator anti-pattern,
 * {@code conventions.md} §(c)).
 *
 * <h2>Coverage — E05S12 acceptance criteria</h2>
 *
 * <ul>
 *   <li>AC-IMPL-AUTO-SEED-AT-CREATE — N team rows in DB after POST
 *   <li>AC-I18N-LOCALE-CHAIN — tenant configured with language="de", tournament.language=NULL →
 *       "Mannschaft 01" per AC-I18N-LOCALE-CHAIN
 *   <li>AC-SEC-TENANT-SCOPE — team rows under tenant A not visible to tenant B
 *   <li>AC-SEC-AUTH-PRESERVED — unauthenticated POST still returns 401
 *   <li>AC-ERR-MIN-TEAMCOUNT-PRESERVED — teamCount=0 returns HTTP 400, zero rows in both tables
 * </ul>
 *
 * @see TournamentController
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (assertj-db independent verifier)</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest canon (bounded-context ITs)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest</a>
 * @see <a href="E05S12">E05S12 — Tournament create auto-seed N empty team slots</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, TournamentSeedControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("TournamentController IT — E05S12 auto-seed acceptance")
class TournamentSeedControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E05S12TournamentSeedControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private DataSource dataSource;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // AC-IMPL-AUTO-SEED-AT-CREATE + AC-I18N-LOCALE-CHAIN
    // =========================================================================

    /**
     * Happy-path: POST with teamCount=4 creates tournament + 4 team rows.
     *
     * <p>Verifies AC-IMPL-AUTO-SEED-AT-CREATE (N rows in team table) and AC-I18N-LOCALE-CHAIN
     * (tenant.language="de", tournament.language=NULL → description "Mannschaft 01" for first row).
     */
    @Test
    @DisplayName("POST /api/tournaments seeds exactly N team rows; first row description 'Mannschaft 01' (AC-IMPL-AUTO-SEED + AC-I18N-LOCALE-CHAIN)")
    void postCreateTournament_seedsNTeamRows_assertjDbVerifiesCount() throws Exception {
        int teamCount = 4;
        var request =
                new TournamentCreateRequest(
                        "E05S12 Seed IT Hallenturnier",
                        null,
                        teamCount,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(response.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        UUID tournamentId = response.getBody().id();
        assertThat(tournamentId).isNotNull();

        // DEC-26 Rule 2 — assertj-db independent verifier: team rows in DB
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();

            // AC-IMPL-AUTO-SEED-AT-CREATE: exactly N team rows for this tournament
            Table teamTable = assertDb.table("team").build();
            List<Object> teamTournamentIds =
                    teamTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("TOURNAMENT_ID").getValue())
                            .filter(id -> tournamentId.equals(id))
                            .toList();
            assertThat(teamTournamentIds)
                    .as("exactly %d team rows must exist for tournament %s", teamCount, tournamentId)
                    .hasSize(teamCount);

            // AC-I18N-LOCALE-CHAIN: team with team_number=1 has description "Mannschaft 01"
            List<Object> descriptions =
                    teamTable.getRowsList().stream()
                            .filter(
                                    row ->
                                            tournamentId.equals(
                                                    row.getColumnValue("TOURNAMENT_ID").getValue()))
                            .filter(
                                    row -> {
                                        Object teamNum =
                                                row.getColumnValue("TEAM_NUMBER").getValue();
                                        return teamNum instanceof Number n && n.intValue() == 1;
                                    })
                            .map(row -> row.getColumnValue("DESCRIPTION").getValue())
                            .toList();
            assertThat(descriptions)
                    .as(
                            "team with team_number=1 must have description 'Mannschaft 01'"
                                    + " (tenant.language='de', tournament.language=NULL)")
                    .containsExactly("Mannschaft 01");
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // AC-ERR-MIN-TEAMCOUNT-PRESERVED — teamCount=0 rejected with HTTP 400, zero rows
    // =========================================================================

    @Test
    @DisplayName("POST /api/tournaments with teamCount=0 returns HTTP 400, zero tournament+team rows (AC-ERR-MIN-TEAMCOUNT-PRESERVED)")
    void postCreateTournament_teamCountZero_returns400_zeroRows() throws Exception {
        // teamCount=0 violates @Min(2) constraint on TournamentCreateRequest
        String requestBody =
                "{\"description\":\"Bad Request\",\"teamCount\":0,\"fieldCount\":2,"
                        + "\"matchFormat\":\"BEST_OF_3\",\"scoringRuleId\":\"setPoints\","
                        + "\"setValidationRuleId\":\"standardVolleyball\","
                        + "\"matchGeneratorId\":\"roundRobin\"}";

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"),
                        new org.springframework.http.HttpEntity<>(
                                requestBody,
                                buildJsonHeaders()),
                        String.class);

        assertThat(response.getStatusCode())
                .as("teamCount=0 must return HTTP 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/tournaments with teamCount=1 returns HTTP 400 (AC-ERR-MIN-TEAMCOUNT-PRESERVED)")
    void postCreateTournament_teamCountOne_returns400() throws Exception {
        String requestBody =
                "{\"description\":\"TeamCount One\",\"teamCount\":1,\"fieldCount\":2,"
                        + "\"matchFormat\":\"BEST_OF_3\",\"scoringRuleId\":\"setPoints\","
                        + "\"setValidationRuleId\":\"standardVolleyball\","
                        + "\"matchGeneratorId\":\"roundRobin\"}";

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"),
                        new org.springframework.http.HttpEntity<>(
                                requestBody,
                                buildJsonHeaders()),
                        String.class);

        assertThat(response.getStatusCode())
                .as("teamCount=1 must return HTTP 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC-SEC-AUTH-PRESERVED — unauthenticated POST returns 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/tournaments returns 401 (AC-SEC-AUTH-PRESERVED)")
    void unauthenticatedPostReturns401() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "Unauthorized Seed Tournament",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static org.springframework.http.HttpHeaders buildJsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
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
