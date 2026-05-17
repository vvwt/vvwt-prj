// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Same-package white-box unit tests for {@link DefaultScoringRuleRegistry} per DEC-36.
 *
 * <p>Tests are in {@code scoring.internal} (same package as the subject) and use the public
 * interface type {@link ScoringRuleRegistry} for the variable holding the subject, per DEC-36.
 *
 * @see DefaultScoringRuleRegistry
 * @see ScoringRuleRegistry
 * @since E57S01 (moved from scoring.ScoringRuleRegistryTest to scoring.internal per DEC-58
 *     interface extraction)
 */
class DefaultScoringRuleRegistryTest {

    /** Minimal test-fixture ScoringRule implementation. */
    private static ScoringRule ruleOf(String beanId) {
        return new ScoringRule() {
            @Override
            public de.vvwt.tm.scoring.ScoringResult calculatePoints(
                    de.vvwt.tm.tournament.MatchOutcome outcome,
                    de.vvwt.tm.tournament.MatchFormat format) {
                throw new UnsupportedOperationException("fixture only");
            }

            @Override
            public String getBeanId() {
                return beanId;
            }
        };
    }

    private ScoringRule ruleA;
    private ScoringRule ruleB;
    private ScoringRuleRegistry registry;

    @BeforeEach
    void setUp() {
        ruleA = ruleOf("setPoints");
        ruleB = ruleOf("threePoint");
        registry = new DefaultScoringRuleRegistry(List.of(ruleA, ruleB));
    }

    // -----------------------------------------------------------------------
    // AC-PUBLIC-API-METHODS: get(String) — happy path
    // -----------------------------------------------------------------------

    @Test
    void get_knownId_returnsExpectedBean() {
        assertThat(registry.get("setPoints")).isSameAs(ruleA);
        assertThat(registry.get("threePoint")).isSameAs(ruleB);
    }

    // -----------------------------------------------------------------------
    // AC-UNKNOWN-ID-VALIDATION + AC-RED-FIRST: get(unknownId) throws
    // -----------------------------------------------------------------------

    @Test
    void get_unknownId_throwsValidationException() {
        assertThatThrownBy(() -> registry.get("nonexistent"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("nonexistent");
    }

    // -----------------------------------------------------------------------
    // AC-NULL-GUARDS: get(null) throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void get_null_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> registry.get(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // AC-PUBLIC-API-METHODS: knownIds() returns all registered IDs
    // -----------------------------------------------------------------------

    @Test
    void knownIds_returnsAllRegisteredIds() {
        Set<String> ids = registry.knownIds();
        assertThat(ids).containsExactlyInAnyOrder("setPoints", "threePoint");
    }

    // -----------------------------------------------------------------------
    // AC-CONSTRUCTOR-SIGNATURES-PRESERVED: List constructor works
    // -----------------------------------------------------------------------

    @Test
    void constructor_emptyList_registryIsEmpty() {
        ScoringRuleRegistry empty = new DefaultScoringRuleRegistry(List.of());
        assertThat(empty.knownIds()).isEmpty();
    }
}
