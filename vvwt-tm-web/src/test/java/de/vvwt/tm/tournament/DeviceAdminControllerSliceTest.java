package de.vvwt.tm.tournament;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice test for {@link DeviceAdminController} (E21S06, AC-REST-SLICE-DeviceAdminController).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link DeviceAdminController} at {@code
 * de.vvwt.tm.tournament.DeviceAdminController} did not exist at commit time — satisfying the DEC-22
 * Iron Law.
 *
 * <h2>Coverage (C-13 Hybrid Split methodology)</h2>
 *
 * <ul>
 *   <li>POST /api/admin/devices/{deviceId}/location/{locationId} → 200 (admin role, DEC-24)
 *   <li>DELETE /api/admin/devices/{deviceId}/location → 200 (admin role, DEC-24)
 *   <li>Anonymous POST → 401 (security gate)
 *   <li>USER role POST → 403 (DEC-24: admin role required)
 * </ul>
 *
 * @see DeviceAdminController
 * @see DeviceService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-24">DEC-24 — device location nullable; admin role required for assignment</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 */
@WebMvcTest(DeviceAdminController.class)
@Import(DeviceAdminControllerSliceTest.MethodSecurityConfig.class)
@DisplayName("DeviceAdminController slice tests — E21S06 AC-REST-SLICE-DeviceAdminController")
class DeviceAdminControllerSliceTest {

    /** Activates {@code @PreAuthorize} processing in the @WebMvcTest slice (E21S06 DEC-24). */
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    @Autowired private WebApplicationContext context;

    @MockitoBean(name = "tmDeviceService")
    private DeviceService deviceService;

    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final String BASE_URL = "/api/admin/devices";

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
    @DisplayName("Anonymous POST /location returns 401")
    void anonymousPostLocation_returns401() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        mockMvc.perform(
                        post(BASE_URL + "/" + deviceId + "/location/" + locationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Security: USER role → 403 (DEC-24: admin role required)
    // =========================================================================

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("USER role POST /location returns 403")
    void userRolePostLocation_returns403() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        mockMvc.perform(
                        post(BASE_URL + "/" + deviceId + "/location/" + locationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // POST /api/admin/devices/{deviceId}/location/{locationId} — assign
    // =========================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN POST /location returns 200 with device summary")
    void adminPostLocation_returns200() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        Device device = buildDevice(deviceId, Device.TYPE_SCORING_TABLET, "tok-admin", "3456");
        device.setLocationId(locationId);
        device.setStatus(Device.STATUS_ASSIGNED);
        when(deviceService.assignLocation(deviceId, locationId)).thenReturn(device);

        mockMvc.perform(
                        post(BASE_URL + "/" + deviceId + "/location/" + locationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deviceId.toString()))
                .andExpect(jsonPath("$.status").value("ASSIGNED"));
    }

    // =========================================================================
    // DELETE /api/admin/devices/{deviceId}/location — unassign
    // =========================================================================

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN DELETE /location returns 200 with device summary (locationId null)")
    void adminDeleteLocation_returns200() throws Exception {
        UUID deviceId = UUID.randomUUID();

        Device device = buildDevice(deviceId, Device.TYPE_SCORING_TABLET, "tok-unassign", "7890");
        device.setLocationId(null);
        device.setStatus(Device.STATUS_REGISTERED);
        when(deviceService.unassignLocation(deviceId)).thenReturn(device);

        mockMvc.perform(
                        delete(BASE_URL + "/" + deviceId + "/location")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deviceId.toString()))
                .andExpect(jsonPath("$.status").value("REGISTERED"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Device buildDevice(UUID id, String type, String token, String pin) {
        Device d = new Device();
        d.setId(id);
        d.setDeviceToken(token);
        d.setPin(pin);
        d.setDeviceType(type);
        d.setStatus(Device.STATUS_REGISTERED);
        return d;
    }
}
