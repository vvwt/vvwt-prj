package de.vvwt.slotopt.worker.runtime.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.ComputeStepException;
import de.vvwt.slotopt.worker.runtime.ComputeStepResult;
import de.vvwt.slotopt.worker.runtime.DispatcherClient;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import de.vvwt.slotopt.worker.runtime.PullPacketRequest;
import de.vvwt.slotopt.worker.runtime.PullPacketResponse;
import de.vvwt.slotopt.worker.runtime.ResultSigner;
import de.vvwt.slotopt.worker.runtime.SigningException;
import de.vvwt.slotopt.worker.runtime.SubmitResultPayload;
import de.vvwt.slotopt.worker.runtime.SubmitResultRequest;
import de.vvwt.slotopt.worker.runtime.SubmitResultResponse;
import de.vvwt.slotopt.worker.solver.PacketSolver;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PacketResult;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default implementation of {@link ComputeStep}.
 *
 * <p>Implements the pull → solve → sign → submit logic for one packet iteration:
 *
 * <ol>
 *   <li>POST /api/pull-packet with {@code supportedAlgorithms} (DEC-43 D2)
 *   <li>If HTTP 204: return {@link ComputeStepResult#NO_PACKET}
 *   <li>If HTTP 200: deserialize {@code packetPayloadJson} → invoke {@code
 *       PacketSolver.solvePacket}
 *   <li>Sign result via {@link ResultSigner} → POST /api/submit-result with {@code algorithm} field
 *   <li>Return {@link ComputeStepResult#PACKET_PROCESSED}
 * </ol>
 *
 * <p>On any failure (dispatcher I/O error, deserialization error, signing error, HTTP 410), throws
 * {@link ComputeStepException} with the appropriate exit code so the calling lifecycle (standalone
 * or embedded) can apply its own policy.
 *
 * <p>DEC-35-by-analogy: implementation in {@code runtime.internal}; public interface {@link
 * ComputeStep} in {@code runtime}.
 *
 * <p>Story: E63S01 AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED, AC-TEST-RUNTIME-LIBRARY-UNIT-TESTS,
 * AC-GOV-NO-TEST-ONLY-MEMBERS-IN-EXTRACTED-CODE (no test-only members),
 * AC-ERR-SOLVE-FAILURE-PROPAGATION.
 */
public class DefaultComputeStep implements ComputeStep {

    /** Exit code for dispatcher unreachable (EX_TEMPFAIL). */
    private static final int EXIT_DISPATCHER_UNREACHABLE = 75;

    /** Exit code for deprecated algorithm at submit time (EX_CONFIG). */
    private static final int EXIT_SUBMIT_REJECTED_DEPRECATED = 78;

    private final DispatcherClient dispatcherClient;
    private final ResultSigner resultSigner;
    private final String signingAlgorithm;
    private final ObjectMapper objectMapper;

    /**
     * Constructs a new {@code DefaultComputeStep}.
     *
     * @param dispatcherClient HTTP client for pull-packet and submit-result; must not be {@code
     *     null}
     * @param resultSigner signs the result payload before submission; must not be {@code null}
     * @param signingAlgorithm the signing algorithm identifier (e.g., {@code "Ed25519"}) advertised
     *     in pull-packet requests (DEC-43 D2); must not be {@code null}
     */
    public DefaultComputeStep(
            DispatcherClient dispatcherClient, ResultSigner resultSigner, String signingAlgorithm) {
        this.dispatcherClient = Objects.requireNonNull(dispatcherClient, "dispatcherClient");
        this.resultSigner = Objects.requireNonNull(resultSigner, "resultSigner");
        this.signingAlgorithm = Objects.requireNonNull(signingAlgorithm, "signingAlgorithm");
        this.objectMapper =
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * {@inheritDoc}
     *
     * @throws ComputeStepException if the dispatcher is unreachable, the algorithm is deprecated at
     *     submit time, packet deserialization fails, or signing fails — the exception carries the
     *     recommended exit code (75 or 78)
     */
    @Override
    public ComputeStepResult execute(UUID workerId, List<String> supportedAlgorithms)
            throws ComputeStepException {
        PullPacketRequest pullRequest = new PullPacketRequest(workerId, supportedAlgorithms);

        Optional<PullPacketResponse> packetOpt;
        try {
            packetOpt = dispatcherClient.pullPacketOptional(pullRequest);
        } catch (DispatcherException e) {
            throw new ComputeStepException(
                    EXIT_DISPATCHER_UNREACHABLE,
                    "dispatcher unreachable during pull-packet: " + e.getMessage(),
                    e);
        }

        if (packetOpt.isEmpty()) {
            return ComputeStepResult.NO_PACKET;
        }

        PullPacketResponse packet = packetOpt.get();
        PacketResult packetResult = solvePacket(packet);

        String resultPayloadJson = buildResultPayloadJson(packet, packetResult);
        SubmitResultPayload signingPayload =
                new SubmitResultPayload(
                        packet.packetId(), workerId, signingAlgorithm, resultPayloadJson);
        byte[] signature;
        try {
            signature = resultSigner.signResult(signingPayload);
        } catch (SigningException e) {
            throw new ComputeStepException(
                    EXIT_DISPATCHER_UNREACHABLE, "signing failed: " + e.getMessage(), e);
        }

        SubmitResultRequest submitRequest =
                new SubmitResultRequest(
                        packet.packetId(),
                        workerId,
                        signingAlgorithm,
                        signature,
                        resultPayloadJson);

        SubmitResultResponse submitResponse;
        try {
            submitResponse = dispatcherClient.submitResult(submitRequest);
        } catch (DispatcherException e) {
            if (e.getHttpStatus() == 410) {
                throw new ComputeStepException(
                        EXIT_SUBMIT_REJECTED_DEPRECATED,
                        "submit-result rejected: algorithm deprecated (HTTP 410)",
                        e);
            }
            throw new ComputeStepException(
                    EXIT_DISPATCHER_UNREACHABLE,
                    "dispatcher unreachable during submit-result: " + e.getMessage(),
                    e);
        }

        // AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED: distinguish accepted vs superseded
        return submitResponse.accepted()
                ? ComputeStepResult.PACKET_PROCESSED
                : ComputeStepResult.PACKET_SUPERSEDED;
    }

    /**
     * Deserializes {@code packetPayloadJson} from the pull-packet response and invokes {@code
     * PacketSolver.solvePacket}.
     *
     * @throws ComputeStepException if deserialization fails (AC-ERR-SOLVE-FAILURE-PROPAGATION)
     */
    private PacketResult solvePacket(PullPacketResponse packet) throws ComputeStepException {
        PacketPayload payload;
        try {
            payload = objectMapper.readValue(packet.packetPayloadJson(), PacketPayload.class);
        } catch (IOException e) {
            throw new ComputeStepException(
                    EXIT_DISPATCHER_UNREACHABLE,
                    "failed to deserialize packetPayloadJson: " + e.getMessage(),
                    e);
        }

        PacketPayload.CanonicalPhaseDefJson phaseDef = payload.getCanonicalPhaseDef();
        CanonicalPhaseDef canonicalPhaseDef =
                new CanonicalPhaseDef(
                        phaseDef.getRowCount(), phaseDef.getAvatarCount(), phaseDef.getRows());
        JobDef jobDef = new JobDef(payload.getJobId(), payload.getN(), canonicalPhaseDef);

        return PacketSolver.solvePacket(jobDef, payload.getRankFrom(), payload.getRankTo());
    }

    /**
     * Builds the {@code resultPayloadJson} string for the submit-result request.
     *
     * <p>The canonical result payload contains the computation outcome that the dispatcher will
     * JCS-canonicalize and verify against the worker's public key (per Brief D-10 + O-6 (ii)).
     */
    private String buildResultPayloadJson(PullPacketResponse packet, PacketResult result) {
        return "{"
                + "\"packetId\":\""
                + packet.packetId()
                + "\","
                + "\"bestRank\":"
                + result.bestRank()
                + ","
                + "\"bestScore\":"
                + result.bestScore()
                + "}";
    }
}
