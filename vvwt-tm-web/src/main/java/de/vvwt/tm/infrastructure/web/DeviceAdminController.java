package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.DeviceService;
import de.vvwt.tm.infrastructure.web.dto.DeviceSummaryResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for device location management (E14S08).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/admin/devices/{deviceId}/location/{locationId} — assign device to a location
 *       (AC4, AC5)
 *   <li>DELETE /api/admin/devices/{deviceId}/location — remove device location assignment (AC5)
 * </ul>
 *
 * <h2>Authentication</h2>
 *
 * <p>All endpoints require admin HTTP Basic auth. The {@code /api/admin/**} path is covered by the
 * existing {@link de.vvwt.tm.auth.SecurityConfig} rule that requires authentication for all {@code
 * /api/**} paths.
 *
 * <h2>Tenant scope (DEC-5, DEC-24)</h2>
 *
 * <p>Location assignment is validated against the active tenant (AC8 — cross-tenant guard): {@code
 * locationId} must belong to the device's tenant. Validation is delegated to {@link
 * DeviceService#assignDeviceLocation(UUID, UUID)}.
 *
 * @see DeviceService#assignDeviceLocation(UUID, UUID)
 * @see DeviceService#unassignDeviceLocation(UUID)
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E14S08.story.md">Story
 *     E14S08</a>
 */
@RestController
@RequestMapping("/api/admin/devices")
public class DeviceAdminController {

    private final DeviceService deviceService;

    public DeviceAdminController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    // -------------------------------------------------------------------------
    // E14S08 AC4, AC5 — POST /api/admin/devices/{deviceId}/location/{locationId}
    // -------------------------------------------------------------------------

    /**
     * Assigns a device to a location (E14S08 AC4, AC5).
     *
     * <p>Requires admin authentication. Idempotent: re-assigning the same location returns 200.
     *
     * <p>Returns:
     *
     * <ul>
     *   <li>200 OK with updated device summary on success
     *   <li>400 Bad Request if {@code locationId} does not belong to the active tenant (AC8)
     *   <li>404 Not Found if {@code deviceId} does not exist for the active tenant
     * </ul>
     *
     * @param deviceId the device UUID
     * @param locationId the location UUID to assign to
     * @return 200 OK with updated device summary
     */
    @PostMapping(value = "/{deviceId}/location/{locationId}", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> assignLocation(
            @PathVariable("deviceId") UUID deviceId, @PathVariable("locationId") UUID locationId) {
        Device device = deviceService.assignDeviceLocation(deviceId, locationId);
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // E14S08 AC5 — DELETE /api/admin/devices/{deviceId}/location
    // -------------------------------------------------------------------------

    /**
     * Removes the location assignment from a device (E14S08 AC5 — unassign step).
     *
     * <p>Sets {@code location_id = NULL}. Idempotent: unassigning an already-unassigned device
     * returns 200 without error.
     *
     * <p>Requires admin authentication.
     *
     * <p>Returns:
     *
     * <ul>
     *   <li>200 OK with updated device summary on success
     *   <li>404 Not Found if {@code deviceId} does not exist for the active tenant
     * </ul>
     *
     * @param deviceId the device UUID
     * @return 200 OK with updated device summary
     */
    @DeleteMapping(value = "/{deviceId}/location", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> unassignLocation(
            @PathVariable("deviceId") UUID deviceId) {
        Device device = deviceService.unassignDeviceLocation(deviceId);
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }
}
