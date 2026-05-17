// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Cross-package tests for {@link WorkerLoopException}.
 *
 * <p>Per DEC-36 cross-package rule: this test is in a different package from {@code
 * runtime.internal} and references the public {@code runtime} type directly.
 *
 * <p>Story: E41S05 AC-CPU-THROTTLE (interrupt handling), AC-EXIT-CODE-RUNTIME.
 */
class WorkerLoopExceptionTest {

    /** TC-1: WorkerLoopException carries exit code and message. */
    @Test
    void constructor_storesExitCodeAndMessage() {
        WorkerLoopException ex = new WorkerLoopException(78, "submit rejected", null);

        assertThat(ex.getExitCode()).isEqualTo(78);
        assertThat(ex.getMessage()).isEqualTo("submit rejected");
        assertThat(ex.getCause()).isNull();
    }

    /** TC-2: WorkerLoopException carries cause. */
    @Test
    void constructor_storesCause() {
        RuntimeException cause = new RuntimeException("underlying");
        WorkerLoopException ex = new WorkerLoopException(75, "dispatcher unreachable", cause);

        assertThat(ex.getExitCode()).isEqualTo(75);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /** TC-3: WorkerLoopException is a RuntimeException. */
    @Test
    void isRuntimeException() {
        WorkerLoopException ex = new WorkerLoopException(0, "graceful", null);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
