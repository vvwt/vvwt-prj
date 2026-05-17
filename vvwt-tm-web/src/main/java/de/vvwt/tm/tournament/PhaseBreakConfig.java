// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

/**
 * Input value object describing a single intra-phase break for timeline calculation.
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.*} (public boundary-API) per Session Brief D-2
 * (2026-04-20, human decision K2=A). Consumed cross-context by {@code print} (Laufzettel timing)
 * and {@code timer} (countdown schedule) via {@code tournament::api}.
 *
 * <p>Passed as part of {@link PhaseConfig#phaseBreaks()} to {@link
 * TimelineCalculationService#calculate}. The service does not read from the database — the caller
 * assembles these records from {@code PhaseBreak} entities.
 *
 * <p>Inventory row 334 (E21S01): classified {@code uncertain}; resolved by D-2 as {@code
 * tournament} public boundary-API (E21S11). See DEC-21, DEC-22.
 *
 * @param afterLapNumber the 1-based lap number after which this break occurs; must be ≥ 1
 * @param durationMinutes duration of the break in minutes; must be {@literal > 0}
 * @param label optional display label shown on the Laufzettel (e.g., "Mittagspause"); may be {@code
 *     null}
 * @see PhaseConfig
 * @see TimelineCalculationService
 */
public record PhaseBreakConfig(int afterLapNumber, int durationMinutes, String label) {

    /**
     * Compact constructor validating field preconditions.
     *
     * @throws IllegalArgumentException if {@code afterLapNumber} < 1 or {@code durationMinutes} ≤ 0
     */
    public PhaseBreakConfig {
        if (afterLapNumber < 1) {
            throw new IllegalArgumentException(
                    "afterLapNumber must be >= 1 but was: " + afterLapNumber);
        }
        if (durationMinutes <= 0) {
            throw new IllegalArgumentException(
                    "durationMinutes must be > 0 but was: " + durationMinutes);
        }
    }
}
