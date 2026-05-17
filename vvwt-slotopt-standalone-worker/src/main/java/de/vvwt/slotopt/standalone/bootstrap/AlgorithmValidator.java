// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.bootstrap;

import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithm;
import de.vvwt.slotopt.worker.runtime.AnnouncedAlgorithmsResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Validates the worker's chosen signing algorithm against the dispatcher's announced list.
 *
 * <p>Implements DEC-43 D2/D3 client-side validation:
 *
 * <ul>
 *   <li>D2: chosen algorithm must be in the announced list
 *   <li>D2: chosen algorithm must not be past its deprecation deadline (DEC-48 boundary semantics)
 *   <li>D3: if chosen algorithm has a future deprecation_date, emit admin warning (not fail-fast)
 * </ul>
 *
 * <p>DEC-48 boundary semantics: {@code deprecation_date} day is entirely accepted; rejection starts
 * at first instant of the day AFTER {@code deprecation_date} in UTC, i.e.: {@code
 * Instant.now().isBefore(deprecationDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())}
 *
 * <p>Story: E41S04 AC-ALGORITHM-VALIDATION, AC-DEC43-PAST-DEPRECATION-FAIL-FAST,
 * AC-RECOMMENDED-MIGRATION-DERIVATION.
 */
public final class AlgorithmValidator {

    private AlgorithmValidator() {}

    /**
     * Validates the chosen algorithm against the announced list.
     *
     * @param chosenAlgorithm the algorithm identifier from {@link
     *     de.vvwt.slotopt.standalone.WorkerConfig#signingAlgorithm()}
     * @param response the announced algorithms response from the dispatcher
     * @return validation result (valid = true; hasDeprecationWarning = true if future deprecation)
     * @throws BootstrapException with {@link ExitCode#ALGORITHM_NOT_ANNOUNCED} if the algorithm is
     *     not in the announced list
     * @throws BootstrapException with {@link ExitCode#ALGORITHM_DEPRECATED_PAST_DEADLINE} if the
     *     algorithm's deprecation deadline has passed (DEC-48 semantics)
     */
    public static AlgorithmValidationResult validate(
            String chosenAlgorithm, AnnouncedAlgorithmsResponse response)
            throws BootstrapException {

        Optional<AnnouncedAlgorithm> match =
                response.algorithms().stream()
                        .filter(a -> chosenAlgorithm.equals(a.algorithmId()))
                        .findFirst();

        if (match.isEmpty()) {
            throw new BootstrapException(
                    ExitCode.ALGORITHM_NOT_ANNOUNCED,
                    "algorithm not in announced list: '"
                            + chosenAlgorithm
                            + "'. Announced: "
                            + response.algorithms().stream()
                                    .map(AnnouncedAlgorithm::algorithmId)
                                    .collect(Collectors.joining(", ")),
                    null);
        }

        AnnouncedAlgorithm chosen = match.get();
        LocalDate deprecationDate = chosen.deprecationDate();

        if (deprecationDate != null) {
            // DEC-48: accepted if Instant.now().isBefore(depDate.plusDays(1).atStartOfDay(UTC))
            Instant boundary = deprecationDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!Instant.now().isBefore(boundary)) {
                // Past deadline — fail-fast
                throw new BootstrapException(
                        ExitCode.ALGORITHM_DEPRECATED_PAST_DEADLINE,
                        "algorithm deprecated past deadline: '"
                                + chosenAlgorithm
                                + "' was deprecated on "
                                + deprecationDate
                                + " (DEC-48: rejection starts at "
                                + boundary
                                + ")",
                        null);
            }
            // Future deprecation — warning, not fail-fast
            List<String> migrationTargets = computeMigrationTargets(chosenAlgorithm, response);
            return new AlgorithmValidationResult(true, true, deprecationDate, migrationTargets);
        }

        // No deprecation date — clean acceptance
        return new AlgorithmValidationResult(true, false, null, List.of());
    }

    /**
     * Derives recommended migration targets per AC-RECOMMENDED-MIGRATION-DERIVATION: the announced
     * non-deprecated algorithms excluding the chosen algorithm.
     *
     * <p>An algorithm is considered non-deprecated if its {@code deprecation_date} is null OR is in
     * the future (DEC-48 semantics: isBefore boundary).
     */
    private static List<String> computeMigrationTargets(
            String chosenAlgorithm, AnnouncedAlgorithmsResponse response) {
        return response.algorithms().stream()
                .filter(a -> !chosenAlgorithm.equals(a.algorithmId()))
                .filter(a -> isNonDeprecated(a))
                .map(AnnouncedAlgorithm::algorithmId)
                .collect(Collectors.toList());
    }

    private static boolean isNonDeprecated(AnnouncedAlgorithm algorithm) {
        if (algorithm.deprecationDate() == null) {
            return true;
        }
        Instant boundary =
                algorithm.deprecationDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return Instant.now().isBefore(boundary);
    }
}
