package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.Team2AvatarDistributor;
import de.vvwt.tm.tournament.Team2AvatarSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link RoundRobinTeam2AvatarDistributor} (AC3, AC8, DEC-22).
 *
 * <p>Same-package test: MAY white-box against implementation class per DEC-36.
 *
 * <p>Round-Robin algorithm: distribute one team per group before advancing position. targetGroup =
 * (i % groupCount) + 1 targetPosition = (i / groupCount) + 1
 *
 * @see RoundRobinTeam2AvatarDistributor
 * @see Team2AvatarDistributor
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy interface</a>
 * @see <a href="E58S02">E58S02 — AC3, AC8</a>
 */
class RoundRobinTeam2AvatarDistributorTest {

    private RoundRobinTeam2AvatarDistributor distributor;

    @BeforeEach
    void setUp() {
        distributor = new RoundRobinTeam2AvatarDistributor();
    }

    // ---------------------------------------------------------------------------
    // AC3: registry key
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getKeyId() returns 'round_robin'")
    void getKeyId_returnsRoundRobin() {
        assertThat(distributor.getKeyId()).isEqualTo("round_robin");
    }

    // ---------------------------------------------------------------------------
    // AC3: round_robin distribution — 4 teams, 2 groups
    // i=0: g1,p1; i=1: g2,p1; i=2: g1,p2; i=3: g2,p2
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute() with 4 teams / 2 groups distributes one-per-group before advancing")
    void distribute_4teams_2groups_roundRobin() {
        List<Team> teams = makeTeams(4);
        List<Team2AvatarSlot> slots = distributor.distribute(teams, 2);

        assertThat(slots).hasSize(4);
        // i=0: g1,p1; i=1: g2,p1; i=2: g1,p2; i=3: g2,p2
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 2, 1);
        assertSlot(slots.get(2), 1, 2);
        assertSlot(slots.get(3), 2, 2);
    }

    // ---------------------------------------------------------------------------
    // AC3: 3 teams, 2 groups (unequal)
    // i=0: g1,p1; i=1: g2,p1; i=2: g1,p2
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute() with 3 teams / 2 groups distributes round-robin (unequal sizes)")
    void distribute_3teams_2groups_unequal() {
        List<Team> teams = makeTeams(3);
        List<Team2AvatarSlot> slots = distributor.distribute(teams, 2);

        assertThat(slots).hasSize(3);
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 2, 1);
        assertSlot(slots.get(2), 1, 2);
    }

    // ---------------------------------------------------------------------------
    // AC3: 6 teams, 3 groups
    // i=0: g1,p1; i=1: g2,p1; i=2: g3,p1; i=3: g1,p2; i=4: g2,p2; i=5: g3,p2
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute() with 6 teams / 3 groups round-robins across groups")
    void distribute_6teams_3groups() {
        List<Team> teams = makeTeams(6);
        List<Team2AvatarSlot> slots = distributor.distribute(teams, 3);

        assertThat(slots).hasSize(6);
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 2, 1);
        assertSlot(slots.get(2), 3, 1);
        assertSlot(slots.get(3), 1, 2);
        assertSlot(slots.get(4), 2, 2);
        assertSlot(slots.get(5), 3, 2);
    }

    // ---------------------------------------------------------------------------
    // AC10: no teamId mutation
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute() does not mutate teams — slots carry no teamId reference")
    void distribute_doesNotMutateTeams() {
        List<Team> teams = makeTeams(4);
        List<UUID> originalIds = teams.stream().map(Team::getId).toList();

        distributor.distribute(teams, 2);

        assertThat(teams.stream().map(Team::getId).toList()).isEqualTo(originalIds);
    }

    // ---------------------------------------------------------------------------
    // Null/empty guards
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute() with null teams throws NullPointerException")
    void distribute_nullTeams_throws() {
        assertThatThrownBy(() -> distributor.distribute(null, 2))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("distribute() with empty teams returns empty list")
    void distribute_emptyTeams_returnsEmpty() {
        assertThat(distributor.distribute(List.of(), 2)).isEmpty();
    }

    @Test
    @DisplayName("distribute() with groupCount < 1 throws IllegalArgumentException")
    void distribute_groupCountLessThanOne_throws() {
        assertThatThrownBy(() -> distributor.distribute(makeTeams(2), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static List<Team> makeTeams(int count) {
        List<Team> teams = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Team t = new Team();
            t.setId(UUID.randomUUID());
            t.setTeamNumber(i + 1);
            t.setDescription("Team " + (i + 1));
            t.setParticipate(true);
            teams.add(t);
        }
        return teams;
    }

    private static void assertSlot(Team2AvatarSlot slot, int expectedGroup, int expectedPosition) {
        assertThat(slot.groupNumber()).as("groupNumber").isEqualTo(expectedGroup);
        assertThat(slot.groupPosition()).as("groupPosition").isEqualTo(expectedPosition);
    }
}
