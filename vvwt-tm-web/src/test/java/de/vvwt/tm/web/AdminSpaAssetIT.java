// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for {@link AdminSpaController} asset delivery — E21S18 (bug-triage: fix {@code
 * /admin/assets/*.css|*.js} served as {@code text/html}).
 *
 * <h2>Scope (AC1–AC7)</h2>
 *
 * <ol>
 *   <li><b>AC1 — Reproduction RED-first per DEC-22 Iron Law</b>: this class was authored and
 *       committed RED (failing) before any fix code. The failing tests reproduce the incident: CSS
 *       and JS assets under {@code /admin/assets/} are served with {@code Content-Type: text/html}
 *       and body identical to {@code index.html}, because {@code
 *       AdminSpaController.adminDeepLink()} {@code @GetMapping("/admin/**")} catches the asset
 *       requests before Spring Boot's {@code ResourceHttpRequestHandler} can serve them.
 *   <li><b>AC2 — CSS asset MIME</b>: dynamic discovery of {@code <link rel="stylesheet">} hrefs
 *       from the served {@code /admin/} response; each CSS asset must return HTTP 200 + {@code
 *       text/css} + correct body length.
 *   <li><b>AC3 — JS asset MIME</b>: dynamic discovery of {@code <script src>} from the served
 *       {@code /admin/} response; each JS asset must return HTTP 200 + {@code
 *       application/javascript} or {@code text/javascript} + correct body length.
 *   <li><b>AC4 — Body identity NOT index.html</b>: asset response body must NOT contain {@code <div
 *       id="app">}.
 *   <li><b>AC5 — False-green guard</b>: parse must discover at least one CSS link and one JS
 *       script; if zero of either kind, test fails explicitly.
 *   <li><b>AC6 — Auth-wall positive path</b>: with valid HTTP Basic, asset retrievals succeed; no
 *       {@code permitAll} bypass introduced.
 *   <li><b>AC7 — Auth-wall negative path</b>: unauthenticated request returns HTTP 401; must NOT be
 *       200 with {@code text/css}.
 * </ol>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-44 D1 — {@code @SpringBootTest(webEnvironment = RANDOM_PORT, classes =
 *       TournamentManagerApplication.class)} per web-module IT canon
 *   <li>DEC-44 D2 empirical refinement — {@code @Import({WebModuleTestConfig.class,
 *       TestAdminCredentials.class})}; single {@code @Primary AdminCredentialsProvider} via inner
 *       {@code TestAdminCredentials}
 *   <li>DEC-40 Clause A — changes confined to {@code de.vvwt.tm.web.*}
 *   <li>DEC-22 Iron Law Q-1a — RED-first: this class was authored failing before fix
 * </ul>
 *
 * @see AdminSpaController
 * @see WebModuleTestConfig
 * @since E21S18
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s18adminassetitdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, AdminSpaAssetIT.TestAdminCredentials.class})
@DisplayName("AdminSpaController IT — E21S18 (bug-triage: asset MIME fix + test-gap closure)")
class AdminSpaAssetIT {

    static final String TEST_PASSWORD = "AdminSpaAssetIT21S18";

    /** Regex to extract {@code <link rel="stylesheet" href="/admin/assets/...">} URLs. */
    private static final Pattern CSS_LINK_PATTERN =
            Pattern.compile(
                    "<link[^>]+rel=[\"']stylesheet[\"'][^>]+href=[\"']([^\"']+)[\"']",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Regex to extract {@code <script src="/admin/assets/...">} or {@code <script type="module"
     * src="...">} URLs. Captures the {@code src} attribute value.
     */
    private static final Pattern JS_SCRIPT_PATTERN =
            Pattern.compile(
                    "<script[^>]+src=[\"']([^\"']*/admin/assets/[^\"']+\\.js[^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE);

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private TestRestTemplate authed;
    private TestRestTemplate anonymous;

    @BeforeEach
    void setUp() {
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
        anonymous = restTemplate;
    }

    // =========================================================================
    // AC5 — False-green guard (assets discovered from /admin/ body)
    // Invoked as a shared setup step by AC2/AC3/AC4 tests.
    // =========================================================================

    /**
     * Retrieves the {@code /admin/} HTML body with authentication and returns a list of CSS asset
     * URL paths discovered via {@link #CSS_LINK_PATTERN}.
     *
     * <p>AC5 guard: if the list is empty, the test MUST fail with an explicit message.
     */
    private List<String> discoverCssAssets() {
        ResponseEntity<String> indexResponse =
                authed.getForEntity("http://localhost:" + port + "/admin/", String.class);
        assertThat(indexResponse.getStatusCode())
                .as("GET /admin/ prerequisite: must return HTTP 200 before asset discovery")
                .isEqualTo(HttpStatus.OK);

        String body = indexResponse.getBody();
        assertThat(body).as("GET /admin/ body must not be null").isNotNull();

        List<String> cssUrls = new ArrayList<>();
        Matcher matcher = CSS_LINK_PATTERN.matcher(body);
        while (matcher.find()) {
            cssUrls.add(matcher.group(1));
        }

        // AC5 false-green guard
        assertThat(cssUrls)
                .as(
                        "AC5 false-green guard: CSS asset discovery from /admin/ index.html"
                                + " returned empty list — index.html structure changed;"
                                + " IT logic stale (check CSS_LINK_PATTERN against current"
                                + " Vite-emitted index.html)")
                .isNotEmpty();

        return cssUrls;
    }

    /**
     * Retrieves the {@code /admin/} HTML body with authentication and returns a list of JS asset
     * URL paths discovered via {@link #JS_SCRIPT_PATTERN}.
     *
     * <p>AC5 guard: if the list is empty, the test MUST fail with an explicit message.
     */
    private List<String> discoverJsAssets() {
        ResponseEntity<String> indexResponse =
                authed.getForEntity("http://localhost:" + port + "/admin/", String.class);
        assertThat(indexResponse.getStatusCode())
                .as("GET /admin/ prerequisite: must return HTTP 200 before asset discovery")
                .isEqualTo(HttpStatus.OK);

        String body = indexResponse.getBody();
        assertThat(body).as("GET /admin/ body must not be null").isNotNull();

        List<String> jsUrls = new ArrayList<>();
        Matcher matcher = JS_SCRIPT_PATTERN.matcher(body);
        while (matcher.find()) {
            jsUrls.add(matcher.group(1));
        }

        // AC5 false-green guard
        assertThat(jsUrls)
                .as(
                        "AC5 false-green guard: JS script asset discovery from /admin/ index.html"
                                + " returned empty list — index.html structure changed;"
                                + " IT logic stale (check JS_SCRIPT_PATTERN against current"
                                + " Vite-emitted index.html)")
                .isNotEmpty();

        return jsUrls;
    }

    // =========================================================================
    // AC2 — CSS asset MIME and body length
    // =========================================================================

    /**
     * AC2 — Each CSS asset discovered from {@code /admin/} index.html MUST return HTTP 200 + {@code
     * Content-Type} matching {@code ^text/css($|;.*)} + correct body length (NOT equal to
     * index.html body length).
     *
     * <p>This test was authored RED-first per DEC-22 Iron Law (AC1). Before the fix, {@link
     * AdminSpaController#adminDeepLink()} intercepts {@code /admin/assets/*.css} and returns {@code
     * text/html} + index.html body. After the fix (deletion of {@code adminDeepLink()}), Spring
     * Boot's {@code ResourceHttpRequestHandler} serves the actual CSS file.
     */
    @Test
    @DisplayName(
            "AC2: CSS assets discovered from /admin/ return text/css + correct length (not"
                    + " index.html body)")
    void cssAssets_withAuthentication_returnCorrectMimeAndLength() throws Exception {
        List<String> cssUrls = discoverCssAssets();

        // Fetch index.html as byte[] so length comparison is apples-to-apples
        ResponseEntity<byte[]> indexBytesResponse =
                authed.getForEntity("http://localhost:" + port + "/admin/", byte[].class);
        int indexBodyLength =
                indexBytesResponse.getBody() != null ? indexBytesResponse.getBody().length : 0;

        for (String cssUrl : cssUrls) {
            // CSS URL from Vite is absolute (/admin/assets/...) — use it directly
            // Use byte[] to get accurate byte length comparison against classpath resource
            String url = "http://localhost:" + port + cssUrl;
            ResponseEntity<byte[]> cssResponse = authed.getForEntity(url, byte[].class);

            assertThat(cssResponse.getStatusCode())
                    .as("AC2: GET %s with auth must return HTTP 200", cssUrl)
                    .isEqualTo(HttpStatus.OK);

            String contentType = cssResponse.getHeaders().getFirst("Content-Type");
            assertThat(contentType)
                    .as(
                            "AC2: GET %s Content-Type must match text/css pattern (not text/html);"
                                    + " actual: %s",
                            cssUrl, contentType)
                    .matches("(?i)^text/css($|;.*)");

            // Body byte length check: classpath resource byte length != index.html byte length
            int assetBodyLength = cssResponse.getBody() != null ? cssResponse.getBody().length : 0;
            assertThat(assetBodyLength)
                    .as(
                            "AC2: GET %s response body byte length (%d) must NOT equal index.html"
                                    + " byte length (%d) — bug: controller serving index.html as"
                                    + " CSS",
                            cssUrl, assetBodyLength, indexBodyLength)
                    .isNotEqualTo(indexBodyLength);
            assertThat(assetBodyLength)
                    .as("AC2: GET %s CSS asset body must not be empty", cssUrl)
                    .isGreaterThan(0);

            // Verify byte length matches classpath resource
            String filename = cssUrl.substring(cssUrl.lastIndexOf('/') + 1);
            ClassPathResource classpathResource =
                    new ClassPathResource("static/admin/assets/" + filename);
            if (classpathResource.exists()) {
                long classpathLength = classpathResource.contentLength();
                assertThat((long) assetBodyLength)
                        .as(
                                "AC2: GET %s body byte length (%d) must equal classpath resource"
                                        + " byte length (%d)",
                                cssUrl, assetBodyLength, classpathLength)
                        .isEqualTo(classpathLength);
            }
        }
    }

    // =========================================================================
    // AC3 — JS asset MIME and body length
    // =========================================================================

    /**
     * AC3 — Each JS asset discovered from {@code /admin/} index.html MUST return HTTP 200 + {@code
     * Content-Type} matching {@code ^(application|text)/javascript($|;.*)} + correct body length
     * (NOT equal to index.html body length).
     *
     * <p>This test was authored RED-first per DEC-22 Iron Law (AC1). Before the fix, {@link
     * AdminSpaController#adminDeepLink()} intercepts {@code /admin/assets/*.js} and returns {@code
     * text/html} + index.html body.
     */
    @Test
    @DisplayName(
            "AC3: JS assets discovered from /admin/ return application/javascript + correct length"
                    + " (not index.html body)")
    void jsAssets_withAuthentication_returnCorrectMimeAndLength() throws Exception {
        List<String> jsUrls = discoverJsAssets();

        // Fetch index.html as byte[] so length comparison with asset byte[] is apples-to-apples
        ResponseEntity<byte[]> indexBytesResponse =
                authed.getForEntity("http://localhost:" + port + "/admin/", byte[].class);
        int indexBodyLength =
                indexBytesResponse.getBody() != null ? indexBytesResponse.getBody().length : 0;

        for (String jsUrl : jsUrls) {
            String url = "http://localhost:" + port + jsUrl;
            // Use byte[] to get accurate byte length comparison against classpath resource
            ResponseEntity<byte[]> jsResponse = authed.getForEntity(url, byte[].class);

            assertThat(jsResponse.getStatusCode())
                    .as("AC3: GET %s with auth must return HTTP 200", jsUrl)
                    .isEqualTo(HttpStatus.OK);

            String contentType = jsResponse.getHeaders().getFirst("Content-Type");
            assertThat(contentType)
                    .as(
                            "AC3: GET %s Content-Type must match (application|text)/javascript"
                                    + " pattern (not text/html); actual: %s",
                            jsUrl, contentType)
                    .matches("(?i)^(application|text)/javascript($|;.*)");

            int assetBodyLength = jsResponse.getBody() != null ? jsResponse.getBody().length : 0;
            assertThat(assetBodyLength)
                    .as(
                            "AC3: GET %s response body byte length (%d) must NOT equal index.html"
                                    + " body String length (%d) — if equal, controller is still"
                                    + " serving index.html as JS",
                            jsUrl, assetBodyLength, indexBodyLength)
                    .isNotEqualTo(indexBodyLength);
            assertThat(assetBodyLength)
                    .as("AC3: GET %s JS asset body must not be empty", jsUrl)
                    .isGreaterThan(0);

            String filename = jsUrl.substring(jsUrl.lastIndexOf('/') + 1);
            ClassPathResource classpathResource =
                    new ClassPathResource("static/admin/assets/" + filename);
            if (classpathResource.exists()) {
                long classpathLength = classpathResource.contentLength();
                assertThat((long) assetBodyLength)
                        .as(
                                "AC3: GET %s body byte length (%d) must equal classpath resource"
                                        + " byte length (%d)",
                                jsUrl, assetBodyLength, classpathLength)
                        .isEqualTo(classpathLength);
            }
        }
    }

    // =========================================================================
    // AC4 — Body identity: NOT index.html
    // =========================================================================

    /**
     * AC4 — For at least one CSS asset and at least one JS asset, response body MUST NOT contain
     * {@code <div id="app">} (an index.html marker, present in {@code
     * classpath:/static/admin/index.html} line 9).
     *
     * <p>Guards against a partial fix that returns the correct {@code Content-Type} header but
     * still serves index.html bytes.
     */
    @Test
    @DisplayName(
            "AC4: CSS and JS asset bodies must NOT contain <div id=\"app\"> (index.html marker)")
    void assets_withAuthentication_bodyDoesNotContainIndexHtmlMarker() {
        List<String> cssUrls = discoverCssAssets();
        List<String> jsUrls = discoverJsAssets();

        // CSS asset body identity check
        String firstCssUrl = cssUrls.get(0);
        ResponseEntity<String> cssResponse =
                authed.getForEntity("http://localhost:" + port + firstCssUrl, String.class);
        assertThat(cssResponse.getBody())
                .as(
                        "AC4: CSS asset body at %s MUST NOT contain '<div id=\"app\">' —"
                                + " if present, controller is still serving index.html bytes"
                                + " despite fix",
                        firstCssUrl)
                .doesNotContain("<div id=\"app\">");

        // JS asset body identity check
        String firstJsUrl = jsUrls.get(0);
        ResponseEntity<String> jsResponse =
                authed.getForEntity("http://localhost:" + port + firstJsUrl, String.class);
        assertThat(jsResponse.getBody())
                .as(
                        "AC4: JS asset body at %s MUST NOT contain '<div id=\"app\">' —"
                                + " if present, controller is still serving index.html bytes"
                                + " despite fix",
                        firstJsUrl)
                .doesNotContain("<div id=\"app\">");
    }

    // =========================================================================
    // AC6 — Auth-wall positive path
    // =========================================================================

    /**
     * AC6 — With valid HTTP Basic credentials, CSS and JS asset retrievals succeed (HTTP 200). The
     * {@code SecurityConfig} {@code /admin/**} authenticated rule is preserved; no {@code
     * permitAll("/admin/assets/**")} workaround is introduced.
     *
     * <p>This is implicitly verified by AC2 and AC3 (both use authenticated requests and assert
     * HTTP 200). This explicit test makes the AC6 obligation visible in the test suite.
     */
    @Test
    @DisplayName("AC6: With valid auth, CSS and JS asset retrievals succeed (HTTP 200)")
    void assets_withAuthentication_returnHttp200() {
        List<String> cssUrls = discoverCssAssets();
        List<String> jsUrls = discoverJsAssets();

        ResponseEntity<String> cssResponse =
                authed.getForEntity("http://localhost:" + port + cssUrls.get(0), String.class);
        assertThat(cssResponse.getStatusCode())
                .as(
                        "AC6: GET %s with valid auth must return HTTP 200 (security config"
                                + " /admin/** authenticated rule preserved)",
                        cssUrls.get(0))
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> jsResponse =
                authed.getForEntity("http://localhost:" + port + jsUrls.get(0), String.class);
        assertThat(jsResponse.getStatusCode())
                .as(
                        "AC6: GET %s with valid auth must return HTTP 200 (security config"
                                + " /admin/** authenticated rule preserved)",
                        jsUrls.get(0))
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // AC7 — Auth-wall negative path
    // =========================================================================

    /**
     * AC7 — Unauthenticated {@code GET /admin/assets/<asset>} MUST return HTTP 401 (HTTP Basic
     * challenge). Must NOT be 200 and must NOT have {@code Content-Type: text/css}.
     *
     * <p>Proves the fix did not relax the auth wall as a side effect.
     */
    @Test
    @DisplayName(
            "AC7: Unauthenticated GET /admin/assets/<asset> returns HTTP 401 (auth-wall"
                    + " preserved)")
    void cssAsset_withoutAuthentication_returns401() {
        List<String> cssUrls = discoverCssAssets();
        String cssUrl = cssUrls.get(0);

        ResponseEntity<String> unauthResponse =
                anonymous.getForEntity("http://localhost:" + port + cssUrl, String.class);

        assertThat(unauthResponse.getStatusCode())
                .as(
                        "AC7: Unauthenticated GET %s must return HTTP 401 (auth-wall); fix must"
                                + " NOT have introduced permitAll for /admin/assets/**",
                        cssUrl)
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(unauthResponse.getStatusCode())
                .as("AC7: Must NOT be HTTP 200 — fix introduced a security bypass")
                .isNotEqualTo(HttpStatus.OK);

        String contentType = unauthResponse.getHeaders().getFirst("Content-Type");
        if (contentType != null) {
            assertThat(contentType)
                    .as(
                            "AC7: Unauthenticated response Content-Type must NOT be text/css —"
                                    + " asset must be gated behind auth-wall",
                            cssUrl)
                    .doesNotMatch("(?i)^text/css.*");
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
