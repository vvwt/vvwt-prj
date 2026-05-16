// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.internal.DefaultTeam2AvatarDistributorRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link Team2AvatarDistributorRegistry} via {@link
 * DefaultTeam2AvatarDistributorRegistry} (AC2, AC3, AC8, DEC-22).
 *
 * <p>Cross-package test in {@code de.vvwt.tm.tournament}: injects {@link
 * Team2AvatarDistributorRegistry} interface per DEC-36. Implementation class directly referenced in
 * constructor call per DEC-36 exception (same-package-test rule does not apply — we use the
 * interface for injection but construct the impl for test).
 *
 * @see Team2AvatarDistributorRegistry
 * @see DefaultTeam2AvatarDistributorRegistry
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributorRegistry</a>
 * @see <a href="E58S02">E58S02 — AC2, AC3</a>
 */
class Team2AvatarDistributorRegistryTest {

    // ---------------------------------------------------------------------------
    // AC2: registry resolves both keys
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("get('sequential') returns the sequential distributor")
    void get_sequential_returnsSequentialDistributor() {
        Team2AvatarDistributor sequential = mockDistributor("sequential");
        Team2AvatarDistributor roundRobin = mockDistributor("round_robin");

        Team2AvatarDistributorRegistry registry =
                new DefaultTeam2AvatarDistributorRegistry(List.of(sequential, roundRobin));

        assertThat(registry.get("sequential")).isSameAs(sequential);
    }

    @Test
    @DisplayName("get('round_robin') returns the round_robin distributor")
    void get_roundRobin_returnsRoundRobinDistributor() {
        Team2AvatarDistributor sequential = mockDistributor("sequential");
        Team2AvatarDistributor roundRobin = mockDistributor("round_robin");

        Team2AvatarDistributorRegistry registry =
                new DefaultTeam2AvatarDistributorRegistry(List.of(sequential, roundRobin));

        assertThat(registry.get("round_robin")).isSameAs(roundRobin);
    }

    @Test
    @DisplayName("knownKeys() contains both 'sequential' and 'round_robin'")
    void knownKeys_containsBothKeys() {
        Team2AvatarDistributor sequential = mockDistributor("sequential");
        Team2AvatarDistributor roundRobin = mockDistributor("round_robin");

        Team2AvatarDistributorRegistry registry =
                new DefaultTeam2AvatarDistributorRegistry(List.of(sequential, roundRobin));

        assertThat(registry.knownKeys()).containsExactlyInAnyOrder("sequential", "round_robin");
    }

    // ---------------------------------------------------------------------------
    // AC2: unknown key throws
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("get('unknown') throws IllegalArgumentException")
    void get_unknownKey_throwsIllegalArgumentException() {
        Team2AvatarDistributorRegistry registry =
                new DefaultTeam2AvatarDistributorRegistry(List.of(mockDistributor("sequential")));

        assertThatThrownBy(() -> registry.get("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    // ---------------------------------------------------------------------------
    // AC2: duplicate key at startup fails fast
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("duplicate key at construction throws IllegalStateException")
    void constructor_duplicateKey_throwsIllegalStateException() {
        Team2AvatarDistributor d1 = mockDistributor("sequential");
        Team2AvatarDistributor d2 = mockDistributor("sequential");

        assertThatThrownBy(() -> new DefaultTeam2AvatarDistributorRegistry(List.of(d1, d2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sequential");
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static Team2AvatarDistributor mockDistributor(String key) {
        Team2AvatarDistributor d = mock(Team2AvatarDistributor.class);
        when(d.getKeyId()).thenReturn(key);
        return d;
    }
}
