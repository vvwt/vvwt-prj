package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.DeviceService;
import de.vvwt.tm.infrastructure.web.dto.DeviceAssignRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceRegisterResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceStatusResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceSummaryResponse;
import de.vvwt.tm.tenant.DefaultTenantProvider;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for device registration and management (E06S03, E06S05).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST   /api/devices/register — register a new tablet (E06S03-AC2); public (no auth required)</li>
 *   <li>GET    /api/devices/status   — tablet polls its own status (E06S03-AC3); public (device token auth)</li>
 *   <li>GET    /api/devices/list     — admin lists all devices (E06S05-AC1); requires admin auth</li>
 *   <li>GET    /api/devices          — admin finds device by PIN (E06S03-AC4); requires admin auth</li>
 *   <li>PUT    /api/devices/{id}/assign — admin assigns device to field (E06S03-AC5); requires admin auth</li>
 *   <li>PUT    /api/devices/{id}/unassign — admin unassigns device (E06S03-AC6); requires admin auth</li>
 *   <li>DELETE /api/devices          — admin clears all devices (E06S05-AC7); requires admin auth</li>
 * </ul>
 *
 * <h2>Authentication (AC2, AC8)</h2>
 * <p>Register and status endpoints are public (tablets don't log in). The device token
 * acts as the tablet's credential for status polling. Assign/unassign/find-by-PIN endpoints
 * require admin authentication (HTTP Basic, see {@link de.vvwt.tm.auth.SecurityConfig}).
 *
 * <h2>Tenant and location scope (DEC-5, DEC-17)</h2>
 * <p>In V1 default-tenant-LAN mode, all requests resolve to the default tenant via the
 * {@link de.vvwt.tm.domain.repo.DefaultTenantContextResolver} interceptor.
 * The location is resolved via {@link DefaultTenantProvider#getDefaultLocationId()}.
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story E06S03</a>
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final DefaultTenantProvider defaultTenantProvider;

    public DeviceController(DeviceService deviceService,
                            DefaultTenantProvider defaultTenantProvider) {
        this.deviceService = deviceService;
        this.defaultTenantProvider = defaultTenantProvider;
    }

    // -------------------------------------------------------------------------
    // AC2 — POST /api/devices/register
    // -------------------------------------------------------------------------

    /**
     * Registers a new scoring tablet device and returns its device token and PIN (AC2).
     *
     * <p>No authentication required. Tenant and location are resolved from the request
     * context (default-tenant-LAN per DEC-17).
     *
     * @return 201 Created with {@code { deviceToken, pin }}
     */
    @PostMapping("/register")
    public ResponseEntity<DeviceRegisterResponse> registerDevice() {
        UUID tenantId = defaultTenantProvider.getDefaultTenantId();
        UUID locationId = defaultTenantProvider.getDefaultLocationId();

        Device device = deviceService.registerDevice(tenantId, locationId);
        return ResponseEntity.status(201).body(DeviceRegisterResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/devices/status?token={deviceToken}
    // -------------------------------------------------------------------------

    /**
     * Returns the device's current status and assigned field number (AC3).
     *
     * <p>Used by the tablet to poll for assignment after registration.
     * No admin auth required — the device token acts as the tablet's credential.
     * Returns 404 if the token is invalid (AC3).
     *
     * @param token the device token
     * @return 200 OK with {@code { status, assignedField }} (assignedField absent when null)
     */
    @GetMapping("/status")
    public ResponseEntity<DeviceStatusResponse> getDeviceStatus(
            @RequestParam("token") String token) {
        Device device = deviceService.getDeviceByToken(token);
        return ResponseEntity.ok(DeviceStatusResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // AC4 — GET /api/devices?pin={pin}
    // -------------------------------------------------------------------------

    /**
     * Returns the device matching the given PIN for the current tenant (AC4).
     *
     * <p>Used by the admin UI (E06S05) to look up a registered tablet by its PIN.
     * Requires admin authentication (HTTP Basic).
     *
     * @param pin the numeric PIN displayed on the tablet
     * @return 200 OK with device summary, or 404 if no device matches
     */
    @GetMapping
    public ResponseEntity<DeviceSummaryResponse> findByPin(@RequestParam("pin") String pin) {
        Device device = deviceService.getDeviceByPin(pin);
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // AC5 — PUT /api/devices/{id}/assign
    // -------------------------------------------------------------------------

    /**
     * Assigns a device to a court field (AC5).
     *
     * <p>Requires admin authentication. Returns:
     * <ul>
     *   <li>200 OK on success</li>
     *   <li>409 Conflict if another device is already assigned to the same field</li>
     *   <li>400 Bad Request if the field number exceeds tournament capacity</li>
     *   <li>404 Not Found if the device does not exist</li>
     * </ul>
     *
     * @param id      the device UUID
     * @param request {@code { fieldNumber }}
     * @return 200 OK with updated device summary
     */
    @PutMapping(value = "/{id}/assign", consumes = "application/json", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> assignDevice(
            @PathVariable("id") UUID id,
            @Valid @RequestBody DeviceAssignRequest request) {
        Device device = deviceService.assignDevice(id, request.fieldNumber());
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // AC6 — PUT /api/devices/{id}/unassign
    // -------------------------------------------------------------------------

    /**
     * Clears the field assignment of a device, transitioning status back to REGISTERED (AC6).
     *
     * <p>Requires admin authentication. Idempotent: unassigning an already-unassigned
     * device returns 200 without error.
     *
     * @param id the device UUID
     * @return 200 OK with updated device summary
     */
    @PutMapping(value = "/{id}/unassign", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> unassignDevice(@PathVariable("id") UUID id) {
        Device device = deviceService.unassignDevice(id);
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // E06S05-AC1 — GET /api/devices/list
    // -------------------------------------------------------------------------

    /**
     * Returns all devices for the current tenant and location (E06S05-AC1).
     *
     * <p>Used by the admin device management view to show the full device list.
     * Each entry includes PIN, device type, assigned field, status, and last seen timestamp.
     *
     * <p>Requires admin authentication.
     *
     * @return 200 OK with list of device summaries (may be empty)
     */
    @GetMapping(value = "/list", produces = "application/json")
    public ResponseEntity<List<DeviceSummaryResponse>> listDevices() {
        List<DeviceSummaryResponse> devices = deviceService.listAllDevices()
                .stream()
                .map(DeviceSummaryResponse::from)
                .toList();
        return ResponseEntity.ok(devices);
    }

    // -------------------------------------------------------------------------
    // E06S05-AC7 — DELETE /api/devices
    // -------------------------------------------------------------------------

    /**
     * Removes all registered devices for the current tenant (E06S05-AC7).
     *
     * <p>This is the post-tournament teardown action. All devices are deleted from the
     * active tenant. Requires admin authentication. Returns 204 No Content.
     *
     * @return 204 No Content
     */
    @DeleteMapping
    public ResponseEntity<Void> clearAllDevices() {
        deviceService.clearAllDevices();
        return ResponseEntity.noContent().build();
    }
}
