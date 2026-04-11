package de.vvwt.dispatcher.result;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes append-only audit log entries for every {@code POST /submit-result} call (E01S08 AC8).
 *
 * <p>Uses {@link Propagation#REQUIRES_NEW} so that the audit row is persisted
 * even when the outer transaction rolls back due to a business error — every
 * call, successful or rejected, is logged per DEC-6 mechanism 10.
 *
 * <p>See Story E01S08 AC8 and DEC-6.
 */
@Service
public class ResultAuditService {

    private final ResultAuditRepository repository;

    public ResultAuditService(ResultAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Logs a single submit-result call.
     *
     * @param request          the HTTP request (for source IP extraction)
     * @param workerKeyId      the worker key ID from the request body; null if not parsed
     * @param packetId         the packet ID from the request body; null if not parsed
     * @param jobId            the job ID from the request body; null if not parsed
     * @param signatureOutcome one of {@link ResultAuditEntry#SIG_VERIFIED},
     *                         {@link ResultAuditEntry#SIG_FAILED},
     *                         {@link ResultAuditEntry#SIG_NA}
     * @param decisionOutcome  one of the {@code DECISION_*} constants in {@link ResultAuditEntry}
     * @param httpStatus       the HTTP response status code
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(HttpServletRequest request,
                    UUID workerKeyId, UUID packetId, UUID jobId,
                    String signatureOutcome, String decisionOutcome,
                    int httpStatus) {
        String sourceIp = request.getRemoteAddr();
        ResultAuditEntry entry = new ResultAuditEntry(
                Instant.now(), sourceIp,
                workerKeyId, packetId, jobId,
                signatureOutcome, decisionOutcome,
                httpStatus);
        repository.save(entry);
    }
}
