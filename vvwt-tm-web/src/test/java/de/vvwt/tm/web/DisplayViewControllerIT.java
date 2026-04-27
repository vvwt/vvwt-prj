package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link DisplayViewController} — E25S02.
 *
 * <h2>Scope</h2>
 *
 * <p>Verifies that the display SPA HTML shell is served correctly for both routes:
 *
 * <ol>
 *   <li>HTTP 200 at {@code GET /display/overview} with content-type text/html
 *       (AC-RED-FIRST-DISPLAY-VIEW-CONTROLLER)
 *   <li>HTTP 200 at {@code GET /display/register} with content-type text/html
 *   <li>No admin authentication required for either route (no admin credentials = 200)
 *   <li>Response bodies contain the Svelte mount point {@code <div id="app">}
 *   <li>No external CDN links in the responses — DEC-16 offline compatibility
 * </ol>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT)} with {@code @Import({WebModuleTestConfig,
 *       TestAdminCredentials})} per 2026-04-27 empirical refinement
 *   <li>DEC-40 Clause A — {@code DisplayViewController} at {@code de.vvwt.tm.web.*}
 *   <li>DEC-22 Iron Law — RED-first: class written before production controller existed (commit
 *       67a8bc0 = delete-legacy RED; this commit = RED test; next commit = GREEN controller)
 * </ul>
 *
 * @see DisplayViewController
 * @see WebModuleTestConfig
 * @since E25S02
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e25s02viewitdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, DisplayViewControllerIT.TestAdminCredentials.class})
class DisplayViewControllerIT {

    static final String TEST_PASSWORD = "DisplayViewCtrlIT25S02";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    // =========================================================================
    // GET /display/overview — AC-RED-FIRST-DISPLAY-VIEW-CONTROLLER
    // =========================================================================

    /**
     * GET /display/overview must return HTTP 200 without admin authentication.
     *
     * <p>The display route is permit-all under Spring Security — no credentials required.
     */
    @Test
    void overviewPageReturns200WithoutAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /display/overview must return 200 without auth"
                                + " (AC-RED-FIRST-DISPLAY-VIEW-CONTROLLER)")
                .isEqualTo(HttpStatus.OK);
    }

    /** GET /display/overview must return content-type text/html. */
    @Test
    void overviewPageReturnsHtmlContentType() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getHeaders().getContentType())
                .as("GET /display/overview content-type must be text/html")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    }

    /**
     * GET /display/overview HTML shell must contain the Svelte mount point {@code <div id="app">}.
     *
     * <p>Verifies that the Vite-built {@code index.html} is served correctly.
     */
    @Test
    void overviewPageContainsSvelteAppMountPoint() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getBody())
                .as("GET /display/overview body must contain <div id=\"app\">")
                .contains("<div id=\"app\">");
    }

    /**
     * GET /display/overview HTML must NOT contain external CDN {@code https://} URLs in {@code
     * <script>} or {@code <link>} tags — DEC-16 offline compatibility.
     */
    @Test
    void overviewPageContainsNoExternalCdnScriptOrLinkTags() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        String body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as(
                        "GET /display/overview must not contain external <script"
                                + " src=\"https://...\"> (DEC-16)")
                .doesNotContainPattern("<script[^>]+src=[\"']https://");

        assertThat(body)
                .as(
                        "GET /display/overview must not contain external <link"
                                + " href=\"https://...\"> (DEC-16)")
                .doesNotContainPattern("<link[^>]+href=[\"']https://");
    }

    /**
     * GET /display/overview must be accessible with no credentials (permit-all route).
     *
     * <p>Display devices never have admin credentials. If the route is not permit-all, this test
     * would return 401.
     */
    @Test
    void overviewPageIsAccessibleWithNoCredentials() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/overview", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/overview must be accessible with no credentials")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // GET /display/register — AC-RED-FIRST-DISPLAY-VIEW-CONTROLLER
    // =========================================================================

    /**
     * GET /display/register must return HTTP 200 without authentication.
     *
     * <p>The registration route is served as the same Svelte SPA shell as /display/overview.
     */
    @Test
    void registerPageReturns200WithoutAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/register must return 200 without auth")
                .isEqualTo(HttpStatus.OK);
    }

    /** GET /display/register must return content-type text/html. */
    @Test
    void registerPageReturnsHtmlContentType() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getHeaders().getContentType())
                .as("GET /display/register content-type must be text/html")
                .isNotNull()
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    }

    /**
     * GET /display/register HTML shell must contain the Svelte mount point {@code <div id="app">}.
     *
     * <p>Both /display/overview and /display/register forward to the same index.html.
     */
    @Test
    void registerPageContainsSvelteAppMountPoint() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getBody())
                .as("GET /display/register body must contain <div id=\"app\">")
                .contains("<div id=\"app\">");
    }

    /**
     * GET /display/register HTML must NOT contain external CDN URLs — DEC-16 offline compatibility.
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
     * GET /display/register must be accessible with no credentials (permit-all route).
     *
     * <p>Display devices open this URL without admin credentials.
     */
    @Test
    void registerPageIsAccessibleWithNoCredentials() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/display/register", String.class);

        assertThat(response.getStatusCode())
                .as("GET /display/register must be accessible with no credentials")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // Test configuration — fixed admin credentials (DEC-44 D2 pattern)
    // =========================================================================

    /**
     * Per-IT {@link AdminCredentialsProvider} providing a fixed test password hash.
     *
     * <p>Per DEC-44 §"2026-04-27 Empirical Refinement": this inner class provides {@code @Primary
     * AdminCredentialsProvider} which overrides the non-primary placeholder in {@link
     * WebModuleTestConfig} via {@code spring.main.allow-bean-definition-overriding=true}. The
     * {@code SecurityFilterChain} + {@code UserDetailsService} remain the production beans; the
     * {@code @Primary AdminCredentialsProvider} feeds the production {@code
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
