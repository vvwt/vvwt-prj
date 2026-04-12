package de.vvwt.tm.domain.rules;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * Registry of all available {@link SetValidationRule} beans (AC5).
 *
 * <p>Spring automatically injects all {@link SetValidationRule} implementations
 * into the {@code rules} map, keyed by Spring bean name (the value declared in
 * each {@code @Component("...")}).
 *
 * <p>Usage by the cascade service and resolvers:
 * <pre>{@code
 * SetValidationRule rule = registry.get("standardVolleyball");
 * }</pre>
 *
 * <p>Throws {@link IllegalArgumentException} with a descriptive message listing
 * known IDs if the requested bean name is not found.
 */
@Component
public class SetValidationRuleRegistry {

    /** All available rules, keyed by Spring bean name. Injected by Spring. */
    private final Map<String, SetValidationRule> rules;

    /**
     * Constructs the registry.
     *
     * @param rules map of all {@link SetValidationRule} beans in the application context,
     *              keyed by Spring bean name — injected automatically by Spring
     */
    public SetValidationRuleRegistry(Map<String, SetValidationRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException(
                    "SetValidationRuleRegistry requires at least one SetValidationRule bean");
        }
        this.rules = Map.copyOf(rules);
    }

    /**
     * Returns the {@link SetValidationRule} bean registered under the given ID.
     *
     * @param beanId the Spring bean name (e.g. {@code "standardVolleyball"})
     * @return the matching rule (never {@code null})
     * @throws IllegalArgumentException if no rule is registered for the given ID,
     *         including the list of known IDs in the message
     * @throws IllegalArgumentException if {@code beanId} is {@code null} or blank
     */
    public SetValidationRule get(String beanId) {
        if (beanId == null || beanId.isBlank()) {
            throw new IllegalArgumentException("beanId must not be null or blank");
        }
        SetValidationRule rule = rules.get(beanId);
        if (rule == null) {
            // Sort the known IDs for a deterministic, readable error message
            String knownIds = String.join(", ", new TreeMap<>(rules).keySet());
            throw new IllegalArgumentException(
                    "No SetValidationRule bean with id '" + beanId
                    + "' — known ids: " + knownIds);
        }
        return rule;
    }

    /**
     * Returns an unmodifiable view of all registered rules, keyed by bean ID.
     * Primarily intended for diagnostics and testing.
     *
     * @return unmodifiable map of all rules
     */
    public Map<String, SetValidationRule> getAll() {
        return rules;
    }
}
