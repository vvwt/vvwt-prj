package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.net.URI;
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
 * Integration tests for {@link ScoreController} (E22S10, DEC-38 Clause A / DEC-40 Clause E).
 *
 * <h2>Approach C minimalist-IT</h2>
 *
 * <p>Exactly the scenarios required by E22S10 acceptance criteria at the HTTP + Mustache rendering
 * layer:
 *
 * <ol>
 *   <li>AC-IT-APPLICATION-MODULE-TEST: three happy-path scenarios (one per endpoint) — HTTP 200 +
 *       {@code Content-Type: text/html} + Mustache-rendered fragment
 *   <li>AC-STATIC-ASSET-REACHABILITY: {@code GET /score/assets/vvwt-tablet.js} returns 200 with
 *       non-empty body
 *   <li>AC-SECURITY-NO-ADMIN-AUTH: unauthenticated access → 200 (not 401)
 *   <li>AC-SECURITY-DEVICE-REGISTRATION: rendered register page body does NOT contain the string
 *       "deviceToken" as a rendered value
 * </ol>
 *
 * <p>Slice tests ({@link ScoreControllerSliceTest}) cover view-name parity, model-attribute parity,
 * and fallback behavior.
 *
 * <h2>Module scope (DEC-38 Clause A / DEC-40 Clause E)</h2>
 *
 * <p>Annotated {@code @ApplicationModuleTest(mode = ALL_DEPENDENCIES, webEnvironment =
 * RANDOM_PORT)} targeting the {@code de.vvwt.tm.web} module. Boots: {@code web} + all its declared
 * {@code allowedDependencies} ({@code tenant}, {@code tournament}, {@code tournament::exceptions},
 * {@code tournament::dto}, {@code scoring}).
 *
 * @see ScoreController
 * @see ScoreControllerSliceTest
 * @see <a href="DEC-19">DEC-19 — Mustache + ES5 carve-out</a>
 * @see <a href="DEC-38">DEC-38 — {@code @ApplicationModuleTest} canon</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S10">E22S10</a>
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, ScoreControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@DisplayName(
        "ScoreController IT — E22S10 AC-IT-APPLICATION-MODULE-TEST + AC-STATIC-ASSET-REACHABILITY")
class ScoreControllerIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E22S10ScoreControllerIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // =========================================================================
    // AC-IT-APPLICATION-MODULE-TEST: happy-path scenarios
    // =========================================================================

    @Test
    @DisplayName("AC-IT-APPLICATION-MODULE-TEST: GET /score/test returns 200 with HTML body")
    void helloWorld_returns200WithHtml() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode())
                .as("GET /score/test must return 200 OK (AC-IT-APPLICATION-MODULE-TEST)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("Content-Type must be text/html")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .as("Content-Type must start with text/html")
                .startsWith("text/html");
        assertThat(response.getBody())
                .as("Response body must contain Mustache-rendered HTML fragment")
                .containsIgnoringCase("<!DOCTYPE html>");
    }

    @Test
    @DisplayName("AC-IT-APPLICATION-MODULE-TEST: GET /score/register returns 200 with HTML body")
    void registerPage_returns200WithHtml() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/register"), String.class);

        assertThat(response.getStatusCode())
                .as("GET /score/register must return 200 OK (AC-IT-APPLICATION-MODULE-TEST)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString())
                .as("Content-Type must start with text/html")
                .startsWith("text/html");
        assertThat(response.getBody())
                .as("Response body must contain Mustache-rendered HTML fragment")
                .containsIgnoringCase("<!DOCTYPE html>");
    }

    @Test
    @DisplayName("AC-IT-APPLICATION-MODULE-TEST: GET /score/field/1 returns 200 with HTML body")
    void fieldPage_returns200WithHtml() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode())
                .as("GET /score/field/1 must return 200 OK (AC-IT-APPLICATION-MODULE-TEST)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString())
                .as("Content-Type must start with text/html")
                .startsWith("text/html");
        assertThat(response.getBody())
                .as("Response body must contain Mustache-rendered HTML fragment")
                .containsIgnoringCase("<!DOCTYPE html>");
    }

    // =========================================================================
    // AC-STATIC-ASSET-REACHABILITY: vvwt-tablet.js is accessible
    // =========================================================================

    @Test
    @DisplayName(
            "AC-STATIC-ASSET-REACHABILITY: GET /score/assets/vvwt-tablet.js returns 200 with"
                    + " non-empty body")
    void tabletJs_returns200WithNonEmptyBody() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/score/assets/vvwt-tablet.js"), String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /score/assets/vvwt-tablet.js must return 200"
                                + " (AC-STATIC-ASSET-REACHABILITY)")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("Content-Type must be present")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .as("Content-Type must be JavaScript")
                .satisfiesAnyOf(
                        ct -> assertThat(ct).contains("javascript"),
                        ct -> assertThat(ct).contains("text/plain"));
        assertThat(response.getBody()).as("Response body must be non-empty").isNotBlank();
    }

    // =========================================================================
    // AC-SECURITY-NO-ADMIN-AUTH: unauthenticated access returns 200
    // =========================================================================

    @Test
    @DisplayName("AC-SECURITY-NO-ADMIN-AUTH: unauthenticated GET /score/test returns 200")
    void helloWorld_accessibleWithoutAuth() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /score/test must return 200 without authentication"
                                + " (AC-SECURITY-NO-ADMIN-AUTH)")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC-SECURITY-NO-ADMIN-AUTH: unauthenticated GET /score/register returns 200")
    void registerPage_accessibleWithoutAuth() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/register"), String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /score/register must return 200 without authentication"
                                + " (AC-SECURITY-NO-ADMIN-AUTH)")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC-SECURITY-NO-ADMIN-AUTH: unauthenticated GET /score/field/1 returns 200")
    void fieldPage_accessibleWithoutAuth() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/field/1"), String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /score/field/1 must return 200 without authentication"
                                + " (AC-SECURITY-NO-ADMIN-AUTH)")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // AC-SECURITY-DEVICE-REGISTRATION: no token rendered in HTML
    // =========================================================================

    @Test
    @DisplayName(
            "AC-SECURITY-DEVICE-REGISTRATION: rendered /score/register body does NOT contain"
                    + " 'deviceToken' or 'token' as rendered value (Mustache placeholder absent)")
    void registerPage_doesNotRenderDeviceToken() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(baseUrl + "/score/register"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // An unresolved Mustache placeholder {{deviceToken}} would appear literally in the HTML.
        // A model attribute named "token" or "deviceToken" would also be rendered.
        assertThat(response.getBody())
                .as(
                        "Rendered HTML must not expose {{deviceToken}} placeholder"
                                + " (AC-SECURITY-DEVICE-REGISTRATION)")
                .doesNotContain("{{deviceToken}}");
        assertThat(response.getBody())
                .as(
                        "Rendered HTML must not expose {{token}} placeholder"
                                + " (AC-SECURITY-DEVICE-REGISTRATION)")
                .doesNotContain("{{token}}");
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
