package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.Tournament;

/**
 * Resolves both rule beans ({@link ScoringRule} + {@link SetValidationRule}) for a given {@link
 * Tournament} in a single operation.
 *
 * <p>Each {@code Tournament} row stores Spring bean IDs for each pluggable rule dimension. This
 * resolver translates those IDs into live rule instances by delegating to the appropriate
 * registries.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @since E57S01
 */
public interface TournamentRuleResolver {

    /**
     * Resolves both rule instances for the given tournament in a single call.
     *
     * @param tournament the tournament whose rules are needed; must not be {@code null}
     * @return a {@link ResolvedRules} tuple (never {@code null})
     * @throws IllegalArgumentException if {@code tournament} is {@code null}
     * @throws de.vvwt.tm.tournament.exceptions.ValidationException if either rule ID is not
     *     registered (propagated from the registries)
     */
    ResolvedRules resolve(Tournament tournament);

    /**
     * Immutable tuple of the two resolved rules for a tournament.
     *
     * <p>Defined here on the interface so that consumers and tests can reference {@code
     * TournamentRuleResolver.ResolvedRules} without importing the internal implementation.
     */
    record ResolvedRules(ScoringRule scoringRule, SetValidationRule setValidationRule) {}
}
