// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.MatchGeneratorInfo;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultMatchGeneratorRegistry} (AC-TDD-MatchGeneratorRegistry, E21S08).
 *
 * <p>Tests use the public interface type {@link MatchGeneratorRegistry} for the subject variable
 * per DEC-36. Construction uses {@link DefaultMatchGeneratorRegistry} (same-package white-box).
 *
 * @see DefaultMatchGeneratorRegistry
 * @see MatchGeneratorRegistry
 * @since E57S01 (moved from tournament.MatchGeneratorRegistryTest to tournament.internal per DEC-58
 *     interface extraction)
 */
class DefaultMatchGeneratorRegistryTest {

    private static MatchGenerator mockGenerator(String keyId) {
        return mockGenerator(keyId, false);
    }

    private static MatchGenerator mockGenerator(String keyId, boolean isLastPhase) {
        MatchGenerator gen = mock(MatchGenerator.class);
        when(gen.getKeyId()).thenReturn(keyId);
        when(gen.isLastPhaseGenerator()).thenReturn(isLastPhase);
        return gen;
    }

    @Test
    void get_knownKey_returnsGenerator() {
        MatchGenerator rrGen = mockGenerator("roundRobinNew");
        MatchGeneratorRegistry registry = new DefaultMatchGeneratorRegistry(List.of(rrGen));

        MatchGenerator result = registry.get("roundRobinNew");

        assertThat(result).isSameAs(rrGen);
    }

    @Test
    void get_unknownKey_throwsIAEWithKnownIds() {
        MatchGenerator rrGen = mockGenerator("roundRobinNew");
        MatchGeneratorRegistry registry = new DefaultMatchGeneratorRegistry(List.of(rrGen));

        assertThatThrownBy(() -> registry.get("nonExistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonExistent")
                .hasMessageContaining("roundRobinNew");
    }

    @Test
    void knownIds_returnsAllRegisteredKeys() {
        MatchGenerator gen1 = mockGenerator("roundRobinNew");
        MatchGenerator gen2 = mockGenerator("swiss");
        MatchGeneratorRegistry registry = new DefaultMatchGeneratorRegistry(List.of(gen1, gen2));

        Set<String> ids = registry.knownIds();

        assertThat(ids).containsExactlyInAnyOrder("roundRobinNew", "swiss");
    }

    @Test
    void knownIds_stableOnRepeatedCalls() {
        MatchGenerator gen1 = mockGenerator("roundRobinNew");
        MatchGenerator gen2 = mockGenerator("swiss");
        MatchGeneratorRegistry registry = new DefaultMatchGeneratorRegistry(List.of(gen1, gen2));

        Set<String> first = registry.knownIds();
        Set<String> second = registry.knownIds();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void constructor_duplicateBeanId_throwsISE() {
        MatchGenerator gen1 = mockGenerator("roundRobinNew");
        MatchGenerator gen2 = mockGenerator("roundRobinNew");

        assertThatThrownBy(() -> new DefaultMatchGeneratorRegistry(List.of(gen1, gen2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("roundRobinNew");
    }

    @Test
    void knownIds_isUnmodifiable() {
        MatchGenerator gen = mockGenerator("roundRobinNew");
        MatchGeneratorRegistry registry = new DefaultMatchGeneratorRegistry(List.of(gen));

        assertThatThrownBy(() -> registry.knownIds().add("hack"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // AC3 + AC4 (E58S01) — MatchGeneratorInfo + getGeneratorInfoList()
    // -------------------------------------------------------------------------

    @Test
    void getGeneratorInfoList_returnsList_withCorrectKeyIdAndIsLastPhase() {
        MatchGenerator rrGen = mockGenerator("roundRobin", false);
        MatchGenerator siegGen = mockGenerator("awardCeremony", true);
        MatchGeneratorRegistry registry =
                new DefaultMatchGeneratorRegistry(List.of(rrGen, siegGen));

        List<MatchGeneratorInfo> infoList = registry.getGeneratorInfoList();

        assertThat(infoList).hasSize(2);
        assertThat(infoList)
                .anySatisfy(
                        info -> {
                            assertThat(info.keyId()).isEqualTo("roundRobin");
                            assertThat(info.isLastPhaseGenerator()).isFalse();
                        });
        assertThat(infoList)
                .anySatisfy(
                        info -> {
                            assertThat(info.keyId()).isEqualTo("awardCeremony");
                            assertThat(info.isLastPhaseGenerator()).isTrue();
                        });
    }

    @Test
    void getGeneratorInfoList_exactlyOneIsLastPhaseGenerator() {
        MatchGenerator rrGen = mockGenerator("roundRobin", false);
        MatchGenerator siegGen = mockGenerator("awardCeremony", true);
        MatchGeneratorRegistry registry =
                new DefaultMatchGeneratorRegistry(List.of(rrGen, siegGen));

        List<MatchGeneratorInfo> infoList = registry.getGeneratorInfoList();

        long lastPhaseCount =
                infoList.stream().filter(MatchGeneratorInfo::isLastPhaseGenerator).count();
        assertThat(lastPhaseCount).isEqualTo(1);
        assertThat(
                        infoList.stream()
                                .filter(MatchGeneratorInfo::isLastPhaseGenerator)
                                .findFirst()
                                .map(MatchGeneratorInfo::keyId))
                .hasValue("awardCeremony");
    }

    @Test
    void getGeneratorInfoList_isUnmodifiable() {
        MatchGenerator gen = mockGenerator("roundRobin", false);
        MatchGeneratorRegistry registry = new DefaultMatchGeneratorRegistry(List.of(gen));

        assertThatThrownBy(
                        () ->
                                registry.getGeneratorInfoList()
                                        .add(new MatchGeneratorInfo("x", false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
