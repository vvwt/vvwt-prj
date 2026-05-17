// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a business constraint is violated, resulting in HTTP 409 Conflict (E21S09,
 * AC-TDD-ConflictException, AC-PKG-ConflictException).
 *
 * <p>This is the new boundary-API exception at {@code de.vvwt.tm.tournament.exceptions.*} per
 * DEC-21 (D-8 package discipline). It replaces the legacy {@code
 * de.vvwt.tm.infrastructure.web.ConflictException} at atomic cutover time. During the
 * parallel-development phase, both coexist.
 *
 * <h2>Examples</h2>
 *
 * <ul>
 *   <li>Activating a second tournament when one is already active (DEC-5)
 *   <li>Any domain invariant violation that surfaces as HTTP 409
 * </ul>
 *
 * <p>Mapped to HTTP 409 by {@code GlobalExceptionHandler} (E21S10).
 *
 * <h2>Security contract</h2>
 *
 * <p>This exception MUST NOT embed credentials, session tokens, or raw SQL in its message or cause.
 * The message must not contain internal system details (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.infrastructure.web.ConflictException legacy counterpart (untouched until cutover)
 */
public class ConflictException extends RuntimeException {

    /**
     * Constructs a {@code ConflictException} with a human-readable message.
     *
     * @param message human-readable description of the conflict (must not contain credentials, SQL,
     *     or stack traces)
     */
    public ConflictException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ConflictException} with a message and the underlying cause.
     *
     * @param message human-readable description of the conflict
     * @param cause the underlying cause (preserved by {@link Throwable#getCause()})
     */
    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
