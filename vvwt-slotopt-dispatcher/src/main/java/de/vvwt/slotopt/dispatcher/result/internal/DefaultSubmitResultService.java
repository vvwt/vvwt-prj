// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.slotopt.dispatcher.crypto.JcsCanonicalizer;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.job.JobFinalizationService;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.result.AlgorithmMismatchException;
import de.vvwt.slotopt.dispatcher.result.LateResult;
import de.vvwt.slotopt.dispatcher.result.LateResultRepository;
import de.vvwt.slotopt.dispatcher.result.PacketNotFoundException;
import de.vvwt.slotopt.dispatcher.result.PacketResultService;
import de.vvwt.slotopt.dispatcher.result.ResultAuditService;
import de.vvwt.slotopt.dispatcher.result.SubmitResultRequest;
import de.vvwt.slotopt.dispatcher.result.SubmitResultResponse;
import de.vvwt.slotopt.dispatcher.result.SubmitResultService;
import de.vvwt.slotopt.dispatcher.result.UnknownWorkerException;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link SubmitResultService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code result.internal}. All consumers reference
 * {@link SubmitResultService} (the public interface) exclusively (DEC-36).
 *
 * <p>Implements the 6-step submit-result flow per AC-SUBMIT-RESULT-SERVICE and spec section (b)
 * Endpoint 4:
 *
 * <ol>
 *   <li>Lookup worker registration — unknown → {@link UnknownWorkerException}.
 *   <li>Verify algorithm matches registered algorithm — mismatch → {@link
 *       AlgorithmMismatchException} (AC-ALGORITHM-MISMATCH-REJECTED, HTTP 400).
 *   <li>Lookup verifier for algorithm — unknown → {@link IllegalStateException} (HTTP 500).
 *   <li>Canonicalize {@code resultPayloadJson} via JCS.
 *   <li>Verify signature — invalid → {@link InvalidSignatureException} (HTTP 401).
 *   <li>First-valid-wins per DEC-6: RESULT_RECEIVED → {@link LateResult} + superseded response;
 *       CLAIMED → retain result atomically + mark RESULT_RECEIVED + audit entry + trigger
 *       finalization + accepted response.
 * </ol>
 *
 * <p>E60S02 extends step 6 (CLAIMED path): result is retained via {@link PacketResultService}
 * BEFORE the {@code RESULT_RECEIVED} status update — both are in the same {@code @Transactional}
 * boundary (AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT). A retention failure propagates as an exception,
 * rolling back the entire transaction so the packet remains {@code CLAIMED} and reissuable.
 *
 * <p>E60S03: the per-packet cache write is removed from this class. The result cache is now written
 * once at finalization by {@link JobFinalizationService} after all packets of a job have reached
 * {@code RESULT_RECEIVED} (AC-TEST-NO-PER-PACKET-CACHE-WRITE, AC-GOV-FINALIZATION-CONFORMS-SPEC).
 * Finalization is triggered here after the packet accept; any finalization failure is absorbed
 * (AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT).
 *
 * <p>Story: E37S09 + E37S10 + E60S02 (AC-TEST-RESULT-RETAINED-ON-ACCEPT,
 * AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT) + E60S03 (AC-TEST-NO-PER-PACKET-CACHE-WRITE,
 * AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT); DEC-6, DEC-35, DEC-36, DEC-43
 */
@Service
class DefaultSubmitResultService implements SubmitResultService {

    private static final Logger LOG = Logger.getLogger(DefaultSubmitResultService.class.getName());

    private final KeyRegistrationRepository keyRegistrationRepository;
    private final PacketRepository packetRepository;
    private final SignatureVerifierRegistry verifierRegistry;
    private final JcsCanonicalizer canonicalizer;
    private final LateResultRepository lateResultRepository;
    private final ResultAuditService auditService;
    private final PacketResultService packetResultService;
    private final JobFinalizationService jobFinalizationService;
    private final ObjectMapper objectMapper;

    DefaultSubmitResultService(
            KeyRegistrationRepository keyRegistrationRepository,
            PacketRepository packetRepository,
            SignatureVerifierRegistry verifierRegistry,
            JcsCanonicalizer canonicalizer,
            LateResultRepository lateResultRepository,
            ResultAuditService auditService,
            PacketResultService packetResultService,
            JobFinalizationService jobFinalizationService) {
        this.keyRegistrationRepository = keyRegistrationRepository;
        this.packetRepository = packetRepository;
        this.verifierRegistry = verifierRegistry;
        this.canonicalizer = canonicalizer;
        this.lateResultRepository = lateResultRepository;
        this.auditService = auditService;
        this.packetResultService = packetResultService;
        this.jobFinalizationService = jobFinalizationService;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    @Transactional
    public SubmitResultResponse submit(SubmitResultRequest request, String sourceIp) {
        Instant receivedAt = Instant.now();

        // Step 1: lookup worker registration
        KeyRegistration registration =
                keyRegistrationRepository
                        .findByWorkerId(request.workerId())
                        .orElseThrow(
                                () ->
                                        new UnknownWorkerException(
                                                "Worker not registered: " + request.workerId()));

        // Step 2: verify algorithm matches registered algorithm (AC-ALGORITHM-MISMATCH-REJECTED)
        String registeredAlgorithm = registration.getAlgorithm();
        String submittedAlgorithm = request.algorithm();
        if (!registeredAlgorithm.equals(submittedAlgorithm)) {
            throw new AlgorithmMismatchException(
                    "registered algorithm "
                            + registeredAlgorithm
                            + " does not match submitted "
                            + submittedAlgorithm);
        }

        // Step 3: lookup verifier for algorithm
        SignatureVerifier verifier =
                verifierRegistry
                        .lookup(submittedAlgorithm)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No verifier found for algorithm: "
                                                        + submittedAlgorithm
                                                        + " — server misconfiguration"));

        // Step 4: canonicalize resultPayloadJson (JCS per spec section (a))
        byte[] canonicalBytes;
        try {
            JsonNode node = objectMapper.readTree(request.resultPayloadJson());
            canonicalBytes = canonicalizer.canonicalize(node);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse resultPayloadJson: " + e.getMessage(), e);
        }

        // Step 5: verify signature
        boolean signatureValid;
        try {
            signatureValid =
                    verifier.verify(
                            registration.getPublicKeyBytes(), canonicalBytes, request.signature());
        } catch (InvalidSignatureException e) {
            auditService.record(
                    request.packetId(),
                    request.workerId(),
                    submittedAlgorithm,
                    sourceIp,
                    receivedAt,
                    "SIGNATURE_INVALID");
            throw e;
        }
        if (!signatureValid) {
            auditService.record(
                    request.packetId(),
                    request.workerId(),
                    submittedAlgorithm,
                    sourceIp,
                    receivedAt,
                    "SIGNATURE_INVALID");
            throw new InvalidSignatureException("Signature verification failed");
        }

        // Step 6: first-valid-wins (DEC-6)
        PacketRecord packet =
                packetRepository
                        .findByPacketId(request.packetId())
                        .orElseThrow(
                                () ->
                                        new PacketNotFoundException(
                                                "Unknown packet: " + request.packetId()));

        if ("RESULT_RECEIVED".equals(packet.getStatus())) {
            // Late result — already have a winner; log but do not override
            LateResult lateResult = new LateResult();
            lateResult.setPacketId(request.packetId());
            lateResult.setWorkerId(request.workerId());
            lateResult.setAlgorithm(submittedAlgorithm);
            lateResult.setSignature(request.signature());
            lateResult.setResultPayloadJson(request.resultPayloadJson());
            lateResult.setReceivedAt(receivedAt);
            lateResultRepository.save(lateResult);

            auditService.record(
                    request.packetId(),
                    request.workerId(),
                    submittedAlgorithm,
                    sourceIp,
                    receivedAt,
                    "SUPERSEDED");

            return new SubmitResultResponse(false, "superseded");
        }

        // CLAIMED — first valid result.
        //
        // E60S02 AC-ERR-RETENTION-ATOMIC-WITH-ACCEPT:
        // Retain the result BEFORE updating packet status. Both writes share this @Transactional
        // boundary. If retainResult() throws (DB failure, malformed payload), the transaction
        // rolls back: the packet stays CLAIMED and no retention row is written.
        // The packet remains claimable / reissuable.
        packetResultService.retainResult(
                request.packetId(), packet.getJobId(), request.resultPayloadJson());

        packet.setStatus("RESULT_RECEIVED");
        packetRepository.save(packet);

        auditService.record(
                request.packetId(),
                request.workerId(),
                submittedAlgorithm,
                sourceIp,
                receivedAt,
                "ACCEPTED");

        // E60S03: trigger job finalization after packet accepted.
        // Finalization checks whether all packets are now RESULT_RECEIVED and, if so, aggregates
        // the global optimum, transitions job to COMPLETED, and writes the result cache once.
        // AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT: any failure is absorbed here.
        try {
            jobFinalizationService.tryFinalizeJob(packet.getJobId());
        } catch (Exception e) {
            LOG.log(
                    Level.WARNING,
                    "Finalization failed for job {0} — absorbed: {1}",
                    new Object[] {packet.getJobId(), e.getMessage()});
        }

        return new SubmitResultResponse(true, null);
    }
}
