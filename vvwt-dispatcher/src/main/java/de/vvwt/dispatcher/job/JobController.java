package de.vvwt.dispatcher.job;

import de.vvwt.dispatcher.audit.AuditEntry;
import de.vvwt.dispatcher.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for {@code POST /submit-job}.
 *
 * <h2>Responses</h2>
 *
 * <ul>
 *   <li>{@code 202 Accepted} — job queued (AC10) or cache hit (AC9)
 *   <li>{@code 400 Bad Request} — missing/invalid fields or rowCount out of range (AC5, AC8, AC11)
 *   <li>{@code 401 Unauthorized} — unknown key, expired key, or signature failure (AC6)
 *   <li>{@code 403 Forbidden} — worker key calling submit-job (AC7)
 *   <li>{@code 422 Unprocessable Entity} — rowCount 16–17 (AC8)
 *   <li>{@code 503 Service Unavailable} — transient database error (AC11)
 *   <li>{@code 500 Internal Server Error} — persistent database error (AC11)
 * </ul>
 *
 * <p>Every call (success or failure) is audit-logged via {@link AuditService} (AC12). The audit log
 * records: source IP, endpoint, keyId-or-null, signatureOutcome, httpStatus.
 *
 * <p>See Story E01S06 AC5–AC12 and DEC-6.
 */
@RestController
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);
    private static final String ENDPOINT = "/submit-job";

    private final JobService jobService;
    private final AuditService auditService;

    public JobController(JobService jobService, AuditService auditService) {
        this.jobService = jobService;
        this.auditService = auditService;
    }

    /**
     * Accepts an optimization job submission from an authenticated submitter.
     *
     * @param request parsed JSON request body
     * @param httpReq HTTP request (used for audit logging)
     * @return 202 with job result, or an appropriate error body
     */
    @PostMapping("/submit-job")
    public ResponseEntity<?> submitJob(
            @RequestBody SubmitJobRequest request, HttpServletRequest httpReq) {

        try {
            JobService.SubmitResult result = jobService.submit(request);

            return switch (result) {
                case JobService.SubmitResult.Queued queued -> {
                    auditService.log(
                            httpReq,
                            ENDPOINT,
                            request.submitterKeyId(),
                            AuditEntry.OUTCOME_VERIFIED,
                            HttpStatus.ACCEPTED.value());
                    yield ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(new SubmitJobResponse(queued.jobId(), "queued", null));
                }
                case JobService.SubmitResult.Cached cached -> {
                    auditService.log(
                            httpReq,
                            ENDPOINT,
                            request.submitterKeyId(),
                            AuditEntry.OUTCOME_VERIFIED,
                            HttpStatus.ACCEPTED.value());
                    SubmitJobResponse.CachedResultSummary summary =
                            new SubmitJobResponse.CachedResultSummary(
                                    cached.cachedResult().bestRank(),
                                    cached.cachedResult().bestScore(),
                                    cached.cachedResult().computedAt());
                    yield ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(new SubmitJobResponse(cached.jobId(), "cached", summary));
                }
            };

        } catch (IllegalArgumentException badRequest) {
            auditService.log(
                    httpReq,
                    ENDPOINT,
                    request.submitterKeyId(),
                    AuditEntry.OUTCOME_NA,
                    HttpStatus.BAD_REQUEST.value());
            return ResponseEntity.badRequest().body(Map.of("error", badRequest.getMessage()));

        } catch (JobService.NCapExceededException nCapExceeded) {
            auditService.log(
                    httpReq,
                    ENDPOINT,
                    request.submitterKeyId(),
                    AuditEntry.OUTCOME_NA,
                    HttpStatus.UNPROCESSABLE_ENTITY.value());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(
                            Map.of(
                                    "error",
                                    "N-cap exceeded",
                                    "message",
                                    "Phase 1 supports N <= 15. N = "
                                            + nCapExceeded.getRowCount()
                                            + " requires Phase 2 (not yet shipped)."));

        } catch (JobService.UnauthorizedException unauthorized) {
            // Log source IP + keyId + FAILED outcome for security audit (AC12)
            auditService.log(
                    httpReq,
                    ENDPOINT,
                    request.submitterKeyId(),
                    AuditEntry.OUTCOME_FAILED,
                    HttpStatus.UNAUTHORIZED.value());
            log.warn(
                    "Unauthorized submit-job attempt from {} for keyId={}: {}",
                    httpReq.getRemoteAddr(),
                    request.submitterKeyId(),
                    unauthorized.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));

        } catch (JobService.ForbiddenException forbidden) {
            auditService.log(
                    httpReq,
                    ENDPOINT,
                    request.submitterKeyId(),
                    AuditEntry.OUTCOME_FAILED,
                    HttpStatus.FORBIDDEN.value());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(
                            Map.of(
                                    "error", "forbidden",
                                    "message", "Only submitter keys may call submit-job"));

        } catch (JobService.DatabaseException dbTransient) {
            log.error("Transient DB error on submit-job", dbTransient);
            auditService.log(
                    httpReq,
                    ENDPOINT,
                    request.submitterKeyId(),
                    AuditEntry.OUTCOME_NA,
                    HttpStatus.SERVICE_UNAVAILABLE.value());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "5")
                    .body(Map.of("error", "service temporarily unavailable"));

        } catch (Exception unexpected) {
            log.error("INCIDENT: Unexpected error on submit-job", unexpected);
            auditService.log(
                    httpReq,
                    ENDPOINT,
                    request.submitterKeyId(),
                    AuditEntry.OUTCOME_NA,
                    HttpStatus.INTERNAL_SERVER_ERROR.value());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "internal server error"));
        }
    }
}
