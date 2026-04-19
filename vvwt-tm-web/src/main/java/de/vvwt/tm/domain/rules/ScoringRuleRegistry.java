package de.vvwt.tm.domain.rules;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Spring bean registry that auto-injects all {@link ScoringRule} beans and exposes a
 * lookup-by-bean-ID API (AC6).
 *
 * <p>Spring collects every bean implementing {@link ScoringRule} and passes them to this
 * constructor. The registry indexes them by their {@link ScoringRule#getBeanId()} return value.
 * Duplicate IDs produce an {@link IllegalStateException} at startup — a configuration error.
 *
 * <p>Usage in the cascade service (E03S11):
 *
 * <pre>{@code
 * ScoringRule rule = registry.get(tournament.getScoringRuleId());
 * }</pre>
 *
 * @see ScoringRule
 * @see TournamentRuleResolver
 */
@Component
public class ScoringRuleRegistry {

    private final Map<String, ScoringRule> rulesByBeanId;

    /**
     * Constructs the registry from all {@link ScoringRule} beans discovered by Spring.
     *
     * @param rules all beans in the application context that implement {@link ScoringRule}
     * @throws IllegalStateException if two rules share the same bean ID
     */
    public ScoringRuleRegistry(List<ScoringRule> rules) {
        this.rulesByBeanId =
                rules.stream()
                        .collect(
                                Collectors.toMap(
                                        ScoringRule::getBeanId,
                                        Function.identity(),
                                        (a, b) -> {
                                            throw new IllegalStateException(
                                                    "Duplicate ScoringRule bean ID: '"
                                                            + a.getBeanId()
                                                            + "'. Each rule must have a unique"
                                                            + " ID.");
                                        }));
    }

    /**
     * Returns the {@link ScoringRule} registered under the given bean ID.
     *
     * @param beanId the Spring bean ID to look up (e.g., {@code "setPoints"})
     * @return the matching {@link ScoringRule} (never null)
     * @throws IllegalArgumentException if no rule is registered under {@code beanId}, with the list
     *     of known IDs in the message
     */
    public ScoringRule get(String beanId) {
        ScoringRule rule = rulesByBeanId.get(beanId);
        if (rule == null) {
            throw new IllegalArgumentException(
                    "No ScoringRule bean with id '"
                            + beanId
                            + "' — known ids: "
                            + String.join(", ", rulesByBeanId.keySet()));
        }
        return rule;
    }

    /**
     * Returns an unmodifiable snapshot of all registered bean IDs. Useful for diagnostics and the
     * error message in {@link #get(String)}.
     *
     * @return immutable set of known IDs
     */
    public java.util.Set<String> knownIds() {
        return java.util.Collections.unmodifiableSet(rulesByBeanId.keySet());
    }
}
