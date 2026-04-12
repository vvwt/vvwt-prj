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
 * <p>E03S07 introduces {@link #resolveSetValidationRule(Tournament)}.
 * E03S08 introduces {@link #resolveScoringRule(Tournament)}.
 * E03S09 will add {@code resolveMatchGenerator(Tournament)} when delivered.
 *
 * @see SetValidationRuleRegistry
 * @see ScoringRuleRegistry
 */
@Component
public class TournamentRuleResolver {

    private final SetValidationRuleRegistry setValidationRuleRegistry;
    private final ScoringRuleRegistry scoringRuleRegistry;

    /**
     * Constructs the resolver. Spring injects the registries.
     *
     * @param setValidationRuleRegistry registry of all {@link SetValidationRule} beans
     * @param scoringRuleRegistry       registry of all {@link ScoringRule} beans
     */
    public TournamentRuleResolver(SetValidationRuleRegistry setValidationRuleRegistry,
                                  ScoringRuleRegistry scoringRuleRegistry) {
        if (setValidationRuleRegistry == null) {
            throw new IllegalArgumentException("setValidationRuleRegistry must not be null");
        }
        if (scoringRuleRegistry == null) {
            throw new IllegalArgumentException("scoringRuleRegistry must not be null");
        }
        this.setValidationRuleRegistry = setValidationRuleRegistry;
        this.scoringRuleRegistry = scoringRuleRegistry;
    }

    /**
     * Resolves the {@link SetValidationRule} configured for the given tournament (AC6, D-16).
     *
     * <p>Reads {@link Tournament#getSetValidationRuleId()} and looks it up in the
     * {@link SetValidationRuleRegistry}.
     *
     * @param tournament the tournament whose set validation rule is needed (must not be null)
     * @return the resolved {@link SetValidationRule} (never null)
     * @throws IllegalArgumentException if {@code tournament} is null, if the
     *         {@code setValidationRuleId} is null/blank, or if it is not registered
     */
    public SetValidationRule resolveSetValidationRule(Tournament tournament) {
        if (tournament == null) {
            throw new IllegalArgumentException("tournament must not be null");
        }
        String ruleId = tournament.getSetValidationRuleId();
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException(
                    "Tournament.setValidationRuleId must not be null or blank");
        }
        return setValidationRuleRegistry.get(ruleId);
    }

    /**
     * Resolves the {@link ScoringRule} configured for the given tournament (AC7, D-15).
     *
     * <p>Reads {@link Tournament#getScoringRuleId()} and looks it up in the
     * {@link ScoringRuleRegistry}.
     *
     * @param tournament the tournament whose scoring rule is needed (must not be null)
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
