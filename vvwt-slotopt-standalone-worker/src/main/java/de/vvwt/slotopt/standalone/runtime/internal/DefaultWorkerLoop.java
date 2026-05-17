// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.runtime.internal;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.log.StructuredLogger;
import de.vvwt.slotopt.standalone.runtime.CpuThrottle;
import de.vvwt.slotopt.standalone.runtime.WorkerLoop;
import de.vvwt.slotopt.standalone.runtime.WorkerLoopException;
import de.vvwt.slotopt.worker.runtime.ComputeStep;
import de.vvwt.slotopt.worker.runtime.ComputeStepException;
import de.vvwt.slotopt.worker.runtime.ComputeStepResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of {@link WorkerLoop}.
 *
 * <p>Orchestrates the polling loop per E37S02 spec § (c) "Polling Loop Semantics":
 *
 * <ol>
 *   <li>Delegate one compute iteration to the injected {@link ComputeStep} (pull-solve-sign-submit)
 *   <li>If {@link ComputeStepResult#NO_PACKET}: sleep(pollInterval) via {@link CpuThrottle}
 *   <li>If {@link ComputeStepResult#PACKET_PROCESSED}: emit observability event and continue
 *   <li>Repeat until graceful-shutdown requested ({@code shutdownRequested}) or unrecoverable error
 * </ol>
 *
 * <p>The JVM shutdown hook is registered in {@link
 * de.vvwt.slotopt.standalone.OptimizerWorkerMain#main} (relocated from the constructor per E63S01
 * to eliminate the test-only coupling to the JVM hook registration — DEC-70,
 * AC-GOV-NO-TEST-ONLY-MEMBERS-IN-EXTRACTED-CODE).
 *
 * <p>DEC-35-by-analogy: implementation in {@code runtime.internal}; public interface {@link
 * WorkerLoop} in {@code runtime}.
 *
 * <p>Story: E41S05 AC-DEFAULT-WORKER-LOOP (re-wired in E63S01); E63S01
 * AC-GOV-NO-TEST-ONLY-MEMBERS-IN-EXTRACTED-CODE, AC-TEST-OUTAGE-SEAM-PLUGGABLE.
 */
public class DefaultWorkerLoop implements WorkerLoop {

    private final ComputeStep computeStep;
    private final WorkerConfig config;
    private final CpuThrottle cpuThrottle;
    private final StructuredLogger logger;
    private final UUID workerId;

    /** Volatile shutdown flag — set by JVM shutdown hook (registered in main()) or by tests. */
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /**
     * Constructs a new {@code DefaultWorkerLoop}.
     *
     * <p>The JVM shutdown hook MUST be registered by the caller (e.g., {@link
     * de.vvwt.slotopt.standalone.OptimizerWorkerMain#main}) via {@link #requestShutdown()}.
     *
     * @param computeStep the shared compute-path step (pull-solve-sign-submit); must not be {@code
     *     null}
     * @param config worker configuration; used for {@code pollInterval} and {@code
     *     signingAlgorithm}
     * @param cpuThrottle paces the polling loop on NO_PACKET responses
     * @param logger structured event logger for observability
     * @param workerId the registered worker ID (from bootstrap phase)
     */
    public DefaultWorkerLoop(
            ComputeStep computeStep,
            WorkerConfig config,
            CpuThrottle cpuThrottle,
            StructuredLogger logger,
            UUID workerId) {
        this.computeStep = computeStep;
        this.config = config;
        this.cpuThrottle = cpuThrottle;
        this.logger = logger;
        this.workerId = workerId;
    }

    /**
     * Requests graceful shutdown of the polling loop. The current iteration (if any) completes
     * before the loop exits.
     *
     * <p>Called by the JVM shutdown hook registered in {@link
     * de.vvwt.slotopt.standalone.OptimizerWorkerMain#main} and may also be called in tests.
     */
    public void requestShutdown() {
        shutdownRequested.set(true);
    }

    /** {@inheritDoc} */
    @Override
    public void run() throws WorkerLoopException {
        List<String> supportedAlgorithms = List.of(config.signingAlgorithm());

        while (!shutdownRequested.get()) {
            ComputeStepResult stepResult;
            try {
                stepResult = computeStep.execute(workerId, supportedAlgorithms);
            } catch (ComputeStepException e) {
                logger.error("dispatcher_unreachable", "compute step failed: " + e.getMessage());
                throw new WorkerLoopException(
                        e.getExitCode(), "compute step failed: " + e.getMessage(), e);
            }

            if (stepResult == ComputeStepResult.NO_PACKET) {
                // HTTP 204 — no packets available; back off per AC-EMPTY-PULL-RESPONSE-BACKOFF
                try {
                    cpuThrottle.sleep(config.pollInterval());
                } catch (WorkerLoopException e) {
                    // INTERRUPTED exit code carried by e
                    emitWorkerStopped(e.getExitCode());
                    throw e;
                }
                continue;
            }

            // Packet was pulled and processed — emit observability events
            // AC-TEST-COMPUTE-PATH-BEHAVIOUR-PRESERVED: preserve event names from old loop
            String workerIdStr = workerId.toString();
            logger.info("packet_pulled", Map.of("workerId", workerIdStr));

            if (stepResult == ComputeStepResult.PACKET_PROCESSED) {
                logger.info("result_submitted", Map.of("workerId", workerIdStr));
                logger.info("packet_processed", Map.of("workerId", workerIdStr));
            } else {
                // PACKET_SUPERSEDED — accepted=false
                logger.info("result_superseded", Map.of("workerId", workerIdStr));
                logger.info("packet_processed", Map.of("workerId", workerIdStr));
            }
            logger.info("packet_processed_cycle", Map.of("workerId", workerIdStr));
        }

        // AC-GRACEFUL-SHUTDOWN: emits worker_stopped, returns 0
        emitWorkerStopped(0);
    }

    /** Emits the {@code worker_stopped} observability event before final exit. */
    private void emitWorkerStopped(int exitCode) {
        logger.info("worker_stopped", Map.of("exitCode", String.valueOf(exitCode)));
    }
}
