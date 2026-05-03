package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for {@link AdminSpaController} at {@code de.vvwt.tm.web.*} using standalone MockMvc
 * (no Spring Boot context) (E21S09, AC-TDD-AdminSpaController, AC-SPA-404, AC-SEC-NO-AUTH-BYPASS).
 *
 * <p>Relocated from the {@code tournament} test package to {@code de.vvwt.tm.web} per DEC-40 Clause
 * A (E22S01, Q-1b whole-class refactor). Per DEC-22 § refactor-clause: existing tests are the
 * regression gate for the Q-1b move.
 *
 * <p>E42S02 update (AC4): controller implementation changed from {@code forward:/static/...} string
 * return to {@code ResponseEntity<ClassPathResource>} direct resource serve (same approach as
 * {@link de.vvwt.tm.web.timer.TimerViewController}). The {@code forwardedUrl} assertions are
 * replaced by HTTP-200 + content-type assertions. Body-content assertions require the Vite-built
 * classpath resource to be present and are covered by {@link AdminSpaControllerIT} (full Spring
 * Boot context with Vite build phase). No new test obligation; this is an AC4-mandated update to
 * keep the existing tests compatible with the fix.
 *
 * <p>E21S18 update (AC8): the deep-link fallback method {@code adminDeepLink()}
 * ({@code @GetMapping("/admin/**")}) was deleted as part of the E21S18 fix. Under Spring Framework
 * 7 / Spring Boot 4, {@code PathPatternParser} dispatches controller mappings before Spring Boot's
 * {@code ResourceHttpRequestHandler}, so the catch-all intercepted asset requests and served them
 * as {@code text/html}. With hash-based routing (svelte-spa-router), the catch-all was vestigial —
 * sub-routes are {@code #/path} fragments, never sent to the server. Tests for {@code
 * adminDeepLink()} are removed in this update (AC8 — incompatible assertions updated). The
 * AC-SPA-404 obligation is met client-side by the Svelte router; no server-side SPA fallback route
 * is needed.
 *
 * <h2>Test approach</h2>
 *
 * <p>Standalone MockMvc is used (matching the legacy {@code
 * de.vvwt.tm.infrastructure.AdminSpaControllerTest} pattern, E05S01). This avoids full Spring
 * context startup. Requests to {@code /admin} and {@code /admin/} return HTTP 200 with content-type
 * {@code text/html}; the actual body is served from {@code classpath:/static/admin/index.html} when
 * the Vite build has run (full body assertions are in {@link AdminSpaControllerIT}).
 *
 * <h2>Security (AC-SEC-NO-AUTH-BYPASS)</h2>
 *
 * <p>Standalone MockMvc does not load the {@code SecurityConfig} filter chain. The security
 * constraint that {@code /admin/**} requires authentication is governed entirely by {@code
 * SecurityConfig} (auth.internal) — it is NOT declared in this controller. This is documented
 * explicitly: no {@code @PermitAll}, no {@code @PreAuthorize("permitAll()")}, no bypass of any kind
 * exists in this controller class. Security is tested at the integration level by the production
 * {@code SecurityConfig} filter chain.
 */
@DisplayName("AdminSpaControllerTest (web) — E21S09 AC-TDD-AdminSpaController")
class AdminSpaControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Standalone MockMvc: only AdminSpaController registered, no context loading.
        // Matches de.vvwt.tm.infrastructure.AdminSpaControllerTest (E05S01 pattern).
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminSpaController()).build();
    }

    // -----------------------------------------------------------------------
    // AC-TDD-AdminSpaController: SPA root
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-AdminSpaController: GET /admin/ returns HTTP 200 with text/html")
    void adminRootWithTrailingSlash_returns200WithHtmlContentType() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/admin/").accept(MediaType.TEXT_HTML))
                        .andExpect(status().isOk())
                        .andReturn();

        assertThat(result.getResponse().getContentType())
                .as("GET /admin/ must return content-type text/html")
                .contains("text/html");
    }

    @Test
    @DisplayName("AC-TDD-AdminSpaController: GET /admin (no slash) returns HTTP 200")
    void adminRootWithoutTrailingSlash_returns200() throws Exception {
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // AC-SEC-NO-AUTH-BYPASS: controller does NOT install permit-all
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC-SEC-NO-AUTH-BYPASS: controller has no @PermitAll — security delegated to"
                    + " SecurityConfig")
    void controller_hasNoPermitAllAnnotation() throws Exception {
        // Verify the controller class does NOT carry @PermitAll or equivalent.
        // Standalone MockMvc allows all requests (no security filter), so this
        // test validates that the controller itself does not weaken the filter chain.
        boolean hasPermitAll =
                java.util.Arrays.stream(AdminSpaController.class.getAnnotations())
                        .anyMatch(
                                a ->
                                        a.annotationType().getSimpleName().contains("PermitAll")
                                                || a.annotationType()
                                                        .getSimpleName()
                                                        .contains("PreAuthorize"));
        org.assertj.core.api.Assertions.assertThat(hasPermitAll)
                .as(
                        "AdminSpaController must NOT carry @PermitAll or @PreAuthorize(permitAll)"
                                + " — security is delegated to SecurityConfig filter chain")
                .isFalse();
    }
}
