package de.vvwt.tm.infrastructure.display;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DisplayViewController} (E07S05).
 *
 * <h2>Scope</h2>
 * <p>Verifies that the display SPA HTML shell is served correctly:
 * <ol>
 *   <li>HTTP 200 at {@code GET /display/overview} with content-type text/html (AC8)</li>
 *   <li>No admin authentication required (AC8, AC11)</li>
 *   <li>Response body contains the Svelte mount point {@code <div id="app">} (AC8)</li>
 *   <li>No external CDN links in the response — DEC-16 / AC7 offline compatibility</li>
 * </ol>
 *
 * <h2>DEC-16 / AC7 offline check</h2>
 * <p>Test 4 verifies that the served HTML does not contain {@code https://} inside
 * {@code <script>} or {@code <link>} tags. This guards against accidental CDN references
 * being introduced in the Vite-built {@code index.html}.
 *
 * <p>The display SPA assets are built by {@code npm run build} in the
 * {@code generate-resources} Maven phase and copied to
 * {@code target/classes/static/display/} by {@code maven-resources-plugin}. These
 * assets are available on the test classpath when this IT runs in the {@code test} phase.
 *
 * @see DisplayViewController
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S05.story.md">Story E07S05</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                DisplayViewControllerIT.TestAdminCredentials.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e07s05viewdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
class DisplayViewControllerIT {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * Provides a fixed, known admin password hash for the test context.
     *
     * <p>The production {@link de.vvwt.tm.auth.AdminCredentialsBootstrap} generates a random
     * password on startup and stores it in the H2 database. In the test context, we override
     * the provider with a hard-coded BCrypt hash so the test can authenticate if needed.
     * (For display route tests, auth is not required, but Spring needs a valid
     * {@link AdminCredentialsProvider} bean to start the context.)
     */
    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider() {
            // BCrypt hash of "testpassword" (cost 10)
            return () -> "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
        }
    }

    // ---------------------------------------------------------------------------
    // AC8 — GET /display/overview returns 200 without authentication
    // ---------------------------------------------------------------------------

    /**
     * AC8: {@code GET /display/overview} must return HTTP 200 without any authentication.
     *
     * <p>The display route is served as a static SPA shell — no admin credentials required.
     * Spring Security permits the entire {@code /display/**} path space.
     */
    @Test
    void overviewPageReturns200WithoutAuthentication() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/overview must return 200 without auth (AC8)")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * AC8: {@code GET /display/overview} must return an HTML response.
     */
    @Test
    void overviewPageReturnsHtmlContentType() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getHeaders().getContentType())
                .as("GET /display/overview content-type must be text/html (AC8)")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    }

    /**
     * AC8: The HTML shell must contain the Svelte mount point {@code <div id="app">}.
     *
     * <p>This verifies that the Vite-built {@code index.html} is served correctly and
     * includes the DOM element the Svelte app mounts into.
     */
    @Test
    void overviewPageContainsSvelteAppMountPoint() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getBody())
                .as("Response body must contain Svelte app mount point <div id=\"app\"> (AC8)")
                .contains("<div id=\"app\">");
    }

    /**
     * AC7, DEC-16: The HTML shell must NOT contain external {@code https://} URLs in
     * {@code <script>} or {@code <link>} tags.
     *
     * <p>All Vite-compiled assets are served from {@code /display/assets/} (local, bundled
     * in the JAR). If any external CDN reference appears, this test fails — preventing
     * a regression where internet connectivity would be required to load the overview.
     */
    @Test
    void overviewPageContainsNoExternalCdnScriptOrLinkTags() {
        ResponseEntity<String> response = restTemplate
                .getForEntity("http://localhost:" + port + "/display/overview", String.class);

        String body = response.getBody();
        assertThat(body).isNotNull();

        // Check <script src="https://..."> — any external script is a DEC-16 violation
        assertThat(body)
                .as("HTML shell must not contain external <script src=\"https://...\"> (AC7 / DEC-16)")
                .doesNotContainPattern("<script[^>]+src=[\"']https://");

        // Check <link href="https://..."> — any external stylesheet is a DEC-16 violation
        assertThat(body)
                .as("HTML shell must not contain external <link href=\"https://...\"> (AC7 / DEC-16)")
                .doesNotContainPattern("<link[^>]+href=[\"']https://");
    }

    /**
     * AC11: {@code GET /display/overview} must NOT require admin authentication.
     *
     * <p>Verifies that a request with no credentials returns 200. The display route is
     * served without any admin authentication requirement. If Spring Security's permit-all
     * rule is missing, this endpoint would return 401.
     *
     * <p>Note: sending wrong HTTP Basic credentials to any Spring Security HTTP-Basic-enabled
     * server causes a 401 even for permit-all routes (Spring processes the malformed auth header
     * before reaching the route-level permitAll check). This test deliberately sends NO
     * credentials to verify the route is accessible without auth — which is the correct
     * AC11 scenario (display devices do not have admin credentials).
     */
    @Test
    void overviewPageIsAccessibleWithNoCredentials() {
        // No credentials provided — verifies the display route is permit-all (AC11)
        // Display devices never have admin credentials; the route must be open.
        ResponseEntity<String> response = restTemplate
                .getForEntity("http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/overview must be accessible with no credentials (AC11)")
                .isEqualTo(HttpStatus.OK);
    }
}
