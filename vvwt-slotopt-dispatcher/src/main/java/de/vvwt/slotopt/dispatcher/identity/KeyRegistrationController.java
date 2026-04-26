package de.vvwt.slotopt.dispatcher.identity;

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
 * </ul>
 *
 * <p>DEC-35: this controller lives in the public {@code identity} package (per DEC-40 amendment to
 * DEC-35 for dispatcher module — not a Spring Modulith web module context, so controller placement
 * follows the pattern from the current dispatcher module rather than TM-specific DEC-40).
 *
 * <p>Story: E37S05; Spec: E37S02 spec (b)
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
     * @return 201 with {@link RegisterKeyResponse} on new registration; 200 on idempotent
     *     re-registration
     */
    @PostMapping("/api/register-key")
    public ResponseEntity<RegisterKeyResponse> registerKey(
            @RequestBody RegisterKeyRequest request) {
        RegistrationOutcome outcome = keyRegistrationService.register(request);
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
}
