package de.vvwt.tm.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.web.AdminSpaController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for {@link AdminSpaController} using standalone MockMvc (no Spring context).
 *
 * <p>Story E05S01 — AC3 (serving SPA at /admin/), AC4 (deep-link fallback), AC8 (SPA fallback for
 * /admin/**).
 *
 * <p>Standalone MockMvc avoids the full Spring Boot context while still validating MVC dispatch,
 * URL pattern matching, and the controller's handling of /admin/** paths.
 *
 * <p>E42S02 update (AC4): {@code forwardedUrl} assertions replaced by HTTP-200 + content-type
 * assertions. The E42S02 bug-fix changes the serving mechanism from {@code forward:} string to
 * {@code ResponseEntity<ClassPathResource>} (ClassPathResource pattern per {@link
 * de.vvwt.tm.web.timer.TimerViewController}, E26S03). Forward assertions are no longer applicable.
 * Full body assertions (AC2) are in {@link de.vvwt.tm.web.AdminSpaControllerIT}.
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
    @DisplayName("AC3: GET /admin/ returns HTTP 200 with text/html")
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
    @DisplayName("AC3: GET /admin (no trailing slash) returns HTTP 200")
    void adminRootWithoutTrailingSlash_returns200() throws Exception {
        mockMvc.perform(get("/admin").accept(MediaType.TEXT_HTML)).andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // AC4 — Deep-link paths within /admin/ fall through to index.html
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC4: GET /admin/tournaments (deep-link) returns HTTP 200")
    void adminDeepLink_tournaments_returns200() throws Exception {
        mockMvc.perform(get("/admin/tournaments").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC4: GET /admin/teams/42 (nested deep-link) returns HTTP 200")
    void adminDeepLink_nestedPath_returns200() throws Exception {
        mockMvc.perform(get("/admin/teams/42").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }

    // -----------------------------------------------------------------------
    // AC8 — Unknown paths under /admin/ return index.html (SPA handles 404)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC8: GET /admin/unknown-path returns HTTP 200 (SPA handles 404 client-side)")
    void adminUnknownPath_returns200() throws Exception {
        mockMvc.perform(get("/admin/unknown-path").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk());
    }
}
