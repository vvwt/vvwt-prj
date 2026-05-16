// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.http;

/**
 * Thrown when communication with the dispatcher fails — non-2xx HTTP status or I/O error.
 *
 * <p>Carries the HTTP status code (0 for I/O errors that produce no HTTP response), the message,
 * and the optional cause.
 *
 * <p>DEC-35-by-analogy: resides in the public {@code http} package as a public exception type
 * crossing the package boundary (used by callers of {@link DispatcherClient}).
 *
 * <p>Story: E41S04 AC-DISPATCHER-EXCEPTION.
 */
public class DispatcherException extends RuntimeException {

    private final int httpStatus;

    /**
     * Constructs a new {@code DispatcherException}.
     *
     * @param httpStatus HTTP status code; 0 if the error was an I/O failure before HTTP response
     * @param message human-readable error description
     * @param cause the underlying exception, or {@code null}
     */
    public DispatcherException(int httpStatus, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /**
     * Returns the HTTP status code associated with this error.
     *
     * @return HTTP status code, or 0 for I/O failures with no HTTP response
     */
    public int getHttpStatus() {
        return httpStatus;
    }
}
