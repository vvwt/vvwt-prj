// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.identity;

import java.time.LocalDate;

/**
 * Thrown when a registration request uses an algorithm that has passed its deprecation date.
 *
 * <p>Per DEC-43 D3 (as amended by DEC-48): after the deprecation date (interpreted as UTC
 * end-of-day, formally the first instant of the day AFTER {@code deprecation_date}), new
 * registrations using that algorithm are rejected with HTTP 410 Gone.
 *
 * <p>Package placement: {@code identity} (public package per DEC-35) — this exception is the public
 * error surface for the controller's {@code @ExceptionHandler} and is consumed by {@link
 * KeyRegistrationController}.
 *
 * <p>Story: E40S03 / AC-DEPRECATED-ALGORITHM-EXCEPTION
 */
public class DeprecatedAlgorithmException extends RuntimeException {

    private final String algorithmId;
    private final LocalDate deprecationDate;

    /**
     * Constructs a {@code DeprecatedAlgorithmException}.
     *
     * @param algorithmId the server-canonical algorithm identifier (e.g., {@code "Ed25519"})
     * @param deprecationDate the deprecation date of the algorithm (UTC end-of-day boundary)
     */
    public DeprecatedAlgorithmException(String algorithmId, LocalDate deprecationDate) {
        super(
                "Algorithm '"
                        + algorithmId
                        + "' deprecated as of "
                        + deprecationDate
                        + " (UTC end-of-day); new registrations rejected per DEC-43 D3.");
        this.algorithmId = algorithmId;
        this.deprecationDate = deprecationDate;
    }

    /**
     * Returns the server-canonical algorithm identifier that was rejected.
     *
     * @return the algorithm identifier; never {@code null}
     */
    public String algorithmId() {
        return algorithmId;
    }

    /**
     * Returns the deprecation date of the algorithm.
     *
     * <p>Per DEC-48 boundary semantics: a registration submitted at any instant on this date
     * (including {@code 23:59:59Z}) is ACCEPTED; a registration submitted at the first instant of
     * the following day ({@code deprecationDate.plusDays(1).atStartOfDay(UTC)}) is REJECTED.
     *
     * @return the deprecation date; never {@code null}
     */
    public LocalDate deprecationDate() {
        return deprecationDate;
    }
}
