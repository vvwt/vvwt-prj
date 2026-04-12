package de.vvwt.tm.domain;

/**
 * Minimal Tournament stub for E03S07 — extended by E03S01 when the core schema story merges.
 *
 * <p>This class is intentionally minimal: it exposes only the fields required by the
 * {@code SetValidationRule} strategy resolution path ({@code set_validation_rule_id}).
 * The full entity (with all strategy rule IDs, match format, lifecycle status, tenant,
 * Flyway-backed persistence, and Spring Data JDBC annotations) is delivered by E03S01.
 * That story will replace this stub with the complete implementation.
 *
 * <p>Merge note: E03S01's {@code Tournament.java} is a strict superset of this stub —
 * it contains the same {@code setValidationRuleId} field and accessor. The merge is
 * expected to be clean.
 */
public class Tournament {

    /**
     * Spring bean id of the {@code SetValidationRule} implementation to use (D-16).
     * Stored in the {@code tournament.set_validation_rule_id} column.
     */
    private String setValidationRuleId;

    /** Default constructor required by Spring Data JDBC (future E03S01 dependency). */
    public Tournament() {
    }

    /**
     * Minimal constructor for testing and rule-resolution context.
     *
     * @param setValidationRuleId Spring bean id of the set validation rule
     */
    public Tournament(String setValidationRuleId) {
        this.setValidationRuleId = setValidationRuleId;
    }

    /**
     * Returns the Spring bean id of the {@link de.vvwt.tm.domain.rules.SetValidationRule}
     * implementation configured for this tournament.
     *
     * @return set validation rule bean id (may be null if not yet set)
     */
    public String getSetValidationRuleId() {
        return setValidationRuleId;
    }

    /**
     * Sets the Spring bean id of the set validation rule.
     *
     * @param setValidationRuleId Spring bean id (e.g. {@code "standardVolleyball"})
     */
    public void setSetValidationRuleId(String setValidationRuleId) {
        this.setValidationRuleId = setValidationRuleId;
    }
}
