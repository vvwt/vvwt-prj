// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link ScoringRuleRegistry}.
 *
 * <p>Spring bean registry for all {@link ScoringRule} implementations in the {@code scoring}
 * module. Spring collects every bean implementing {@link ScoringRule} and passes them to this
 * constructor. The registry indexes them by their {@link ScoringRule#getBeanId()} return value.
 * Duplicate IDs produce an {@link IllegalStateException} at startup — a configuration error.
 *
 * <p>Public API surface of the {@code scoring} bounded context (DEC-35 §Public package). Cross-
 * module consumers (e.g., {@code web.ScoringRulesController} post-E22S08) call {@link #knownIds()}
 * for the rules-listing endpoint without reaching into {@code scoring.internal.*}.
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.ScoringRuleRegistry} per DEC-22
 * Reconstruction-in-Place (E22S05). Legacy type remains in place during the coexistence window
 * ending at the E22S11 cutover.
 *
 * <h2>ComponentScan exclusion note (AC-COMPONENT-SCAN-EXCLUSION-INHERITED)</h2>
 *
 * <p>The legacy {@code domain.rules.*} concrete rule classes (SetPointsRule, ThreePointMatchRule,
 * TwoPointMatchRule, StandardVolleyballSet, TimeBoundedSet) are excluded from component scanning by
 * the REGEX filter installed in E22S04 on {@code TournamentManagerApplication}. This class (and
 * {@code DefaultSetValidationRuleRegistry}) are {@code @Component} beans in the new {@code
 * scoring.*} package — they are NOT excluded and register normally.
 *
 * @see ScoringRule
 * @see TournamentRuleResolver
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from ScoringRuleRegistry, moved to
 *     scoring.internal, implements {@link ScoringRuleRegistry})
 */
@Component("scoringModuleScoringRuleRegistry")
class DefaultScoringRuleRegistry implements ScoringRuleRegistry {

    private final Map<String, ScoringRule> rulesByBeanId;

    /**
     * Constructs the registry from all {@link ScoringRule} beans discovered by Spring.
     *
     * <p>Constructor signature matches the legacy {@code
     * de.vvwt.tm.domain.rules.ScoringRuleRegistry} constructor (List&lt;ScoringRule&gt;) per
     * AC-CONSTRUCTOR-SIGNATURES-PRESERVED.
     *
     * @param rules all beans in the application context that implement {@link ScoringRule}; Spring
     *     injects this list automatically
     * @throws IllegalStateException if two rules share the same bean ID
     */
    DefaultScoringRuleRegistry(List<ScoringRule> rules) {
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
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code beanId} is {@code null}
     * @throws ValidationException if no rule is registered under {@code beanId}
     */
    @Override
    public ScoringRule get(String beanId) {
        if (beanId == null) {
            throw new IllegalArgumentException("beanId must not be null");
        }
        ScoringRule rule = rulesByBeanId.get(beanId);
        if (rule == null) {
            throw new ValidationException(
                    "unknown scoring rule id: '"
                            + beanId
                            + "' — known ids: "
                            + String.join(", ", rulesByBeanId.keySet()));
        }
        return rule;
    }

    /**
     * {@inheritDoc}
     *
     * @return immutable set of known IDs
     */
    @Override
    public Set<String> knownIds() {
        return Collections.unmodifiableSet(rulesByBeanId.keySet());
    }
}
