// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.slotopt.standalone.bootstrap.ExitCode;
import de.vvwt.slotopt.worker.runtime.DispatcherException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StandaloneOutagePolicy}.
 *
 * <p>Authored RED-first before the implementation, per DEC-22 Iron Law (Q-1a: behaviour-changing
 * extraction — the outage-handling seam is now pluggable, and this tests the standalone-worker
 * variant).
 *
 * <p>Same package as {@link StandaloneOutagePolicy}: white-box is appropriate here since {@link
 * StandaloneOutagePolicy} is not in an {@code .internal} sub-package.
 *
 * <p>Story: E63S01 AC-TEST-OUTAGE-SEAM-PLUGGABLE, AC-ERR-OUTAGE-SEAM-STANDALONE-UNCHANGED,
 * AC-GOV-RED-FIRST.
 */
class StandaloneOutagePolicyTest {

    /**
     * TC-OUT-01: {@code onOutage()} throws {@link WorkerLoopException} with exit code 75
     * (DISPATCHER_UNREACHABLE_RUNTIME).
     *
     * <p>RED before implementation: {@link StandaloneOutagePolicy} did not exist.
     */
    @Test
    void onOutage_throws_workerLoopException_with_exit75() {
        StandaloneOutagePolicy policy = new StandaloneOutagePolicy();
        DispatcherException cause = new DispatcherException(0, "I/O error", null);

        assertThatThrownBy(() -> policy.onOutage(cause))
                .isInstanceOf(WorkerLoopException.class)
                .satisfies(
                        e -> {
                            WorkerLoopException wle = (WorkerLoopException) e;
                            assertThat(wle.getExitCode())
                                    .isEqualTo(ExitCode.DISPATCHER_UNREACHABLE_RUNTIME);
                        });
    }

    /**
     * TC-OUT-02: The thrown {@link WorkerLoopException} carries the original {@link
     * DispatcherException} as its cause.
     */
    @Test
    void onOutage_exception_has_dispatcher_cause() {
        StandaloneOutagePolicy policy = new StandaloneOutagePolicy();
        DispatcherException cause = new DispatcherException(0, "connection refused", null);

        assertThatThrownBy(() -> policy.onOutage(cause))
                .isInstanceOf(WorkerLoopException.class)
                .hasCause(cause);
    }

    /**
     * TC-OUT-03: An alternative policy that returns normally (simulating the embedded-worker resume
     * policy) is a substitutable seam — validates the {@link
     * de.vvwt.slotopt.worker.runtime.OutagePolicy} contract is not locked to throwing.
     *
     * <p>This test demonstrates the seam admits an alternative policy
     * (AC-TEST-OUTAGE-SEAM-PLUGGABLE).
     */
    @Test
    void outagePolicy_seam_admits_alternative_resume_policy() {
        // Alternative (non-terminating) policy — what the embedded worker will use in E63S03
        de.vvwt.slotopt.worker.runtime.OutagePolicy resumePolicy =
                cause -> {
                    // Does NOT throw — just logs or retries
                };

        DispatcherException cause = new DispatcherException(0, "transient", null);
        // Must not throw
        resumePolicy.onOutage(cause);
    }
}
