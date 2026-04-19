package de.vvwt.dispatcher.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes append-only audit log entries for every {@code register-key} and {@code submit-job} call.
 *
 * <p>Audit logging uses {@link Propagation#REQUIRES_NEW} so that the audit row is persisted even
 * when the outer transaction is rolled back due to a business error — ensuring that rejected
 * requests are also recorded (AC12).
 *
 * <p>See Story E01S06 AC12 and DEC-6.
 */
@Service
public class AuditService {

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Logs a single register-key or submit-job call.
     *
     * @param request the HTTP request (for source IP extraction)
     * @param endpoint {@code "/register-key"} or {@code "/submit-job"}
     * @param keyId the key ID involved in this call, or null if unresolved
     * @param signatureOutcome {@link AuditEntry#OUTCOME_NA}, {@link AuditEntry#OUTCOME_VERIFIED},
     *     or {@link AuditEntry#OUTCOME_FAILED}
     * @param httpStatus the HTTP response status code that was returned
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(
            HttpServletRequest request,
            String endpoint,
            UUID keyId,
            String signatureOutcome,
            int httpStatus) {
        String sourceIp = request.getRemoteAddr();
        AuditEntry entry =
                new AuditEntry(
                        Instant.now(), sourceIp, endpoint, keyId, signatureOutcome, httpStatus);
        auditRepository.save(entry);
    }
}
