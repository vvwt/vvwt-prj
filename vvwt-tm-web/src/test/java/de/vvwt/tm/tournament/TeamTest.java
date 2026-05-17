// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Team} entity invariants (E21S04, AC-TDD-Team).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link Team} at {@code de.vvwt.tm.tournament.Team} did not exist
 * at commit time, causing a compile error — satisfying the DEC-22 Iron Law (no characterization
 * tests; new code tested first in red state).
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>ID-equality invariant (two entities with same UUID have the same ID)
 *   <li>Entity with null ID is distinct from one with an ID
 *   <li>teamNumber and description accessible via getters
 *   <li>Flags (participate, refereeAssignment, withoutAssessment) default behaviour
 * </ul>
 *
 * @see Team
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 185)</a>
 */
@DisplayName("Team entity invariants — E21S04 AC-TDD-Team")
class TeamTest {

    @Test
    @DisplayName("Entities with same UUID are identified by that UUID")
    void entitiesWithSameUuidHaveSameId() {
        UUID id = UUID.randomUUID();
        Team t1 = new Team();
        t1.setId(id);
        Team t2 = new Team();
        t2.setId(id);

        assertThat(t1.getId())
                .as("Both entities must reference the same UUID")
                .isEqualTo(t2.getId());
    }

    @Test
    @DisplayName("Entity with null ID is distinct from entity with non-null ID")
    void entityWithNullIdIsDistinctFromEntityWithId() {
        UUID id = UUID.randomUUID();
        Team withId = new Team();
        withId.setId(id);
        Team withoutId = new Team();

        assertThat(withoutId.getId()).as("Entity without ID must have null getId()").isNull();
        assertThat(withId.getId())
                .as("Entity with ID must return the set UUID")
                .isNotNull()
                .isEqualTo(id);
    }

    @Test
    @DisplayName("description and teamNumber are accessible via getters after set")
    void descriptionAndTeamNumberAccessible() {
        Team team = new Team();
        team.setDescription("Mannschaft A");
        team.setTeamNumber(3);

        assertThat(team.getDescription()).isEqualTo("Mannschaft A");
        assertThat(team.getTeamNumber()).isEqualTo(3);
    }

    @Test
    @DisplayName("boolean flags are settable and gettable correctly")
    void booleanFlagsSettableAndGettable() {
        Team team = new Team();
        team.setParticipate(true);
        team.setRefereeAssignment(false);
        team.setWithoutAssessment(true);

        assertThat(team.isParticipate()).isTrue();
        assertThat(team.isRefereeAssignment()).isFalse();
        assertThat(team.isWithoutAssessment()).isTrue();
    }
}
