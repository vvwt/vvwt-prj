package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
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
 * Integration tests for {@link AdminSpaController} — E42S02 (bug-triage: restore SPA delivery).
 *
 * <h2>Scope (AC1 + AC2 + AC3)</h2>
 *
 * <ol>
 *   <li><b>AC1 — Reproduction RED-first</b>: {@code GET /admin/} with valid HTTP Basic credentials
 *       returns HTTP 200 (was 404 after Spring Boot 4 migration E42S01).
 *   <li><b>AC2 — End-user-observable behavior</b>: response body contains {@code <div id="app">}
 *       and matches Vite-emitted script reference {@code /admin/assets/index-.*\.js}.
 *   <li><b>AC3 — Pre-controller direct static resolution</b>: {@code GET /admin/index.html} with
 *       valid auth returns HTTP 200, verifying the underlying static resource is reachable through
 *       Spring's resource handler chain independently of the controller forward.
 * </ol>
 *
 * <p>Per DEC-22 Iron Law Q-1a (AC1): this class was committed RED (failing) before any fix code was
 * written. The failing tests confirm that {@code forward:/static/admin/index.html} in {@link
 * AdminSpaController} resolves to HTTP 404 under Spring Framework 7 / Spring Boot 4 semantics.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-44 D1 — {@code @AutoConfigureTestRestTemplate @SpringBootTest(RANDOM_PORT, classes =
 *       TournamentManagerApplication.class)} per E42S02 (web-module IT canon)
 *   <li>DEC-44 D2 empirical refinement — {@code @Import({WebModuleTestConfig.class,
 *       TestAdminCredentials.class})}; single {@code @Primary AdminCredentialsProvider} via per-IT
 *       inner {@code TestAdminCredentials}
 *   <li>DEC-40 Clause A — {@code AdminSpaController} at {@code de.vvwt.tm.web.*}
 *   <li>DEC-22 Iron Law Q-1a — RED-first: this class was authored and committed failing before fix
 * </ul>
 *
 * @see AdminSpaController
 * @see WebModuleTestConfig
 * @since E42S02
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e42s02adminspaitdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, AdminSpaControllerIT.TestAdminCredentials.class})
@DisplayName("AdminSpaController IT — E42S02 (bug-triage: restore /admin/ SPA delivery)")
class AdminSpaControllerIT {

    static final String TEST_PASSWORD = "AdminSpaControllerIT42S02";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC1 — Reproduction RED-first: GET /admin/ with auth returns HTTP 200
    // =========================================================================

    /**
     * AC1 — {@code GET /admin/} with valid HTTP Basic credentials MUST return HTTP 200.
     *
     * <p>This test was authored RED-first per DEC-22 Iron Law. Before the fix, {@link
     * AdminSpaController#adminRoot()} returns {@code forward:/static/admin/index.html}. Spring
     * Framework 7 does not resolve {@code /static/admin/index.html} as a static resource (the URL
     * prefix {@code /static/} is not mounted by Spring Boot's {@code ResourceHttpRequestHandler}).
     * Result: HTTP 404. After the fix (forward target corrected to {@code /admin/index.html}), this
     * test passes.
     */
    @Test
    @DisplayName("AC1: GET /admin/ with auth → HTTP 200 (regression: was 404 after E42S01)")
    void adminRoot_withAuthentication_returns200() {
        ResponseEntity<String> response =
                authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /admin/ with valid auth must return HTTP 200 (AC1 — regression: was"
                                + " 404 after Spring Boot 4 migration E42S01)")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // AC2 — End-user-observable behavior: body contains SPA mount point + JS ref
    // =========================================================================

    /**
     * AC2 — Response body for {@code GET /admin/} MUST contain {@code <div id="app">}.
     *
     * <p>The Vite-built {@code index.html} (classpath:/static/admin/index.html, line 11) contains
     * this literal string. Asserting the controller-method return value alone does not close the
     * test gap (AC2 requirement: full HTTP request → response body exercise).
     */
    @Test
    @DisplayName("AC2: GET /admin/ body contains <div id=\"app\"> (SPA mount point)")
    void adminRoot_withAuthentication_bodyContainsSveAppMountPoint() {
        ResponseEntity<String> response =
                authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

        assertThat(response.getBody())
                .as(
                        "GET /admin/ body must contain <div id=\"app\">"
                                + " — closing test gap (AC2 E42S02)")
                .contains("<div id=\"app\">");
    }

    /**
     * AC2 — Response body for {@code GET /admin/} MUST contain a Vite-emitted script reference
     * matching {@code /admin/assets/index-.*\.js}.
     *
     * <p>The Vite-built {@code index.html} references the compiled entry bundle as {@code
     * /admin/assets/index-<hash>.js}. The hash changes on each rebuild; the pattern is
     * hash-agnostic by design.
     */
    @Test
    @DisplayName("AC2: GET /admin/ body contains Vite-emitted /admin/assets/index-*.js script ref")
    void adminRoot_withAuthentication_bodyContainsViteScriptRef() {
        ResponseEntity<String> response =
                authed.getForEntity("http://localhost:" + port + "/admin/", String.class);

        assertThat(response.getBody())
                .as(
                        "GET /admin/ body must match /admin/assets/index-.*\\.js"
                                + " — Vite-emitted script reference (AC2 E42S02)")
                .matches("(?s).*\\/admin\\/assets\\/index-.*\\.js.*");
    }

    // =========================================================================
    // AC3 — Pre-controller direct static resolution: GET /admin/index.html
    // =========================================================================

    /**
     * AC3 — {@code GET /admin/index.html} with valid auth MUST return HTTP 200.
     *
     * <p>This request bypasses {@link AdminSpaController}: Spring Boot's {@code
     * ResourceHttpRequestHandler} serves {@code classpath:/static/admin/index.html} directly when
     * the URL path is {@code /admin/index.html}. If this test returns 404, the static admin assets
     * are missing from the classpath (Vite build not run) or the Security filter chain blocks the
     * path (which it should not, since {@code SecurityConfig} requires authentication and these
     * credentials are valid).
     */
    @Test
    @DisplayName("AC3: GET /admin/index.html with auth → HTTP 200 (direct static resolution)")
    void adminIndexHtml_withAuthentication_returns200() {
        ResponseEntity<String> response =
                authed.getForEntity("http://localhost:" + port + "/admin/index.html", String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /admin/index.html with valid auth must return HTTP 200"
                                + " (AC3 — direct static resource, bypasses controller forward)")
                .isEqualTo(HttpStatus.OK);
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
