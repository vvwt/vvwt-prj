package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.internal.DeviceLimitErrorResponse;
import de.vvwt.tm.tournament.internal.DeviceLimitExceededException;
import de.vvwt.tm.tournament.internal.DeviceService;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterRequest;
import de.vvwt.tm.tournament.internal.dto.DeviceRegisterResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceStatusResponse;
import de.vvwt.tm.tournament.internal.dto.DeviceSummaryResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Device operations — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.DeviceController} but lives at the Modulith
 * target package {@code de.vvwt.tm.tournament} (public API surface per DEC-21 §Module layout). Uses
 * {@code /api/tm/devices} mapping to avoid {@code RequestMappingHandlerMapping} ambiguity with the
 * legacy {@code /api/devices} controller during reconstruction-in-place. The mapping will be
 * normalized at the E21S13 atomic cutover.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/tm/devices/register — register a new device (SCORING_TABLET or DISPLAY)
 *   <li>GET /api/tm/devices/status — get device status by token
 *   <li>GET /api/tm/devices/list — list all devices for the current tenant
 * </ul>
 *
 * <h2>Device limit enforcement</h2>
 *
 * <p>POST /register returns HTTP 409 Conflict with a {@link DeviceLimitErrorResponse} body when the
 * per-tenant device cap is exceeded ({@link DeviceLimitExceededException}).
 *
 * @see DeviceService
 * @see DeviceAdminController
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 */
@RestController("tmDeviceController")
@RequestMapping("/api/tm/devices")
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
            @RequestBody DeviceRegisterRequest request) {
        String deviceType =
                request.deviceType() != null ? request.deviceType() : Device.TYPE_SCORING_TABLET;
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
}
