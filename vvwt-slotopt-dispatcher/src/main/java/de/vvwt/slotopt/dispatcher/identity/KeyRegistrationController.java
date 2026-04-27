package de.vvwt.slotopt.dispatcher.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the DEC-6 key-registration endpoint.
 *
 * <p>Handles {@code POST /api/register-key}. Accepts JSON {@link RegisterKeyRequest}, delegates to
 * {@link KeyRegistrationService}, and returns JSON {@link RegisterKeyResponse}.
 *
 * <p>HTTP status codes per AC-REGISTER-KEY-CONTROLLER (E37S05) + spec section (b):
 *
 * <ul>
 *   <li>201 Created — new registration persisted
 *   <li>200 OK — idempotent re-registration (same worker ID, same role, same algorithm)
 *   <li>400 Bad Request — unknown algorithm, out-of-range key length, missing/empty algorithm field
 *   <li>409 Conflict — role conflict for existing worker ID
 *   <li>410 Gone — algorithm is deprecated per DEC-43 D3; new registrations rejected
 * </ul>
 *
 * <p>DEC-35: this controller lives in the public {@code identity} package (per DEC-40 amendment to
 * DEC-35 for dispatcher module — not a Spring Modulith web module context, so controller placement
 * follows the pattern from the current dispatcher module rather than TM-specific DEC-40).
 *
 * <p>E37S06 amendment: {@link HttpServletRequest} injected to extract source IP for audit wiring
 * per AC-IDENTITY-INTEGRATION-WIRING.
 *
 * <p>E40S03 amendment: {@code @ExceptionHandler(DeprecatedAlgorithmException.class)} added per
 * AC-CONTROLLER-410-GONE-PATH. Returns HTTP 410 Gone with JSON error body when service throws
 * {@link DeprecatedAlgorithmException} (DEC-43 D3 verbatim).
 *
 * <p>Story: E37S05 (initial); E37S06 (audit source-IP wiring); E40S03 (410 Gone handler)
 */
@RestController
public class KeyRegistrationController {

    private final KeyRegistrationService keyRegistrationService;

    public KeyRegistrationController(KeyRegistrationService keyRegistrationService) {
        this.keyRegistrationService = keyRegistrationService;
    }

    /**
     * Registers a public key with the dispatcher identity registry.
     *
     * @param request the registration request body
     * @param httpRequest the HTTP servlet request — used to extract the client source IP for audit
     * @return 201 with {@link RegisterKeyResponse} on new registration; 200 on idempotent
     *     re-registration
     */
    @PostMapping("/api/register-key")
    public ResponseEntity<RegisterKeyResponse> registerKey(
            @RequestBody RegisterKeyRequest request, HttpServletRequest httpRequest) {
        String sourceIp = httpRequest.getRemoteAddr();
        RegistrationOutcome outcome = keyRegistrationService.register(request, sourceIp);
        HttpStatus status = outcome.isNew() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(outcome.response());
    }

    /**
     * Handles {@link IllegalArgumentException} — maps to 400 Bad Request.
     *
     * <p>Triggered by unknown algorithm, out-of-range key length, or missing algorithm field.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /** Handles {@link RoleConflictException} — maps to 409 Conflict. */
    @ExceptionHandler(RoleConflictException.class)
    public ResponseEntity<Map<String, String>> handleRoleConflict(RoleConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    /**
     * Handles {@link DeprecatedAlgorithmException} — maps to 410 Gone.
     *
     * <p>Triggered when a new registration request uses an algorithm that has passed its DEC-43 D3
     * deprecation date (as amended by DEC-48). Returns HTTP 410 Gone per DEC-43 D3 verbatim: "the
     * server returns a registration error (HTTP 410 Gone or equivalent application-level error) for
     * any new registration request using a deprecated algorithm."
     *
     * <p>Story: E40S03 / AC-CONTROLLER-410-GONE-PATH
     */
    @ExceptionHandler(DeprecatedAlgorithmException.class)
    public ResponseEntity<Map<String, String>> handleDeprecatedAlgorithm(
            DeprecatedAlgorithmException ex) {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of("error", ex.getMessage()));
    }
}
