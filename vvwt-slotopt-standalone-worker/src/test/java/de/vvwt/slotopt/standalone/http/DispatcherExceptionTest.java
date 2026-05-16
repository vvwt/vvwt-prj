// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Cross-package test for {@link DispatcherException}.
 *
 * <p>Tests in {@code de.vvwt.slotopt.standalone.http} (same package as subject) — allowed to
 * reference the concrete exception class directly per DEC-36 same-package carve-out.
 *
 * <p>Story: E41S04 AC-DISPATCHER-EXCEPTION.
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
