package de.vvwt.slotopt.dispatcher.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import de.vvwt.slotopt.dispatcher.crypto.JcsCanonicalizer;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.result.LateResultRepository;
import de.vvwt.slotopt.dispatcher.result.PacketResultService;
import de.vvwt.slotopt.dispatcher.result.ResultAuditService;
import de.vvwt.slotopt.dispatcher.result.SubmitResultRequest;
import de.vvwt.slotopt.dispatcher.result.SubmitResultResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for result-retention behaviour in {@link DefaultSubmitResultService}.
 *
 * <p>Same-package test (DEC-36 § same-package white-box allowed). Covers the AC-TEST-* and AC-ERR-*
 * acceptance criteria for E60S02 result-retention.
 *
 * <p>RED-first per DEC-22 / AC-GOV-RED-FIRST: these tests are written before the corresponding
 * production changes to {@link DefaultSubmitResultService} and before {@link PacketResultService}
 * exists — they will fail until the implementation is in place.
 *
 * <p>Story: E60S02; AC-TEST-RESULT-RETAINED-ON-ACCEPT, AC-TEST-LATE-RESULT-DOES-NOT-OVERWRITE,
 * AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT, AC-ERR-DUPLICATE-RESULT-NO-CORRUPTION,
 * AC-ERR-RETENTION-REJECTS-MALFORMED-RESULT; DEC-22, DEC-36
 */
class DefaultSubmitResultServiceRetentionTest {

    private KeyRegistrationRepository keyRegistrationRepository;
    private PacketRepository packetRepository;
    private SignatureVerifierRegistry verifierRegistry;
    private JcsCanonicalizer canonicalizer;
    private LateResultRepository lateResultRepository;
    private ResultAuditService auditService;
    private ResultsCacheService resultsCacheService;
    private JobRepository jobRepository;
    private PacketResultService packetResultService;
    private DefaultSubmitResultService service;

    @BeforeEach
    void setUp() {
        keyRegistrationRepository = mock(KeyRegistrationRepository.class);
        packetRepository = mock(PacketRepository.class);
        verifierRegistry = mock(SignatureVerifierRegistry.class);
        canonicalizer = mock(JcsCanonicalizer.class);
        lateResultRepository = mock(LateResultRepository.class);
        auditService = mock(ResultAuditService.class);
        resultsCacheService = mock(ResultsCacheService.class);
        jobRepository = mock(JobRepository.class);
        packetResultService = mock(PacketResultService.class);
        service =
                new DefaultSubmitResultService(
                        keyRegistrationRepository,
                        packetRepository,
                        verifierRegistry,
                        canonicalizer,
                        lateResultRepository,
                        auditService,
                        resultsCacheService,
                        jobRepository,
                        packetResultService);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-RESULT-RETAINED-ON-ACCEPT
    // When a CLAIMED packet's result is accepted, packetResultService.retainResult() is called
    // -------------------------------------------------------------------------

    @Test
    void submit_packetClaimed_retentionServiceCalledWithBestRankAndBestScore() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":3,\"bestScore\":42.5}";

        setUpValidSignedRequest(workerId, packetId, jobId, resultPayload, "CLAIMED");

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        SubmitResultResponse resp = service.submit(req, "127.0.0.1");

        assertThat(resp.accepted()).isTrue();
        // Retention service must be called with the packet and result payload
        verify(packetResultService).retainResult(packetId, jobId, resultPayload);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-LATE-RESULT-DOES-NOT-OVERWRITE
    // Superseded result does NOT call retention
    // -------------------------------------------------------------------------

    @Test
    void submit_packetResultReceived_retentionNotCalled() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":3,\"bestScore\":42.5}";

        setUpValidSignedRequest(workerId, packetId, jobId, resultPayload, "RESULT_RECEIVED");

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        service.submit(req, "127.0.0.1");

        // Late (superseded) results must NOT trigger retention
        verify(packetResultService, never()).retainResult(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT
    // If retainResult() throws, the packet must NOT be marked RESULT_RECEIVED
    // (atomicity — both succeed or neither does)
    // -------------------------------------------------------------------------

    @Test
    void submit_retentionFails_packetStatusNotUpdated() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":3,\"bestScore\":42.5}";

        setUpValidSignedRequest(workerId, packetId, jobId, resultPayload, "CLAIMED");
        doThrow(new RuntimeException("DB failure"))
                .when(packetResultService)
                .retainResult(packetId, jobId, resultPayload);

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        // The exception propagates — the @Transactional boundary ensures rollback
        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB failure");

        // packetRepository.save() must NOT have been called (retention must happen before status
        // update, OR within same TX so rollback covers both)
        verify(packetRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // AC-ERR-RETENTION-REJECTS-MALFORMED-RESULT
    // A result payload without extractable bestRank/bestScore is rejected
    // -------------------------------------------------------------------------

    @Test
    void submit_malformedPayload_missingBestRank_throwsIllegalArgumentException() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        // Missing bestRank and bestScore
        String malformedPayload = "{\"someOtherField\":\"value\"}";

        setUpValidSignedRequest(workerId, packetId, jobId, malformedPayload, "CLAIMED");
        doThrow(new IllegalArgumentException("bestRank missing from result payload"))
                .when(packetResultService)
                .retainResult(packetId, jobId, malformedPayload);

        SubmitResultRequest req =
                new SubmitResultRequest(
                        packetId, workerId, "Ed25519", new byte[64], malformedPayload);

        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class);

        // Packet was NOT marked RESULT_RECEIVED
        verify(packetRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setUpValidSignedRequest(
            UUID workerId, UUID packetId, UUID jobId, String resultPayload, String packetStatus)
            throws Exception {
        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));

        SignatureVerifier verifier = mock(SignatureVerifier.class);
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(verifier));
        when(canonicalizer.canonicalize(any())).thenReturn(new byte[] {1});
        when(verifier.verify(any(), any(), any())).thenReturn(true);

        PacketRecord packet = new PacketRecord();
        packet.setPacketId(packetId);
        packet.setJobId(jobId);
        packet.setStatus(packetStatus);
        when(packetRepository.findByPacketId(packetId)).thenReturn(Optional.of(packet));

        if ("CLAIMED".equals(packetStatus)) {
            // Set up job for cache fingerprint computation
            JobRecord jobRecord = new JobRecord();
            jobRecord.setJobId(jobId);
            jobRecord.setJobDefJson(
                    "{\"jobId\":\""
                            + jobId
                            + "\",\"n\":2,"
                            + "\"canonicalPhaseDef\":{\"rowCount\":1,\"avatarCount\":2,"
                            + "\"rows\":[[0,1]]}}");
            when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(jobRecord));
        }
    }

    private static KeyRegistration buildRegistration(UUID workerId, String algorithm) {
        KeyRegistration reg = new KeyRegistration();
        reg.setWorkerId(workerId);
        reg.setAlgorithm(algorithm);
        reg.setPublicKeyBytes(new byte[32]);
        reg.setRole("worker");
        return reg;
    }
}
