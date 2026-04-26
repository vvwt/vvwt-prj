package de.vvwt.slotopt.dispatcher.job;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the submit-job endpoint.
 *
 * <p>Handles {@code POST /api/submit-job}. Accepts JSON {@link SubmitJobRequest}, delegates to
 * {@link JobService}, and returns JSON {@link SubmitJobResponse}.
 *
 * <p>HTTP status codes per AC-JOB-CONTROLLER (E37S07) + spec section (b):
 *
 * <ul>
 *   <li>202 Accepted — job accepted for processing (spec mandates 202 for submit-job)
 *   <li>400 Bad Request — validation failures (N-cap, null phase, malformed JSON, DEC-9 violation)
 * </ul>
 *
 * <p>DEC-9 violations surface as {@link HttpMessageNotReadableException} (Jackson deserialization
 * failure via {@link RawPhaseDefDeserializer}) → handled with HTTP 400.
 *
 * <p>Story: E37S07; AC-JOB-CONTROLLER; DEC-9, DEC-35
 */
@RestController
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Accepts a new optimization job from a submitter.
     *
     * @param request the job submission request body
     * @return 202 Accepted with {@link SubmitJobResponse} on success
     */
    @PostMapping("/api/submit-job")
    public ResponseEntity<SubmitJobResponse> submitJob(@RequestBody SubmitJobRequest request) {
        SubmitJobResponse response = jobService.submitJob(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * Handles {@link IllegalArgumentException} — maps to 400 Bad Request.
     *
     * <p>Triggered by validation failures: N-cap exceeded, null phase.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleValidationFailure(
            IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /**
     * Handles {@link HttpMessageNotReadableException} — maps to 400 Bad Request.
     *
     * <p>Triggered by malformed JSON or DEC-9 violations (UUID detection in {@link
     * RawPhaseDefDeserializer} throws {@code IOException} → wrapped as {@code
     * HttpMessageNotReadableException} by Spring MVC).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleMessageNotReadable(
            HttpMessageNotReadableException ex) {
        String message =
                ex.getMessage() != null ? ex.getMessage() : "Malformed or invalid request body";
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
