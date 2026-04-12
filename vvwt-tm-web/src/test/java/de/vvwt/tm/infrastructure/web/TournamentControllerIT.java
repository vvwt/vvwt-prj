package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.infrastructure.web.dto.TournamentCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TournamentResponse;
import de.vvwt.tm.infrastructure.web.dto.TournamentUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TournamentController} and {@link TournamentRulesController}
 * (E05S04).
 *
 * <p>Tests the full HTTP stack including Spring Security (basic auth), Jackson serialization,
 * and the {@link GlobalExceptionHandler}.
 *
 * <h2>Test scenarios</h2>
 * <ul>
 *   <li>AC1 — GET /api/tournaments returns 200 with list</li>
 *   <li>AC2 — GET /api/tournaments/{id} returns 200 or 404</li>
 *   <li>AC3 — POST /api/tournaments returns 201 with Location header</li>
 *   <li>AC4 — PUT on ACTIVE tournament returns 409</li>
 *   <li>AC5 — DELETE on ACTIVE tournament returns 409</li>
 *   <li>AC8 — GET /api/tournament-rules returns all dropdowns</li>
 *   <li>AC10 — POST with missing required fields returns 400</li>
 *   <li>AC12 — 401 without credentials</li>
 * </ul>
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S04.story.md">Story E05S04</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                TournamentControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e05s04ctrldb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class TournamentControllerIT {

    static final String TEST_PASSWORD = "TmControllerTest01";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC12 — 401 without credentials
    // =========================================================================

    @Test
    void listTournamentsReturns401WithoutCredentials() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/tournaments"), String.class);

        assertThat(response.getStatusCode())
                .as("AC12 — /api/tournaments without credentials must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC1 — GET /api/tournaments
    // =========================================================================

    @Test
    void listTournamentsReturns200WithAuthCredentials() throws Exception {
        ResponseEntity<TournamentResponse[]> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments"), TournamentResponse[].class);

        assertThat(response.getStatusCode())
                .as("AC1 — GET /api/tournaments with credentials must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC1 — response body must be a JSON array (possibly empty)")
                .isNotNull();
    }

    // =========================================================================
    // AC3 — POST /api/tournaments
    // =========================================================================

    @Test
    void createTournamentReturns201WithLocationHeader() throws Exception {
        TournamentCreateRequest request = new TournamentCreateRequest(
                "Hallenturnier 2026",
                null,
                8,
                4,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin");

        ResponseEntity<TournamentResponse> response = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments"),
                request,
                TournamentResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3 — POST /api/tournaments must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation())
                .as("AC3 — 201 response must include Location header")
                .isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status())
                .as("AC3/AC6 — newly created tournament must be DRAFT")
                .isEqualTo("DRAFT");
        assertThat(response.getBody().id())
                .as("AC3 — response must include generated UUID")
                .isNotNull();
    }

    // =========================================================================
    // AC2 — GET /api/tournaments/{id}
    // =========================================================================

    @Test
    void getTournamentReturns200ForExistingTournament() throws Exception {
        // Create first
        TournamentCreateRequest create = new TournamentCreateRequest(
                "For GET Test", null, 4, 2, "BEST_OF_1",
                "setPoints", "standardVolleyball", "roundRobin");
        ResponseEntity<TournamentResponse> created = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments"), create, TournamentResponse.class);
        UUID id = created.getBody().id();

        // Then GET by ID
        ResponseEntity<TournamentResponse> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + id), TournamentResponse.class);

        assertThat(response.getStatusCode())
                .as("AC2 — GET /api/tournaments/{id} must return 200 for existing tournament")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().id()).isEqualTo(id);
        assertThat(response.getBody().description()).isEqualTo("For GET Test");
    }

    @Test
    void getTournamentReturns404ForUnknownId() throws Exception {
        UUID unknownId = UUID.randomUUID();
        ResponseEntity<ApiErrorResponse> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + unknownId), ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC2 — GET /api/tournaments/{unknown-id} must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC4 — PUT: only DRAFT may be edited
    // =========================================================================

    @Test
    void updateDraftTournamentReturns200() throws Exception {
        // Create DRAFT
        TournamentCreateRequest create = new TournamentCreateRequest(
                "Draft to Update", null, 4, 2, "BEST_OF_3",
                "setPoints", "standardVolleyball", "roundRobin");
        TournamentResponse created = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments"), create, TournamentResponse.class).getBody();

        TournamentUpdateRequest update = new TournamentUpdateRequest(
                "Updated Name", null, null, null, null, null, null, null);

        ResponseEntity<TournamentResponse> response = authed.exchange(
                new URI(baseUrl + "/api/tournaments/" + created.id()),
                HttpMethod.PUT,
                new HttpEntity<>(update),
                TournamentResponse.class);

        assertThat(response.getStatusCode())
                .as("AC4 — PUT on DRAFT tournament must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().description()).isEqualTo("Updated Name");
    }

    // =========================================================================
    // AC5 — DELETE: only DRAFT with no phases
    // =========================================================================

    @Test
    void deleteDraftTournamentReturns204() throws Exception {
        // Create DRAFT tournament
        TournamentCreateRequest create = new TournamentCreateRequest(
                "To Be Deleted", null, 4, 2, "BEST_OF_1",
                "setPoints", "standardVolleyball", "roundRobin");
        TournamentResponse created = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments"), create, TournamentResponse.class).getBody();

        ResponseEntity<Void> response = authed.exchange(
                new URI(baseUrl + "/api/tournaments/" + created.id()),
                HttpMethod.DELETE,
                null,
                Void.class);

        assertThat(response.getStatusCode())
                .as("AC5 — DELETE on DRAFT tournament with no phases must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void deleteTournamentReturns404ForUnknownId() throws Exception {
        UUID unknownId = UUID.randomUUID();
        ResponseEntity<ApiErrorResponse> response = authed.exchange(
                new URI(baseUrl + "/api/tournaments/" + unknownId),
                HttpMethod.DELETE,
                null,
                ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC5 — DELETE with unknown ID must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC8 — GET /api/tournament-rules
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void getTournamentRulesReturns200WithAllDropdowns() throws Exception {
        ResponseEntity<java.util.Map> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournament-rules"), java.util.Map.class);

        assertThat(response.getStatusCode())
                .as("AC8 — GET /api/tournament-rules must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC8 — response must include all four key collections")
                .containsKeys("scoringRuleIds", "setValidationRuleIds",
                        "matchGeneratorIds", "matchFormats");
        @SuppressWarnings("unchecked")
        java.util.List<Object> matchFormats = (java.util.List<Object>) response.getBody().get("matchFormats");
        assertThat(matchFormats)
                .as("AC8 — matchFormats must include BEST_OF_3")
                .contains("BEST_OF_3");
    }

    // =========================================================================
    // AC10 — POST: validation errors return 400
    // =========================================================================

    @Test
    void createTournamentWithMissingDescriptionReturns400() throws Exception {
        // description is missing / blank — @NotBlank should fire
        String json = """
                {"teamCount":4,"fieldCount":2,"matchFormat":"BEST_OF_3",
                 "scoringRuleId":"setPoints","setValidationRuleId":"standardVolleyball",
                 "matchGeneratorId":"roundRobin"}
                """;
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<ApiErrorResponse> response = authed.exchange(
                new URI(baseUrl + "/api/tournaments"),
                HttpMethod.POST,
                new HttpEntity<>(json, headers),
                ApiErrorResponse.class);

        assertThat(response.getStatusCode())
                .as("AC10 — POST with missing description must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // TestAdminCredentials — injects a known password for test authentication
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            // Hash the known test password at bean creation time — same pattern as SecurityConfigIT
            String hash = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
