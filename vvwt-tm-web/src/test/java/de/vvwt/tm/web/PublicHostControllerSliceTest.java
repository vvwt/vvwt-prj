package de.vvwt.tm.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.LanHostDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice test for {@link PublicHostController} — Story E49S04.
 *
 * <p>AC-TEST-REGISTRATION-URL-NOT-LOOPBACK-RED: verifies the controller surfaces the detected host
 * (not {@code window.location.origin} when that origin is loopback). The test was RED before {@link
 * PublicHostController} was authored; GREEN after.
 *
 * <p>Security note: {@code /api/public-host} is declared {@code permitAll()} in {@code
 * SecurityConfig} (E49S04). In the {@code @WebMvcTest} slice the auto-generated {@code
 * UserDetailsService} is used (not the full {@code AuthConfiguration}), so tests use
 * {@code @WithMockUser} to pass Spring Security's default "authenticated" check — consistent with
 * the pattern established by {@link DeviceControllerSliceTest}. The {@code permitAll()} rule is
 * verified at the integration level.
 *
 * @see PublicHostController
 * @see <a href="../../../../../../../../docs/governance/stories/E49S04.story.md">Story E49S04</a>
 */
@WebMvcTest(PublicHostController.class)
class PublicHostControllerSliceTest {

    @Autowired private WebApplicationContext context;

    @MockitoBean private LanHostDetector lanHostDetector;

    // Required by the web slice context to satisfy tenantContextResolver bean dependency.
    @MockitoBean private TenantContext tenantContext;

    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(tenantContext.bind(any())).thenReturn(() -> {});
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-TEST-REGISTRATION-URL-NOT-LOOPBACK-RED
    // The controller must surface the LAN host, not a loopback value.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser
    void getPublicHost_returnsDetectedLanHost() throws Exception {
        when(lanHostDetector.detectHost()).thenReturn("192.168.1.42");

        mockMvc.perform(get("/api/public-host"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("192.168.1.42"))
                .andExpect(jsonPath("$.port").isNumber())
                .andExpect(jsonPath("$.scheme").value("http"));
    }

    @Test
    @WithMockUser
    void getPublicHost_returnsConfiguredOverride() throws Exception {
        when(lanHostDetector.detectHost()).thenReturn("10.0.0.99");

        mockMvc.perform(get("/api/public-host"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("10.0.0.99"));
    }

    @Test
    @WithMockUser
    void getPublicHost_neverReturnsLoopbackOrigin() throws Exception {
        // Simulates: auto-detection finds a site-local address (not loopback)
        when(lanHostDetector.detectHost()).thenReturn("192.168.50.100");

        mockMvc.perform(get("/api/public-host"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value(org.hamcrest.Matchers.not("127.0.0.1")))
                .andExpect(jsonPath("$.host").value(org.hamcrest.Matchers.not("localhost")));
    }

    @Test
    @WithMockUser
    void getPublicHost_returnsSchemeHttp() throws Exception {
        when(lanHostDetector.detectHost()).thenReturn("192.168.1.1");

        mockMvc.perform(get("/api/public-host"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme").value("http"));
    }
}
