// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.runtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Cross-package contract test for the {@link WorkerLoop} interface.
 *
 * <p>Per DEC-36: test is in {@code runtime} package — references only the public {@link WorkerLoop}
 * interface, not the implementation.
 *
 * <p>Story: E41S05 AC-WORKER-LOOP-INTERFACE.
 */
@ExtendWith(MockitoExtension.class)
class WorkerLoopTest {

    @Mock private WorkerLoop workerLoop;

    /** TC-16: WorkerLoop.run() can be invoked normally without throwing. */
    @Test
    void run_interfaceInvocable() throws WorkerLoopException {
        workerLoop.run(); // should not throw
    }

    /** TC-17: WorkerLoop.run() can throw WorkerLoopException. */
    @Test
    void run_canThrowWorkerLoopException() throws WorkerLoopException {
        doThrow(new WorkerLoopException(78, "submit rejected", null)).when(workerLoop).run();

        assertThatThrownBy(workerLoop::run)
                .isInstanceOf(WorkerLoopException.class)
                .satisfies(
                        e -> {
                            WorkerLoopException wle = (WorkerLoopException) e;
                            assert wle.getExitCode() == 78;
                        });
    }
}
