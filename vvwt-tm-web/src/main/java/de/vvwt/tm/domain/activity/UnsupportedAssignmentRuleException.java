package de.vvwt.tm.domain.activity;

/**
 * Thrown when an {@link de.vvwt.tm.domain.ActivityType} references an assignment rule that is not
 * yet implemented by the {@link ActivityAssignmentService}.
 *
 * <p>V1 supports {@link de.vvwt.tm.domain.AssignmentRule#FIRST_FREE_ROUND} only. Post-V1 rules
 * stored in the database will trigger this exception until the corresponding rule implementation is
 * added (E08S04 AC8).
 *
 * <p>This exception enables clean failure-reporting at the service layer. The REST endpoint
 * (E08S06) is responsible for translating it into an appropriate HTTP error response using an i18n
 * message key.
 *
 * @see ActivityAssignmentService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S04.story.md">Story
 *     E08S04 AC8</a>
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
                        + "'. Implement the rule in ActivityAssignmentServiceImpl before using"
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
