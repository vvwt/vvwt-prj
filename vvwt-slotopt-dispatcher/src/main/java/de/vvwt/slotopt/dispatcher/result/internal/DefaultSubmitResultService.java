package de.vvwt.slotopt.dispatcher.result.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.dispatcher.crypto.InvalidSignatureException;
import de.vvwt.slotopt.dispatcher.crypto.JcsCanonicalizer;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifier;
import de.vvwt.slotopt.dispatcher.crypto.SignatureVerifierRegistry;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistration;
import de.vvwt.slotopt.dispatcher.identity.KeyRegistrationRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.result.AlgorithmMismatchException;
import de.vvwt.slotopt.dispatcher.result.LateResult;
import de.vvwt.slotopt.dispatcher.result.LateResultRepository;
import de.vvwt.slotopt.dispatcher.result.PacketNotFoundException;
import de.vvwt.slotopt.dispatcher.result.ResultAuditService;
import de.vvwt.slotopt.dispatcher.result.SubmitResultRequest;
import de.vvwt.slotopt.dispatcher.result.SubmitResultResponse;
import de.vvwt.slotopt.dispatcher.result.SubmitResultService;
import de.vvwt.slotopt.dispatcher.result.UnknownWorkerException;
import java.time.Instant;
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
 *       CLAIMED → mark RESULT_RECEIVED + audit entry + accepted response.
 * </ol>
 *
 * <p>Story: E37S09; AC-SUBMIT-RESULT-SERVICE; AC-ALGORITHM-MISMATCH-REJECTED; AC-FIRST-VALID-WINS;
 * DEC-6, DEC-35, DEC-36, DEC-43
 */
@Service
class DefaultSubmitResultService implements SubmitResultService {

    private final KeyRegistrationRepository keyRegistrationRepository;
    private final PacketRepository packetRepository;
    private final SignatureVerifierRegistry verifierRegistry;
    private final JcsCanonicalizer canonicalizer;
    private final LateResultRepository lateResultRepository;
    private final ResultAuditService auditService;
    private final ObjectMapper objectMapper;

    DefaultSubmitResultService(
            KeyRegistrationRepository keyRegistrationRepository,
            PacketRepository packetRepository,
            SignatureVerifierRegistry verifierRegistry,
            JcsCanonicalizer canonicalizer,
            LateResultRepository lateResultRepository,
            ResultAuditService auditService) {
        this.keyRegistrationRepository = keyRegistrationRepository;
        this.packetRepository = packetRepository;
        this.verifierRegistry = verifierRegistry;
        this.canonicalizer = canonicalizer;
        this.lateResultRepository = lateResultRepository;
        this.auditService = auditService;
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

        // CLAIMED — first valid result
        packet.setStatus("RESULT_RECEIVED");
        packetRepository.save(packet);

        auditService.record(
                request.packetId(),
                request.workerId(),
                submittedAlgorithm,
                sourceIp,
                receivedAt,
                "ACCEPTED");

        return new SubmitResultResponse(true, null);
    }
}
