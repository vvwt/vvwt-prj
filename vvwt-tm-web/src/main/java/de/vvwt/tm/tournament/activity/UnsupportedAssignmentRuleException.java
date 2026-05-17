// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.activity;

/**
 * Thrown when an {@link ActivityType} references an assignment rule that is not yet implemented by
 * the {@link ActivityAssignmentService}.
 *
 * <p>V1 supports {@link AssignmentRule#FIRST_FREE_ROUND} only. Post-V1 rules stored in the database
 * will trigger this exception until the corresponding rule implementation is added (E08S04 AC8).
 *
 * <p>This exception enables clean failure-reporting at the service layer. The REST endpoint
 * (E08S06) is responsible for translating it into an appropriate HTTP error response using an i18n
 * message key.
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code
 * de.vvwt.tm.domain.activity.UnsupportedAssignmentRuleException} into {@code tournament.activity}
 * public surface per DEC-21 + DEC-35.
 *
 * @see ActivityAssignmentService
 */
public class UnsupportedAssignmentRuleException extends RuntimeException {

    /** The rule name (as stored in the database) that triggered this exception. */
    private final String ruleName;

    /**
     * Constructs the exception with the unrecognized rule name.
     *
     * @param ruleName the raw rule name string from the {@code activity_types.assignment_rule}
     *     column
     */
    public UnsupportedAssignmentRuleException(String ruleName) {
        super(
                "Unsupported activity assignment rule: '"
                        + ruleName
                        + "'. Implement the rule in DefaultActivityAssignmentService before using"
                        + " it.");
        this.ruleName = ruleName;
    }

    /**
     * Returns the unrecognized rule name.
     *
     * @return rule name string as stored in the database; never null
     */
    public String getRuleName() {
        return ruleName;
    }
}
