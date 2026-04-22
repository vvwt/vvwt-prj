package de.vvwt.tm.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-package unit tests for {@link SetValidationRuleRegistry} per DEC-36.
 *
 * <p>Tests reference {@link SetValidationRule} (the public interface) for fixture implementations.
 * The registry itself is in the same package as this test class per AC-FILES-CREATED.
 *
 * <p>RED-first per DEC-22 Iron Law: tests written before {@link SetValidationRuleRegistry} exists.
 *
 * @see SetValidationRuleRegistry
 * @see SetValidationRule
 */
class SetValidationRuleRegistryTest {

    /** Minimal test-fixture SetValidationRule implementation. */
    private static SetValidationRule ruleOf(String beanId) {
        return new SetValidationRule() {
            @Override
            public de.vvwt.tm.scoring.ValidationResult isSetClosed(
                    int team1Pts, int team2Pts, int setIndex, MatchFormat format) {
                throw new UnsupportedOperationException("fixture only");
            }

            @Override
            public String getBeanId() {
                return beanId;
            }
        };
    }

    private SetValidationRule ruleA;
    private SetValidationRule ruleB;
    private SetValidationRuleRegistry registry;

    @BeforeEach
    void setUp() {
        ruleA = ruleOf("standardVolleyball");
        ruleB = ruleOf("timeBounded");
        registry =
                new SetValidationRuleRegistry(
                        Map.of("standardVolleyball", ruleA, "timeBounded", ruleB));
    }

    // -----------------------------------------------------------------------
    // AC-PUBLIC-API-METHODS: get(String) — happy path
    // -----------------------------------------------------------------------

    @Test
    void get_knownId_returnsExpectedBean() {
        assertThat(registry.get("standardVolleyball")).isSameAs(ruleA);
        assertThat(registry.get("timeBounded")).isSameAs(ruleB);
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
    // AC-PUBLIC-API-METHODS: getAll() returns all registered rules
    // -----------------------------------------------------------------------

    @Test
    void getAll_returnsAllRegisteredRules() {
        Map<String, SetValidationRule> all = registry.getAll();
        assertThat(all).containsKeys("standardVolleyball", "timeBounded");
        assertThat(all.get("standardVolleyball")).isSameAs(ruleA);
    }

    // -----------------------------------------------------------------------
    // AC-CONSTRUCTOR-SIGNATURES-PRESERVED: Map constructor works
    // -----------------------------------------------------------------------

    @Test
    void constructor_emptyMap_registryIsEmpty() {
        SetValidationRuleRegistry empty = new SetValidationRuleRegistry(Map.of());
        assertThat(empty.getAll()).isEmpty();
    }
}
