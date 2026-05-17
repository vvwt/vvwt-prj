// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SigningException}.
 *
 * <p>Moved from {@code vvwt-slotopt-standalone-worker} to {@code vvwt-slotopt-worker-runtime} in
 * E63S01 (AC-MOD-RUNTIME-LIBRARY-MODULE).
 *
 * <p>Story: E41S03 AC-SIGNING-EXCEPTION; E63S01 AC-MOD-RUNTIME-LIBRARY-MODULE.
 */
class SigningExceptionTest {

    @Test
    void wrapsMessageAndCause() {
        RuntimeException cause = new RuntimeException("jce error");

        SigningException ex = new SigningException("signing failed", cause);

        assertThat(ex.getMessage()).isEqualTo("signing failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isRuntimeException() {
        SigningException ex = new SigningException("msg", new RuntimeException());

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void messageOnlyConstructorAvailable() {
        SigningException ex = new SigningException("only message", null);
        assertThat(ex.getMessage()).isEqualTo("only message");
    }
}
