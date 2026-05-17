// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceService;
import de.vvwt.tm.tournament.exceptions.DeviceLimitExceededException;
import de.vvwt.tm.tournament.exceptions.DevicePinLockedException;
import de.vvwt.tm.tournament.exceptions.DisplayDeviceLimitExceededException;
import de.vvwt.tm.tournament.exceptions.PinMismatchException;
import de.vvwt.tm.tournament.exceptions.PinMissingForTabletException;
import de.vvwt.tm.tournament.exceptions.RenameNotSupportedForDisplayException;
import de.vvwt.tm.tournament.exceptions.UnexpectedPinForDisplayException;
import de.vvwt.tm.tournament.internal.dto.DeviceAssignRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceConfigureRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceLimitErrorResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceRenameRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceStatusResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceSummaryResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Device operations — primary-adapter-isolation target (DEC-40 Clause A).
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament.DeviceController} to {@code
 * de.vvwt.tm.web} per DEC-40 Clause D (Q-1b whole-class relocation, DEC-22 §refactor-clause). URL
 * mappings and JSON wire format preserved byte-equivalent (C-14). {@code @Qualifier} preserved
 * verbatim (C-12). Normalized to {@code /api/devices} at E21S13 atomic cutover (the transitional
 * {@code /api/tm/devices} prefix has been removed — the legacy controller it conflicted with is now
 * deleted).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/devices/register — register a new device (SCORING_TABLET or DISPLAY)
 *   <li>GET /api/devices/status — get device status by token
 *   <li>GET /api/devices/list — list all devices for the current tenant
 *   <li>PUT /api/devices/{id}/assign — assign device to field (with PIN for SCORING_TABLET)
 *   <li>POST /api/devices/{id}/pin-lock/reset — admin unlock after PIN-lock (E49S01 AC6)
 *   <li>PUT /api/devices/{id}/rename — rename SCORING_TABLET device (E49S01 AC11)
 * </ul>
 *
 * <h2>Device limit enforcement</h2>
 *
 * <p>POST /register returns HTTP 409 Conflict with a {@link DeviceLimitErrorResponse} body when the
 * per-tenant device cap is exceeded ({@link DeviceLimitExceededException}).
 *
 * @see DeviceService
 * @see de.vvwt.tm.web.DeviceAdminController
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E22S07">E22S07 — Relocate DeviceController to de.vvwt.tm.web</a>
 * @see <a href="E49S01">E49S01 — PIN out-of-band + inline-row assignment</a>
 */
@RestController("tmDeviceController")
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(@Qualifier("tmDeviceService") DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    // -------------------------------------------------------------------------
    // POST /api/tm/devices/register
    // -------------------------------------------------------------------------

    /**
     * Registers a new device for the current tenant.
     *
     * <p>Returns 201 Created with the device token and PIN (SCORING_TABLET) or no PIN (DISPLAY).
     * Returns 409 Conflict with {@link DeviceLimitErrorResponse} body if the device limit is
     * exceeded.
     *
     * @param request the register request (deviceType, nullable — defaults to SCORING_TABLET)
     * @return 201 with {@link DeviceRegisterResponse}
     */
    @PostMapping(value = "/register", produces = "application/json")
    public ResponseEntity<DeviceRegisterResponse> register(
            @RequestBody(required = false) DeviceRegisterRequest request) {
        String deviceType =
                (request != null && request.deviceType() != null)
                        ? request.deviceType()
                        : Device.TYPE_SCORING_TABLET;
        Device device = deviceService.register(deviceType);
        return ResponseEntity.status(HttpStatus.CREATED).body(DeviceRegisterResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // GET /api/tm/devices/status
    // -------------------------------------------------------------------------

    /**
     * Returns the current status of the device identified by the given token.
     *
     * @param token the device token
     * @return 200 OK with {@link DeviceStatusResponse}
     */
    @GetMapping(value = "/status", produces = "application/json")
    public ResponseEntity<DeviceStatusResponse> getStatus(@RequestParam("token") String token) {
        Device device = deviceService.getDeviceByToken(token);
        return ResponseEntity.ok(DeviceStatusResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // GET /api/tm/devices/list
    // -------------------------------------------------------------------------

    /**
     * Returns all devices for the current tenant.
     *
     * @return 200 OK with a JSON array of {@link DeviceSummaryResponse}
     */
    @GetMapping(value = "/list", produces = "application/json")
    public ResponseEntity<List<DeviceSummaryResponse>> listDevices() {
        List<DeviceSummaryResponse> responses =
                deviceService.listDevices().stream().map(DeviceSummaryResponse::from).toList();
        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // PUT /api/devices/{id}/assign (E49S01 — PIN out-of-band)
    // -------------------------------------------------------------------------

    /**
     * Assigns a device to a court field.
     *
     * <p>For SCORING_TABLET: requires {@code pin} in the request body matching the device's stored
     * PIN (E49S01 AC5). Returns 422 if PIN absent, 403 if PIN wrong, 423 if device is PIN-locked
     * (N=10 failures).
     *
     * <p>For DISPLAY: {@code pin} must be absent. Returns 422 if PIN supplied.
     *
     * <p>Returns 409 Conflict if another device is already assigned to the same field. Returns 404
     * if the device does not exist.
     *
     * @param deviceId the device UUID
     * @param request the assign request (fieldNumber, pin)
     * @return 200 OK with {@link DeviceSummaryResponse}
     */
    @PutMapping(
            value = "/{deviceId}/assign",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> assignDevice(
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody DeviceAssignRequest request) {
        Device device = deviceService.assignDevice(deviceId, request.fieldNumber(), request.pin());
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // POST /api/devices/{id}/pin-lock/reset (E49S01 AC6)
    // -------------------------------------------------------------------------

    /**
     * Resets the PIN fail-counter for the given device to 0, unlocking it.
     *
     * <p>Admin-only endpoint. Returns 204 No Content on success, 404 if device not found.
     *
     * @param deviceId the device UUID
     * @return 204 No Content
     */
    @PostMapping(value = "/{deviceId}/pin-lock/reset")
    public ResponseEntity<Void> resetPinLock(@PathVariable("deviceId") UUID deviceId) {
        deviceService.resetPinLockCounter(deviceId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // PUT /api/devices/{id}/rename (E49S01 AC11)
    // -------------------------------------------------------------------------

    /**
     * Renames a SCORING_TABLET device.
     *
     * <p>Admin-only endpoint. Returns 422 if device is DISPLAY. Returns 404 if device not found.
     *
     * @param deviceId the device UUID
     * @param request the rename request (newName)
     * @return 200 OK with {@link DeviceSummaryResponse}
     */
    @PutMapping(
            value = "/{deviceId}/rename",
            consumes = "application/json",
            produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> renameDevice(
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody DeviceRenameRequest request) {
        Device device = deviceService.renameDevice(deviceId, request.newName());
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // PUT /api/devices/{id}/unassign
    // -------------------------------------------------------------------------

    /**
     * Clears the field assignment of a device, transitioning status back to REGISTERED.
     *
     * <p>Requires admin authentication. Idempotent. Returns 404 if device not found.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.infrastructure.web.DeviceController}
     * during E21S13 cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @param deviceId the device UUID
     * @return 200 OK with {@link DeviceSummaryResponse}
     */
    @PutMapping(value = "/{deviceId}/unassign", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> unassignDevice(
            @PathVariable("deviceId") UUID deviceId) {
        Device device = deviceService.unassignDevice(deviceId);
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // PUT /api/devices/{id}/configure
    // -------------------------------------------------------------------------

    /**
     * Configures a DISPLAY device with a human-readable name and JSON configuration.
     *
     * <p>Requires admin authentication. Returns 200 OK with the updated device summary. Returns 400
     * Bad Request if the device is not a DISPLAY device (SCORING_TABLET).
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.infrastructure.web.DeviceController}
     * during E21S13 cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @param deviceId the device UUID
     * @param request the configure request (deviceName, configuration)
     * @return 200 OK with {@link DeviceSummaryResponse}
     */
    @PutMapping(value = "/{deviceId}/configure", produces = "application/json")
    public ResponseEntity<DeviceSummaryResponse> configure(
            @PathVariable("deviceId") UUID deviceId,
            @Valid @RequestBody DeviceConfigureRequest request) {
        Device device =
                deviceService.configure(deviceId, request.deviceName(), request.configuration());
        return ResponseEntity.ok(DeviceSummaryResponse.from(device));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/devices/{id}
    // -------------------------------------------------------------------------

    /**
     * Deletes the device with the given id from the current tenant.
     *
     * <p>Requires admin authentication. Returns 204 No Content on success. Returns 404 Not Found if
     * the device does not exist.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.infrastructure.web.DeviceController}
     * during E21S13 cutover (DEC-22 refactor phase — behavior-preserving).
     *
     * @param deviceId the device UUID
     * @return 204 No Content
     */
    @DeleteMapping(value = "/{deviceId}", produces = "application/json")
    public ResponseEntity<Void> deleteDevice(@PathVariable("deviceId") UUID deviceId) {
        deviceService.deleteDevice(deviceId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // DELETE /api/devices (clear all)
    // -------------------------------------------------------------------------

    /**
     * Deletes all devices for the current tenant.
     *
     * <p>Requires admin authentication. Returns 204 No Content.
     *
     * <p>Migrated from the deleted legacy {@code de.vvwt.tm.infrastructure.web.DeviceController}
     * during E21S13 cutover (DEC-22 refactor phase — behavior-preserving). Used by the admin UI
     * "clear all devices" action (E06S05-AC7).
     *
     * @return 204 No Content
     */
    @DeleteMapping
    public ResponseEntity<Void> clearAllDevices() {
        deviceService.clearAllDevices();
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Exception handlers
    // -------------------------------------------------------------------------

    /**
     * Maps {@link DeviceLimitExceededException} to HTTP 409 Conflict.
     *
     * @param ex the exception
     * @return 409 with {@link DeviceLimitErrorResponse}
     */
    @ExceptionHandler(DeviceLimitExceededException.class)
    public ResponseEntity<DeviceLimitErrorResponse> handleDeviceLimitExceeded(
            DeviceLimitExceededException ex) {
        DeviceLimitErrorResponse body =
                new DeviceLimitErrorResponse(
                        "DEVICE_LIMIT_EXCEEDED", ex.getConfiguredLimit(), ex.getCurrentCount());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Maps {@link DisplayDeviceLimitExceededException} to HTTP 429 Too Many Requests.
     *
     * <p>Migrated from the deleted legacy display-device limit handler during E21S13 cutover
     * (DEC-22 refactor phase — behavior-preserving).
     *
     * @return 429 with {@link DeviceLimitErrorResponse}
     */
    @ExceptionHandler(DisplayDeviceLimitExceededException.class)
    public ResponseEntity<DeviceLimitErrorResponse> handleDisplayDeviceLimitExceeded(
            DisplayDeviceLimitExceededException ex) {
        DeviceLimitErrorResponse body =
                new DeviceLimitErrorResponse(
                        "DEVICE_LIMIT_EXCEEDED",
                        ex.getMaxCount(),
                        ex.getCurrentCount(),
                        "error.device.limitExceeded");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    /**
     * Maps {@link PinMissingForTabletException} to HTTP 422 Unprocessable Entity (E49S01 AC5).
     *
     * @return 422 with RFC-7807 problem body
     */
    @ExceptionHandler(PinMissingForTabletException.class)
    public ResponseEntity<java.util.Map<String, Object>> handlePinMissingForTablet(
            PinMissingForTabletException ex) {
        return ResponseEntity.status(422)
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/problem+json"))
                .body(
                        java.util.Map.of(
                                "type",
                                "urn:vvwt:tm:device:pin-missing",
                                "title",
                                "PIN required",
                                "status",
                                422,
                                "detail",
                                ex.getMessage()));
    }

    /**
     * Maps {@link UnexpectedPinForDisplayException} to HTTP 422 Unprocessable Entity (E49S01 AC5).
     *
     * @return 422 with RFC-7807 problem body
     */
    @ExceptionHandler(UnexpectedPinForDisplayException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleUnexpectedPinForDisplay(
            UnexpectedPinForDisplayException ex) {
        return ResponseEntity.status(422)
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/problem+json"))
                .body(
                        java.util.Map.of(
                                "type",
                                "urn:vvwt:tm:device:unexpected-pin",
                                "title",
                                "PIN not expected for DISPLAY",
                                "status",
                                422,
                                "detail",
                                ex.getMessage()));
    }

    /**
     * Maps {@link PinMismatchException} to HTTP 403 Forbidden (E49S01 AC5).
     *
     * @return 403 with RFC-7807 problem body
     */
    @ExceptionHandler(PinMismatchException.class)
    public ResponseEntity<java.util.Map<String, Object>> handlePinMismatch(
            PinMismatchException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/problem+json"))
                .body(
                        java.util.Map.of(
                                "type",
                                "urn:vvwt:tm:device:pin-mismatch",
                                "title",
                                "PIN mismatch",
                                "status",
                                403,
                                "detail",
                                ex.getMessage()));
    }

    /**
     * Maps {@link DevicePinLockedException} to HTTP 423 Locked (E49S01 AC6).
     *
     * @return 423 with RFC-7807 problem body
     */
    @ExceptionHandler(DevicePinLockedException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleDevicePinLocked(
            DevicePinLockedException ex) {
        return ResponseEntity.status(423)
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/problem+json"))
                .body(
                        java.util.Map.of(
                                "type", "urn:vvwt:tm:device:pin-locked",
                                "title", "Device PIN-locked",
                                "status", 423,
                                "detail", "Device is locked after too many failed PIN attempts"));
    }

    /**
     * Maps {@link RenameNotSupportedForDisplayException} to HTTP 422 Unprocessable Entity (E49S01
     * AC11).
     *
     * @return 422 with RFC-7807 problem body
     */
    @ExceptionHandler(RenameNotSupportedForDisplayException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleRenameNotSupportedForDisplay(
            RenameNotSupportedForDisplayException ex) {
        return ResponseEntity.status(422)
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/problem+json"))
                .body(
                        java.util.Map.of(
                                "type",
                                "urn:vvwt:tm:device:rename-not-supported",
                                "title",
                                "Rename not supported for DISPLAY",
                                "status",
                                422,
                                "detail",
                                ex.getMessage()));
    }
}
