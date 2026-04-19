package de.vvwt.tm.infrastructure.display;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link DisplayViewController} (E07S05, E07S07).
 *
 * <h2>Scope</h2>
 *
 * <p>Verifies that the display SPA HTML shell is served correctly for both routes:
 *
 * <ol>
 *   <li>HTTP 200 at {@code GET /display/overview} with content-type text/html (E07S05 AC8)
 *   <li>HTTP 200 at {@code GET /display/register} with content-type text/html (E07S07 AC1, AC8)
 *   <li>No admin authentication required for either route (AC8, AC11)
 *   <li>Response bodies contain the Svelte mount point {@code <div id="app">} (AC8)
 *   <li>No external CDN links in the responses — DEC-16 / AC8 offline compatibility
 * </ol>
 *
 * <h2>DEC-16 / AC7 offline check</h2>
 *
 * <p>Test 4 verifies that the served HTML does not contain {@code https://} inside {@code <script>}
 * or {@code <link>} tags. This guards against accidental CDN references being introduced in the
 * Vite-built {@code index.html}.
 *
 * <p>The display SPA assets are built by {@code npm run build} in the {@code generate-resources}
 * Maven phase and copied to {@code target/classes/static/display/} by {@code
 * maven-resources-plugin}. These assets are available on the test classpath when this IT runs in
 * the {@code test} phase.
 *
 * @see DisplayViewController
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S05.story.md">Story
 *     E07S05</a>
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
@Import(TenantContextTestSupport.class)
class DisplayViewControllerIT {

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    /**
     * Provides a fixed, known admin password hash for the test context.
     *
     * <p>The production {@link de.vvwt.tm.auth.AdminCredentialsBootstrap} generates a random
     * password on startup and stores it in the H2 database. In the test context, we override the
     * provider with a hard-coded BCrypt hash so the test can authenticate if needed. (For display
     * route tests, auth is not required, but Spring needs a valid {@link AdminCredentialsProvider}
     * bean to start the context.)
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
     * <p>The display route is served as a static SPA shell — no admin credentials required. Spring
     * Security permits the entire {@code /display/**} path space.
     */
    @Test
    void overviewPageReturns200WithoutAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/overview must return 200 without auth (AC8)")
                .isEqualTo(HttpStatus.OK);
    }

    /** AC8: {@code GET /display/overview} must return an HTML response. */
    @Test
    void overviewPageReturnsHtmlContentType() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getHeaders().getContentType())
                .as("GET /display/overview content-type must be text/html (AC8)")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    }

    /**
     * AC8: The HTML shell must contain the Svelte mount point {@code <div id="app">}.
     *
     * <p>This verifies that the Vite-built {@code index.html} is served correctly and includes the
     * DOM element the Svelte app mounts into.
     */
    @Test
    void overviewPageContainsSvelteAppMountPoint() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getBody())
                .as("Response body must contain Svelte app mount point <div id=\"app\"> (AC8)")
                .contains("<div id=\"app\">");
    }

    /**
     * AC7, DEC-16: The HTML shell must NOT contain external {@code https://} URLs in {@code
     * <script>} or {@code <link>} tags.
     *
     * <p>All Vite-compiled assets are served from {@code /display/assets/} (local, bundled in the
     * JAR). If any external CDN reference appears, this test fails — preventing a regression where
     * internet connectivity would be required to load the overview.
     */
    @Test
    void overviewPageContainsNoExternalCdnScriptOrLinkTags() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        String body = response.getBody();
        assertThat(body).isNotNull();

        // Check <script src="https://..."> — any external script is a DEC-16 violation
        assertThat(body)
                .as(
                        "HTML shell must not contain external <script src=\"https://...\"> (AC7 /"
                                + " DEC-16)")
                .doesNotContainPattern("<script[^>]+src=[\"']https://");

        // Check <link href="https://..."> — any external stylesheet is a DEC-16 violation
        assertThat(body)
                .as(
                        "HTML shell must not contain external <link href=\"https://...\"> (AC7 /"
                                + " DEC-16)")
                .doesNotContainPattern("<link[^>]+href=[\"']https://");
    }

    /**
     * AC11: {@code GET /display/overview} must NOT require admin authentication.
     *
     * <p>Verifies that a request with no credentials returns 200. The display route is served
     * without any admin authentication requirement. If Spring Security's permit-all rule is
     * missing, this endpoint would return 401.
     *
     * <p>Note: sending wrong HTTP Basic credentials to any Spring Security HTTP-Basic-enabled
     * server causes a 401 even for permit-all routes (Spring processes the malformed auth header
     * before reaching the route-level permitAll check). This test deliberately sends NO credentials
     * to verify the route is accessible without auth — which is the correct AC11 scenario (display
     * devices do not have admin credentials).
     */
    @Test
    void overviewPageIsAccessibleWithNoCredentials() {
        // No credentials provided — verifies the display route is permit-all (AC11)
        // Display devices never have admin credentials; the route must be open.
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/overview must be accessible with no credentials (AC11)")
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------------
    // E07S07 — GET /display/register tests (AC1, AC8, AC11, DEC-16)
    // ---------------------------------------------------------------------------

    /**
     * E07S07 AC1, AC8: {@code GET /display/register} must return HTTP 200 without authentication.
     *
     * <p>The registration route is served as the same Svelte SPA shell as /display/overview.
     * Display devices open this URL to begin the registration lifecycle. No admin credentials are
     * required — the route is permit-all under {@code /display/**}.
     */
    @Test
    void registerPageReturns200WithoutAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/register must return 200 without auth (E07S07 AC1, AC8)")
                .isEqualTo(HttpStatus.OK);
    }

    /** E07S07 AC8: {@code GET /display/register} must return an HTML response. */
    @Test
    void registerPageReturnsHtmlContentType() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getHeaders().getContentType())
                .as("GET /display/register content-type must be text/html (E07S07 AC8)")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    }

    /**
     * E07S07 AC8: The HTML shell for {@code /display/register} must contain the Svelte mount point
     * {@code <div id="app">}.
     *
     * <p>Both /display/overview and /display/register forward to the same index.html. The Svelte
     * app reads window.location.pathname to dispatch to DisplayRegisterPage.
     */
    @Test
    void registerPageContainsSvelteAppMountPoint() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getBody())
                .as(
                        "GET /display/register body must contain Svelte mount point <div"
                                + " id=\"app\"> (AC8)")
                .contains("<div id=\"app\">");
    }

    /**
     * E07S07 AC8, DEC-16: The HTML shell at {@code /display/register} must NOT contain external
     * {@code https://} URLs in {@code <script>} or {@code <link>} tags.
     *
     * <p>The registration page is part of the same Vite build as the overview page and shares the
     * same locally-bundled assets. No CDN references must be introduced (DEC-16 offline).
     */
    @Test
    void registerPageContainsNoExternalCdnScriptOrLinkTags() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        String body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as(
                        "GET /display/register must not contain external <script"
                                + " src=\"https://...\"> (DEC-16)")
                .doesNotContainPattern("<script[^>]+src=[\"']https://");

        assertThat(body)
                .as(
                        "GET /display/register must not contain external <link"
                                + " href=\"https://...\"> (DEC-16)")
                .doesNotContainPattern("<link[^>]+href=[\"']https://");
    }

    /**
     * E07S07 AC11: {@code GET /display/register} must NOT require admin authentication.
     *
     * <p>Display devices open this URL without any admin credentials. If the route requires
     * authentication, device registration would be impossible without human intervention.
     */
    @Test
    void registerPageIsAccessibleWithNoCredentials() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/register must be accessible with no credentials (E07S07 AC11)")
                .isEqualTo(HttpStatus.OK);
    }
}
