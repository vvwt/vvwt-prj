package de.vvwt.tm.domain.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.AssignmentRule;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FirstFreeRoundAssigner} covering AC2, AC3, AC4, AC6, AC7.
 *
 * <p>All tests use fixed UUIDs for determinism. The UUID values are chosen so their natural
 * ordering is predictable: UUID comparison is lexicographic on the canonical string representation
 * (most-significant bits first).
 */
class FirstFreeRoundAssignerTest {

    private FirstFreeRoundAssigner assigner;

    // Fixed UUIDs with predictable ordering (0000..01 < 0000..02 < ... < 0000..06)
    private static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID T3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID T4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID T5 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID T6 = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID T7 = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID T8 = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID T9 = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID T10 = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID T11 = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID T12 = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assigner = new FirstFreeRoundAssigner();
    }

    // -------------------------------------------------------------------------
    // Helper factories
    // -------------------------------------------------------------------------

    private ActivityType makeActivityType(String name, Integer capacityPerRound) {
        return new ActivityType(
                UUID.randomUUID(),
                TOURNAMENT_ID,
                name,
                AssignmentRule.FIRST_FREE_ROUND.name(),
                capacityPerRound,
                1,
                TENANT_ID);
    }

    /**
     * Builds a match schedule where teams[i] and teams[i+step] play in each lap. Simplified: for
     * AC2/AC3 scenarios, create explicit schedules below.
     */
    private Map<Integer, Set<UUID>> buildMatchSchedule(Map<Integer, Set<UUID>> schedule) {
        return schedule;
    }

    private LapSchedule emptySchedule() {
        return new LapSchedule(Map.of(), Map.of());
    }

    // -------------------------------------------------------------------------
    // AC2: FIRST_FREE_ROUND — basic (6 teams, 5 laps, 3 fields, unlimited capacity)
    //
    // Setup: 6 teams in a round-robin on 3 fields → 3 matches per lap, 3 free teams per lap.
    // Laps 1..5. Unlimited capacity → all 3 free teams assigned in each lap.
    // Teams T1..T6, each plays 5 times total (5 laps × 3 matches / 2 sides = ~5).
    // For the test, we just set up a schedule where each team has exactly one free lap
    // and verify that each team is assigned in its first free lap.
    // -------------------------------------------------------------------------

    @Test
    void ac2_unlimitedCapacity_eachTeamAssignedInFirstFreeLap() {
        // 6 teams, 3 courts per lap (5 laps)
        // lap 1: T1, T2, T3, T4 playing → T5, T6 free
        // lap 2: T1, T2, T5, T6 playing → T3, T4 free
        // etc.
        // Simpler setup: 6 teams, all free in lap 1 (no matches scheduled)
        // with unlimited capacity → all 6 assigned in lap 1
        ActivityType photoType = makeActivityType("Team Photo", null);
        Set<UUID> allTeams = Set.of(T1, T2, T3, T4, T5, T6);
        LapSchedule schedule = emptySchedule(); // all teams free in all laps

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 5, allTeams);

        assertThat(result.getAssignments()).hasSize(6);
        assertThat(result.getUnassignedTeams()).isEmpty();
        // All assigned in lap 1 (their first free lap)
        result.getAssignments().forEach(a -> assertThat(a.getLapNumber()).isEqualTo(1));
        // Each team appears exactly once
        List<UUID> assignedTeams =
                result.getAssignments().stream().map(ActivityAssignment::getTeamId).toList();
        assertThat(assignedTeams).containsExactlyInAnyOrder(T1, T2, T3, T4, T5, T6);
    }

    @Test
    void ac2_eachTeamAssignedOnlyWhenNotPlaying() {
        // 6 teams, 3 courts per lap
        // Lap 1: T1,T2,T3,T4 playing → T5,T6 free
        // Lap 2: T1,T2,T5,T6 playing → T3,T4 free
        // Lap 3: T3,T4,T5,T6 playing → T1,T2 free
        // Unlimited capacity → earliest free lap for each team
        Map<Integer, Set<UUID>> matchSched = new HashMap<>();
        matchSched.put(1, Set.of(T1, T2, T3, T4));
        matchSched.put(2, Set.of(T1, T2, T5, T6));
        matchSched.put(3, Set.of(T3, T4, T5, T6));

        ActivityType photoType = makeActivityType("Team Photo", null);
        Set<UUID> allTeams = Set.of(T1, T2, T3, T4, T5, T6);
        LapSchedule schedule = new LapSchedule(matchSched, Map.of());

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 3, allTeams);

        assertThat(result.getAssignments()).hasSize(6);
        assertThat(result.getUnassignedTeams()).isEmpty();

        // T5, T6 free in lap 1 → assigned lap 1
        assertLapForTeam(result, T5, 1);
        assertLapForTeam(result, T6, 1);
        // T3, T4 busy in lap 1, free in lap 2 → assigned lap 2
        assertLapForTeam(result, T3, 2);
        assertLapForTeam(result, T4, 2);
        // T1, T2 busy in laps 1+2, free in lap 3 → assigned lap 3
        assertLapForTeam(result, T1, 3);
        assertLapForTeam(result, T2, 3);
    }

    // -------------------------------------------------------------------------
    // AC3: capacity limit — 6 teams, 5 laps, capacity = 2
    // 3 free teams per lap, only 2 assigned → 3rd overflows
    // -------------------------------------------------------------------------

    @Test
    void ac3_capacityLimit_twicePerLap_sixTeams() {
        // All teams free in all laps (no matches). Capacity = 2.
        // Expected: T1, T2 assigned in lap 1; T3, T4 in lap 2; T5, T6 in lap 3.
        // (TreeSet → UUID natural order: T1 < T2 < T3 < T4 < T5 < T6)
        ActivityType photoType = makeActivityType("Team Photo", 2);
        Set<UUID> allTeams = Set.of(T1, T2, T3, T4, T5, T6);
        LapSchedule schedule = emptySchedule();

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 5, allTeams);

        assertThat(result.getAssignments()).hasSize(6);
        assertThat(result.getUnassignedTeams()).isEmpty();

        assertLapForTeam(result, T1, 1);
        assertLapForTeam(result, T2, 1);
        assertLapForTeam(result, T3, 2);
        assertLapForTeam(result, T4, 2);
        assertLapForTeam(result, T5, 3);
        assertLapForTeam(result, T6, 3);

        // No lap exceeds capacity
        for (int lap = 1; lap <= 5; lap++) {
            int finalLap = lap;
            long assignedInLap =
                    result.getAssignments().stream()
                            .filter(a -> a.getLapNumber() == finalLap)
                            .count();
            assertThat(assignedInLap).isLessThanOrEqualTo(2);
        }
    }

    // -------------------------------------------------------------------------
    // AC4: 12 teams, 3 fields, capacity 2 → assigned across 6 laps
    // -------------------------------------------------------------------------

    @Test
    void ac4_twelveTeams_capacity2_assignedAcrossSixLaps() {
        ActivityType photoType = makeActivityType("Team Photo", 2);
        Set<UUID> allTeams = Set.of(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12);
        LapSchedule schedule = emptySchedule(); // all free

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 10, allTeams);

        assertThat(result.getAssignments()).hasSize(12);
        assertThat(result.getUnassignedTeams()).isEmpty();

        // No lap exceeds capacity of 2
        for (int lap = 1; lap <= 10; lap++) {
            int finalLap = lap;
            long assignedInLap =
                    result.getAssignments().stream()
                            .filter(a -> a.getLapNumber() == finalLap)
                            .count();
            assertThat(assignedInLap)
                    .as("lap %d must not exceed capacity", lap)
                    .isLessThanOrEqualTo(2);
        }

        // All 12 teams assigned exactly once
        List<UUID> assignedTeams =
                result.getAssignments().stream().map(ActivityAssignment::getTeamId).toList();
        assertThat(assignedTeams).hasSize(12);
        assertThat(new HashSet<>(assignedTeams))
                .containsExactlyInAnyOrder(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12);
    }

    // -------------------------------------------------------------------------
    // AC6: team with no free round → reported as unassigned, no exception
    // -------------------------------------------------------------------------

    @Test
    void ac6_teamWithNoFreeRound_isReportedAsUnassigned() {
        // T1 plays in every lap (laps 1..3). T2 and T3 are free.
        Map<Integer, Set<UUID>> matchSched = new HashMap<>();
        matchSched.put(1, Set.of(T1));
        matchSched.put(2, Set.of(T1));
        matchSched.put(3, Set.of(T1));

        ActivityType photoType = makeActivityType("Team Photo", null);
        Set<UUID> allTeams = Set.of(T1, T2, T3);
        LapSchedule schedule = new LapSchedule(matchSched, Map.of());

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 3, allTeams);

        // T2 and T3 are assigned; T1 is unassigned
        assertThat(result.getAssignments()).hasSize(2);
        List<UUID> assignedIds =
                result.getAssignments().stream().map(ActivityAssignment::getTeamId).toList();
        assertThat(assignedIds).containsExactlyInAnyOrder(T2, T3);
        assertThat(result.getUnassignedTeams()).containsExactly(T1);
    }

    @Test
    void ac6_allTeamsBusy_allUnassigned() {
        // All teams busy in all laps
        Map<Integer, Set<UUID>> matchSched =
                Map.of(
                        1, Set.of(T1, T2),
                        2, Set.of(T1, T2));
        ActivityType photoType = makeActivityType("Team Photo", null);
        Set<UUID> allTeams = Set.of(T1, T2);
        LapSchedule schedule = new LapSchedule(matchSched, Map.of());

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 2, allTeams);

        assertThat(result.getAssignments()).isEmpty();
        assertThat(result.getUnassignedTeams()).containsExactlyInAnyOrder(T1, T2);
    }

    // -------------------------------------------------------------------------
    // AC7: determinism — same input → same output
    // -------------------------------------------------------------------------

    @Test
    void ac7_identicalInputsProduceIdenticalOutputs() {
        ActivityType photoType = makeActivityType("Team Photo", 2);
        Set<UUID> allTeams = Set.of(T1, T2, T3, T4, T5, T6);
        LapSchedule schedule = emptySchedule();

        FirstFreeRoundAssigner.FirstFreeRoundResult result1 =
                assigner.assign(photoType, schedule, 5, allTeams);
        FirstFreeRoundAssigner.FirstFreeRoundResult result2 =
                assigner.assign(photoType, schedule, 5, allTeams);

        assertThat(result1.getAssignments()).isEqualTo(result2.getAssignments());
        assertThat(result1.getUnassignedTeams()).isEqualTo(result2.getUnassignedTeams());
    }

    @Test
    void ac7_teamOrderingWithinLapIsByUuidAscending() {
        // All free in lap 1, unlimited capacity
        // UUID order: T1 < T2 < T3 < T4 < T5 < T6
        ActivityType photoType = makeActivityType("Team Photo", null);
        Set<UUID> allTeams = Set.of(T6, T2, T4, T1, T3, T5); // deliberately unsorted input
        LapSchedule schedule = emptySchedule();

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 1, allTeams);

        // All 6 assigned in lap 1; order should be T1..T6 ascending
        List<UUID> assignedOrder =
                result.getAssignments().stream().map(ActivityAssignment::getTeamId).toList();
        assertThat(assignedOrder).containsExactly(T1, T2, T3, T4, T5, T6);
    }

    // -------------------------------------------------------------------------
    // Edge / guard cases
    // -------------------------------------------------------------------------

    @Test
    void emptyTeamSet_returnsEmptyResult() {
        ActivityType photoType = makeActivityType("Team Photo", null);
        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, emptySchedule(), 3, Set.of());

        assertThat(result.getAssignments()).isEmpty();
        assertThat(result.getUnassignedTeams()).isEmpty();
    }

    @Test
    void guardNullActivityType_throwsIllegalArgument() {
        assertThatThrownBy(() -> assigner.assign(null, emptySchedule(), 3, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activityType");
    }

    @Test
    void guardNullLapSchedule_throwsIllegalArgument() {
        ActivityType photoType = makeActivityType("Photo", null);
        assertThatThrownBy(() -> assigner.assign(photoType, null, 3, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lapSchedule");
    }

    @Test
    void guardZeroTotalLapCount_throwsIllegalArgument() {
        ActivityType photoType = makeActivityType("Photo", null);
        assertThatThrownBy(() -> assigner.assign(photoType, emptySchedule(), 0, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalLapCount");
    }

    // -------------------------------------------------------------------------
    // Referees are also busy (test that refereeSchedule is honoured)
    // -------------------------------------------------------------------------

    @Test
    void refereeingTeamIsNotFree() {
        // T1 referees in lap 1 (but does not play). T2 plays in lap 1.
        // T3 is fully free.
        Map<Integer, Set<UUID>> refSched = Map.of(1, Set.of(T1));
        Map<Integer, Set<UUID>> matchSched = Map.of(1, Set.of(T2));
        LapSchedule schedule = new LapSchedule(matchSched, refSched);

        ActivityType photoType = makeActivityType("Photo", null);
        Set<UUID> allTeams = Set.of(T1, T2, T3);

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 2, allTeams);

        // Lap 1: only T3 is free → T3 assigned in lap 1
        // Lap 2: T1 and T2 are free → both assigned in lap 2
        assertLapForTeam(result, T3, 1);
        assertLapForTeam(result, T1, 2);
        assertLapForTeam(result, T2, 2);
        assertThat(result.getUnassignedTeams()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Activity name is propagated to each assignment
    // -------------------------------------------------------------------------

    @Test
    void activityTypeNamePropagatedToAssignments() {
        ActivityType photoType = makeActivityType("Mannschaftsfoto", null);
        Set<UUID> allTeams = Set.of(T1);
        LapSchedule schedule = emptySchedule();

        FirstFreeRoundAssigner.FirstFreeRoundResult result =
                assigner.assign(photoType, schedule, 1, allTeams);

        assertThat(result.getAssignments()).hasSize(1);
        assertThat(result.getAssignments().get(0).getActivityTypeName())
                .isEqualTo("Mannschaftsfoto");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void assertLapForTeam(
            FirstFreeRoundAssigner.FirstFreeRoundResult result, UUID teamId, int expectedLap) {
        result.getAssignments().stream()
                .filter(a -> a.getTeamId().equals(teamId))
                .findFirst()
                .ifPresentOrElse(
                        a ->
                                assertThat(a.getLapNumber())
                                        .as("Team %s should be in lap %d", teamId, expectedLap)
                                        .isEqualTo(expectedLap),
                        () -> {
                            throw new AssertionError("No assignment found for team " + teamId);
                        });
    }
}
