package de.vvwt.tm.tournament.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.activity.internal.DefaultActivityAssignmentService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Q-1a TDD tests for {@link ActivityAssignmentService} (via {@code
 * DefaultActivityAssignmentService}).
 *
 * <p>Written RED-first under DEC-22 Iron Law against the relocated production code in {@code
 * de.vvwt.tm.tournament.activity}.
 *
 * <p>Tests are in the same package as the interface ({@code tournament.activity}) — same-package
 * test for the interface surface; the impl class is tested through the interface per DEC-36
 * (cross-package test typing rule applies when the test is in a DIFFERENT package from the impl;
 * here both interface and test share the same package root).
 */
class ActivityAssignmentServiceTest {

    private ActivityAssignmentService service;

    private static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID T3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultActivityAssignmentService();
    }

    // -------------------------------------------------------------------------
    // Empty activity list → empty result
    // -------------------------------------------------------------------------

    @Test
    void emptyActivityList_returnsEmptyResult() {
        ActivityAssignmentResult result =
                service.assignActivities(List.of(), Map.of(), Map.of(), 3, Set.of(T1, T2));

        assertThat(result.getAssignments()).isEmpty();
        assertThat(result.getUnassignedTeams()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Single activity — all teams assigned in first free lap
    // -------------------------------------------------------------------------

    @Test
    void singleActivity_allFree_allAssignedInLap1() {
        ActivityType photo = makeActivityType("Team Photo", null);
        Set<UUID> allTeams = Set.of(T1, T2, T3);

        ActivityAssignmentResult result =
                service.assignActivities(List.of(photo), Map.of(), Map.of(), 3, allTeams);

        assertThat(result.getAssignments()).containsKey(photo);
        assertThat(result.getAssignments().get(photo)).hasSize(3);
        assertThat(result.getUnassignedTeams().get(photo)).isEmpty();
        result.getAssignments().get(photo).forEach(a -> assertThat(a.getLapNumber()).isEqualTo(1));
    }

    // -------------------------------------------------------------------------
    // Multiple activities computed independently (AC5)
    // -------------------------------------------------------------------------

    @Test
    void multipleActivities_computedIndependently_eachTeamAssignedToBoth() {
        ActivityType photo = makeActivityType("Team Photo", null);
        ActivityType warmup = makeActivityType("Warm-up", null);
        Set<UUID> allTeams = Set.of(T1, T2, T3);

        ActivityAssignmentResult result =
                service.assignActivities(List.of(photo, warmup), Map.of(), Map.of(), 3, allTeams);

        assertThat(result.getAssignments()).containsKeys(photo, warmup);
        assertThat(result.getAssignments().get(photo)).hasSize(3);
        assertThat(result.getAssignments().get(warmup)).hasSize(3);

        List<UUID> photoTeams =
                result.getAssignments().get(photo).stream()
                        .map(ActivityAssignment::getTeamId)
                        .toList();
        List<UUID> warmupTeams =
                result.getAssignments().get(warmup).stream()
                        .map(ActivityAssignment::getTeamId)
                        .toList();
        assertThat(photoTeams).containsExactlyInAnyOrder(T1, T2, T3);
        assertThat(warmupTeams).containsExactlyInAnyOrder(T1, T2, T3);
    }

    // -------------------------------------------------------------------------
    // Capacity limit (AC3) — 2 per lap, 3 teams: T1+T2 in lap 1, T3 in lap 2
    // -------------------------------------------------------------------------

    @Test
    void capacityLimit_overflowsToNextLap() {
        ActivityType photo = makeActivityType("Team Photo", 2);
        Set<UUID> allTeams = Set.of(T1, T2, T3);

        ActivityAssignmentResult result =
                service.assignActivities(List.of(photo), Map.of(), Map.of(), 3, allTeams);

        assertThat(result.getAssignments().get(photo)).hasSize(3);
        // T1 and T2 (lowest UUIDs) assigned in lap 1; T3 in lap 2
        long lap1Count =
                result.getAssignments().get(photo).stream()
                        .filter(a -> a.getLapNumber() == 1)
                        .count();
        long lap2Count =
                result.getAssignments().get(photo).stream()
                        .filter(a -> a.getLapNumber() == 2)
                        .count();
        assertThat(lap1Count).isEqualTo(2);
        assertThat(lap2Count).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Team with no free round → reported as unassigned, no exception (AC6)
    // -------------------------------------------------------------------------

    @Test
    void teamWithNoFreeRound_reportedAsUnassigned() {
        // T1 busy in all laps
        Map<Integer, Set<UUID>> matchSched = Map.of(1, Set.of(T1), 2, Set.of(T1), 3, Set.of(T1));
        ActivityType photo = makeActivityType("Team Photo", null);

        ActivityAssignmentResult result =
                service.assignActivities(
                        List.of(photo), matchSched, Map.of(), 3, Set.of(T1, T2, T3));

        List<UUID> assignedIds =
                result.getAssignments().get(photo).stream()
                        .map(ActivityAssignment::getTeamId)
                        .toList();
        assertThat(assignedIds).containsExactlyInAnyOrder(T2, T3);
        assertThat(result.getUnassignedTeams().get(photo)).containsExactly(T1);
    }

    // -------------------------------------------------------------------------
    // Referees are also busy
    // -------------------------------------------------------------------------

    @Test
    void refereeingTeamIsNotFree() {
        Map<Integer, Set<UUID>> refSched = Map.of(1, Set.of(T1));
        Map<Integer, Set<UUID>> matchSched = Map.of(1, Set.of(T2));
        ActivityType photo = makeActivityType("Photo", null);

        ActivityAssignmentResult result =
                service.assignActivities(
                        List.of(photo), matchSched, refSched, 2, Set.of(T1, T2, T3));

        // Lap 1: only T3 free → assigned lap 1
        // Lap 2: T1 and T2 free → assigned lap 2
        assertThat(
                        result.getAssignments().get(photo).stream()
                                .filter(a -> a.getTeamId().equals(T3))
                                .findFirst()
                                .map(ActivityAssignment::getLapNumber))
                .hasValue(1);
        assertThat(
                        result.getAssignments().get(photo).stream()
                                .filter(a -> a.getTeamId().equals(T1))
                                .findFirst()
                                .map(ActivityAssignment::getLapNumber))
                .hasValue(2);
    }

    // -------------------------------------------------------------------------
    // Unknown assignment rule → UnsupportedAssignmentRuleException (AC8)
    // -------------------------------------------------------------------------

    @Test
    void unknownRule_throwsUnsupportedAssignmentRuleException() {
        ActivityType badType =
                new ActivityType(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        "Future Activity",
                        "FUTURE_ROUND_ROBIN",
                        null,
                        1);

        assertThatThrownBy(
                        () ->
                                service.assignActivities(
                                        List.of(badType), Map.of(), Map.of(), 3, Set.of(T1)))
                .isInstanceOf(UnsupportedAssignmentRuleException.class)
                .satisfies(
                        ex -> {
                            UnsupportedAssignmentRuleException rex =
                                    (UnsupportedAssignmentRuleException) ex;
                            assertThat(rex.getRuleName()).isEqualTo("FUTURE_ROUND_ROBIN");
                        });
    }

    // -------------------------------------------------------------------------
    // Input validation guards
    // -------------------------------------------------------------------------

    @Test
    void nullActivityTypes_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.assignActivities(null, Map.of(), Map.of(), 3, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activityTypes");
    }

    @Test
    void nullMatchSchedule_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.assignActivities(List.of(), null, Map.of(), 3, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchSchedule");
    }

    @Test
    void zeroTotalLapCount_throwsIllegalArgument() {
        assertThatThrownBy(
                        () ->
                                service.assignActivities(
                                        List.of(), Map.of(), Map.of(), 0, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalLapCount");
    }

    // -------------------------------------------------------------------------
    // Activity name propagated to assignments
    // -------------------------------------------------------------------------

    @Test
    void activityNamePropagatedToAssignment() {
        ActivityType photo = makeActivityType("Mannschaftsfoto", null);

        ActivityAssignmentResult result =
                service.assignActivities(List.of(photo), Map.of(), Map.of(), 1, Set.of(T1));

        assertThat(result.getAssignments().get(photo)).hasSize(1);
        assertThat(result.getAssignments().get(photo).get(0).getActivityTypeName())
                .isEqualTo("Mannschaftsfoto");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private ActivityType makeActivityType(String name, Integer capacityPerRound) {
        return new ActivityType(
                UUID.randomUUID(),
                TOURNAMENT_ID,
                name,
                AssignmentRule.FIRST_FREE_ROUND.name(),
                capacityPerRound,
                1);
    }
}
