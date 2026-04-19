package de.vvwt.tm.infrastructure.print;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.activity.ActivityAssignment;
import de.vvwt.tm.domain.activity.ActivityAssignmentResult;
import de.vvwt.tm.domain.activity.ActivityAssignmentService;
import de.vvwt.tm.domain.timeline.TimelineCalculationService;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActivityScheduleAssembler} — E08S09.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC3: Happy-path row assembly — rounds ordered by lap number, empty rounds omitted
 *   <li>AC4: Break separator rows interleaved from timeline
 *   <li>AC5: Summary counts (totalAssignedTeams, roundCount)
 *   <li>AC6: Unassigned team names collected
 *   <li>AC7: No-start-time path — time windows empty, no breaks emitted
 * </ul>
 *
 * <p>The {@link TimelineCalculationService} is used as a real instance (pure function). The {@link
 * ActivityAssignmentService} is mocked to control assignment outcomes. The integration-level route
 * and auth checks are in {@link PrintActivityScheduleIT}.
 *
 * @see ActivityScheduleAssembler
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S09.story.md">Story
 *     E08S09</a>
 */
@DisplayName("ActivityScheduleAssembler — E08S09 unit tests")
class ActivityScheduleAssemblerTest {

    // -------------------------------------------------------------------------
    // Shared IDs
    // -------------------------------------------------------------------------

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TOURNAMENT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PHASE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static final UUID TEAM1_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID TEAM2_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID TEAM3_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");

    private static final UUID AVATAR1_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID AVATAR2_ID = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID AVATAR3_ID = UUID.fromString("00000000-0000-0000-0000-000000000022");

    private static final UUID ACTIVITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final UUID MATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");

    // -------------------------------------------------------------------------
    // Domain objects (set up in @BeforeEach)
    // -------------------------------------------------------------------------

    /** Team 1 with description */
    private Team team1;

    /** Team 2 with description */
    private Team team2;

    /** Team 3 without description — display name falls back to "Team 3" */
    private Team team3;

    private Phase phase1;
    private TeamAvatar avatar1;
    private TeamAvatar avatar2;
    private TeamAvatar avatar3;

    /** Match pairing avatar1 vs avatar2 in lap 1, field 1 */
    private Match match;

    private ActivityType activityType;

    private ActivityAssignmentService activityAssignmentService;
    private ActivityScheduleAssembler assembler;

    @BeforeEach
    void setUp() {
        team1 =
                new Team(
                        TEAM1_ID,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        1,
                        "Rote Wölfe",
                        true,
                        false,
                        false,
                        null);
        team2 =
                new Team(
                        TEAM2_ID,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        2,
                        "Blaue Haie",
                        true,
                        false,
                        false,
                        null);
        team3 = new Team(TEAM3_ID, TENANT_ID, TOURNAMENT_ID, 3, null, true, false, false, null);

        phase1 = new Phase(PHASE_ID, TENANT_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 0, null);

        avatar1 =
                new TeamAvatar(
                        AVATAR1_ID, TENANT_ID, TOURNAMENT_ID, PHASE_ID, 1, 1, TEAM1_ID, null, null);
        avatar2 =
                new TeamAvatar(
                        AVATAR2_ID, TENANT_ID, TOURNAMENT_ID, PHASE_ID, 1, 2, TEAM2_ID, null, null);
        avatar3 =
                new TeamAvatar(
                        AVATAR3_ID, TENANT_ID, TOURNAMENT_ID, PHASE_ID, 1, 3, TEAM3_ID, null, null);

        // Match: avatar1 vs avatar2 in lap 1, field 1 (no referee)
        match =
                new Match(
                        MATCH_ID,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        null);

        activityType = buildActivityType(ACTIVITY_ID, "Mannschaftsfoto");

        activityAssignmentService = mock(ActivityAssignmentService.class);
        // Default: empty result (no assignments)
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new ActivityAssignmentResult(
                                Collections.emptyMap(), Collections.emptyMap()));

        assembler =
                new ActivityScheduleAssembler(
                        new TimelineCalculationService(), activityAssignmentService);
    }

    // =========================================================================
    // AC3 — Happy-path: round rows ordered by lap number, empty rounds omitted
    // =========================================================================

    @Test
    @DisplayName(
            "AC3: single assigned round produces one data row with correct lap number and team"
                    + " names")
    void happyPath_singleAssignedRound() {
        // team3 assigned in lap 2 (free round — not playing in lap 1)
        ActivityAssignment assignment = new ActivityAssignment(TEAM3_ID, 2, "Mannschaftsfoto");
        ActivityAssignmentResult assignResult =
                new ActivityAssignmentResult(
                        Map.of(activityType, List.of(assignment)), Map.of(activityType, Set.of()));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        // We need a second match in lap 2 for the assembler to compute maxLap >= 2
        Match match2 =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        2,
                        1,
                        null,
                        null,
                        null,
                        null);

        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3)),
                        Map.of(PHASE_ID, List.of(match, match2)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        List<ActivityScheduleRow> rows = model.rows();
        // Only lap 2 is assigned — lap 1 has no assignment and must be omitted
        assertThat(rows).as("AC3: only assigned rounds shown").hasSize(1);
        ActivityScheduleRow row = rows.get(0);
        assertThat(row.isDataRow()).as("AC3: row must be a data row").isTrue();
        assertThat(row.getRoundNumber()).as("AC3: round number must be 2").isEqualTo(2);
        assertThat(row.getTeamNames()).as("AC3: team3 fallback name").isEqualTo("Team 3");
        assertThat(row.getTimeWindow()).as("AC3: no time window without start time").isEmpty();
    }

    @Test
    @DisplayName("AC3: multiple assigned rounds appear in ascending lap order")
    void happyPath_multipleRoundsOrdered() {
        // team1 in lap 3, team2 in lap 1
        ActivityAssignment a1 = new ActivityAssignment(TEAM1_ID, 3, "Mannschaftsfoto");
        ActivityAssignment a2 = new ActivityAssignment(TEAM2_ID, 1, "Mannschaftsfoto");
        ActivityAssignmentResult assignResult =
                new ActivityAssignmentResult(
                        Map.of(activityType, List.of(a1, a2)), Map.of(activityType, Set.of()));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        // Matches in laps 1, 2, 3 so maxLap = 3
        Match m2 =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        2,
                        1,
                        null,
                        null,
                        null,
                        null);
        Match m3 =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        3,
                        1,
                        null,
                        null,
                        null,
                        null);

        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3)),
                        Map.of(PHASE_ID, List.of(match, m2, m3)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        List<ActivityScheduleRow> dataRows =
                model.rows().stream().filter(ActivityScheduleRow::isDataRow).toList();
        assertThat(dataRows).as("AC3: two non-empty rounds").hasSize(2);
        assertThat(dataRows.get(0).getRoundNumber()).as("AC3: first row is lap 1").isEqualTo(1);
        assertThat(dataRows.get(1).getRoundNumber()).as("AC3: second row is lap 3").isEqualTo(3);
    }

    @Test
    @DisplayName("AC3: empty rounds (no assignments) are omitted from the result")
    void emptyRoundsOmitted() {
        // No assignments for the target activity type
        ActivityAssignmentResult emptyResult =
                new ActivityAssignmentResult(Collections.emptyMap(), Collections.emptyMap());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(emptyResult);

        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2)),
                        Map.of(PHASE_ID, List.of(match)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        assertThat(model.rows()).as("AC3: no rows when no assignments").isEmpty();
        assertThat(model.totalAssignedTeams()).isEqualTo(0);
        assertThat(model.roundCount()).isEqualTo(0);
    }

    // =========================================================================
    // AC5 — Summary counts
    // =========================================================================

    @Test
    @DisplayName("AC5: totalAssignedTeams and roundCount reflect actual assignments")
    void summaryCountsCorrect() {
        ActivityAssignment a1 = new ActivityAssignment(TEAM1_ID, 2, "Mannschaftsfoto");
        ActivityAssignment a2 = new ActivityAssignment(TEAM2_ID, 2, "Mannschaftsfoto");
        ActivityAssignment a3 = new ActivityAssignment(TEAM3_ID, 3, "Mannschaftsfoto");
        ActivityAssignmentResult result =
                new ActivityAssignmentResult(
                        Map.of(activityType, List.of(a1, a2, a3)), Map.of(activityType, Set.of()));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(result);

        Match m2 =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        2,
                        1,
                        null,
                        null,
                        null,
                        null);
        Match m3 =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        3,
                        1,
                        null,
                        null,
                        null,
                        null);

        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3)),
                        Map.of(PHASE_ID, List.of(match, m2, m3)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        // 3 assignments total across 2 rounds (laps 2 and 3)
        assertThat(model.totalAssignedTeams()).as("AC5: 3 total assignments").isEqualTo(3);
        assertThat(model.roundCount()).as("AC5: 2 non-empty rounds").isEqualTo(2);
    }

    // =========================================================================
    // AC6 — Unassigned teams warning
    // =========================================================================

    @Test
    @DisplayName(
            "AC6: teams with no free round appear in unassignedTeamNames; hasUnassigned is true")
    void unassignedTeamsListed() {
        // team3 cannot be assigned
        ActivityAssignmentResult result =
                new ActivityAssignmentResult(
                        Map.of(activityType, Collections.emptyList()),
                        Map.of(activityType, Set.of(TEAM3_ID)));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(result);

        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3)),
                        Map.of(PHASE_ID, List.of(match)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        assertThat(model.hasUnassigned()).as("AC6: hasUnassigned must be true").isTrue();
        assertThat(model.unassignedTeamNames())
                .as("AC6: team3 has no description — fallback name 'Team 3'")
                .contains("Team 3");
    }

    @Test
    @DisplayName("AC6: when all teams are assigned, hasUnassigned is false")
    void allTeamsAssigned_noWarning() {
        ActivityAssignment a1 = new ActivityAssignment(TEAM1_ID, 2, "Mannschaftsfoto");
        ActivityAssignmentResult result =
                new ActivityAssignmentResult(
                        Map.of(activityType, List.of(a1)), Map.of(activityType, Set.of()));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(result);

        Match m2 =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        2,
                        1,
                        null,
                        null,
                        null,
                        null);

        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2)),
                        Map.of(PHASE_ID, List.of(match, m2)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        assertThat(model.hasUnassigned()).as("AC6: no unassigned teams").isFalse();
        assertThat(model.unassignedTeamNames()).isEmpty();
    }

    // =========================================================================
    // AC7 — No-start-time path
    // =========================================================================

    @Test
    @DisplayName(
            "AC7: tournament without plannedStartTime — time windows are empty, hasTime is false")
    void noStartTime_timeWindowsEmpty() {
        ActivityAssignment a1 = new ActivityAssignment(TEAM3_ID, 1, "Mannschaftsfoto");
        ActivityAssignmentResult result =
                new ActivityAssignmentResult(
                        Map.of(activityType, List.of(a1)), Map.of(activityType, Set.of()));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(result);

        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3)),
                        Map.of(PHASE_ID, List.of(match)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        assertThat(model.hasTime()).as("AC7: hasTime must be false without start time").isFalse();
        List<ActivityScheduleRow> dataRows =
                model.rows().stream().filter(ActivityScheduleRow::isDataRow).toList();
        assertThat(dataRows).hasSize(1);
        assertThat(dataRows.get(0).getTimeWindow())
                .as("AC7: time window must be empty without start time")
                .isEmpty();
        // No break rows without timeline
        List<ActivityScheduleRow> breakRows =
                model.rows().stream().filter(ActivityScheduleRow::isBreak).toList();
        assertThat(breakRows).as("AC7: no break rows without timeline").isEmpty();
    }

    @Test
    @DisplayName(
            "AC7: tournament with plannedStartTime — time windows are formatted, hasTime is true")
    void withStartTime_timeWindowsSet() {
        ActivityAssignment a1 = new ActivityAssignment(TEAM3_ID, 1, "Mannschaftsfoto");
        ActivityAssignmentResult result =
                new ActivityAssignmentResult(
                        Map.of(activityType, List.of(a1)), Map.of(activityType, Set.of()));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(result);

        // Start time: 10:00 → lap 1 = 10:00–10:15
        Tournament tournament = tournamentWithStartTime(LocalTime.of(10, 0));
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        List.of(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3)),
                        Map.of(PHASE_ID, List.of(match)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        assertThat(model.hasTime()).as("AC7: hasTime must be true with start time").isTrue();
        List<ActivityScheduleRow> dataRows =
                model.rows().stream().filter(ActivityScheduleRow::isDataRow).toList();
        assertThat(dataRows).hasSize(1);
        assertThat(dataRows.get(0).getTimeWindow())
                .as("AC7: time window must be formatted HH:mm–HH:mm")
                .matches("\\d{2}:\\d{2}\u2013\\d{2}:\\d{2}");
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    @DisplayName("Empty phases list returns empty model")
    void emptyPhases_returnsEmptyModel() {
        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        Collections.emptyList(),
                        List.of(team1),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        assertThat(model.rows()).isEmpty();
        assertThat(model.totalAssignedTeams()).isEqualTo(0);
    }

    @Test
    @DisplayName("Empty teams list returns empty model")
    void emptyTeams_returnsEmptyModel() {
        Tournament tournament = tournamentWithoutStartTime();
        ActivityScheduleModel model =
                assembler.assemble(
                        tournament,
                        List.of(phase1),
                        Collections.emptyList(),
                        Map.of(PHASE_ID, Collections.emptyList()),
                        Map.of(PHASE_ID, List.of(match)),
                        Collections.emptyMap(),
                        List.of(activityType),
                        activityType);

        assertThat(model.rows()).isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament tournamentWithoutStartTime() {
        return new Tournament(
                TOURNAMENT_ID,
                TENANT_ID,
                "Test Turnier",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                null,
                null,
                4,
                8,
                null);
    }

    private Tournament tournamentWithStartTime(LocalTime startTime) {
        return new Tournament(
                TOURNAMENT_ID,
                TENANT_ID,
                "Test Turnier",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                null,
                null,
                4,
                8,
                startTime);
    }

    private ActivityType buildActivityType(UUID id, String name) {
        ActivityType at = new ActivityType();
        at.setId(id);
        at.setTournamentId(TOURNAMENT_ID);
        at.setName(name);
        at.setAssignmentRule("FIRST_FREE_ROUND");
        return at;
    }
}
