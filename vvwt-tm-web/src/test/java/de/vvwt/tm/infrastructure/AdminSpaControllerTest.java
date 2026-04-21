package de.vvwt.tm.infrastructure;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.tournament.AdminSpaController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for {@link AdminSpaController} using standalone MockMvc (no Spring context).
 *
 * <p>Story E05S01 — AC3 (serving SPA at /admin/), AC4 (deep-link fallback), AC8 (SPA fallback for
 * /admin/**).
 *
 * <p>Standalone MockMvc avoids the full Spring Boot context while still validating MVC dispatch,
 * URL pattern matching, and forward() behavior of the controller.
 */
@DisplayName("AdminSpaController — SPA routing tests (E05S01)")
class AdminSpaControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Standalone setup: only AdminSpaController is registered, no Spring Boot scanning.
        // This is the correct approach for testing a single @Controller without its dependencies.
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminSpaController()).build();
    }

    // -----------------------------------------------------------------------
    // AC3 — /admin/ and /admin serve the SPA index.html
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC3: GET /admin/ returns forward to static/admin/index.html")
    void adminRootWithTrailingSlash_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }

    @Test
    @DisplayName("AC3: GET /admin (no trailing slash) returns forward to static/admin/index.html")
    void adminRootWithoutTrailingSlash_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }

    // -----------------------------------------------------------------------
    // AC4 — Deep-link paths within /admin/ fall through to index.html
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC4: GET /admin/tournaments (deep-link) is forwarded to index.html for client-side"
                    + " routing")
    void adminDeepLink_tournaments_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/tournaments"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }

    @Test
    @DisplayName("AC4: GET /admin/teams/42 (nested deep-link) is forwarded to index.html")
    void adminDeepLink_nestedPath_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/teams/42"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }

    // -----------------------------------------------------------------------
    // AC8 — Unknown paths under /admin/ return index.html (SPA handles 404)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC8: GET /admin/unknown-path is forwarded to index.html (SPA handles 404 client-side)")
    void adminUnknownPath_forwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/admin/unknown-path"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/static/admin/index.html"));
    }
}
