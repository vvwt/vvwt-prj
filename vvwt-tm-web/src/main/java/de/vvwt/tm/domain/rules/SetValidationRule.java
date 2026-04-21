package de.vvwt.tm.domain.rules;

import de.vvwt.tm.tournament.MatchFormat;

/**
 * Strategy interface for pluggable set-validation logic (AC1, D-16).
 *
 * <p>Each implementation encapsulates the sport-specific rule that determines whether a set can be
 * closed at a given point score.
 *
 * <p>V1 implementations:
 *
 * <ul>
 *   <li>{@link StandardVolleyballSet} — official volleyball federation set-ending rule
 *   <li>{@link TimeBoundedSet} — time-bounded, no point-target rule (beta variant)
 * </ul>
 *
 * <p>Post-V1 sports register additional rule beans without schema changes — the {@link
 * SetValidationRuleRegistry} picks them up automatically via Spring's {@code Map<String,
 * SetValidationRule>} injection.
 */
public interface SetValidationRule {

    /**
     * Evaluates whether the set can be closed at the given point scores.
     *
     * @param team1Pts points scored by team 1 in this set (&ge; 0)
     * @param team2Pts points scored by team 2 in this set (&ge; 0)
     * @param setIndex 0-based index of this set within the match (&ge; 0)
     * @param format the match format governing this set (must not be {@code null})
     * @return a {@link ValidationResult} indicating whether the set is closed and, if so, which
     *     team won
     * @throws IllegalArgumentException if {@code format} is {@code null}, or if {@code team1Pts} or
     *     {@code team2Pts} is negative, or if the implementation does not support the given format
     */
    ValidationResult isSetClosed(int team1Pts, int team2Pts, int setIndex, MatchFormat format);

    /**
     * Returns the Spring bean ID string used for registry lookup.
     *
     * <p>This value must match the {@code @Component("...")} annotation on the implementing class
     * and the value stored in {@code Tournament.set_validation_rule_id}.
     *
     * @return the Spring bean name (never {@code null} or blank)
     */
    String getBeanId();
}
