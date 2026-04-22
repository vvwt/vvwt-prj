package de.vvwt.tm.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-package unit tests for {@link ScoringRuleRegistry} per DEC-36.
 *
 * <p>Tests reference {@link ScoringRule} (the public interface), never the registry implementation
 * class directly from a different-package perspective — this class IS in the same package as the
 * subject, so it is technically a same-package test (white-box permitted per DEC-36), but we use
 * the public interface type for collaborators in accordance with the story's DEC-36 cross-package
 * consumer perspective for the rule fixtures.
 *
 * <p>RED-first per DEC-22 Iron Law: tests written before {@link ScoringRuleRegistry} exists.
 *
 * @see ScoringRuleRegistry
 * @see ScoringRule
 */
class ScoringRuleRegistryTest {

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
        registry = new ScoringRuleRegistry(List.of(ruleA, ruleB));
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
        ScoringRuleRegistry empty = new ScoringRuleRegistry(List.of());
        assertThat(empty.knownIds()).isEmpty();
    }
}
