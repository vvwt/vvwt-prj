package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.internal.MatchGenerator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MatchGeneratorRegistry} (AC-TDD-MatchGeneratorRegistry, E21S08).
 *
 * <p>Verifies: registry returns registered generators by key; unknown key throws typed exception;
 * knownIds returns all registered keys; iteration order stable.
 *
 * <p>Note: {@link MatchGeneratorRegistry} is at the ROOT package {@code de.vvwt.tm.tournament} per
 * AC-PACKAGE-D8 — it is boundary-API exposed via S02's {@code TournamentRulesController}.
 *
 * <p>Source: inventory row 257 — {@code de.vvwt.tm.tournament.MatchGeneratorRegistry}.
 */
class MatchGeneratorRegistryTest {

    private static MatchGenerator mockGenerator(String beanId) {
        MatchGenerator gen = mock(MatchGenerator.class);
        when(gen.getBeanId()).thenReturn(beanId);
        return gen;
    }

    // -------------------------------------------------------------------------
    // Registry returns registered generator by key
    // -------------------------------------------------------------------------

    @Test
    void get_knownKey_returnsGenerator() {
        MatchGenerator rrGen = mockGenerator("roundRobinNew");
        MatchGeneratorRegistry registry = new MatchGeneratorRegistry(List.of(rrGen));

        MatchGenerator result = registry.get("roundRobinNew");

        assertThat(result).isSameAs(rrGen);
    }

    // -------------------------------------------------------------------------
    // Unknown key throws IAE with message containing known IDs
    // -------------------------------------------------------------------------

    @Test
    void get_unknownKey_throwsIAEWithKnownIds() {
        MatchGenerator rrGen = mockGenerator("roundRobinNew");
        MatchGeneratorRegistry registry = new MatchGeneratorRegistry(List.of(rrGen));

        assertThatThrownBy(() -> registry.get("nonExistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonExistent")
                .hasMessageContaining("roundRobinNew");
    }

    // -------------------------------------------------------------------------
    // knownIds returns all registered keys
    // -------------------------------------------------------------------------

    @Test
    void knownIds_returnsAllRegisteredKeys() {
        MatchGenerator gen1 = mockGenerator("roundRobinNew");
        MatchGenerator gen2 = mockGenerator("swiss");
        MatchGeneratorRegistry registry = new MatchGeneratorRegistry(List.of(gen1, gen2));

        Set<String> ids = registry.knownIds();

        assertThat(ids).containsExactlyInAnyOrder("roundRobinNew", "swiss");
    }

    // -------------------------------------------------------------------------
    // knownIds is stable (same result on repeated calls)
    // -------------------------------------------------------------------------

    @Test
    void knownIds_stableOnRepeatedCalls() {
        MatchGenerator gen1 = mockGenerator("roundRobinNew");
        MatchGenerator gen2 = mockGenerator("swiss");
        MatchGeneratorRegistry registry = new MatchGeneratorRegistry(List.of(gen1, gen2));

        Set<String> first = registry.knownIds();
        Set<String> second = registry.knownIds();

        assertThat(first).isEqualTo(second);
    }

    // -------------------------------------------------------------------------
    // Duplicate bean ID throws ISE at construction
    // -------------------------------------------------------------------------

    @Test
    void constructor_duplicateBeanId_throwsISE() {
        MatchGenerator gen1 = mockGenerator("roundRobinNew");
        MatchGenerator gen2 = mockGenerator("roundRobinNew");

        assertThatThrownBy(() -> new MatchGeneratorRegistry(List.of(gen1, gen2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roundRobinNew");
    }

    // -------------------------------------------------------------------------
    // knownIds returns unmodifiable set
    // -------------------------------------------------------------------------

    @Test
    void knownIds_isUnmodifiable() {
        MatchGenerator gen = mockGenerator("roundRobinNew");
        MatchGeneratorRegistry registry = new MatchGeneratorRegistry(List.of(gen));

        assertThatThrownBy(() -> registry.knownIds().add("hack"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
