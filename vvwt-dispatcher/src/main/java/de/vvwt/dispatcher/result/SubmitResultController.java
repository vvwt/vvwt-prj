package de.vvwt.dispatcher.result;

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
 * REST controller for {@code POST /submit-result} (E01S08 AC1–AC12).
 *
 * <h2>Responses</h2>
 *
 * <ul>
 *   <li>{@code 200 OK} — result accepted or late-logged; body is {@link SubmitResultResponse}
 *   <li>{@code 400 Bad Request} — missing/invalid fields or malformed JSON (AC1, AC11)
 *   <li>{@code 401 Unauthorized} — signature verification failed (AC2)
 *   <li>{@code 404 Not Found} — unknown packetId (AC11)
 *   <li>{@code 409 Conflict} — not assigned to this worker (AC6)
 *   <li>{@code 410 Gone} — deadline exceeded (AC7)
 *   <li>{@code 503 Service Unavailable} — transient DB error (AC11)
 * </ul>
 *
 * <p>Every call is audit-logged via {@link ResultAuditService} (AC8).
 *
 * <p>See Story E01S08 and DEC-6.
 */
@RestController
public class SubmitResultController {

    private static final Logger log = LoggerFactory.getLogger(SubmitResultController.class);

    private final SubmitResultService submitResultService;
    private final ResultAuditService auditService;

    public SubmitResultController(
            SubmitResultService submitResultService, ResultAuditService auditService) {
        this.submitResultService = submitResultService;
        this.auditService = auditService;
    }

    /**
     * Accepts a signed packet result from an authenticated worker.
     *
     * @param req parsed JSON request body
     * @param httpReq HTTP request (source IP for audit logging)
     * @return 200 with result body, or an appropriate error response
     */
    @PostMapping("/submit-result")
    public ResponseEntity<?> submitResult(
            @RequestBody SubmitResultRequest req, HttpServletRequest httpReq) {

        try {
            SubmitResultResponse response = submitResultService.process(req);

            String decision =
                    response.firstResult()
                            ? ResultAuditEntry.DECISION_ACCEPTED_FIRST
                            : ResultAuditEntry.DECISION_ACCEPTED_LATE;

            auditService.log(
                    httpReq,
                    req.workerKeyId(),
                    req.packetId(),
                    req.jobId(),
                    ResultAuditEntry.SIG_VERIFIED,
                    decision,
                    HttpStatus.OK.value());

            return ResponseEntity.ok(response);

        } catch (SubmitResultService.BadRequestException badRequest) {
            auditService.log(
                    httpReq,
                    safeWorkerKeyId(req),
                    safePacketId(req),
                    safeJobId(req),
                    ResultAuditEntry.SIG_NA,
                    ResultAuditEntry.DECISION_REJECTED_OTHER,
                    HttpStatus.BAD_REQUEST.value());
            return ResponseEntity.badRequest().body(Map.of("error", badRequest.getMessage()));

        } catch (SubmitResultService.UnauthorizedException unauthorized) {
            auditService.log(
                    httpReq,
                    safeWorkerKeyId(req),
                    safePacketId(req),
                    safeJobId(req),
                    ResultAuditEntry.SIG_FAILED,
                    ResultAuditEntry.DECISION_REJECTED_SIGNATURE,
                    HttpStatus.UNAUTHORIZED.value());
            log.warn(
                    "submit-result: unauthorized from {} workerKeyId={}: {}",
                    httpReq.getRemoteAddr(),
                    safeWorkerKeyId(req),
                    unauthorized.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "unauthorized"));

        } catch (SubmitResultService.PacketNotFoundException notFound) {
            auditService.log(
                    httpReq,
                    safeWorkerKeyId(req),
                    safePacketId(req),
                    safeJobId(req),
                    ResultAuditEntry.SIG_NA,
                    ResultAuditEntry.DECISION_REJECTED_OTHER,
                    HttpStatus.NOT_FOUND.value());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", notFound.getMessage()));

        } catch (SubmitResultService.NotAssignedException notAssigned) {
            auditService.log(
                    httpReq,
                    safeWorkerKeyId(req),
                    safePacketId(req),
                    safeJobId(req),
                    ResultAuditEntry.SIG_VERIFIED,
                    ResultAuditEntry.DECISION_REJECTED_NOT_ASSIGNED,
                    HttpStatus.CONFLICT.value());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "not-assigned-to-this-worker"));

        } catch (SubmitResultService.DeadlineExceededException deadlineExceeded) {
            auditService.log(
                    httpReq,
                    safeWorkerKeyId(req),
                    safePacketId(req),
                    safeJobId(req),
                    ResultAuditEntry.SIG_VERIFIED,
                    ResultAuditEntry.DECISION_REJECTED_DEADLINE,
                    HttpStatus.GONE.value());
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(
                            Map.of(
                                    "error",
                                    "deadline-exceeded",
                                    "deadline",
                                    deadlineExceeded.getDeadline().toString()));

        } catch (Exception unexpected) {
            log.error(
                    "INCIDENT: Unexpected error on submit-result workerKeyId={} packetId={}",
                    safeWorkerKeyId(req),
                    safePacketId(req),
                    unexpected);
            auditService.log(
                    httpReq,
                    safeWorkerKeyId(req),
                    safePacketId(req),
                    safeJobId(req),
                    ResultAuditEntry.SIG_NA,
                    ResultAuditEntry.DECISION_REJECTED_OTHER,
                    HttpStatus.SERVICE_UNAVAILABLE.value());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "5")
                    .body(Map.of("error", "service temporarily unavailable"));
        }
    }

    // -------------------------------------------------------------------------
    // Safe field accessors (req may be partially parsed if Jackson fails)
    // -------------------------------------------------------------------------

    private static java.util.UUID safeWorkerKeyId(SubmitResultRequest req) {
        return req != null ? req.workerKeyId() : null;
    }

    private static java.util.UUID safePacketId(SubmitResultRequest req) {
        return req != null ? req.packetId() : null;
    }

    private static java.util.UUID safeJobId(SubmitResultRequest req) {
        return req != null ? req.jobId() : null;
    }
}
