package de.vvwt.tm.domain.rules;

import de.vvwt.tm.domain.Tournament;
import org.springframework.stereotype.Component;

/**
 * Service helper that resolves the configured {@link SetValidationRule} for a given
 * {@link Tournament} instance (AC6).
 *
 * <p>Reads {@code Tournament.setValidationRuleId} and delegates to the
 * {@link SetValidationRuleRegistry} for the actual bean lookup.
 *
 * <p>This resolver is stateless and delegates all error handling to the registry
 * (which throws {@link IllegalArgumentException} for unknown IDs).
 *
 * <p>Used by the cascade service (E03S11) before invoking set-validation.
 */
@Component
public class TournamentRuleResolver {

    private final SetValidationRuleRegistry registry;

    /**
     * Constructs the resolver.
     *
     * @param registry the rule registry that holds all {@link SetValidationRule} beans
     */
    public TournamentRuleResolver(SetValidationRuleRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
    }

    /**
     * Resolves the {@link SetValidationRule} configured for the given tournament.
     *
     * @param tournament the tournament whose {@code set_validation_rule_id} is used
     *                   for rule lookup (must not be {@code null})
     * @return the matching {@link SetValidationRule} bean
     * @throws IllegalArgumentException if {@code tournament} is {@code null}, if
     *         {@code tournament.getSetValidationRuleId()} is {@code null} or blank,
     *         or if no rule bean is registered for the given id
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
        return registry.get(ruleId);
    }
}
