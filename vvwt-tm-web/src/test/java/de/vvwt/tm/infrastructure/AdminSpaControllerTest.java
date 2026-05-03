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
 *
 * <p>E21S18 update (AC8): tests for {@code adminDeepLink()} ({@code @GetMapping("/admin/**")})
 * removed because that method was deleted as part of the E21S18 bug-fix. Under Spring Framework 7 /
 * Spring Boot 4, the catch-all intercepted asset requests ({@code /admin/assets/*.css}, {@code
 * /admin/assets/*.js}) and served them as {@code text/html}. With hash-based routing
 * (svelte-spa-router), the catch-all was vestigial — sub-routes are {@code #/path} fragments never
 * sent to the server. Deletion of {@code adminDeepLink()} allows asset requests to reach Spring
 * Boot's {@code ResourceHttpRequestHandler} for correct MIME-type-aware static resource serving.
 * Tests verifying deep-link paths ({@code /admin/tournaments}, {@code /admin/teams/42}, {@code
 * /admin/unknown-path}) are removed here per AC8 (incompatible with the fix). The remaining test of
 * /admin/ and /admin root delivery is retained.
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
}
