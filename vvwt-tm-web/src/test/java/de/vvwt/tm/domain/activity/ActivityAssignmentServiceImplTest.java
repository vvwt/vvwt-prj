package de.vvwt.tm.domain.activity;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.AssignmentRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ActivityAssignmentServiceImpl} covering AC1, AC5, AC8.
 *
 * <p>The underlying algorithm correctness is covered in {@link FirstFreeRoundAssignerTest}.
 * This test class focuses on the service layer: dispatching, multi-activity independence,
 * error handling, and input validation.
 */
class ActivityAssignmentServiceImplTest {

    private ActivityAssignmentServiceImpl service;

    private static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID T3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ActivityAssignmentServiceImpl();
    }

    // -------------------------------------------------------------------------
    // AC1: service interface is a Spring bean, method signature
    // -------------------------------------------------------------------------

    @Test
    void ac1_emptyActivityList_returnsEmptyResult() {
        ActivityAssignmentResult result = service.assignActivities(
                List.of(),
                Map.of(),
                Map.of(),
                3,
                Set.of(T1, T2));

        assertThat(result.getAssignments()).isEmpty();
        assertThat(result.getUnassignedTeams()).isEmpty();
    }

    @Test
    void ac1_singleActivity_returnsMappedResult() {
        ActivityType photo = makeActivityType("Team Photo", null, AssignmentRule.FIRST_FREE_ROUND);
        Set<UUID> allTeams = Set.of(T1, T2, T3);

        ActivityAssignmentResult result = service.assignActivities(
                List.of(photo),
                Map.of(),
                Map.of(),
                3,
                allTeams);

        assertThat(result.getAssignments()).containsKey(photo);
        assertThat(result.getAssignments().get(photo)).hasSize(3);
        assertThat(result.getUnassignedTeams().get(photo)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC5: multiple activities computed independently
    // -------------------------------------------------------------------------

    @Test
    void ac5_multipleActivities_computedIndependently() {
        ActivityType photo = makeActivityType("Team Photo", null, AssignmentRule.FIRST_FREE_ROUND);
        ActivityType warmup = makeActivityType("Warm-up", null, AssignmentRule.FIRST_FREE_ROUND);

        Set<UUID> allTeams = Set.of(T1, T2, T3);

        ActivityAssignmentResult result = service.assignActivities(
                List.of(photo, warmup),
                Map.of(),
                Map.of(),
                3,
                allTeams);

        // Both activity types have all 3 teams assigned
        assertThat(result.getAssignments()).containsKeys(photo, warmup);
        assertThat(result.getAssignments().get(photo)).hasSize(3);
        assertThat(result.getAssignments().get(warmup)).hasSize(3);

        // The same team may appear in both results (independence — AC5)
        List<UUID> photoTeams = result.getAssignments().get(photo).stream()
                .map(ActivityAssignment::getTeamId).toList();
        List<UUID> warmupTeams = result.getAssignments().get(warmup).stream()
                .map(ActivityAssignment::getTeamId).toList();
        assertThat(photoTeams).containsExactlyInAnyOrder(T1, T2, T3);
        assertThat(warmupTeams).containsExactlyInAnyOrder(T1, T2, T3);
    }

    @Test
    void ac5_twoActivities_sameTeamCanAppearInSameLapForBoth() {
        // All teams free → all assigned in lap 1 for both activity types
        ActivityType photo = makeActivityType("Team Photo", null, AssignmentRule.FIRST_FREE_ROUND);
        ActivityType warmup = makeActivityType("Warm-up", null, AssignmentRule.FIRST_FREE_ROUND);

        ActivityAssignmentResult result = service.assignActivities(
                List.of(photo, warmup),
                Map.of(), Map.of(), 1,
                Set.of(T1));

        // T1 appears in lap 1 for both activities
        assertThat(result.getAssignments().get(photo).get(0).getLapNumber()).isEqualTo(1);
        assertThat(result.getAssignments().get(warmup).get(0).getLapNumber()).isEqualTo(1);
        assertThat(result.getAssignments().get(photo).get(0).getTeamId()).isEqualTo(T1);
        assertThat(result.getAssignments().get(warmup).get(0).getTeamId()).isEqualTo(T1);
    }

    // -------------------------------------------------------------------------
    // AC8: unrecognized assignment rule → UnsupportedAssignmentRuleException
    // -------------------------------------------------------------------------

    @Test
    void ac8_unknownRule_throwsUnsupportedAssignmentRuleException() {
        ActivityType badType = new ActivityType(
                UUID.randomUUID(),
                TOURNAMENT_ID,
                "Future Activity",
                "FUTURE_ROUND_ROBIN", // not a valid enum constant
                null,
                1,
                TENANT_ID
        );

        assertThatThrownBy(() ->
                service.assignActivities(
                        List.of(badType),
                        Map.of(), Map.of(), 3, Set.of(T1)))
                .isInstanceOf(UnsupportedAssignmentRuleException.class)
                .satisfies(ex -> {
                    UnsupportedAssignmentRuleException rex = (UnsupportedAssignmentRuleException) ex;
                    assertThat(rex.getRuleName()).isEqualTo("FUTURE_ROUND_ROBIN");
                    assertThat(rex.getMessage()).contains("FUTURE_ROUND_ROBIN");
                });
    }

    @Test
    void ac8_nullRule_throwsUnsupportedAssignmentRuleException() {
        ActivityType badType = new ActivityType(
                UUID.randomUUID(),
                TOURNAMENT_ID,
                "Null Rule Activity",
                null, // null assignment rule
                null,
                1,
                TENANT_ID
        );

        assertThatThrownBy(() ->
                service.assignActivities(
                        List.of(badType),
                        Map.of(), Map.of(), 3, Set.of(T1)))
                .isInstanceOf(UnsupportedAssignmentRuleException.class);
    }

    // -------------------------------------------------------------------------
    // Input validation
    // -------------------------------------------------------------------------

    @Test
    void nullActivityTypes_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.assignActivities(null, Map.of(), Map.of(), 3, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activityTypes");
    }

    @Test
    void nullMatchSchedule_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.assignActivities(List.of(), null, Map.of(), 3, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matchSchedule");
    }

    @Test
    void nullRefereeSchedule_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.assignActivities(List.of(), Map.of(), null, 3, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refereeSchedule");
    }

    @Test
    void zeroTotalLapCount_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.assignActivities(List.of(), Map.of(), Map.of(), 0, Set.of(T1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalLapCount");
    }

    @Test
    void nullAllTeamIds_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.assignActivities(List.of(), Map.of(), Map.of(), 3, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allTeamIds");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private ActivityType makeActivityType(String name, Integer capacityPerRound, AssignmentRule rule) {
        return new ActivityType(
                UUID.randomUUID(),
                TOURNAMENT_ID,
                name,
                rule.name(),
                capacityPerRound,
                1,
                TENANT_ID
        );
    }
}
