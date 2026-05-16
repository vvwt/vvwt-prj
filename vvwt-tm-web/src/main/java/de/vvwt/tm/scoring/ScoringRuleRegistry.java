package de.vvwt.tm.scoring;

import java.util.Set;

/**
 * Registry for all {@link ScoringRule} implementations in the {@code scoring} module.
 *
 * <p>Public API surface of the {@code scoring} bounded context (DEC-35 §Public package). Cross-
 * module consumers (e.g., {@code web.ScoringRulesController}) call {@link #knownIds()} for the
 * rules-listing endpoint without reaching into {@code scoring.internal.*}.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see ScoringRule
 * @since E57S01
 */
public interface ScoringRuleRegistry {

    /**
     * Returns the {@link ScoringRule} registered under the given bean ID.
     *
     * @param beanId the Spring bean ID to look up; must not be {@code null}
     * @return the matching {@link ScoringRule} (never null)
     * @throws IllegalArgumentException if {@code beanId} is {@code null}
     * @throws de.vvwt.tm.tournament.exceptions.ValidationException if no rule is registered under
     *     {@code beanId}
     */
    ScoringRule get(String beanId);

    /**
     * Returns an unmodifiable snapshot of all registered bean IDs.
     *
     * @return immutable set of known IDs
     */
    Set<String> knownIds();
}
