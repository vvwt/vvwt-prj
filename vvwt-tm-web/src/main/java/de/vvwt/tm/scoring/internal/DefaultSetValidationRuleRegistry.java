// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.SetValidationRuleRegistry;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link SetValidationRuleRegistry}.
 *
 * <p>Spring bean registry for all {@link SetValidationRule} implementations in the {@code scoring}
 * module. Spring automatically injects all {@link SetValidationRule} beans into the {@code rules}
 * map, keyed by Spring bean name. Consumers call {@link #get(String)} to resolve the applicable
 * rule for a given tournament; the {@code web} module may call {@link #getAll()} for rule-listing
 * endpoints.
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
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from SetValidationRuleRegistry, moved
 *     to scoring.internal, implements {@link SetValidationRuleRegistry})
 */
@Component("scoringModuleSetValidationRuleRegistry")
class DefaultSetValidationRuleRegistry implements SetValidationRuleRegistry {

    /** All available rules, keyed by Spring bean name. */
    private final Map<String, SetValidationRule> rules;

    /**
     * Constructs the registry.
     *
     * <p>Constructor signature matches the legacy {@code
     * de.vvwt.tm.domain.rules.SetValidationRuleRegistry} constructor
     * (Map&lt;String,SetValidationRule&gt;) per AC-CONSTRUCTOR-SIGNATURES-PRESERVED.
     *
     * @param rules map of all {@link SetValidationRule} beans in the application context, keyed by
     *     Spring bean name — injected automatically by Spring; must not be {@code null}
     * @throws IllegalArgumentException if {@code rules} is {@code null}
     */
    DefaultSetValidationRuleRegistry(Map<String, SetValidationRule> rules) {
        if (rules == null) {
            throw new IllegalArgumentException(
                    "SetValidationRuleRegistry rules map must not be null");
        }
        // Empty map is permitted during the DEC-22 reconstruction-in-place coexistence window.
        this.rules = Map.copyOf(rules);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code beanId} is {@code null} or blank
     * @throws ValidationException if no rule is registered for the given ID
     */
    @Override
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
     * {@inheritDoc}
     *
     * @return unmodifiable map of all rules (never {@code null})
     */
    @Override
    public Map<String, SetValidationRule> getAll() {
        return rules;
    }
}
