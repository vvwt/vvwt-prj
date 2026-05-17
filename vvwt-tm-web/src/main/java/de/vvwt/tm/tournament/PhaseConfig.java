// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Objects;

/**
 * Input value object describing one phase's configuration for timeline calculation.
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.*} (public boundary-API) per Session Brief D-2
 * (2026-04-20, human decision K2=A). Consumed cross-context by {@code print} (Laufzettel timing)
 * and {@code timer} (countdown schedule) via {@code tournament::api}.
 *
 * <p>Passed in an ordered list to {@link TimelineCalculationService#calculate}. The service is
 * stateless and has no database access — the caller constructs these records from {@code Phase} and
 * {@code PhaseBreak} entities.
 *
 * <p>Phase order within the list determines the output timeline order: phase at index 0 is the
 * first phase, phase at index 1 follows after a section break, and so on.
 *
 * <p>Inventory row 335 (E21S01): classified {@code uncertain}; resolved by D-2 as {@code
 * tournament} public boundary-API (E21S11). See DEC-21, DEC-22.
 *
 * @param phaseNumber 1-based sequential number for this phase, used to populate {@link
 *     TimelineEntry#phaseNumber()} on all generated entries
 * @param lapCount number of laps (match rounds) in this phase; 0 produces a single zero-duration
 *     MATCH_ROUND marker entry; must be ≥ 0
 * @param lapTimeMinutes duration of each match round in minutes; must be {@literal > 0} when {@code
 *     lapCount > 0}; ignored when {@code lapCount == 0}
 * @param lapBreakMinutes duration of the short break between consecutive laps in minutes; 0 means
 *     no lap break is inserted; must be ≥ 0
 * @param phaseBreaks ordered list of intra-phase breaks; must not be {@code null}; may be empty
 * @see PhaseBreakConfig
 * @see TimelineCalculationService
 */
public record PhaseConfig(
        int phaseNumber,
        int lapCount,
        int lapTimeMinutes,
        int lapBreakMinutes,
        List<PhaseBreakConfig> phaseBreaks) {

    /**
     * Compact constructor validating field preconditions.
     *
     * @throws IllegalArgumentException if {@code lapCount} < 0, {@code lapTimeMinutes} ≤ 0 when
     *     {@code lapCount > 0}, or {@code lapBreakMinutes} < 0
     * @throws NullPointerException if {@code phaseBreaks} is {@code null}
     */
    public PhaseConfig {
        Objects.requireNonNull(phaseBreaks, "phaseBreaks must not be null");
        if (lapCount < 0) {
            throw new IllegalArgumentException("lapCount must be >= 0 but was: " + lapCount);
        }
        if (lapCount > 0 && lapTimeMinutes <= 0) {
            throw new IllegalArgumentException(
                    "lapTimeMinutes must be > 0 when lapCount > 0 but was: " + lapTimeMinutes);
        }
        if (lapBreakMinutes < 0) {
            throw new IllegalArgumentException(
                    "lapBreakMinutes must be >= 0 but was: " + lapBreakMinutes);
        }
    }
}
