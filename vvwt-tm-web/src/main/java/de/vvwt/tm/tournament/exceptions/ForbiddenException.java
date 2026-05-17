// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a request is authenticated but not authorized for the requested operation (E21S09,
 * AC-TDD-ForbiddenException, AC-PKG-ForbiddenException).
 *
 * <p>This is the new boundary-API exception at {@code de.vvwt.tm.tournament.exceptions.*} per
 * DEC-21 (D-8 package discipline). It replaces the legacy {@code
 * de.vvwt.tm.domain.ForbiddenException} at atomic cutover time. During the parallel-development
 * phase, both coexist.
 *
 * <p>Distinguishes from {@link UnauthorizedException} (invalid/missing credentials → 401) by
 * conveying that the credentials are valid but the caller is not authorized for the requested
 * operation (→ HTTP 403).
 *
 * <p>Mapped to HTTP 403 by {@code GlobalExceptionHandler} (E21S10).
 *
 * <h2>Security contract</h2>
 *
 * <p>This exception MUST NOT embed credentials, session tokens, or raw SQL in its message or cause.
 * Constructors accept only {@code String message} and {@code Throwable cause}
 * (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see UnauthorizedException for HTTP 401
 * @see de.vvwt.tm.domain.ForbiddenException legacy counterpart (untouched until cutover)
 */
public class ForbiddenException extends RuntimeException {

    /**
     * Constructs a {@code ForbiddenException} with a human-readable message.
     *
     * @param message human-readable description of the forbidden operation
     */
    public ForbiddenException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ForbiddenException} with a message and the underlying cause.
     *
     * @param message human-readable description of the forbidden operation
     * @param cause the underlying cause (preserved by {@link Throwable#getCause()})
     */
    public ForbiddenException(String message, Throwable cause) {
        super(message, cause);
    }
}
