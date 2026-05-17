// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.bootstrap;

import java.time.LocalDate;
import java.util.List;

/**
 * Result of algorithm validation against the dispatcher's announced algorithm list.
 *
 * <p>Captures whether the chosen algorithm is valid, whether a DEC-43 D3 deprecation warning
 * applies, and the recommended migration targets for the warning message.
 *
 * <p>Story: E41S04 AC-ALGORITHM-VALIDATION, AC-DEC43-D3-ADMIN-WARNING-SURFACE,
 * AC-RECOMMENDED-MIGRATION-DERIVATION.
 */
public record AlgorithmValidationResult(
        boolean isValid,
        boolean hasDeprecationWarning,
        LocalDate deprecationDate,
        List<String> recommendedMigrationTargets) {

    /**
     * Returns the recommended migration targets as a comma-separated string for use in warning
     * messages.
     *
     * @return comma-separated algorithm IDs, or {@code "(none)"} if the list is empty
     */
    public String recommendedMigrationCommaSeparated() {
        if (recommendedMigrationTargets.isEmpty()) {
            return "(none)";
        }
        return String.join(", ", recommendedMigrationTargets);
    }
}
