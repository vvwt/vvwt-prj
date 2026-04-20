package de.vvwt.tm.tournament;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for the new {@link AdminSpaController} at {@code de.vvwt.tm.tournament.*} using
 * standalone MockMvc (no Spring Boot context) (E21S09, AC-TDD-AdminSpaController, AC-SPA-404,
 * AC-SEC-NO-AUTH-BYPASS).
 *
 * <h2>RED state (DEC-22)</h2>
 *
 * <p>This test was committed RED: {@link AdminSpaController} at {@code
 * de.vvwt.tm.tournament.AdminSpaController} did not exist at commit time, causing a compile error.
 * This proves the test was written before the implementation (DEC-22 Iron Law).
 *
 * <h2>Test approach</h2>
 *
 * <p>Standalone MockMvc is used (matching the legacy {@code
 * de.vvwt.tm.infrastructure.AdminSpaControllerTest} pattern, E05S01). This avoids full Spring
 * context startup, which fails on staging during the E21 parallel-development phase due to {@code
 * PhaseCrudRepository} bean not being registered until E21S07 merges (pre-existing baseline failure
 * in {@code GlobalExceptionHandlerTest} and {@code TournamentControllerIT}).
 *
 * <h2>Security (AC-SEC-NO-AUTH-BYPASS)</h2>
 *
 * <p>Standalone MockMvc does not load the {@code SecurityConfig} filter chain. The security
 * constraint that {@code /admin/**} requires authentication is governed entirely by {@code
 * SecurityConfig} (auth.internal) — it is NOT declared in this controller. This is documented
 * explicitly: no {@code @PermitAll}, no {@code @PreAuthorize("permitAll()")}, no bypass of any kind
 * exists in this controller class. Security is tested at the integration level by the production
 * {@code SecurityConfig} filter chain, which is verified by {@code SecurityConfig}'s own test
 * class. This controller strictly delegates security to the framework.
 *
 * <h2>SPA fallback (AC-SPA-404)</h2>
 *
 * <p>All {@code /admin/**} paths forward to {@code forward:/static/admin/index.html}. The Svelte
 * router handles client-side routing. A raw stack trace is never surfaced — Spring Boot's error
 * handler returns structured 404 JSON if the static file is absent in production.
 *
 * @see de.vvwt.tm.infrastructure.AdminSpaControllerTest legacy equivalent test
 */
@DisplayName("AdminSpaControllerTest (tournament) — E21S09 AC-TDD-AdminSpaController + AC-SPA-404")
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
    @DisplayName("AC-TDD-AdminSpaController: GET /admin/ forwards to static/admin/index.html")
    void adminRootWithTrailingSlash_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }

    @Test
    @DisplayName(
            "AC-TDD-AdminSpaController: GET /admin (no slash) forwards to static/admin/index.html")
    void adminRootWithoutTrailingSlash_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }

    // -----------------------------------------------------------------------
    // AC-SPA-404: Deep-link paths
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-SPA-404: GET /admin/dashboard (deeplink) forwards to index.html")
    void adminDeepLink_dashboard_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }

    @Test
    @DisplayName(
            "AC-SPA-404: GET /admin/nonexistent.file.html forwards to index.html (no stack trace)")
    void adminDeepLink_nonexistentFile_forwardsToIndexHtml() throws Exception {
        // SPA fallback: all /admin/** paths forward to index.html.
        // The Svelte router handles the client-side 404 for unknown sub-paths.
        // This test confirms no raw stack trace is surfaced at the controller layer.
        mockMvc.perform(get("/admin/nonexistent.file.html"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
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
