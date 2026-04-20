package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.internal.DeviceService;
import de.vvwt.tm.tournament.internal.dto.DeviceSummaryResponse;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin REST controller for device location management — reconstruction-in-place target
 * (DEC-21/DEC-22, DEC-24).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.DeviceAdminController} but lives at the Modulith
 * target package {@code de.vvwt.tm.tournament} (public API surface per DEC-21 §Module layout). Uses
 * {@code /api/tm/admin/devices} mapping to avoid {@code RequestMappingHandlerMapping} ambiguity
 * with the legacy {@code /api/admin/devices} controller during reconstruction-in-place.
 *
 * <p>All endpoints require ADMIN role (DEC-24: location assignment is an admin-only step).
 * Method-level security is enforced via {@code @PreAuthorize("hasRole('ADMIN')")} (requires
 * {@code @EnableMethodSecurity} in {@code AuthConfiguration}).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/tm/admin/devices/{deviceId}/location/{locationId} — assign device to a location
 *       (DEC-24)
 *   <li>DELETE /api/tm/admin/devices/{deviceId}/location — remove device location assignment
 *       (DEC-24)
 * </ul>
 *
 * @see DeviceService
 * @see DeviceController
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-24">DEC-24 — device location nullable; admin role for assignment</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 */
@RestController("tmDeviceAdminController")
@RequestMapping("/api/tm/admin/devices")
@PreAuthorize("hasRole('ADMIN')")
public class DeviceAdminController {

    private final DeviceService deviceService;

    public DeviceAdminController(@Qualifier("tmDeviceService") DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    // -------------------------------------------------------------------------
    // POST /api/tm/admin/devices/{deviceId}/location/{locationId}
    // -------------------------------------------------------------------------

    /**
     * Assigns a device to a location (DEC-24 post-registration admin step).
     *
     * <p>Requires ADMIN role. Returns 200 OK with updated device summary.
     *
     * @param deviceId the device UUID
     * @param locationId the location UUID to assign
     * @return 200 OK with {@link DeviceSummaryResponse}
     */
    @PostMapping(value = "/{deviceId}/location/{locationId}", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> assignLocation(
            @PathVariable("deviceId") UUID deviceId, @PathVariable("locationId") UUID locationId) {
        Device device = deviceService.assignLocation(deviceId, locationId);
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/tm/admin/devices/{deviceId}/location
    // -------------------------------------------------------------------------

    /**
     * Removes the location assignment from a device (DEC-24).
     *
     * <p>Sets {@code location_id = null}. Idempotent: unassigning an already-unassigned device
     * returns 200 without error. Requires ADMIN role.
     *
     * @param deviceId the device UUID
     * @return 200 OK with {@link DeviceSummaryResponse}
     */
    @DeleteMapping(value = "/{deviceId}/location", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> unassignLocation(
            @PathVariable("deviceId") UUID deviceId) {
        Device device = deviceService.unassignLocation(deviceId);
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }
}
