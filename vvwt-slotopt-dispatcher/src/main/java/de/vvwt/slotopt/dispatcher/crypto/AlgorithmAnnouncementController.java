package de.vvwt.slotopt.dispatcher.crypto;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the DEC-43 D1 algorithm-announcement endpoint.
 *
 * <p>Handles {@code GET /api/algorithms}. Returns the dispatcher's supported signature algorithm
 * list as a JSON array of {@link AnnouncedAlgorithm} objects. HTTP 200 OK on success.
 *
 * <p>The endpoint is intentionally unauthenticated (AC-NO-AUTH-REQUIRED): clients must be able to
 * discover the algorithm list BEFORE generating a keypair for registration (DEC-43 D2 free-choice
 * contract). No SecurityConfig exists in the dispatcher module — the endpoint is public by default.
 *
 * <p>HTTP status codes:
 *
 * <ul>
 *   <li>200 OK — algorithm list returned (possibly empty if no {@link SignatureVerifier} beans
 *       registered; see AC-EMPTY-VERIFIER-LIST)
 *   <li>500 Internal Server Error — if {@link AlgorithmAnnouncementService#announcedAlgorithms()}
 *       throws a runtime exception; Spring Boot's default error handling returns 500 with no
 *       internal-detail leakage in the response body (AC-SERVICE-EXCEPTION-MAPPING)
 * </ul>
 *
 * <p>Package layout: controller lives in the {@code crypto} package root per the established E37
 * dispatcher convention (controller-in-context-package). The dispatcher is not a Spring Modulith
 * application, so DEC-40's {@code de.vvwt.tm.web} isolation rule does not apply here.
 *
 * <p>Spec: E40S02 AC-ANNOUNCEMENT-CONTROLLER; DEC-43 § D1; DEC-35 (dispatcher-specific controller
 * placement); DEC-43 D2 (unauthenticated pre-registration access).
 */
@RestController
public class AlgorithmAnnouncementController {

    private final AlgorithmAnnouncementService algorithmAnnouncementService;

    public AlgorithmAnnouncementController(
            AlgorithmAnnouncementService algorithmAnnouncementService) {
        this.algorithmAnnouncementService = algorithmAnnouncementService;
    }

    /**
     * Returns the dispatcher's supported signature algorithm list per DEC-43 D1.
     *
     * @return HTTP 200 with JSON array of {@link AnnouncedAlgorithm} objects; empty array {@code
     *     []} if no verifiers are registered
     */
    @GetMapping("/api/algorithms")
    public List<AnnouncedAlgorithm> getAlgorithms() {
        return algorithmAnnouncementService.announcedAlgorithms();
    }

    /**
     * Handles unexpected runtime exceptions during algorithm-list aggregation.
     *
     * <p>AC-SERVICE-EXCEPTION-MAPPING: maps any runtime exception thrown during {@link
     * AlgorithmAnnouncementService#announcedAlgorithms()} aggregation to HTTP 500 without leaking
     * internal exception detail (no stack trace, no class name, no exception message) in the
     * response body.
     *
     * <p>Per AC-SERVICE-EXCEPTION-MAPPING: "Spring's default exception handling returns HTTP 500
     * with no internal-detail leakage." This handler makes that contract explicit and testable.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "An internal error occurred"));
    }
}
