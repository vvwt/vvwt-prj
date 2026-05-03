package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Integration tests for E44S02 — TM branding rollout.
 *
 * <h2>Scope (AC1–AC4 + AC8)</h2>
 *
 * <p>Asserts that server-rendered HTML for the 3 Vite SPAs (admin/timer/display) and the 3
 * score-tablet Mustache pages contain the VVW blue favicon links, correct {@code ToM · {App}}
 * titles, and apple-touch-icon declarations per the Story acceptance criteria.
 *
 * <ul>
 *   <li><b>AC1</b> — {@code GET /admin/} response body contains SVG-first favicon link + PNG
 *       fallback.
 *   <li><b>AC2</b> — {@code GET /timer/tournaments/00000000-0000-0000-0000-000000000001} and {@code
 *       GET /display/overview} response bodies contain SVG-first favicon link + PNG fallback.
 *   <li><b>AC3</b> — {@code GET /score/test}, {@code GET /score/register}, {@code GET
 *       /score/field/1} each contain SVG favicon link.
 *   <li><b>AC4</b> — server-rendered {@code <title>} for admin = {@code "ToM · Tournament
 *       Manager"}, timer = {@code "ToM · Timer"}, display = {@code "ToM · Overview"}.
 *   <li><b>AC8</b> — {@code GET /admin/non-existent-asset.svg} returns 404; no {@code
 *       vvw-icon-blue.svg} reference in served {@code index.html} {@code <script>} blocks.
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT, classes =
 *       TournamentManagerApplication.class)} (web-module IT canon)
 *   <li>DEC-44 D2 empirical refinement — {@code @Import({WebModuleTestConfig.class,
 *       TestAdminCredentials.class})}; single {@code @Primary AdminCredentialsProvider}
 *   <li>DEC-40 Clause A — test class in {@code de.vvwt.tm.web} package
 *   <li>DEC-22 Iron Law Q-1a — RED-first: authored and committed failing before production changes
 *   <li>DEC-19 — score-tablet tests assert HTML-only changes (no ES5 JS assertions)
 * </ul>
 *
 * @see AdminSpaController
 * @see ScoreController
 * @see WebModuleTestConfig
 * @since E44S02
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e44s02brandfaviconitdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.main.allow-bean-definition-overriding=true"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, BrandFaviconIT.TestAdminCredentials.class})
@DisplayName("BrandFaviconIT — E44S02 (favicon + lockup + ToM titles branding rollout)")
class BrandFaviconIT {

    static final String TEST_PASSWORD = "BrandFaviconIT44S02";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC1 — Admin SPA: favicon links in server-rendered HTML
    // =========================================================================

    @Nested
    @DisplayName("AC1 — Admin SPA /admin/ favicon links (RED-first per DEC-22)")
    class AdminFavicon {

        @Test
        @DisplayName("AC1: GET /admin/ body contains SVG-first favicon link")
        void adminRoot_containsSvgFaviconLink() {
            ResponseEntity<String> response =
                    authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

            assertThat(response.getStatusCode())
                    .as("GET /admin/ must return 200 OK (AC1 — E44S02)")
                    .isEqualTo(HttpStatus.OK);

            assertThat(response.getBody())
                    .as(
                            "GET /admin/ body must contain SVG favicon link"
                                    + " <link rel=\"icon\" type=\"image/svg+xml\""
                                    + " href=\"vvw-icon-blue.svg\"> (AC1 — E44S02)")
                    .contains(
                            "<link rel=\"icon\" type=\"image/svg+xml\" href=\"vvw-icon-blue.svg\">");
        }

        @Test
        @DisplayName("AC1: GET /admin/ body contains at least one PNG favicon fallback")
        void adminRoot_containsPngFaviconFallback() {
            ResponseEntity<String> response =
                    authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

            assertThat(response.getBody())
                    .as(
                            "GET /admin/ body must contain PNG favicon fallback"
                                    + " <link rel=\"icon\" type=\"image/png\" sizes=\"32x32\""
                                    + " href=\"vvw-favicon-blue-32.png\"> (AC1 — E44S02)")
                    .contains("type=\"image/png\"")
                    .contains("vvw-favicon-blue-");
        }

        @Test
        @DisplayName("AC1: GET /admin/ body contains SVG link declared before PNG links (AC7 order)")
        void adminRoot_svgFaviconBeforePng() {
            ResponseEntity<String> response =
                    authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

            String body = response.getBody();
            assertThat(body).as("body must not be null").isNotNull();

            int svgPos = body.indexOf("type=\"image/svg+xml\"");
            int pngPos = body.indexOf("type=\"image/png\"");

            assertThat(svgPos)
                    .as("SVG favicon link must appear before PNG links in document order (AC7)")
                    .isGreaterThanOrEqualTo(0);
            assertThat(pngPos)
                    .as("PNG favicon link must be present (AC7)")
                    .isGreaterThanOrEqualTo(0);
            assertThat(svgPos)
                    .as("SVG link must be declared before first PNG link (AC7 — E44S02)")
                    .isLessThan(pngPos);
        }

        @Test
        @DisplayName("AC1: GET /admin/ body contains apple-touch-icon WITHOUT sizes attribute (AC6)")
        void adminRoot_containsAppleTouchIconWithoutSizes() {
            ResponseEntity<String> response =
                    authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

            String body = response.getBody();
            assertThat(body).as("body must not be null").isNotNull();

            assertThat(body)
                    .as(
                            "GET /admin/ body must contain apple-touch-icon link (AC6 — E44S02)."
                                    + " Expected: <link rel=\"apple-touch-icon\""
                                    + " href=\"vvw-favicon-blue-256.png\">")
                    .contains("rel=\"apple-touch-icon\"")
                    .contains("vvw-favicon-blue-256.png");

            // AC6: apple-touch-icon must NOT have a sizes attribute (misleading metadata)
            int appleLinkStart = body.indexOf("rel=\"apple-touch-icon\"");
            int appleLinkEnd = body.indexOf(">", appleLinkStart);
            String appleLink = body.substring(appleLinkStart, appleLinkEnd + 1);
            assertThat(appleLink)
                    .as(
                            "apple-touch-icon must NOT have a sizes= attribute"
                                    + " — 256px PNG with iOS auto-scaling is the canonical choice (AC6 — E44S02)")
                    .doesNotContain("sizes=");
        }

        @Test
        @DisplayName("AC1: GET /admin/ body contains 16x16 PNG favicon variant (AC13)")
        void adminRoot_contains16x16PngFavicon() {
            ResponseEntity<String> response =
                    authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

            assertThat(response.getBody())
                    .as(
                            "GET /admin/ body must contain 16x16 PNG favicon"
                                    + " <link rel=\"icon\" type=\"image/png\" sizes=\"16x16\""
                                    + " href=\"vvw-favicon-blue-16.png\"> (AC13 — E44S02)")
                    .contains("sizes=\"16x16\"")
                    .contains("vvw-favicon-blue-16.png");
        }
    }

    // =========================================================================
    // AC2 — Timer and Display SPAs: favicon links
    // =========================================================================

    @Nested
    @DisplayName("AC2 — Timer SPA /timer/ favicon links (RED-first per DEC-22)")
    class TimerFavicon {

        @Test
        @DisplayName("AC2: GET /timer/tournaments/00000000-0000-0000-0000-000000000001 contains SVG favicon")
        void timerPage_containsSvgFaviconLink() {
            ResponseEntity<String> response =
                    authed.getForEntity(
                            "http://localhost:"
                                    + port
                                    + "/timer/tournaments/00000000-0000-0000-0000-000000000001",
                            String.class);

            assertThat(response.getStatusCode())
                    .as("GET /timer/... must return 200 OK (AC2 — E44S02)")
                    .isEqualTo(HttpStatus.OK);

            assertThat(response.getBody())
                    .as(
                            "GET /timer/... body must contain SVG favicon link (AC2 — E44S02)"
                                    + " Expected: <link rel=\"icon\" type=\"image/svg+xml\""
                                    + " href=\"vvw-icon-blue.svg\">")
                    .contains(
                            "<link rel=\"icon\" type=\"image/svg+xml\" href=\"vvw-icon-blue.svg\">");
        }

        @Test
        @DisplayName("AC2: GET /timer/... body contains apple-touch-icon (AC6)")
        void timerPage_containsAppleTouchIcon() {
            ResponseEntity<String> response =
                    authed.getForEntity(
                            "http://localhost:"
                                    + port
                                    + "/timer/tournaments/00000000-0000-0000-0000-000000000001",
                            String.class);

            assertThat(response.getBody())
                    .as("GET /timer/... body must contain apple-touch-icon (AC6 — E44S02)")
                    .contains("rel=\"apple-touch-icon\"")
                    .contains("vvw-favicon-blue-256.png");
        }
    }

    @Nested
    @DisplayName("AC2 — Display SPA /display/ favicon links (RED-first per DEC-22)")
    class DisplayFavicon {

        @Test
        @DisplayName("AC2: GET /display/overview contains SVG favicon link")
        void displayOverview_containsSvgFaviconLink() {
            // /display/overview is publicly accessible (display does not require admin auth)
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            "http://localhost:" + port + "/display/overview", String.class);

            assertThat(response.getStatusCode())
                    .as("GET /display/overview must return 200 OK (AC2 — E44S02)")
                    .isEqualTo(HttpStatus.OK);

            assertThat(response.getBody())
                    .as(
                            "GET /display/overview body must contain SVG favicon link (AC2 — E44S02)"
                                    + " Expected: <link rel=\"icon\" type=\"image/svg+xml\""
                                    + " href=\"vvw-icon-blue.svg\">")
                    .contains(
                            "<link rel=\"icon\" type=\"image/svg+xml\" href=\"vvw-icon-blue.svg\">");
        }

        @Test
        @DisplayName("AC2: GET /display/overview contains apple-touch-icon (AC6)")
        void displayOverview_containsAppleTouchIcon() {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            "http://localhost:" + port + "/display/overview", String.class);

            assertThat(response.getBody())
                    .as("GET /display/overview body must contain apple-touch-icon (AC6 — E44S02)")
                    .contains("rel=\"apple-touch-icon\"")
                    .contains("vvw-favicon-blue-256.png");
        }
    }

    // =========================================================================
    // AC3 — Score-tablet Mustache pages: favicon links
    // =========================================================================

    @Nested
    @DisplayName("AC3 — Score-tablet Mustache pages: favicon links (RED-first per DEC-22)")
    class ScoreFavicon {

        @Test
        @DisplayName("AC3: GET /score/test (hello.mustache) contains SVG favicon link")
        void scoreHello_containsSvgFaviconLink() {
            // Score pages are publicly accessible (no auth required per DEC-19 / AC-SECURITY-NO-ADMIN-AUTH)
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            "http://localhost:" + port + "/score/test", String.class);

            assertThat(response.getStatusCode())
                    .as("GET /score/test must return 200 OK (AC3 — E44S02)")
                    .isEqualTo(HttpStatus.OK);

            assertThat(response.getBody())
                    .as(
                            "GET /score/test body must contain SVG favicon link"
                                    + " (AC3 hello.mustache — E44S02)")
                    .contains(
                            "<link rel=\"icon\" type=\"image/svg+xml\""
                                    + " href=\"/score/vvw-icon-blue.svg\">");
        }

        @Test
        @DisplayName("AC3: GET /score/register (register.mustache) contains SVG favicon link")
        void scoreRegister_containsSvgFaviconLink() {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            "http://localhost:" + port + "/score/register", String.class);

            assertThat(response.getStatusCode())
                    .as("GET /score/register must return 200 OK (AC3 — E44S02)")
                    .isEqualTo(HttpStatus.OK);

            assertThat(response.getBody())
                    .as(
                            "GET /score/register body must contain SVG favicon link"
                                    + " (AC3 register.mustache — E44S02)")
                    .contains(
                            "<link rel=\"icon\" type=\"image/svg+xml\""
                                    + " href=\"/score/vvw-icon-blue.svg\">");
        }

        @Test
        @DisplayName("AC3: GET /score/field/1 (field.mustache) contains SVG favicon link")
        void scoreField_containsSvgFaviconLink() {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            "http://localhost:" + port + "/score/field/1", String.class);

            assertThat(response.getStatusCode())
                    .as("GET /score/field/1 must return 200 OK (AC3 — E44S02)")
                    .isEqualTo(HttpStatus.OK);

            assertThat(response.getBody())
                    .as(
                            "GET /score/field/1 body must contain SVG favicon link"
                                    + " (AC3 field.mustache — E44S02)")
                    .contains(
                            "<link rel=\"icon\" type=\"image/svg+xml\""
                                    + " href=\"/score/vvw-icon-blue.svg\">");
        }
    }

    // =========================================================================
    // AC4 — Vite SPAs: ToM · {App} titles in server-rendered <title>
    // =========================================================================

    @Nested
    @DisplayName("AC4 — ToM · {App} titles in server-rendered HTML (RED-first per DEC-22)")
    class TomTitles {

        @Test
        @DisplayName("AC4: GET /admin/ body contains <title>ToM · Tournament Manager</title>")
        void adminRoot_containsToMTitle() {
            ResponseEntity<String> response =
                    authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

            assertThat(response.getBody())
                    .as(
                            "GET /admin/ body must contain <title>ToM · Tournament Manager</title>"
                                    + " (AC4 — E44S02). Current title is 'Tournament Manager'.")
                    .contains("<title>ToM · Tournament Manager</title>");
        }

        @Test
        @DisplayName("AC4: GET /timer/... body contains <title>ToM · Timer</title>")
        void timerPage_containsToMTitle() {
            ResponseEntity<String> response =
                    authed.getForEntity(
                            "http://localhost:"
                                    + port
                                    + "/timer/tournaments/00000000-0000-0000-0000-000000000001",
                            String.class);

            assertThat(response.getBody())
                    .as(
                            "GET /timer/... body must contain <title>ToM · Timer</title>"
                                    + " (AC4 — E44S02). Current title is 'Timer'.")
                    .contains("<title>ToM · Timer</title>");
        }

        @Test
        @DisplayName("AC4: GET /display/overview body contains <title>ToM · Overview</title>")
        void displayOverview_containsToMTitle() {
            ResponseEntity<String> response =
                    restTemplate.getForEntity(
                            "http://localhost:" + port + "/display/overview", String.class);

            assertThat(response.getBody())
                    .as(
                            "GET /display/overview body must contain <title>ToM · Overview</title>"
                                    + " (AC4 — E44S02). Current title is 'Gesamtübersicht'.")
                    .contains("<title>ToM · Overview</title>");
        }
    }

    // =========================================================================
    // AC8 — Graceful degradation: missing asset returns 404; no script deps on SVG
    // =========================================================================

    @Nested
    @DisplayName("AC8 — Graceful degradation (missing asset 404)")
    class GracefulDegradation {

        @Test
        @DisplayName("AC8: GET /admin/non-existent-asset.svg returns 404 (Spring static handler)")
        void missingAsset_returns404() {
            ResponseEntity<String> response =
                    authed.getForEntity(
                            "http://localhost:" + port + "/admin/non-existent-asset.svg",
                            String.class);

            assertThat(response.getStatusCode())
                    .as(
                            "GET /admin/non-existent-asset.svg must return 404"
                                    + " (Spring static-resource handler default, AC8 — E44S02)")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName(
                "AC8: GET /admin/ body contains zero <script> references to vvw-icon-blue.svg")
        void adminRoot_noScriptReferenceToFaviconSvg() {
            ResponseEntity<String> response =
                    authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

            String body = response.getBody();
            assertThat(body).as("body must not be null").isNotNull();

            // Find all <script> blocks and assert none reference vvw-icon-blue.svg
            // (AC8: favicon SVG is a declarative link, not a script dependency)
            int scriptStart = body.indexOf("<script");
            while (scriptStart >= 0) {
                int scriptEnd = body.indexOf("</script>", scriptStart);
                if (scriptEnd >= 0) {
                    String scriptBlock = body.substring(scriptStart, scriptEnd + 9);
                    assertThat(scriptBlock)
                            .as(
                                    "No <script> block in /admin/ response body may reference"
                                            + " vvw-icon-blue.svg (AC8 — E44S02)")
                            .doesNotContain("vvw-icon-blue.svg");
                    scriptStart = body.indexOf("<script", scriptEnd + 9);
                } else {
                    break;
                }
            }
        }
    }

    // =========================================================================
    // Test configuration — fixed admin credentials (DEC-44 D2 empirical pattern)
    // =========================================================================

    /**
     * Per-IT {@link AdminCredentialsProvider} providing a fixed test password hash.
     *
     * <p>Per DEC-44 §"2026-04-27 Empirical Refinement": this inner class provides a {@code @Primary
     * AdminCredentialsProvider} which overrides the non-primary placeholder in {@link
     * WebModuleTestConfig} via {@code spring.main.allow-bean-definition-overriding=true}. The
     * production {@code SecurityFilterChain} + {@code UserDetailsService} remain as the sole
     * instances; the {@code @Primary AdminCredentialsProvider} feeds {@code
     * AuthConfiguration.userDetailsService()} with the per-IT hashed test password.
     */
    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
