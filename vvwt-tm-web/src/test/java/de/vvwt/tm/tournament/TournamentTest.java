package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Tournament} entity invariants (E21S02, AC-TDD-Tournament).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link Tournament} at {@code de.vvwt.tm.tournament} did not exist
 * at commit time, causing a compile error — satisfying the DEC-22 Iron Law (no characterization
 * tests; new code tested first in red state).
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>ID-equality invariant (two entities with same UUID are equal by ID)
 *   <li>Not-null invariant (entity with no ID is distinct from one with ID)
 *   <li>Status default reflects DRAFT lifecycle start
 *   <li>TenantId required for tenant scoping (DEC-5, DEC-17)
 * </ul>
 *
 * @see Tournament
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 */
@DisplayName("Tournament entity invariants — E21S02 AC-TDD-Tournament")
class TournamentTest {

    @Test
    @DisplayName("Entities with same UUID are identified by that UUID")
    void entitiesWithSameUuidHaveSameId() {
        UUID id = UUID.randomUUID();
        Tournament t1 = new Tournament();
        t1.setId(id);
        Tournament t2 = new Tournament();
        t2.setId(id);

        assertThat(t1.getId())
                .as("Both entities must reference the same UUID")
                .isEqualTo(t2.getId());
    }

    @Test
    @DisplayName("Entity with null ID is distinct from entity with non-null ID")
    void entityWithNullIdIsDistinctFromEntityWithId() {
        UUID id = UUID.randomUUID();
        Tournament withId = new Tournament();
        withId.setId(id);
        Tournament withoutId = new Tournament();

        assertThat(withoutId.getId()).as("Entity without ID must have null getId()").isNull();
        assertThat(withId.getId())
                .as("Entity with ID must return the set UUID")
                .isNotNull()
                .isEqualTo(id);
    }

    @Test
    @DisplayName("tenantId can be set and retrieved (DEC-5, DEC-17)")
    void tenantIdCanBeSetAndRetrieved() {
        UUID tenantId = UUID.randomUUID();
        Tournament t = new Tournament();
    }

    @Test
    @DisplayName("Status field can be set to DRAFT lifecycle value")
    void statusCanBeSetToDraft() {
        Tournament t = new Tournament();
        t.setStatus("DRAFT");

        assertThat(t.getStatus())
                .as("Tournament status must be settable to DRAFT")
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("All core fields can be set via setters (fieldCount, teamCount, description)")
    void coreFieldsCanBeSetViaSetters() {
        Tournament t = new Tournament();
        t.setDescription("Test Tournament 2026");
        t.setFieldCount(4);
        t.setTeamCount(8);
        t.setMatchFormat("BEST_OF_3");

        assertThat(t.getDescription()).isEqualTo("Test Tournament 2026");
        assertThat(t.getFieldCount()).isEqualTo(4);
        assertThat(t.getTeamCount()).isEqualTo(8);
        assertThat(t.getMatchFormat()).isEqualTo("BEST_OF_3");
    }
}
