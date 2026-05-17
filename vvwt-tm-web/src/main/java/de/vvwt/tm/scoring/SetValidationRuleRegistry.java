// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import java.util.Map;

/**
 * Registry for all {@link SetValidationRule} implementations in the {@code scoring} module.
 *
 * <p>Public API surface of the {@code scoring} bounded context (DEC-35 §Public package).
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see SetValidationRule
 * @since E57S01
 */
public interface SetValidationRuleRegistry {

    /**
     * Returns the {@link SetValidationRule} bean registered under the given ID.
     *
     * @param beanId the Spring bean name; must not be {@code null} or blank
     * @return the matching rule (never {@code null})
     * @throws IllegalArgumentException if {@code beanId} is {@code null} or blank
     * @throws de.vvwt.tm.tournament.exceptions.ValidationException if no rule is registered for the
     *     given ID
     */
    SetValidationRule get(String beanId);

    /**
     * Returns an unmodifiable view of all registered rules, keyed by bean ID.
     *
     * @return unmodifiable map of all rules (never {@code null})
     */
    Map<String, SetValidationRule> getAll();
}
