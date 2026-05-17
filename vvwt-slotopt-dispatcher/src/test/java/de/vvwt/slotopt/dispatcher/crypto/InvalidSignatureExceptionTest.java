// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link InvalidSignatureException}.
 *
 * <p>RED-first per DEC-22 / AC-INVALID-SIGNATURE-EXCEPTION: tests written before the production
 * class is created.
 *
 * <p>Spec: E37S04 AC-INVALID-SIGNATURE-EXCEPTION.
 */
class InvalidSignatureExceptionTest {

    @Test
    void constructWithMessage_messageIsRetained() {
        var ex = new InvalidSignatureException("bad signature bytes");
        assertThat(ex.getMessage()).isEqualTo("bad signature bytes");
    }

    @Test
    void constructWithMessageAndCause_messageAndCauseAreRetained() {
        var cause = new RuntimeException("underlying jce error");
        var ex = new InvalidSignatureException("signature verification failed", cause);
        assertThat(ex.getMessage()).isEqualTo("signature verification failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isRuntimeException() {
        var ex = new InvalidSignatureException("test");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
