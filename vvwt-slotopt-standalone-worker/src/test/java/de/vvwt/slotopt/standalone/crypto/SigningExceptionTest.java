// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SigningException}.
 *
 * <p>TDD Iron Law (DEC-22): authored RED-first before {@link SigningException} exists.
 *
 * <p>Same-package test (DEC-36): located in {@code de.vvwt.slotopt.standalone.crypto}.
 *
 * <p>Story: E41S03 AC-SIGNING-EXCEPTION.
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
        // SigningException(String message, Throwable cause) — AC specifies this constructor form
        SigningException ex = new SigningException("only message", null);
        assertThat(ex.getMessage()).isEqualTo("only message");
    }
}
