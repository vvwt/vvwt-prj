// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure JUnit tests for all five boundary-API exception classes at {@code
 * de.vvwt.tm.tournament.exceptions.*} (E21S09, AC-EXCEPTION-CONTRACT).
 *
 * <h2>RED state (DEC-22)</h2>
 *
 * <p>This test was committed RED: the five exception classes under {@code
 * de.vvwt.tm.tournament.exceptions.*} did not exist at commit time, causing a compile error. This
 * proves the tests were written before the implementation (DEC-22 Iron Law).
 *
 * <h2>Contract verified</h2>
 *
 * <ul>
 *   <li>Each exception extends {@link RuntimeException} (unchecked)
 *   <li>Single-message constructor: {@code getMessage()} returns the message
 *   <li>Message+cause constructor: {@code getMessage()} returns the message, {@code getCause()}
 *       returns the cause
 *   <li>No checked-exception declaration needed — verified implicitly by the test passing
 *       compilation without a {@code throws} clause
 * </ul>
 *
 * <h2>AC-SEC-EXCEPTION-NO-LEAK</h2>
 *
 * <p>None of the exception constructors accept {@code char[] password}, {@code String token}, or
 * {@code ResultSet} — verified by code inspection (grep-based check in QA) and by the constructor
 * signatures in this test file.
 */
@DisplayName("ExceptionContractTest — E21S09 AC-EXCEPTION-CONTRACT")
class ExceptionContractTest {

    // -----------------------------------------------------------------------
    // ForbiddenException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-ForbiddenException: extends RuntimeException, (String) constructor")
    void forbiddenException_messageConstructor() {
        ForbiddenException ex = new ForbiddenException("forbidden");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("forbidden");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("AC-TDD-ForbiddenException: (String, Throwable) constructor — cause chaining")
    void forbiddenException_causeConstructor() {
        Throwable cause = new IllegalStateException("root");
        ForbiddenException ex = new ForbiddenException("forbidden", cause);
        assertThat(ex.getMessage()).isEqualTo("forbidden");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // -----------------------------------------------------------------------
    // UnauthorizedException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-UnauthorizedException: extends RuntimeException, (String) constructor")
    void unauthorizedException_messageConstructor() {
        UnauthorizedException ex = new UnauthorizedException("unauthorized");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("unauthorized");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("AC-TDD-UnauthorizedException: (String, Throwable) constructor — cause chaining")
    void unauthorizedException_causeConstructor() {
        Throwable cause = new IllegalStateException("root");
        UnauthorizedException ex = new UnauthorizedException("unauthorized", cause);
        assertThat(ex.getMessage()).isEqualTo("unauthorized");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // -----------------------------------------------------------------------
    // ValidationException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-ValidationException: extends RuntimeException, (String) constructor")
    void validationException_messageConstructor() {
        ValidationException ex = new ValidationException("invalid input");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isNotNull().isNotEmpty();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("AC-TDD-ValidationException: (String, Throwable) constructor — cause chaining")
    void validationException_causeConstructor() {
        Throwable cause = new IllegalStateException("root");
        ValidationException ex = new ValidationException("invalid input", cause);
        assertThat(ex.getMessage()).isNotNull().isNotEmpty();
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // -----------------------------------------------------------------------
    // TooManyRequestsException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-TooManyRequestsException: extends RuntimeException, (String) constructor")
    void tooManyRequestsException_messageConstructor() {
        TooManyRequestsException ex = new TooManyRequestsException("too many");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("too many");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName(
            "AC-TDD-TooManyRequestsException: (String, Throwable) constructor — cause chaining")
    void tooManyRequestsException_causeConstructor() {
        Throwable cause = new IllegalStateException("root");
        TooManyRequestsException ex = new TooManyRequestsException("too many", cause);
        assertThat(ex.getMessage()).isEqualTo("too many");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // -----------------------------------------------------------------------
    // ConflictException
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-TDD-ConflictException: extends RuntimeException, (String) constructor")
    void conflictException_messageConstructor() {
        ConflictException ex = new ConflictException("conflict");
        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getMessage()).isEqualTo("conflict");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("AC-TDD-ConflictException: (String, Throwable) constructor — cause chaining")
    void conflictException_causeConstructor() {
        Throwable cause = new IllegalStateException("root");
        ConflictException ex = new ConflictException("conflict", cause);
        assertThat(ex.getMessage()).isEqualTo("conflict");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
