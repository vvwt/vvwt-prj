package de.vvwt.dispatcher.identity;

import de.vvwt.dispatcher.audit.AuditEntry;
import de.vvwt.dispatcher.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for {@code POST /register-key}.
 *
 * <h2>Responses</h2>
 * <ul>
 *   <li>{@code 200 OK} — key registered (or idempotent match)</li>
 *   <li>{@code 400 Bad Request} — missing/invalid fields or invalid public key</li>
 *   <li>{@code 409 Conflict} — same key bytes registered with a different role</li>
 * </ul>
 *
 * <p>Every call (success or failure) is audit-logged via {@link AuditService} (AC12).
 *
 * <p>See Story E01S06 AC1–AC4, AC12 and DEC-6.
 */
@RestController
public class KeyRegistrationController {

    private static final String ENDPOINT = "/register-key";

    private final KeyRegistrationService service;
    private final AuditService auditService;

    public KeyRegistrationController(KeyRegistrationService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    /**
     * Registers an Ed25519 public key for a worker or submitter.
     *
     * @param request  parsed JSON request body
     * @param httpReq  HTTP request (used for audit logging)
     * @return {@link RegisterKeyResponse} on success, or an error body on failure
     */
    @PostMapping("/register-key")
    public ResponseEntity<?> registerKey(
            @RequestBody RegisterKeyRequest request,
            HttpServletRequest httpReq) {

        // Audit logging uses REQUIRES_NEW — persists even if the business logic rolls back
        try {
            RegisterKeyResponse response = service.register(request);
            auditService.log(httpReq, ENDPOINT, response.keyId(),
                    AuditEntry.OUTCOME_NA, HttpStatus.OK.value());
            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException invalidInput) {
            auditService.log(httpReq, ENDPOINT, null,
                    AuditEntry.OUTCOME_NA, HttpStatus.BAD_REQUEST.value());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invalid public key"));

        } catch (RoleConflictException conflict) {
            auditService.log(httpReq, ENDPOINT, null,
                    AuditEntry.OUTCOME_NA, HttpStatus.CONFLICT.value());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "role conflict",
                                 "message", conflict.getMessage()));
        }
    }
}
