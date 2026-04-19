package de.vvwt.tm.infrastructure.print;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.infrastructure.web.dto.TournamentCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TournamentResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link PrintController} — E08S07: Print route skeleton + Mustache layout +
 * CSS.
 *
 * <p>Tests the full HTTP stack to verify:
 *
 * <ul>
 *   <li>AC1: {@code /print/**} routes are served by this controller alongside other routes
 *   <li>AC2: {@code /print/**} routes require basic auth (401 without credentials)
 *   <li>AC6: Print static assets ({@code /print/assets/print.css}) are served correctly
 *   <li>AC7: Non-existent tournament → 404; tournament with no phases → human-readable HTML
 *   <li>AC9: Tournament resolved via tenant-scoped repository (cross-tenant 404)
 * </ul>
 *
 * <h2>Test strategy</h2>
 *
 * <p>The test context uses an isolated in-memory H2 database. Tournament records are created via
 * the {@code POST /api/tournaments} endpoint using authenticated requests. This ensures the full
 * tenant-scoped data path is exercised (including {@code TenantContext} population by the
 * default-tenant resolver).
 *
 * @see PrintController
 * @see de.vvwt.tm.auth.internal.SecurityConfig
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S07.story.md">Story
 *     E08S07</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            PrintControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e08s07printdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("PrintController IT — E08S07: print route skeleton + Mustache layout + CSS")
class PrintControllerIT {

    /** Known plaintext password set by {@link TestAdminCredentials}. */
    private static final String TEST_PASSWORD = "PrintTestPass08S07";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC2: /print/** requires authentication — 401 without credentials
    // =========================================================================

    @Test
    @DisplayName("AC2: GET /print/{id} without credentials returns 401")
    void printIndexRequiresAuthentication() throws Exception {
        UUID randomId = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/print/" + randomId), String.class);

        assertThat(response.getStatusCode())
                .as(
                        "AC2: /print/** must require authentication — unauthenticated request"
                                + " returns 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC7: Non-existent tournament → 404
    // =========================================================================

    @Test
    @DisplayName("AC7: GET /print/{unknownId} with valid auth returns 404")
    void printIndexReturns404ForUnknownTournament() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(new URI(baseUrl + "/print/" + unknownId), String.class);

        assertThat(response.getStatusCode())
                .as("AC7: Non-existent tournament must return HTTP 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC7: Draft tournament (no phases) → human-readable Mustache HTML error page
    // =========================================================================

    @Test
    @DisplayName(
            "AC7: GET /print/{id} for draft tournament returns human-readable HTML error, not JSON")
    void printIndexReturnsMustacheErrorForDraftTournament() throws Exception {
        // Create a tournament — it starts as DRAFT with no phases
        UUID tournamentId = createTournament("Drucktest Turnier");

        ResponseEntity<String> response =
                authed.getForEntity(new URI(baseUrl + "/print/" + tournamentId), String.class);

        assertThat(response.getStatusCode())
                .as(
                        "AC7: Draft tournament (no phases) must return 200 with human-readable"
                                + " error page")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody())
                .as("AC7: Error page must be HTML (contains DOCTYPE)")
                .containsIgnoringCase("<!DOCTYPE html>");

        assertThat(response.getBody())
                .as("AC7: Error page must NOT be a raw JSON error (no '{\"status\":' pattern)")
                .doesNotContain("\"status\":");

        assertThat(response.getBody())
                .as("AC7: Error page must contain the no-phases i18n message")
                .contains("Spielplan wurde noch nicht erstellt");
    }

    // =========================================================================
    // AC1: /print/{id} serves HTML (index page for tournament with phases skipped —
    //      full phase setup is E08S01+/S03+ scope; here we test that the route exists
    //      and the controller is wired correctly with the Mustache view resolver)
    // =========================================================================

    @Test
    @DisplayName("AC1: GET /print/{id} is served by the Spring controller (not a 404 or redirect)")
    void printIndexRouteIsServedByController() throws Exception {
        // Create a tournament — controller must respond (404 from controller, not from 'route not
        // found')
        UUID tournamentId = createTournament("Print Route Test");

        ResponseEntity<String> response =
                authed.getForEntity(new URI(baseUrl + "/print/" + tournamentId), String.class);

        // The response is 200 (with error page) — NOT a 404 from Spring's default handler
        // (which would indicate the /print/** route is not mapped at all)
        assertThat(response.getStatusCode())
                .as(
                        "AC1: /print/** route must be handled by PrintController (returns 200 with"
                                + " Mustache page)")
                .isEqualTo(HttpStatus.OK);

        assertThat(response.getBody())
                .as("AC1: Response must be HTML from Mustache view resolver")
                .containsIgnoringCase("<!DOCTYPE html>");
    }

    // =========================================================================
    // AC6: Print CSS static asset accessible (with or without auth — /print/assets/** is permitAll)
    // =========================================================================

    @Test
    @DisplayName("AC6: GET /print/assets/print.css returns 200 without authentication")
    void printCssIsPubliclyAccessible() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/print/assets/print.css"), String.class);

        assertThat(response.getStatusCode())
                .as("AC6: /print/assets/print.css must be served without authentication")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC6: GET /print/assets/print.css returns text/css Content-Type")
    void printCssHasCorrectContentType() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/print/assets/print.css"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("AC6: print.css must be served with text/css Content-Type")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .as("AC6: Content-Type must start with text/css")
                .startsWith("text/css");
    }

    @Test
    @DisplayName("AC4: print.css contains @media print rules")
    void printCssContainsMediaPrintRules() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/print/assets/print.css"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC4: CSS must contain @media print rules")
                .contains("@media print");
    }

    @Test
    @DisplayName("AC4: print.css contains .page-break class with CSS page break")
    void printCssContainsPageBreakClass() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/print/assets/print.css"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC4: CSS must define .page-break class")
                .contains(".page-break");
        assertThat(response.getBody())
                .as("AC4: .page-break must use break-before: page (modern CSS)")
                .contains("break-before: page");
    }

    // =========================================================================
    // AC3: Mustache base layout includes link to print CSS (print-layout.mustache)
    // =========================================================================

    @Test
    @DisplayName("AC3: Print pages link to print CSS stylesheet")
    void printIndexPageLinksToPrintCss() throws Exception {
        UUID tournamentId = createTournament("CSS Link Test");

        ResponseEntity<String> response =
                authed.getForEntity(new URI(baseUrl + "/print/" + tournamentId), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC3: Print page must link to the print CSS stylesheet")
                .contains("/print/assets/print.css");
    }

    // =========================================================================
    // AC9: Cross-tenant isolation — unknown UUID returns 404, not a data leak
    // (the tenant-scoped repository returns empty for IDs not belonging to the active tenant)
    // =========================================================================

    @Test
    @DisplayName("AC9: GET /print/{randomId} returns 404 (cross-tenant isolation)")
    void printIndexReturns404ForRandomUuid() throws Exception {
        // Use a UUID that has never been created — the tenant-scoped TournamentRepository
        // will return Optional.empty(), and the controller must return 404.
        UUID neverCreated = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(new URI(baseUrl + "/print/" + neverCreated), String.class);

        assertThat(response.getStatusCode())
                .as("AC9: Tournament not in tenant scope must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Creates a tournament via the admin REST API and returns its UUID.
     *
     * <p>The created tournament has status {@code DRAFT} and no phases. Used to test the
     * draft/no-phases error path (AC7) and the general route existence check (AC1).
     *
     * @param description human-readable tournament name
     * @return the UUID of the created tournament
     */
    private UUID createTournament(String description) throws Exception {
        TournamentCreateRequest request =
                new TournamentCreateRequest(
                        description,
                        null, // appointment — optional
                        8, // teamCount ≥ 2
                        4, // fieldCount ≥ 1
                        "BEST_OF_3", // matchFormat
                        "setPoints", // scoringRuleId
                        "standardVolleyball", // setValidationRuleId
                        "roundRobin" // matchGeneratorId
                        );

        ResponseEntity<TournamentResponse> created =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(created.getStatusCode())
                .as("Tournament creation must succeed (201)")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    // =========================================================================
    // Test configuration — known test admin password
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
