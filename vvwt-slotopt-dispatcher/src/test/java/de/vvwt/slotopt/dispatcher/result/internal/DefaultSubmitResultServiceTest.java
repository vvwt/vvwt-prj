package de.vvwt.slotopt.dispatcher.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.slotopt.dispatcher.crypto.JcsCanonicalizer;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.result.AlgorithmMismatchException;
import de.vvwt.slotopt.dispatcher.result.LateResultRepository;
import de.vvwt.slotopt.dispatcher.result.PacketNotFoundException;
import de.vvwt.slotopt.dispatcher.result.ResultAuditService;
import de.vvwt.slotopt.dispatcher.result.SubmitResultRequest;
import de.vvwt.slotopt.dispatcher.result.SubmitResultResponse;
import de.vvwt.slotopt.dispatcher.result.UnknownWorkerException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultSubmitResultService}.
 *
 * <p>Same-package test (DEC-36 § same-package white-box allowed). Mocks are typed as the
 * collaborator interfaces (DEC-36 cross-package applies to collaborators that are cross-package
 * from this test class — {@code KeyRegistrationRepository}, {@code PacketRepository}, {@code
 * SignatureVerifierRegistry}, {@code JcsCanonicalizer}, {@code LateResultRepository}, {@code
 * ResultAuditService} are all public interfaces from their respective packages, used here via their
 * interface types).
 *
 * <p>RED-first per DEC-22 / AC-TDD-RED-FIRST-EVIDENCE (E37S09).
 *
 * <p>Story: E37S09 + E37S10 (AC-CACHE-WRITE-ON-ACCEPTED-RESULT retrofit); AC-SUBMIT-RESULT-SERVICE;
 * AC-ALGORITHM-MISMATCH-REJECTED; AC-FIRST-VALID-WINS; DEC-22, DEC-36
 */
class DefaultSubmitResultServiceTest {

    private KeyRegistrationRepository keyRegistrationRepository;
    private PacketRepository packetRepository;
    private SignatureVerifierRegistry verifierRegistry;
    private JcsCanonicalizer canonicalizer;
    private LateResultRepository lateResultRepository;
    private ResultAuditService auditService;
    private ResultsCacheService resultsCacheService;
    private JobRepository jobRepository;
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
        service =
                new DefaultSubmitResultService(
                        keyRegistrationRepository,
                        packetRepository,
                        verifierRegistry,
                        canonicalizer,
                        lateResultRepository,
                        auditService,
                        resultsCacheService,
                        jobRepository);
    }

    // -------------------------------------------------------------------------
    // Step 1: Unknown worker → UnknownWorkerException
    // -------------------------------------------------------------------------

    @Test
    void submit_unknownWorker_throwsUnknownWorkerException() {
        UUID workerId = UUID.randomUUID();
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.empty());

        SubmitResultRequest req =
                new SubmitResultRequest(UUID.randomUUID(), workerId, "Ed25519", new byte[64], "{}");

        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
                .isInstanceOf(UnknownWorkerException.class);
    }

    // -------------------------------------------------------------------------
    // Step 2: Algorithm mismatch → AlgorithmMismatchException (→ HTTP 400)
    // AC-ALGORITHM-MISMATCH-REJECTED
    // -------------------------------------------------------------------------

    @Test
    void submit_algorithmMismatch_throwsAlgorithmMismatchException() {
        UUID workerId = UUID.randomUUID();
        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));

        // Submitted algorithm differs from registered algorithm
        SubmitResultRequest req =
                new SubmitResultRequest(
                        UUID.randomUUID(), workerId, "ML-DSA-65", new byte[64], "{}");

        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
                .isInstanceOf(AlgorithmMismatchException.class)
                .hasMessageContaining("Ed25519")
                .hasMessageContaining("ML-DSA-65");
    }

    // -------------------------------------------------------------------------
    // Step 3: Unknown algorithm at runtime → IllegalStateException (→ HTTP 500)
    // -------------------------------------------------------------------------

    @Test
    void submit_unknownAlgorithmInRegistry_throwsIllegalStateException() {
        UUID workerId = UUID.randomUUID();
        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.empty());
        when(canonicalizer.canonicalize(any())).thenReturn(new byte[0]);

        SubmitResultRequest req =
                new SubmitResultRequest(UUID.randomUUID(), workerId, "Ed25519", new byte[64], "{}");

        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Step 5: Invalid signature → InvalidSignatureException (→ HTTP 401)
    // -------------------------------------------------------------------------

    @Test
    void submit_invalidSignature_throwsInvalidSignatureException() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));

        SignatureVerifier verifier = mock(SignatureVerifier.class);
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(verifier));
        when(canonicalizer.canonicalize(any())).thenReturn(new byte[] {1, 2, 3});
        when(verifier.verify(any(), any(), any())).thenReturn(false);

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], "{}");

        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
                .isInstanceOf(InvalidSignatureException.class);
    }

    // -------------------------------------------------------------------------
    // Step 6a: Packet CLAIMED → first-valid-wins → accepted=true (AC-FIRST-VALID-WINS)
    // -------------------------------------------------------------------------

    @Test
    void submit_packetClaimed_firstResult_returnsAcceptedTrue() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));

        SignatureVerifier verifier = mock(SignatureVerifier.class);
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(verifier));
        when(canonicalizer.canonicalize(any())).thenReturn(new byte[] {1});
        when(verifier.verify(any(), any(), any())).thenReturn(true);

        PacketRecord packet = new PacketRecord();
        packet.setPacketId(packetId);
        packet.setStatus("CLAIMED");
        when(packetRepository.findByPacketId(packetId)).thenReturn(Optional.of(packet));

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], "{}");

        SubmitResultResponse resp = service.submit(req, "127.0.0.1");

        assertThat(resp.accepted()).isTrue();
        assertThat(resp.reason()).isNull();

        // Verify packet status was updated to RESULT_RECEIVED
        verify(packetRepository).save(packet);
        assertThat(packet.getStatus()).isEqualTo("RESULT_RECEIVED");

        // Verify audit entry recorded
        verify(auditService).record(any(), any(), anyString(), anyString(), any(), anyString());
    }

    // -------------------------------------------------------------------------
    // Step 6b: Packet RESULT_RECEIVED → superseded → LateResult (AC-FIRST-VALID-WINS)
    // -------------------------------------------------------------------------

    @Test
    void submit_packetResultReceived_superseded_returnsAcceptedFalse() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));

        SignatureVerifier verifier = mock(SignatureVerifier.class);
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(verifier));
        when(canonicalizer.canonicalize(any())).thenReturn(new byte[] {1});
        when(verifier.verify(any(), any(), any())).thenReturn(true);

        PacketRecord packet = new PacketRecord();
        packet.setPacketId(packetId);
        packet.setStatus("RESULT_RECEIVED");
        when(packetRepository.findByPacketId(packetId)).thenReturn(Optional.of(packet));

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], "{}");

        SubmitResultResponse resp = service.submit(req, "127.0.0.1");

        assertThat(resp.accepted()).isFalse();
        assertThat(resp.reason()).isEqualTo("superseded");

        // Verify LateResult was persisted
        verify(lateResultRepository).save(any());

        // Packet was NOT mutated
        verify(packetRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Unknown packet → PacketNotFoundException
    // -------------------------------------------------------------------------

    @Test
    void submit_unknownPacket_throwsPacketNotFoundException() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));

        SignatureVerifier verifier = mock(SignatureVerifier.class);
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(verifier));
        when(canonicalizer.canonicalize(any())).thenReturn(new byte[] {1});
        when(verifier.verify(any(), any(), any())).thenReturn(true);
        when(packetRepository.findByPacketId(packetId)).thenReturn(Optional.empty());

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], "{}");

        assertThatThrownBy(() -> service.submit(req, "127.0.0.1"))
                .isInstanceOf(PacketNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // AC-CACHE-WRITE-ON-ACCEPTED-RESULT (E37S10 retrofit)
    // -------------------------------------------------------------------------

    /**
     * Verifies that when a result is accepted (CLAIMED packet), {@link
     * ResultsCacheService#recordAcceptedResult} is called with the structural fingerprint derived
     * from the job's {@link de.vvwt.slotopt.worker.types.CanonicalPhaseDef} and the canonical V1
     * game mode.
     *
     * <p>RED-first per DEC-22 / AC-CACHE-WRITE-ON-ACCEPTED-RESULT + AC-RETROFIT-EVIDENCE (E37S10).
     */
    @Test
    void submit_packetClaimed_writesToCache() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":5}";

        KeyRegistration reg = buildRegistration(workerId, "Ed25519");
        when(keyRegistrationRepository.findByWorkerId(workerId)).thenReturn(Optional.of(reg));

        SignatureVerifier verifier = mock(SignatureVerifier.class);
        when(verifierRegistry.lookup("Ed25519")).thenReturn(Optional.of(verifier));
        when(canonicalizer.canonicalize(any())).thenReturn(new byte[] {1});
        when(verifier.verify(any(), any(), any())).thenReturn(true);

        PacketRecord packet = new PacketRecord();
        packet.setPacketId(packetId);
        packet.setJobId(jobId);
        packet.setStatus("CLAIMED");
        when(packetRepository.findByPacketId(packetId)).thenReturn(Optional.of(packet));

        // JobRecord with a minimal valid jobDefJson containing a CanonicalPhaseDef
        // The jobDefJson is what DefaultJobService persists; here we stub the lookup.
        JobRecord jobRecord = new JobRecord();
        jobRecord.setJobId(jobId);
        // Minimal JSON for JobDef: {jobId, n, canonicalPhaseDef{rowCount, avatarCount, rows}}
        jobRecord.setJobDefJson(
                "{\"jobId\":\""
                        + jobId
                        + "\",\"n\":2,"
                        + "\"canonicalPhaseDef\":{\"rowCount\":1,\"avatarCount\":2,"
                        + "\"rows\":[[0,1]]}}");
        when(jobRepository.findByJobId(jobId)).thenReturn(Optional.of(jobRecord));

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        service.submit(req, "127.0.0.1");

        // Verify cache write was called with non-null fingerprint, correct gameMode + payload
        verify(resultsCacheService)
                .recordAcceptedResult(
                        any(byte[].class), eq("default"), eq(resultPayload), eq(jobId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static KeyRegistration buildRegistration(UUID workerId, String algorithm) {
        KeyRegistration reg = new KeyRegistration();
        reg.setWorkerId(workerId);
        reg.setAlgorithm(algorithm);
        reg.setPublicKeyBytes(new byte[32]);
        reg.setRole("worker");
        return reg;
    }
}
