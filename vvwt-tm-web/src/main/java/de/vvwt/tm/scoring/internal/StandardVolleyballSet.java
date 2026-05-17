// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.ValidationResult;
import de.vvwt.tm.tournament.MatchFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Standard volleyball set-validation rule (AC3).
 *
 * <p>Official volleyball federation set-ending rule:
 *
 * <ul>
 *   <li>In a non-deciding set: the winner must reach <strong>25 points</strong> with a lead of at
 *       least 2 points.
 *   <li>In the deciding set (the last possible set of the match): the winner must reach <strong>15
 *       points</strong> with a lead of at least 2 points.
 * </ul>
 *
 * <p>The Spring bean name {@code "standardVolleyball"} is the identifier stored in {@code
 * Tournament.set_validation_rule_id} and used for registry lookup. This value is persisted via
 * Flyway V2__e03_core_schema.sql and must not be changed (AC-BEAN-NAME-PRESERVED / E22S04).
 *
 * <p>This rule is <em>not</em> compatible with {@link MatchFormat#FIXED_2_SETS} because that format
 * has no deciding-set concept ({@code decidingSet} is empty). Attempting to use this rule with
 * {@code FIXED_2_SETS} throws {@link IllegalArgumentException} — the organizer must configure
 * {@code timeBounded} for {@code FIXED_2_SETS} tournaments.
 *
 * <p>This class is stateless and thread-safe.
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.StandardVolleyballSet} per DEC-22
 * Reconstruction-in-Place; legacy type remains in place during coexistence window ending at E22S11
 * cutover. Dual-bean-boot conflict resolved by the {@code @ComponentScan} on {@link
 * de.vvwt.tm.TournamentManagerApplication} exclusion (AC-COMPONENT-SCAN-EXCLUSION / E22S04).
 *
 * @see SetValidationRule
 * @see ValidationResult
 */
@Component("standardVolleyball")
class StandardVolleyballSet implements SetValidationRule {

    private static final Logger log = LoggerFactory.getLogger(StandardVolleyballSet.class);

    private static final String BEAN_ID = "standardVolleyball";

    private static final int STANDARD_TARGET = 25;
    private static final int DECIDING_TARGET = 15;
    private static final int MINIMUM_LEAD = 2;

    /**
     * {@inheritDoc}
     *
     * <p>Logic:
     *
     * <ol>
     *   <li>Validate inputs (null format, negative points)
     *   <li>Reject {@code FIXED_2_SETS} — no deciding-set concept
     *   <li>Determine the target: 15 pts if {@code setIndex} is the deciding-set index, 25 pts
     *       otherwise
     *   <li>Check whether either team leads with &ge; target AND a &ge; 2-point margin
     * </ol>
     *
     * @throws IllegalArgumentException if {@code format} is {@code null}, if either point count is
     *     negative, or if {@code format} is {@code FIXED_2_SETS}
     */
    @Override
    public ValidationResult isSetClosed(
            int team1Pts, int team2Pts, int setIndex, MatchFormat format) {
        validateInputs(team1Pts, team2Pts, format);

        // Reject FIXED_2_SETS — no deciding-set concept
        if (!format.getDecidingSet().isPresent()) {
            throw new IllegalArgumentException(
                    "StandardVolleyballSet is not compatible with format "
                            + format
                            + " — format has no deciding set concept");
        }

        int decidingSetIndex = format.getDecidingSet().getAsInt() - 1; // convert 1-based to 0-based
        int target = (setIndex == decidingSetIndex) ? DECIDING_TARGET : STANDARD_TARGET;

        int lead = team1Pts - team2Pts;
        int absLead = Math.abs(lead);

        ValidationResult result;

        if (team1Pts >= target && lead >= MINIMUM_LEAD) {
            result = ValidationResult.winner1();
        } else if (team2Pts >= target && (-lead) >= MINIMUM_LEAD) {
            result = ValidationResult.winner2();
        } else if (absLead < MINIMUM_LEAD && (team1Pts >= target || team2Pts >= target)) {
            result = ValidationResult.open("no 2-point lead");
        } else {
            result = ValidationResult.open("target not reached");
        }

        log.debug(
                "{} decision for ({},{}) set={} format={} target={}: closed={} reason='{}'",
                BEAN_ID,
                team1Pts,
                team2Pts,
                setIndex,
                format,
                target,
                result.isClosed(),
                result.getReason());

        return result;
    }

    /** {@inheritDoc} */
    @Override
    public String getBeanId() {
        return BEAN_ID;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static void validateInputs(int team1Pts, int team2Pts, MatchFormat format) {
        if (format == null) {
            throw new IllegalArgumentException("format must not be null");
        }
        if (team1Pts < 0) {
            throw new IllegalArgumentException("team1Pts must be >= 0, got: " + team1Pts);
        }
        if (team2Pts < 0) {
            throw new IllegalArgumentException("team2Pts must be >= 0, got: " + team2Pts);
        }
    }
}
