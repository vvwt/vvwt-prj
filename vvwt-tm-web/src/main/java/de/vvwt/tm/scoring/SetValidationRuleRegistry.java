package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Spring bean registry for all {@link SetValidationRule} implementations in the {@code scoring}
 * module.
 *
 * <p>Spring automatically injects all {@link SetValidationRule} beans into the {@code rules} map,
 * keyed by Spring bean name (the value declared in each {@code @Component("...")}). Consumers
 * (e.g., {@code TournamentRuleResolver}) call {@link #get(String)} to resolve the applicable rule
 * for a given tournament; the {@code web} module may call {@link #getAll()} for rule-listing
 * endpoints.
 *
 * <p>Public API surface of the {@code scoring} bounded context (DEC-35 §Public package).
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.SetValidationRuleRegistry} per DEC-22
 * Reconstruction-in-Place (E22S05). Legacy type remains in place during the coexistence window
 * ending at the E22S11 cutover.
 *
 * <h2>ComponentScan exclusion note (AC-COMPONENT-SCAN-EXCLUSION-INHERITED)</h2>
 *
 * <p>The legacy {@code domain.rules.*} concrete rule classes are excluded by the REGEX filter
 * installed in E22S04 on {@code TournamentManagerApplication}. This registry is in the new {@code
 * scoring.*} package and registers normally as a Spring bean.
 *
 * @see SetValidationRule
 * @see TournamentRuleResolver
 */
@Component("scoringModuleSetValidationRuleRegistry")
public class SetValidationRuleRegistry {

    /** All available rules, keyed by Spring bean name. */
    private final Map<String, SetValidationRule> rules;

    /**
     * Constructs the registry.
     *
     * <p>Constructor signature matches the legacy {@code
     * de.vvwt.tm.domain.rules.SetValidationRuleRegistry} constructor (Map&lt;String,
     * SetValidationRule&gt;) per AC-CONSTRUCTOR-SIGNATURES-PRESERVED.
     *
     * @param rules map of all {@link SetValidationRule} beans in the application context, keyed by
     *     Spring bean name — injected automatically by Spring; must not be {@code null}
     * @throws IllegalArgumentException if {@code rules} is {@code null}
     */
    public SetValidationRuleRegistry(Map<String, SetValidationRule> rules) {
        if (rules == null) {
            throw new IllegalArgumentException(
                    "SetValidationRuleRegistry rules map must not be null");
        }
        // Empty map is permitted during the DEC-22 reconstruction-in-place coexistence window.
        this.rules = Map.copyOf(rules);
    }

    /**
     * Returns the {@link SetValidationRule} bean registered under the given ID.
     *
     * @param beanId the Spring bean name (e.g., {@code "standardVolleyball"}); must not be {@code
     *     null}
     * @return the matching rule (never {@code null})
     * @throws IllegalArgumentException if {@code beanId} is {@code null} or blank
     * @throws ValidationException if no rule is registered for the given ID
     *     (AC-UNKNOWN-ID-VALIDATION, AC-SECURITY-ID-VALIDATION)
     */
    public SetValidationRule get(String beanId) {
        if (beanId == null || beanId.isBlank()) {
            throw new IllegalArgumentException("beanId must not be null or blank");
        }
        SetValidationRule rule = rules.get(beanId);
        if (rule == null) {
            String knownIds = String.join(", ", new TreeMap<>(rules).keySet());
            throw new ValidationException(
                    "unknown set validation rule id: '" + beanId + "' — known ids: " + knownIds);
        }
        return rule;
    }

    /**
     * Returns an unmodifiable view of all registered rules, keyed by bean ID.
     *
     * @return unmodifiable map of all rules (never {@code null})
     */
    public Map<String, SetValidationRule> getAll() {
        return rules;
    }
}
