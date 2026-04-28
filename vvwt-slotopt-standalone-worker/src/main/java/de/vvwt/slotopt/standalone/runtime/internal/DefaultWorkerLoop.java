package de.vvwt.slotopt.standalone.runtime.internal;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.standalone.crypto.ResultSigner;
import de.vvwt.slotopt.standalone.crypto.SigningException;
import de.vvwt.slotopt.standalone.crypto.SubmitResultPayload;
import de.vvwt.slotopt.standalone.http.DispatcherClient;
import de.vvwt.slotopt.standalone.http.DispatcherException;
import de.vvwt.slotopt.standalone.http.PullPacketRequest;
import de.vvwt.slotopt.standalone.http.PullPacketResponse;
import de.vvwt.slotopt.standalone.http.SubmitResultRequest;
import de.vvwt.slotopt.standalone.http.SubmitResultResponse;
import de.vvwt.slotopt.standalone.log.StructuredLogger;
import de.vvwt.slotopt.standalone.runtime.CpuThrottle;
import de.vvwt.slotopt.standalone.runtime.WorkerLoop;
import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.solver.PacketSolver;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PacketResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of {@link WorkerLoop}.
 *
 * <p>Implements the polling loop per E37S02 spec § (c) "Polling Loop Semantics":
 *
 * <ol>
 *   <li>POST /api/pull-packet with {@code supportedAlgorithms: [signingAlgorithm]} (DEC-43 D2)
 *   <li>If HTTP 204: sleep(pollInterval) via {@link CpuThrottle} and continue
 *   <li>If HTTP 200: deserialize {@code packetPayloadJson} → invoke {@code
 *       PacketSolver.solvePacket}
 *   <li>Sign result via {@link ResultSigner} → POST /api/submit-result with {@code algorithm} field
 *   <li>Emit structured observability events per AC-OBSERVABILITY-EVENTS-RUNTIME
 *   <li>Repeat until graceful-shutdown requested or unrecoverable error
 * </ol>
 *
 * <p>DEC-35-by-analogy: implementation in {@code runtime.internal}; public interface {@link
 * WorkerLoop} in {@code runtime}.
 *
 * <p>Story: E41S05 AC-DEFAULT-WORKER-LOOP, AC-PULL-PACKET-WITH-CAPABILITY-ADVERTISEMENT,
 * AC-PACKET-PROCESSING, AC-SUBMIT-RESULT-WITH-ALGORITHM, AC-EMPTY-PULL-RESPONSE-BACKOFF,
 * AC-HTTP-410-DEPRECATED-AT-SUBMIT, AC-HTTP-410-DEPRECATED-AT-REGISTER, AC-GRACEFUL-SHUTDOWN,
 * AC-OBSERVABILITY-EVENTS-RUNTIME, AC-EXIT-CODE-RUNTIME.
 */
public class DefaultWorkerLoop implements WorkerLoop {

    private final DispatcherClient dispatcherClient;
    private final ResultSigner resultSigner;
    private final WorkerKeyManager workerKeyManager;
    private final WorkerConfig config;
    private final CpuThrottle cpuThrottle;
    private final StructuredLogger logger;
    private final UUID workerId;
    private final ObjectMapper objectMapper;

    /** Volatile shutdown flag — set by JVM shutdown hook or by {@link #requestShutdown()}. */
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /**
     * Test-only flag: stop after the next iteration completes (regardless of work done).
     * Package-private so same-package tests can set it directly.
     */
    volatile boolean stopAfterNextIteration = false;

    /**
     * Constructs a new {@code DefaultWorkerLoop}.
     *
     * @param dispatcherClient HTTP client for pull-packet and submit-result
     * @param resultSigner signs the result payload before submission
     * @param workerKeyManager manages the worker's keypair (not used directly in loop —
     *     ResultSigner owns signing; included for constructor completeness per
     *     AC-DEFAULT-WORKER-LOOP)
     * @param config worker configuration
     * @param cpuThrottle paces the polling loop on 204 responses
     * @param logger structured event logger for observability
     * @param workerId the registered worker ID (from bootstrap phase)
     */
    public DefaultWorkerLoop(
            DispatcherClient dispatcherClient,
            ResultSigner resultSigner,
            WorkerKeyManager workerKeyManager,
            WorkerConfig config,
            CpuThrottle cpuThrottle,
            StructuredLogger logger,
            UUID workerId) {
        this.dispatcherClient = dispatcherClient;
        this.resultSigner = resultSigner;
        this.workerKeyManager = workerKeyManager;
        this.config = config;
        this.cpuThrottle = cpuThrottle;
        this.logger = logger;
        this.workerId = workerId;
        this.objectMapper =
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        // AC-GRACEFUL-SHUTDOWN: register JVM shutdown hook
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    shutdownRequested.set(true);
                                    // The hook signals shutdown; the loop itself emits
                                    // worker_stopped
                                    // and exits. We do not join the loop thread here (would
                                    // deadlock).
                                },
                                "worker-shutdown-hook"));
    }

    /**
     * Requests graceful shutdown of the polling loop. The current iteration (if any) completes
     * before the loop exits. Called by the JVM shutdown hook and by tests.
     */
    public void requestShutdown() {
        shutdownRequested.set(true);
    }

    /**
     * Test-only: instruct the loop to stop after the next successful iteration (allows
     * single-iteration tests without hanging).
     */
    public void stopAfterNextIteration() {
        stopAfterNextIteration = true;
    }

    /** {@inheritDoc} */
    @Override
    public void run() throws WorkerLoopException {
        List<String> supportedAlgorithms = List.of(config.signingAlgorithm());

        while (!shutdownRequested.get()) {
            PullPacketRequest pullRequest = new PullPacketRequest(workerId, supportedAlgorithms);

            Optional<PullPacketResponse> packetOpt;
            try {
                packetOpt = dispatcherClient.pullPacketOptional(pullRequest);
            } catch (DispatcherException e) {
                logger.error("dispatcher_unreachable", "pull-packet failed: " + e.getMessage());
                throw new WorkerLoopException(
                        ExitCode.DISPATCHER_UNREACHABLE_RUNTIME,
                        "dispatcher unreachable during pull-packet: " + e.getMessage(),
                        e);
            }

            if (packetOpt.isEmpty()) {
                // HTTP 204 — no packets available; back off per AC-EMPTY-PULL-RESPONSE-BACKOFF
                try {
                    cpuThrottle.sleep(config.pollInterval());
                } catch (WorkerLoopException e) {
                    // INTERRUPTED exit code carried by e
                    emitWorkerStopped(e.getExitCode());
                    throw e;
                }
                if (stopAfterNextIteration) {
                    break;
                }
                continue;
            }

            PullPacketResponse packet = packetOpt.get();

            // AC-OBSERVABILITY-EVENTS-RUNTIME: packet_pulled
            logger.info(
                    "packet_pulled",
                    Map.of(
                            "packetId", packet.packetId().toString(),
                            "jobId", packet.jobId().toString()));

            // AC-PACKET-PROCESSING: deserialize + solve
            PacketResult packetResult = solvePacket(packet);

            // AC-OBSERVABILITY-EVENTS-RUNTIME: packet_processed
            logger.info(
                    "packet_processed",
                    Map.of(
                            "packetId", packet.packetId().toString(),
                            "jobId", packet.jobId().toString(),
                            "durationMs",
                                    String.valueOf(packetResult.wallClockNanos() / 1_000_000L)));

            // AC-SUBMIT-RESULT-WITH-ALGORITHM: sign + submit
            String resultPayloadJson = buildResultPayloadJson(packet, packetResult);
            SubmitResultPayload signingPayload =
                    new SubmitResultPayload(
                            packet.packetId(),
                            workerId,
                            config.signingAlgorithm(),
                            resultPayloadJson);
            byte[] signature;
            try {
                signature = resultSigner.signResult(signingPayload);
            } catch (SigningException e) {
                throw new WorkerLoopException(
                        ExitCode.DISPATCHER_UNREACHABLE_RUNTIME, // best mapping for sign failure
                        "signing failed: " + e.getMessage(),
                        e);
            }

            SubmitResultRequest submitRequest =
                    new SubmitResultRequest(
                            packet.packetId(),
                            workerId,
                            config.signingAlgorithm(),
                            signature,
                            resultPayloadJson);

            SubmitResultResponse submitResponse;
            try {
                submitResponse = dispatcherClient.submitResult(submitRequest);
            } catch (DispatcherException e) {
                if (e.getHttpStatus() == 410) {
                    // AC-HTTP-410-DEPRECATED-AT-SUBMIT: log ERROR + exit
                    logger.error(
                            "submit_rejected_deprecated",
                            "HTTP 410 at submit-result: algorithm "
                                    + config.signingAlgorithm()
                                    + " deprecated past deadline");
                    throw new WorkerLoopException(
                            ExitCode.SUBMIT_REJECTED_DEPRECATED,
                            "submit-result rejected: algorithm deprecated (HTTP 410)",
                            e);
                }
                logger.error("dispatcher_unreachable", "submit-result failed: " + e.getMessage());
                throw new WorkerLoopException(
                        ExitCode.DISPATCHER_UNREACHABLE_RUNTIME,
                        "dispatcher unreachable during submit-result: " + e.getMessage(),
                        e);
            }

            if (submitResponse.accepted()) {
                // AC-OBSERVABILITY-EVENTS-RUNTIME: result_submitted
                logger.info(
                        "result_submitted",
                        Map.of(
                                "packetId", packet.packetId().toString(),
                                "jobId", packet.jobId().toString(),
                                "accepted", "true"));
            } else {
                // AC-OBSERVABILITY-EVENTS-RUNTIME: result_superseded (DEC-6 first-valid-wins)
                logger.info(
                        "result_superseded",
                        Map.of(
                                "packetId", packet.packetId().toString(),
                                "jobId", packet.jobId().toString(),
                                "reason",
                                        submitResponse.reason() != null
                                                ? submitResponse.reason()
                                                : "superseded"));
            }

            if (stopAfterNextIteration) {
                break;
            }
        }

        // AC-GRACEFUL-SHUTDOWN: emits worker_stopped, returns 0
        emitWorkerStopped(0);
    }

    /**
     * Deserializes {@code packetPayloadJson} from the pull-packet response and invokes {@code
     * PacketSolver.solvePacket}.
     */
    private PacketResult solvePacket(PullPacketResponse packet) throws WorkerLoopException {
        PacketPayload payload;
        try {
            payload = objectMapper.readValue(packet.packetPayloadJson(), PacketPayload.class);
        } catch (IOException e) {
            throw new WorkerLoopException(
                    ExitCode.DISPATCHER_UNREACHABLE_RUNTIME,
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
     * JCS-canonicalize and verify against the worker's public key.
     */
    private String buildResultPayloadJson(PullPacketResponse packet, PacketResult result) {
        // Construct minimal JSON payload with packetId + bestRank + bestScore
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

    /** Emits the {@code worker_stopped} observability event before final exit. */
    private void emitWorkerStopped(int exitCode) {
        logger.info("worker_stopped", Map.of("exitCode", String.valueOf(exitCode)));
    }
}
