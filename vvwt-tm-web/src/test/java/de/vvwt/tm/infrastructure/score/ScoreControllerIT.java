package de.vvwt.tm.infrastructure.score;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ScoreController} — E06S02: Mustache + ES5 scoring route skeleton.
 *
 * <p>Tests the full HTTP stack to verify:
 * <ul>
 *   <li>AC3: /score/test accessible and returns Mustache-rendered HTML</li>
 *   <li>AC4: /score/assets/vvwt-tablet.js served correctly (ES5 utility script)</li>
 *   <li>AC7: No Svelte SPA bundle loaded on scoring page (no Svelte contamination)</li>
 *   <li>AC9: /score/** accessible without authentication</li>
 *   <li>AC10: Rendered page contains i18n strings from MessageSource</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                ScoreControllerIT.TestAdminCredentials.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e06s02scoredb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@DisplayName("ScoreController IT — E06S02: Mustache + ES5 route skeleton")
class ScoreControllerIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
    }

    // -----------------------------------------------------------------------
    // AC3: Route separation — /score/test accessible and returns Mustache HTML
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: GET /score/test returns 200 with Mustache-rendered HTML")
    void scoreTestPage_returns200() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode())
                .as("GET /score/test must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Response must be HTML (contains DOCTYPE)")
                .containsIgnoringCase("<!DOCTYPE html>");
    }

    @Test
    @DisplayName("AC3: /score/test is served from /score/** route, not /admin/** or /api/**")
    void scoreTestPage_isOnScoreRoute() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Verify it's the scoring tablet page, not a redirect to /admin/
        assertThat(response.getBody())
                .as("Must not redirect to admin SPA")
                .doesNotContain("/admin/");
    }

    // -----------------------------------------------------------------------
    // AC4: ES5 utility script served at /score/assets/vvwt-tablet.js
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC4: GET /score/assets/vvwt-tablet.js returns 200")
    void tabletJs_returns200() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/assets/vvwt-tablet.js"), String.class);

        assertThat(response.getStatusCode())
                .as("ES5 utility script must be accessible")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Script must contain VvwtWebSocket (AC4a — WebSocket manager)")
                .contains("VvwtWebSocket");
        assertThat(response.getBody())
                .as("Script must contain VvwtPoller (AC4b — polling fallback)")
                .contains("VvwtPoller");
        assertThat(response.getBody())
                .as("Script must contain vvwtSerializeForm (AC4d — form serialization)")
                .contains("vvwtSerializeForm");
        assertThat(response.getBody())
                .as("Script must contain vvwtQuery (AC4c — DOM helper)")
                .contains("vvwtQuery");
    }

    // -----------------------------------------------------------------------
    // AC5: ES5 strict compliance — no ES2015+ syntax in vvwt-tablet.js
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC5: vvwt-tablet.js contains no 'const' (ES6+ keyword) outside comments")
    void tabletJs_containsNoConst() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/assets/vvwt-tablet.js"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Strip comment lines (both // and block comment lines starting with optional whitespace + *)
        // before checking for ES6+ keywords. This avoids false positives from comment text.
        String codeOnly = stripCommentLines(response.getBody());
        assertThat(codeOnly)
                .as("vvwt-tablet.js must not use 'const' (ES6+) in non-comment code — DEC-19 ES5 constraint")
                .doesNotContainPattern("\\bconst\\b");
    }

    @Test
    @DisplayName("AC5: vvwt-tablet.js contains no 'let' (ES6+ keyword) outside comments")
    void tabletJs_containsNoLet() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/assets/vvwt-tablet.js"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String codeOnly = stripCommentLines(response.getBody());
        assertThat(codeOnly)
                .as("vvwt-tablet.js must not use 'let' (ES6+) in non-comment code — DEC-19 ES5 constraint")
                .doesNotContainPattern("\\blet\\b");
    }

    @Test
    @DisplayName("AC5: vvwt-tablet.js contains no arrow functions (ES6+ syntax) outside comments")
    void tabletJs_containsNoArrowFunctions() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/assets/vvwt-tablet.js"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String codeOnly = stripCommentLines(response.getBody());
        // Arrow function: => not preceded by >= or <=
        assertThat(codeOnly)
                .as("vvwt-tablet.js must not use arrow functions (ES6+) — DEC-19 ES5 constraint")
                .doesNotContainPattern("(?<![=<>!])=>(?!=)");
    }

    @Test
    @DisplayName("AC5: vvwt-tablet.js contains no template literals (ES6+ syntax) outside comments")
    void tabletJs_containsNoTemplateLiterals() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/assets/vvwt-tablet.js"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String codeOnly = stripCommentLines(response.getBody());
        assertThat(codeOnly)
                .as("vvwt-tablet.js must not use template literals (ES6+) — DEC-19 ES5 constraint")
                .doesNotContain("`");
    }

    /**
     * Strip single-line ({@code //}) and block comment lines ({@code * ...}) from JS source.
     * Used by ES5 compliance checks to avoid false positives on comment text.
     */
    private static String stripCommentLines(String jsSource) {
        if (jsSource == null) { return ""; }
        StringBuilder sb = new StringBuilder();
        for (String line : jsSource.split("\n")) {
            String trimmed = line.trim();
            // Skip: // comments, block comment openers (/*), block comment lines (* ...), closers (*/)
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*") || trimmed.equals("*/")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // AC7: No Svelte contamination — scoring page does NOT load SPA bundle
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC7: /score/test page does NOT load Svelte SPA bundle")
    void scoreTestPage_doesNotLoadSvelteBundle() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body)
                .as("Scoring tablet page must not reference the Svelte SPA at /admin/")
                .doesNotContain("/admin/assets/");
        assertThat(body)
                .as("Scoring tablet page must not reference /admin/ paths")
                .doesNotContain("src=\"/admin/");
    }

    @Test
    @DisplayName("AC7: /score/test page DOES load the ES5 utility script")
    void scoreTestPage_loadsTabletJs() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Scoring tablet page must include /score/assets/vvwt-tablet.js (AC4)")
                .contains("/score/assets/vvwt-tablet.js");
    }

    // -----------------------------------------------------------------------
    // AC8: Error banner element present in rendered HTML
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC8: /score/test page contains error-banner element for window.onerror")
    void scoreTestPage_containsErrorBannerElement() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Page must contain id='error-banner' element (AC8 — global error handler)")
                .contains("id=\"error-banner\"");
    }

    // -----------------------------------------------------------------------
    // AC9: Security — /score/** accessible without authentication
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC9: GET /score/test returns 200 without Authorization header")
    void scoreTestPage_accessibleWithoutAuth() throws Exception {
        // TestRestTemplate without credentials
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode())
                .as("GET /score/test must return 200 without auth (AC9 — scorekeepers do not log in)")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC9: GET /score/assets/vvwt-tablet.js accessible without auth")
    void tabletJs_accessibleWithoutAuth() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/assets/vvwt-tablet.js"), String.class);

        assertThat(response.getStatusCode())
                .as("ES5 utility script must be accessible without auth (AC9)")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC9: GET /admin/ still requires authentication (no regression)")
    void adminRoute_stillRequiresAuth() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/admin/"), String.class);

        // Without credentials: Spring Security returns 401
        assertThat(response.getStatusCode())
                .as("Admin route must still require authentication (AC9 regression guard)")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -----------------------------------------------------------------------
    // AC10: Rendered page contains i18n strings
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC10: /score/test page contains i18n heading from MessageSource")
    void scoreTestPage_containsI18nHeading() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/score/test"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Rendered page must contain the heading from messages.properties (AC10)")
                .containsAnyOf("Scoring Tablet", "VVWT Tournament Manager");
    }

    // -----------------------------------------------------------------------
    // Test configuration
    // -----------------------------------------------------------------------

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode("ScoreTestPass02");
            return () -> hash;
        }
    }
}
