package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.config.DeviceLimitConfig;
import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.DeviceService;
import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.infrastructure.web.dto.DeviceAssignRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceConfigureRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceRegisterRequest;
import de.vvwt.tm.infrastructure.web.dto.DeviceRegisterResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceStatusResponse;
import de.vvwt.tm.infrastructure.web.dto.DeviceSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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

/**
 * REST controller for device registration and management (E06S03, E06S05, E07S02, E14S08).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/devices/register — register a scoring tablet or display device (E06S03 AC2,
 *       E07S02 AC1); public
 *   <li>GET /api/devices/status — device polls its own status + config (E06S03 AC3, E07S02 AC3);
 *       public
 *   <li>GET /api/devices/list — admin lists all devices (E06S05-AC1); requires admin auth
 *   <li>GET /api/devices/display-limit — returns max display device count (E07S03 AC5); requires
 *       admin auth
 *   <li>GET /api/devices — admin finds device by PIN (E06S03 AC4); requires admin auth
 *   <li>PUT /api/devices/{id}/assign — admin assigns device to field (E06S03 AC5); requires admin
 *       auth
 *   <li>PUT /api/devices/{id}/unassign — admin unassigns device (E06S03 AC6); requires admin auth
 *   <li>PUT /api/devices/{id}/configure — admin configures display device (E07S02 AC4); requires
 *       admin auth
 *   <li>DELETE /api/devices/{id} — admin deletes a device (E07S02 AC5, E07S03 AC4); requires admin
 *       auth
 * </ul>
 *
 * <h2>Authentication (E07S02 AC10)</h2>
 *
 * <p>Register and status endpoints are public (devices don't log in). Configure and delete
 * endpoints require admin authentication (HTTP Basic, see {@link de.vvwt.tm.auth.SecurityConfig}).
 *
 * <h2>Tenant scope (DEC-5, DEC-24)</h2>
 *
 * <p>The tenant context is resolved per HTTP request by {@link
 * de.vvwt.tm.domain.repo.DefaultTenantContextResolver}. Device registration no longer requires a
 * location at creation time (DEC-24 device-model carve-out): {@code location_id} is persisted as
 * {@code NULL} and assigned later via {@link DeviceAdminController} (AC9: {@code
 * DefaultTenantProvider} is NOT used by this class).
 *
 * @see DeviceAdminController
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S03.story.md">Story
 *     E06S03</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S02.story.md">Story
 *     E07S02</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E14S08.story.md">Story
 *     E14S08</a>
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final TenantContext tenantContext;
    private final DeviceLimitConfig deviceLimitConfig;

    public DeviceController(
            DeviceService deviceService,
            TenantContext tenantContext,
            DeviceLimitConfig deviceLimitConfig) {
        this.deviceService = deviceService;
        this.tenantContext = tenantContext;
        this.deviceLimitConfig = deviceLimitConfig;
    }

    // -------------------------------------------------------------------------
    // E06S03 AC2 + E07S02 AC1, AC6 + E14S08 AC3 — POST /api/devices/register
    // -------------------------------------------------------------------------

    /**
     * Registers a new device (scoring tablet or display device) and returns its device token.
     *
     * <p>No authentication required. Tenant is resolved from the active request context. Location
     * is NOT required at registration time — per DEC-24, devices are assigned to a location via a
     * separate admin action ({@link DeviceAdminController}).
     *
     * <ul>
     *   <li>No body or {@code deviceType=SCORING_TABLET}: creates a scoring tablet with a PIN (AC6
     *       backward compat)
     *   <li>{@code deviceType=DISPLAY}: creates a display device with no PIN (E07S02 AC1)
     * </ul>
     *
     * <p>Returns 429 if the DISPLAY device limit is reached (E07S02 AC2).
     *
     * @param registerRequest optional request body; null body defaults to SCORING_TABLET
     * @return 201 Created with {@code { deviceToken }} (and {@code pin} for scoring tablets)
     */
    @PostMapping("/register")
    public ResponseEntity<DeviceRegisterResponse> registerDevice(
            @RequestBody(required = false) DeviceRegisterRequest registerRequest) {
        UUID tenantId = tenantContext.getTenantId();

        String deviceType =
                (registerRequest != null && registerRequest.getDeviceType() != null)
                        ? registerRequest.getDeviceType()
                        : Device.TYPE_SCORING_TABLET;

        // E14S08 AC3: location_id is null at registration time (DEC-24 device-model carve-out)
        Device device = deviceService.registerDevice(tenantId, null, deviceType);
        return ResponseEntity.status(201).body(DeviceRegisterResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/devices/status?token={deviceToken}
    // -------------------------------------------------------------------------

    /**
     * Returns the device's current status and assigned field number (AC3).
     *
     * <p>Used by the tablet to poll for assignment after registration. No admin auth required — the
     * device token acts as the tablet's credential. Returns 404 if the token is invalid (AC3).
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
    // E06S05-AC1 — GET /api/devices/list
    // -------------------------------------------------------------------------

    /**
     * Returns all devices for the current tenant and location (E06S05-AC1).
     *
     * <p>Used by the admin device management view to show the full device list. Each entry includes
     * PIN, device type, assigned field, status, and last seen timestamp. Extended in E07S03 to
     * include DISPLAY devices.
     *
     * <p>Requires admin authentication.
     *
     * @return 200 OK with list of device summaries (may be empty)
     */
    @GetMapping(value = "/list", produces = "application/json")
    public ResponseEntity<List<DeviceSummaryResponse>> listDevices() {
        List<DeviceSummaryResponse> devices =
                deviceService.listAllDevices().stream().map(DeviceSummaryResponse::from).toList();
        return ResponseEntity.ok(devices);
    }

    // -------------------------------------------------------------------------
    // E07S03 AC5 — GET /api/devices/display-limit
    // -------------------------------------------------------------------------

    /**
     * Returns the configured maximum number of DISPLAY devices per tenant+location (E07S03 AC5).
     *
     * <p>Used by the admin UI to show the device limit indicator. Requires admin authentication.
     *
     * @return 200 OK with {@code { maxDisplayCount: int }}
     */
    @GetMapping(value = "/display-limit", produces = "application/json")
    public ResponseEntity<java.util.Map<String, Integer>> getDisplayLimit() {
        return ResponseEntity.ok(
                java.util.Map.of("maxDisplayCount", deviceLimitConfig.getMaxDisplayCount()));
    }

    // -------------------------------------------------------------------------
    // AC4 — GET /api/devices?pin={pin}
    // -------------------------------------------------------------------------

    /**
     * Returns the device matching the given PIN for the current tenant (AC4).
     *
     * <p>Used by the admin UI (E06S05) to look up a registered tablet by its PIN. Requires admin
     * authentication (HTTP Basic).
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
     *
     * <ul>
     *   <li>200 OK on success
     *   <li>409 Conflict if another device is already assigned to the same field
     *   <li>400 Bad Request if the field number exceeds tournament capacity
     *   <li>404 Not Found if the device does not exist
     * </ul>
     *
     * @param id the device UUID
     * @param request {@code { fieldNumber }}
     * @return 200 OK with updated device summary
     */
    @PutMapping(
            value = "/{id}/assign",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> assignDevice(
            @PathVariable("id") UUID id, @Valid @RequestBody DeviceAssignRequest request) {
        Device device = deviceService.assignDevice(id, request.fieldNumber());
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // AC6 — PUT /api/devices/{id}/unassign
    // -------------------------------------------------------------------------

    /**
     * Clears the field assignment of a device, transitioning status back to REGISTERED (AC6).
     *
     * <p>Requires admin authentication. Idempotent: unassigning an already-unassigned device
     * returns 200 without error.
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
    // E07S02 AC4 — PUT /api/devices/{id}/configure
    // -------------------------------------------------------------------------

    /**
     * Sets the device name and configuration for a DISPLAY device (E07S02 AC4, E07S03 AC3).
     *
     * <p>Requires admin authentication. Returns:
     *
     * <ul>
     *   <li>200 OK on success
     *   <li>400 Bad Request if the device is not of type DISPLAY
     *   <li>400 Bad Request if {@code deviceName} is blank
     *   <li>404 Not Found if the device does not exist
     * </ul>
     *
     * @param id the device UUID
     * @param request {@code { deviceName, configuration }}
     * @return 200 OK with updated device summary
     */
    @PutMapping(
            value = "/{id}/configure",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> configureDevice(
            @PathVariable("id") UUID id, @Valid @RequestBody DeviceConfigureRequest request) {
        Device device =
                deviceService.configureDevice(id, request.deviceName(), request.configuration());
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // E07S02 AC5 / E07S03 AC4 — DELETE /api/devices/{id}
    // -------------------------------------------------------------------------

    /**
     * Deletes a device (scoring tablet or display device) by its ID (E07S02 AC5, E07S03 AC4).
     *
     * <p>Requires admin authentication. Returns:
     *
     * <ul>
     *   <li>204 No Content on successful deletion
     *   <li>404 Not Found if the device does not exist
     * </ul>
     *
     * @param id the device UUID
     * @return 204 No Content
     */
    @DeleteMapping(value = "/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable("id") UUID id) {
        deviceService.deleteDevice(id);
        return ResponseEntity.noContent().build();
    }

    // E06S05-AC7 — DELETE /api/devices (clear all devices)
    // -------------------------------------------------------------------------

    /**
     * Clears all devices for the active tenant (E06S05-AC7).
     *
     * <p>Requires admin authentication. Returns 204 No Content.
     *
     * @return 204 No Content
     */
    @DeleteMapping
    public ResponseEntity<Void> clearAllDevices() {
        deviceService.clearAllDevices();
        return ResponseEntity.noContent().build();
    }
}
