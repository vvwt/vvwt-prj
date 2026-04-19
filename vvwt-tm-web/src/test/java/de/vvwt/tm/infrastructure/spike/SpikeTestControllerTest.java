package de.vvwt.tm.infrastructure.spike;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for {@link SpikeTestController} — iOS 9 compatibility spike (E06S01).
 *
 * <p>Standalone MockMvc (no Spring Boot context) validates:
 *
 * <ul>
 *   <li>AC1: echo endpoint returns JSON with status, message, and timestamp
 *   <li>AC4/AC7: spike page redirects to the static HTML file
 * </ul>
 */
@DisplayName("SpikeTestController — iOS 9 spike tests (E06S01)")
class SpikeTestControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpikeTestController()).build();
    }

    @Test
    @DisplayName(
            "AC1: GET /score/spike/api/echo returns 200 with JSON containing status and timestamp")
    void echoEndpoint_returnsJsonWithStatusAndTimestamp() throws Exception {
        mockMvc.perform(get("/score/spike/api/echo"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.message").value("Spike echo response"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("AC4/AC7: GET /score/spike/ redirects to static spike test page")
    void spikePageWithTrailingSlash_redirectsToTestHtml() throws Exception {
        mockMvc.perform(get("/score/spike/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/score/spike/test.html"));
    }

    @Test
    @DisplayName(
            "AC4/AC7: GET /score/spike (no trailing slash) redirects to static spike test page")
    void spikePageWithoutTrailingSlash_redirectsToTestHtml() throws Exception {
        mockMvc.perform(get("/score/spike"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/score/spike/test.html"));
    }
}
