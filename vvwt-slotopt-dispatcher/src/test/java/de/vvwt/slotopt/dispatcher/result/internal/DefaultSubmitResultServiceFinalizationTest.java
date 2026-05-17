// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result.internal;

import static org.assertj.core.api.Assertions.assertThat;
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
import de.vvwt.slotopt.dispatcher.job.JobFinalizationService;
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
 * Unit tests for E60S03 finalization integration in {@link DefaultSubmitResultService}.
 *
 * <p>Same-package test (DEC-36 § same-package white-box allowed).
 *
 * <p>Tests verify: (1) finalization is triggered after packet accept, (2) finalization failure is
 * absorbed (AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT), (3) cache is no longer written
 * per-packet (AC-TEST-NO-PER-PACKET-CACHE-WRITE).
 *
 * <p>RED-first per DEC-22.
 *
 * <p>Story: E60S03; AC-TEST-NO-PER-PACKET-CACHE-WRITE,
 * AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT; DEC-22, DEC-36
 */
class DefaultSubmitResultServiceFinalizationTest {

    private KeyRegistrationRepository keyRegistrationRepository;
    private PacketRepository packetRepository;
    private SignatureVerifierRegistry verifierRegistry;
    private JcsCanonicalizer canonicalizer;
    private LateResultRepository lateResultRepository;
    private ResultAuditService auditService;
    private ResultsCacheService resultsCacheService;
    private PacketResultService packetResultService;
    private JobFinalizationService jobFinalizationService;
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
        packetResultService = mock(PacketResultService.class);
        jobFinalizationService = mock(JobFinalizationService.class);
        service =
                new DefaultSubmitResultService(
                        keyRegistrationRepository,
                        packetRepository,
                        verifierRegistry,
                        canonicalizer,
                        lateResultRepository,
                        auditService,
                        packetResultService,
                        jobFinalizationService);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-NO-PER-PACKET-CACHE-WRITE
    // submit() for an accepted packet must NOT write to cache per-packet
    // -------------------------------------------------------------------------

    @Test
    void submit_packetClaimed_noCacheWritePerPacket() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":3,\"bestScore\":42.5}";

        setUpValidSignedRequest(workerId, packetId, jobId, resultPayload, "CLAIMED");

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        SubmitResultResponse resp = service.submit(req, "127.0.0.1");
        assertThat(resp.accepted()).isTrue();

        // Cache MUST NOT be written per-packet (cache write moved to finalization)
        verify(resultsCacheService, never()).recordAcceptedResult(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Finalization is invoked after packet accept
    // -------------------------------------------------------------------------

    @Test
    void submit_packetClaimed_finalizationServiceInvoked() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":3,\"bestScore\":42.5}";

        setUpValidSignedRequest(workerId, packetId, jobId, resultPayload, "CLAIMED");

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        service.submit(req, "127.0.0.1");

        // Finalization service must be called with the job's UUID
        verify(jobFinalizationService).tryFinalizeJob(jobId);
    }

    // -------------------------------------------------------------------------
    // AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT
    // Finalization failure is absorbed — result accept stands
    // -------------------------------------------------------------------------

    @Test
    void submit_finalizationFails_resultAcceptStillSucceeds() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":3,\"bestScore\":42.5}";

        setUpValidSignedRequest(workerId, packetId, jobId, resultPayload, "CLAIMED");

        // Finalization throws
        doThrow(new RuntimeException("finalization failure"))
                .when(jobFinalizationService)
                .tryFinalizeJob(jobId);

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        // submit() must return accepted=true despite finalization failure
        SubmitResultResponse resp = service.submit(req, "127.0.0.1");
        assertThat(resp.accepted()).isTrue();

        // Packet must still be saved as RESULT_RECEIVED
        verify(packetRepository).save(any());
    }

    // -------------------------------------------------------------------------
    // Finalization NOT invoked for late (superseded) results
    // -------------------------------------------------------------------------

    @Test
    void submit_packetResultReceived_finalizationNotInvoked() throws Exception {
        UUID workerId = UUID.randomUUID();
        UUID packetId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String resultPayload = "{\"bestRank\":3,\"bestScore\":42.5}";

        setUpValidSignedRequest(workerId, packetId, jobId, resultPayload, "RESULT_RECEIVED");

        SubmitResultRequest req =
                new SubmitResultRequest(packetId, workerId, "Ed25519", new byte[64], resultPayload);

        service.submit(req, "127.0.0.1");

        // Late result: finalization must NOT be triggered
        verify(jobFinalizationService, never()).tryFinalizeJob(any());
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

        // No jobRepository stubbing needed — cache fingerprint computation moved to
        // DefaultJobFinalizationService (E60S03)
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
