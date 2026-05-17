// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when input validation fails (E21S09, AC-TDD-ValidationException,
 * AC-PKG-ValidationException).
 *
 * <p>This is the new boundary-API exception at {@code de.vvwt.tm.tournament.exceptions.*} per
 * DEC-21 (D-8 package discipline). It replaces the legacy {@code
 * de.vvwt.tm.domain.ValidationException} at atomic cutover time. During the parallel-development
 * phase, both coexist.
 *
 * <p>This is an unchecked exception so that Spring's {@code @Transactional} handling automatically
 * triggers rollback — no {@code rollbackFor} attribute needed.
 *
 * <p>Mapped to HTTP 400 by {@code GlobalExceptionHandler} (E21S10).
 *
 * <h2>Security contract</h2>
 *
 * <p>This exception MUST NOT embed credentials, session tokens, or raw SQL in its message or cause
 * (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.domain.ValidationException legacy counterpart (untouched until cutover)
 */
public class ValidationException extends RuntimeException {

    /**
     * Constructs a {@code ValidationException} with a human-readable message.
     *
     * @param message human-readable description of the validation failure
     */
    public ValidationException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ValidationException} with a message and the underlying cause.
     *
     * @param message human-readable description of the validation failure
     * @param cause the underlying cause (preserved by {@link Throwable#getCause()})
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
