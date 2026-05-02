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
 * Integration tests for {@link LandingController} — E43S01 (convenience redirect: GET / → 302
 * /admin/).
 *
 * <h2>Scope (AC1 + AC2 + AC3)</h2>
 *
 * <ol>
 *   <li><b>AC1 — Redirect contract RED-first</b>: unauthenticated {@code GET /} (redirect NOT
 *       followed) returns {@code HTTP 302} with {@code Location: /admin/}.
 *   <li><b>AC2 — End-to-end redirect chain</b>: {@code GET /} with redirect-following enabled
 *       reaches {@code /admin/} and returns a non-404 response.
 *   <li><b>AC3 — Exact-path matcher / no catch-all</b>: {@code GET /unknown-path-that-should-404}
 *       (redirect NOT followed) returns {@code HTTP 404}.
 * </ol>
 *
 * <p>Per DEC-22 Iron Law Q-1a (AC12): this class was committed RED (failing) before any controller
 * or SecurityConfig code was written. The failing tests confirm that {@code GET /} currently returns
 * {@code HTTP 401} (not 302) because no {@code permitAll("/")} rule is present and no
 * {@code LandingController} exists.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}
 *       per E43S01 (web-module IT canon)
 *   <li>DEC-44 D2 empirical refinement — {@code @Import({WebModuleTestConfig.class,
 *       TestAdminCredentials.class})}; single {@code @Primary AdminCredentialsProvider} via per-IT
 *       inner {@code TestAdminCredentials}
 *   <li>DEC-40 Clause A — {@code LandingController} at {@code de.vvwt.tm.web.*}
 *   <li>DEC-22 Iron Law Q-1a — RED-first: this class was authored and committed failing before
 *       controller code landed
 * </ul>
 *
 * @see LandingController
 * @see WebModuleTestConfig
 * @since E43S01
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e43s01landingitdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.main.allow-bean-definition-overriding=true"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, LandingControllerIT.TestAdminCredentials.class})
@DisplayName("LandingController IT — E43S01 (GET / → 302 /admin/)")
class LandingControllerIT {

    static final String TEST_PASSWORD = "LandingControllerIT43S01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    /** {@code TestRestTemplate} configured to NOT follow redirects (used by AC1 and AC3). */
    private TestRestTemplate noRedirect;

    @BeforeEach
    void setUp() {
        // TestRestTemplate by default does NOT follow redirects — use as-is for AC1/AC3.
        noRedirect = restTemplate;
    }

    // =========================================================================
    // AC1 — Redirect contract: unauthenticated GET / → HTTP 302 + Location: /admin/
    // =========================================================================

    /**
     * AC1 — Unauthenticated {@code GET /} (redirect NOT followed) MUST return {@code HTTP 302} with
     * {@code Location} header equal to {@code /admin/} (exact match, trailing slash included).
     *
     * <p>This test was authored RED-first per DEC-22 Iron Law. Before the fix:
     *
     * <ul>
     *   <li>No {@code LandingController} exists → no handler for {@code /}.
     *   <li>No {@code permitAll("/")} in {@code SecurityConfig} → the {@code anyRequest().authenticated()}
     *       catch-all intercepts {@code GET /} before any controller → {@code HTTP 401}.
     * </ul>
     *
     * <p>After the fix ({@code LandingController} + {@code SecurityConfig} update), this test passes.
     *
     * <p>Per AC7: {@code requestMatchers("/").permitAll()} MUST be placed BEFORE
     * {@code anyRequest().authenticated()} in the SecurityConfig chain.
     */
    @Test
    @DisplayName("AC1: unauthenticated GET / → HTTP 302 + Location: /admin/ (redirect NOT followed)")
    void getRoot_unauthenticated_returns302WithLocationAdminSlash() {
        ResponseEntity<String> response =
                noRedirect.getForEntity("http://localhost:" + port + "/", String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET / unauthenticated (no redirect follow) MUST return HTTP 302"
                                + " (AC1 — E43S01: LandingController redirect)")
                .isEqualTo(HttpStatus.FOUND);

        assertThat(response.getHeaders().getLocation())
                .as("Location header MUST equal /admin/ (exact, case-sensitive, trailing slash)")
                .isNotNull()
                .hasPath("/admin/");
    }

    // =========================================================================
    // AC2 — End-to-end redirect chain: GET / followed → reaches /admin/ (non-404)
    // =========================================================================

    /**
     * AC2 — {@code GET /} with redirect-following enabled MUST ultimately reach {@code /admin/} and
     * return a non-404 response (the Svelte Admin SPA shell from E42S02).
     *
     * <p>This test does NOT re-assert SPA body content ({@code <div id="app">}) — that assertion is
     * owned by E42S02 AC2 ({@link AdminSpaControllerIT}). It only verifies the redirect chain
     * completes against a reachable target.
     *
     * <p>An authenticated client is used because {@code /admin/} requires authentication per
     * {@code SecurityConfig}. After following the redirect, the client reaches {@code /admin/} with
     * credentials and expects a non-404 response.
     */
    @Test
    @DisplayName("AC2: GET / (redirect followed, authenticated) reaches /admin/ → non-404")
    void getRoot_withRedirectFollow_authenticated_reachesAdminSlash() {
        TestRestTemplate authed =
                restTemplate.withBasicAuth(
                        AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);

        ResponseEntity<String> response =
                authed.getForEntity("http://localhost:" + port + "/", String.class);

        assertThat(response.getStatusCode().value())
                .as(
                        "GET / (redirect followed, authenticated) MUST reach /admin/ and return"
                                + " a non-404 status (AC2 — E43S01)")
                .isNotEqualTo(HttpStatus.NOT_FOUND.value());
    }

    // =========================================================================
    // AC3 — Exact-path matcher: unknown path still returns 404 (no catch-all)
    // =========================================================================

    /**
     * AC3 — {@code GET /unknown-path-that-should-404} (redirect NOT followed) MUST return
     * {@code HTTP 404}.
     *
     * <p>Guards against accidentally introducing a catch-all matcher in {@code LandingController}
     * that would swallow unrecognized paths and silently break broken bookmarks or typos.
     *
     * <p>The {@code anyRequest().authenticated()} rule in SecurityConfig means an unauthenticated
     * request to an unknown path returns {@code HTTP 401}, not 404. We supply authentication here so
     * that the security filter passes through to the dispatcher, which can then return the real 404
     * from the absence of a matching handler.
     */
    @Test
    @DisplayName("AC3: GET /unknown-path-that-should-404 (authenticated) → HTTP 404 (no catch-all)")
    void getUnknownPath_authenticated_returns404() {
        TestRestTemplate authed =
                restTemplate.withBasicAuth(
                        AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);

        ResponseEntity<String> response =
                authed.getForEntity(
                        "http://localhost:" + port + "/unknown-path-that-should-404",
                        String.class);

        assertThat(response.getStatusCode())
                .as(
                        "GET /unknown-path-that-should-404 (authenticated) MUST return HTTP 404"
                                + " — guards against catch-all in LandingController (AC3 E43S01)")
                .isEqualTo(HttpStatus.NOT_FOUND);
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
