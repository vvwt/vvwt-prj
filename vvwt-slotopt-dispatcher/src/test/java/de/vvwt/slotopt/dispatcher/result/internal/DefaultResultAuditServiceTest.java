package de.vvwt.slotopt.dispatcher.result.internal;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.vvwt.slotopt.dispatcher.result.ResultAuditRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultResultAuditService}.
 *
 * <p>Same-package test per DEC-36. Collaborator {@code ResultAuditRepository} is mocked via its
 * public Spring Data interface (cross-package from this test class — interface type used per
 * DEC-36).
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09).
 *
 * <p>Story: E37S09; AC-RESULT-AUDIT; DEC-22, DEC-35, DEC-36
 */
class DefaultResultAuditServiceTest {

    private ResultAuditRepository auditRepository;
    private DefaultResultAuditService service;

    @BeforeEach
    void setUp() {
        auditRepository = mock(ResultAuditRepository.class);
        service = new DefaultResultAuditService(auditRepository);
    }

    @Test
    void record_persistsAuditEntryWithCorrectFields() {
        UUID packetId = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        Instant receivedAt = Instant.now();

        service.record(packetId, workerId, "Ed25519", "192.168.1.1", receivedAt, "ACCEPTED");

        verify(auditRepository)
                .save(
                        argThat(
                                entry ->
                                        entry.getPacketId().equals(packetId)
                                                && entry.getWorkerId().equals(workerId)
                                                && "Ed25519".equals(entry.getAlgorithm())
                                                && "192.168.1.1".equals(entry.getSourceIp())
                                                && receivedAt.equals(entry.getReceivedAt())
                                                && "ACCEPTED".equals(entry.getOutcome())));
    }
}
