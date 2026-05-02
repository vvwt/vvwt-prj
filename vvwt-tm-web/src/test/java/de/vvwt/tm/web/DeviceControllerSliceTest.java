package de.vvwt.tm.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceService;
import de.vvwt.tm.tournament.exceptions.DeviceLimitExceededException;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice test for {@link DeviceController} (E21S06, AC-REST-SLICE-DeviceController).
 *
 * <h2>Relocation note (E22S07)</h2>
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament} to {@code de.vvwt.tm.web} per DEC-40
 * Clause D (Q-1b whole-class relocation, DEC-22 §refactor-clause). {@code @WebMvcTest} annotation
 * retained per DEC-38 erratum — slice tests do not load service beans and are unaffected by module
 * boundaries. Package line is the only change.
 *
 * <h2>Coverage (C-13 Hybrid Split methodology)</h2>
 *
 * <ul>
 *   <li>POST /api/devices/register happy path → 201 (SCORING_TABLET)
 *   <li>POST /api/devices/register happy path → 201 (DISPLAY)
 *   <li>POST /api/devices/register DeviceLimit exceeded → 409 DeviceLimitErrorResponse
 *   <li>GET /api/devices/status → 200
 *   <li>GET /api/devices/list → 200 with list
 *   <li>Unauthenticated GET /api/devices/list → 401
 * </ul>
 *
 * @see DeviceController
 * @see DeviceService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate DeviceController to de.vvwt.tm.web</a>
 */
@WebMvcTest(DeviceController.class)
@DisplayName("DeviceController slice tests — E21S06 AC-REST-SLICE-DeviceController")
class DeviceControllerSliceTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean(name = "tmDeviceService")
    private DeviceService deviceService;

    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String BASE_URL = "/api/devices";

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
        when(tenantContext.bind(any())).thenReturn(() -> {});
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
    }

    // =========================================================================
    // Security: unauthenticated requests
    // =========================================================================

    @Test
    @DisplayName("Anonymous GET /list returns 401")
    void anonymousGetList_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL + "/list").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // POST /api/devices/register — register SCORING_TABLET
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("POST /register SCORING_TABLET returns 201")
    void registerScoringTablet_returns201() throws Exception {
        Device device = buildDevice(UUID.randomUUID(), Device.TYPE_SCORING_TABLET, "tok-1", "1234");
        when(deviceService.register(Device.TYPE_SCORING_TABLET)).thenReturn(device);

        DeviceRegisterRequest req = new DeviceRegisterRequest("SCORING_TABLET");
        mockMvc.perform(
                        post(BASE_URL + "/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceToken").value("tok-1"))
                .andExpect(jsonPath("$.pin").value("1234"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /register DISPLAY returns 201 with null pin")
    void registerDisplay_returns201WithNullPin() throws Exception {
        Device device = buildDevice(UUID.randomUUID(), Device.TYPE_DISPLAY, "tok-display", null);
        when(deviceService.register(Device.TYPE_DISPLAY)).thenReturn(device);

        DeviceRegisterRequest req = new DeviceRegisterRequest("DISPLAY");
        mockMvc.perform(
                        post(BASE_URL + "/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceToken").value("tok-display"));
    }

    // =========================================================================
    // POST /api/devices/register — DeviceLimit exceeded → 409
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("POST /register when limit exceeded returns 409 with DeviceLimitErrorResponse")
    void registerWhenLimitExceeded_returns409() throws Exception {
        when(deviceService.register(any())).thenThrow(new DeviceLimitExceededException(5, 5L));

        DeviceRegisterRequest req = new DeviceRegisterRequest("SCORING_TABLET");
        mockMvc.perform(
                        post(BASE_URL + "/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").exists())
                .andExpect(jsonPath("$.configuredLimit").value(5))
                .andExpect(jsonPath("$.currentCount").value(5));
    }

    // =========================================================================
    // GET /api/devices/status
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET /status returns 200 with device status")
    void getStatus_returns200() throws Exception {
        Device device = buildDevice(UUID.randomUUID(), Device.TYPE_SCORING_TABLET, "tok-s", "5678");
        device.setStatus(Device.STATUS_REGISTERED);
        when(deviceService.getDeviceByToken("tok-s")).thenReturn(device);

        mockMvc.perform(get(BASE_URL + "/status").param("token", "tok-s"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    // =========================================================================
    // GET /api/devices/list
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET /list returns 200 with device list")
    void getList_authenticated_returns200() throws Exception {
        Device device = buildDevice(UUID.randomUUID(), Device.TYPE_SCORING_TABLET, "tok-l", "9012");
        when(deviceService.listDevices()).thenReturn(List.of(device));

        mockMvc.perform(get(BASE_URL + "/list").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Device buildDevice(UUID id, String type, String token, String pin) {
        Device d = new Device();
        d.setId(id);
        d.setTenantId(TENANT_ID);
        d.setDeviceToken(token);
        d.setPin(pin);
        d.setDeviceType(type);
        d.setStatus(Device.STATUS_REGISTERED);
        return d;
    }
}
