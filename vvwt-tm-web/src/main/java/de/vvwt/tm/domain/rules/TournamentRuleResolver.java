package de.vvwt.tm.domain.rules;

import de.vvwt.tm.domain.Tournament;
import org.springframework.stereotype.Component;

/**
 * Service that resolves Strategy-rule beans from a {@link Tournament}'s configuration fields.
 *
 * <p>Each {@code Tournament} row stores Spring bean IDs for each pluggable rule dimension
 * (D-15, D-16, D-27). This resolver translates those IDs into live rule instances by
 * delegating to the appropriate registries.
 *
 * <p>E03S08 introduces {@link #resolveScoringRule(Tournament)}.
 * E03S07 will add {@code resolveSetValidationRule(Tournament)} when delivered.
 * E03S09 will add {@code resolveMatchGenerator(Tournament)} when delivered.
 *
 * @see ScoringRuleRegistry
 */
@Component
public class TournamentRuleResolver {

    private final ScoringRuleRegistry scoringRuleRegistry;

    /**
     * Constructs the resolver. Spring injects the registries.
     *
     * @param scoringRuleRegistry registry of all {@link ScoringRule} beans
     */
    public TournamentRuleResolver(ScoringRuleRegistry scoringRuleRegistry) {
        this.scoringRuleRegistry = scoringRuleRegistry;
    }

    /**
     * Resolves the {@link ScoringRule} configured for the given tournament (AC7).
     *
     * <p>Reads {@link Tournament#getScoringRuleId()} and looks it up in the
     * {@link ScoringRuleRegistry}.
     *
     * @param tournament the tournament whose scoring rule is needed (non-null)
     * @return the resolved {@link ScoringRule} (never null)
     * @throws IllegalArgumentException if the tournament's {@code scoringRuleId} is not
     *                                  registered in the registry
     * @throws NullPointerException     if {@code tournament} is null
     */
    public ScoringRule resolveScoringRule(Tournament tournament) {
        if (tournament == null) {
            throw new NullPointerException("tournament must not be null");
        }
        return scoringRuleRegistry.get(tournament.getScoringRuleId());
    }
}
