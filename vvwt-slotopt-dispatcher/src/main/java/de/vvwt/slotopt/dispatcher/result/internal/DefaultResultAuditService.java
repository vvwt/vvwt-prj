package de.vvwt.slotopt.dispatcher.result.internal;

import de.vvwt.slotopt.dispatcher.result.ResultAuditEntry;
import de.vvwt.slotopt.dispatcher.result.ResultAuditRepository;
import de.vvwt.slotopt.dispatcher.result.ResultAuditService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link ResultAuditService}.
 *
 * <p>Per DEC-35: implementation lives in {@code result.internal}. Callers outside this package
 * reference {@link ResultAuditService} (the public interface) exclusively (DEC-36).
 *
 * <p>Story: E37S09; AC-RESULT-AUDIT; DEC-35, DEC-36
 */
@Service
class DefaultResultAuditService implements ResultAuditService {

    private final ResultAuditRepository auditRepository;

    DefaultResultAuditService(ResultAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public void record(
            UUID packetId,
            UUID workerId,
            String algorithm,
            String sourceIp,
            Instant receivedAt,
            String outcome) {
        ResultAuditEntry entry = new ResultAuditEntry();
        entry.setPacketId(packetId);
        entry.setWorkerId(workerId);
        entry.setAlgorithm(algorithm);
        entry.setSourceIp(sourceIp);
        entry.setReceivedAt(receivedAt);
        entry.setOutcome(outcome);
        auditRepository.save(entry);
    }
}
