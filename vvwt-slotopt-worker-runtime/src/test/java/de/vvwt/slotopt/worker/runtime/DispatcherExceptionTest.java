// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DispatcherException}.
 *
 * <p>Moved from {@code vvwt-slotopt-standalone-worker} to {@code vvwt-slotopt-worker-runtime} in
 * E63S01 (AC-MOD-RUNTIME-LIBRARY-MODULE).
 *
 * <p>Story: E41S04 AC-DISPATCHER-EXCEPTION; E63S01 AC-MOD-RUNTIME-LIBRARY-MODULE.
 */
class DispatcherExceptionTest {

    /** TC-1: DispatcherException carries httpStatus + message + cause. */
    @Test
    void constructor_stores_httpStatus_message_cause() {
        RuntimeException cause = new RuntimeException("network error");

        DispatcherException ex = new DispatcherException(503, "service unavailable", cause);

        assertThat(ex.getHttpStatus()).isEqualTo(503);
        assertThat(ex.getMessage()).isEqualTo("service unavailable");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    /** TC-2a: DispatcherException is a RuntimeException. */
    @Test
    void is_runtime_exception() {
        DispatcherException ex = new DispatcherException(500, "error", null);

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    /** TC-2b: DispatcherException can be constructed with null cause. */
    @Test
    void constructor_accepts_null_cause() {
        DispatcherException ex = new DispatcherException(410, "gone", null);

        assertThat(ex.getHttpStatus()).isEqualTo(410);
        assertThat(ex.getCause()).isNull();
    }
}
