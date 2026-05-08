package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import java.net.URI;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Minimalist integration tests for {@link TournamentController} (E21S02,
 * AC-REST-IT-HAPPY-TournamentController + AC-REST-IT-SEC-TournamentController).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>Exactly 2 {@code @Test} methods per controller:
 *
 * <ol>
 *   <li>Happy-path authenticated POST → 201 + assertj-db independent DB verification (DEC-26 Rule
 *       2)
 *   <li>Unauthenticated request → 401 (security gate)
 * </ol>
 *
 * <p>Slice tests ({@link TournamentControllerSliceTest}) cover all other scenarios.
 *
 * <h2>assertj-db independent verifier (DEC-26 Rule 2)</h2>
 *
 * <p>The happy-path test reads the persisted row directly from the DataSource via assertj-db — not
 * via the service or repository. This confirms the write path reaches the DB independently of the
 * read path.
 *
 * <h2>Module scope (DEC-38/DEC-40 Clause E)</h2>
 *
 * <p>Relocated from {@code de.vvwt.tm.tournament} to {@code de.vvwt.tm.web} per DEC-40 Clause D
 * (E22S07, Q-1b whole-class relocation). The {@code @ApplicationModuleTest} annotation now resolves
 * {@code de.vvwt.tm.web} as the module under test (booting web + tenant + tournament +
 * tournament::exceptions + tournament::dto + scoring per {@code allowedDependencies}). {@link
 * WebModuleTestConfig} is used instead of {@link de.vvwt.tm.tournament.TournamentModuleTestConfig}
 * because Spring Modulith 1.4.6's {@code ModuleTestExecutionBeanDefinitionSelector} requires the
 * test config to reside in the module under test (DEC-38 Clause C).
 *
 * @see TournamentController
 * @see TournamentControllerSliceTest
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate TournamentController to de.vvwt.tm.web</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, TournamentControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName("TournamentController IT — E21S02 AC-REST-IT (2-test minimalist)")
class TournamentControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S02TournamentControllerIT01";

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
    // Happy-path: authenticated POST → 201, row verified via assertj-db
    // =========================================================================

    @Test
    @DisplayName("authenticated POST /api/tournaments creates tournament; assertj-db verifies row")
    void authenticatedPostCreatesTournamentAndPersistsRow() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "IT Hallenturnier E21S02",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null);

        ResponseEntity<TournamentResponse> response =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(response.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().description()).isEqualTo("IT Hallenturnier E21S02");
        assertThat(response.getBody().status()).isEqualTo("DRAFT");
        assertThat(response.getBody().id()).isNotNull();

        // DEC-26 Rule 2 — assertj-db independent verifier
        UUID newId = response.getBody().id();
        tenantBinder.bindDefaultTenant();
        try {
            AssertDbConnection assertDb = AssertDbConnectionFactory.of(dataSource).create();
            Table tournamentTable = assertDb.table("tournament").build();
            List<Object> ids =
                    tournamentTable.getRowsList().stream()
                            .map(row -> row.getColumnValue("ID").getValue())
                            .toList();
            assertThat(ids)
                    .as("tournament table must contain the newly created tournament UUID")
                    .contains(newId);
        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security: unauthenticated POST → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/tournaments returns 401")
    void unauthenticatedPostReturns401() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "Unauthorized Tournament",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, String.class);

        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC-TEST-CREATE-WITH-PLANNED-START-TIME-IT-RED (E48S14)
    // RED-first: before production fix, plannedStartTime is silently dropped by all four layers.
    // After fix, POST with plannedStartTime persists the value; GET returns it.
    // =========================================================================

    @Test
    @DisplayName(
            "E48S14 AC-TEST-CREATE-WITH-PLANNED-START-TIME-IT-RED: "
                    + "POST with plannedStartTime='09:00' → 201; GET returns plannedStartTime set")
    void createWithPlannedStartTime_persistsAndReturnsStartTime() throws Exception {
        // Use raw Map to include plannedStartTime in the JSON body regardless of DTO state.
        // Before the fix: TournamentCreateRequest.java lacks the field → backend ignores it →
        // GET returns null → assertion FAILS (RED).
        // After the fix: all four layers wire the field → assertion PASSES (GREEN).
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "E48S14 IT Tournament With Start Time");
        body.put("appointment", null);
        body.put("teamCount", 4);
        body.put("fieldCount", 2);
        body.put("matchFormat", "BEST_OF_3");
        body.put("scoringRuleId", "setPoints");
        body.put("setValidationRuleId", "standardVolleyball");
        body.put("matchGeneratorId", "roundRobin");
        body.put("plannedStartTime", "09:00");

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), body, TournamentResponse.class);

        assertThat(createResponse.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID newId = createResponse.getBody().id();

        // Fetch the newly created tournament and verify plannedStartTime is persisted.
        ResponseEntity<TournamentResponse> getResponse =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + newId), TournamentResponse.class);

        assertThat(getResponse.getStatusCode())
                .as("GET must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        // Jackson 3.x serializes LocalTime as ISO-8601 "HH:mm:ss" when timestamps are disabled
        // (confirmed: WRITE_DATES_AS_TIMESTAMPS disabled in JacksonConfig, E21S10).
        // TournamentResponse.plannedStartTime is a LocalTime field deserialized from the DB value
        // persisted by DefaultTournamentService.createTournament (E48S14 fix).
        assertThat(getResponse.getBody().plannedStartTime())
                .as("plannedStartTime must be persisted by CREATE (E48S14 fix)")
                .isEqualTo(LocalTime.of(9, 0));
    }

    // =========================================================================
    // AC-TEST-CREATE-WITHOUT-PLANNED-START-TIME-IT-GREEN (E48S14)
    // Complementary: POST without plannedStartTime → 201; GET returns plannedStartTime null.
    // Confirms field is optional and does not break existing CREATE callers.
    // =========================================================================

    @Test
    @DisplayName(
            "E48S14 AC-TEST-CREATE-WITHOUT-PLANNED-START-TIME-IT-GREEN: "
                    + "POST without plannedStartTime → 201; GET returns plannedStartTime null")
    void createWithoutPlannedStartTime_returnsNullStartTime() throws Exception {
        // This test should be GREEN both before and after the fix:
        // before: field is silently dropped (never set anyway) → null;
        // after: field is optional (null means "no start time") → null.
        var request =
                new TournamentCreateRequest(
                        "E48S14 IT Tournament Without Start Time",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null);

        ResponseEntity<TournamentResponse> createResponse =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(createResponse.getStatusCode())
                .as("POST must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID newId = createResponse.getBody().id();

        ResponseEntity<TournamentResponse> getResponse =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + newId), TournamentResponse.class);

        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().plannedStartTime())
                .as("plannedStartTime must be null when not provided")
                .isNull();
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
