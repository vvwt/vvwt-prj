// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime;

import java.util.List;
import java.util.UUID;

/**
 * A single iteration of the worker compute loop: pull one packet from the dispatcher, solve it,
 * sign the result, and submit it.
 *
 * <p>This interface is the shared compute-path seam used by both the standalone worker and (in
 * E63S03+) the TM embedded worker. Callers orchestrate the lifecycle (loop control, backoff, outage
 * handling); this interface handles only one iteration.
 *
 * <p>DEC-35-by-analogy: public interface in the {@code runtime} root package; canonical
 * implementation {@link de.vvwt.slotopt.worker.runtime.internal.DefaultComputeStep} in {@code
 * runtime.internal}.
 *
 * <p>Story: E63S01 AC-GOV-NO-TEST-ONLY-MEMBERS-IN-EXTRACTED-CODE (replaces the test-only {@code
 * stopAfterNextIteration} seam in the old {@code DefaultWorkerLoop});
 * AC-TEST-OUTAGE-SEAM-PLUGGABLE.
 */
public interface ComputeStep {

    /**
     * Executes one compute-loop iteration.
     *
     * <p>Pulls the next packet from the dispatcher; if HTTP 204 (no packet available), returns
     * {@link ComputeStepResult#NO_PACKET}. If a packet is available, solves it via the worker-lib
     * {@code PacketSolver}, signs the result via {@link ResultSigner}, submits it, and returns
     * {@link ComputeStepResult#PACKET_PROCESSED}.
     *
     * @param workerId the registered worker UUID (from bootstrap)
     * @param supportedAlgorithms the list of algorithm IDs the worker advertises per DEC-43 D2
     * @return the outcome of this iteration
     * @throws ComputeStepException if the step fails unrecoverably (dispatcher I/O failure,
     *     deprecated algorithm on submit, packet deserialization failure, signing failure); the
     *     exception carries the recommended exit code so the caller can apply its own lifecycle
     *     policy (e.g., terminate for the standalone worker; retry/resume for the embedded worker)
     */
    ComputeStepResult execute(UUID workerId, List<String> supportedAlgorithms)
            throws ComputeStepException;
}
