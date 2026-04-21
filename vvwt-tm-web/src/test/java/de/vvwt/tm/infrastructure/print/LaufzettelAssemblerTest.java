package de.vvwt.tm.infrastructure.print;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.domain.activity.ActivityAssignmentResult;
import de.vvwt.tm.domain.activity.ActivityAssignmentService;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.Tournament;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LaufzettelAssembler} — E08S08.
 *
 * <p>Covers the assembly logic for all team row states (AC4–AC8, AC10, AC11) using a minimal test
 * dataset with one tournament, one phase, two teams, and one match. The {@link
 * TimelineCalculationService} is injected as a real instance (it is a pure function with no
 * dependencies). The {@link ActivityAssignmentService} is mocked.
 *
 * <p>The integration-level route and auth checks are in {@link PrintLaufzettelIT}.
 *
 * @see LaufzettelAssembler
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S08.story.md">Story
 *     E08S08</a>
 */
@DisplayName("LaufzettelAssembler — E08S08 unit tests")
class LaufzettelAssemblerTest {

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

    private static final UUID MATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

    // -------------------------------------------------------------------------
    // Domain objects
    // -------------------------------------------------------------------------

    /** Team 1 — will be playing in our test match */
    private Team team1;

    /** Team 2 — will be team 1's opponent */
    private Team team2;

    /** Team 3 — for activity / referee / free tests */
    private Team team3;

    /** Phase with sequenceNumber=1 */
    private Phase phase1;

    /** Avatar for team 1 in phase 1 */
    private TeamAvatar avatar1;

    /** Avatar for team 2 in phase 1 */
    private TeamAvatar avatar2;

    /** Avatar for team 3 in phase 1 */
    private TeamAvatar avatar3;

    /** Match pairing avatar1 vs avatar2 in lap 1, field 2 */
    private Match match;

    // -------------------------------------------------------------------------
    // SUT
    // -------------------------------------------------------------------------

    private ActivityAssignmentService activityAssignmentService;
    private LaufzettelAssembler assembler;

    @BeforeEach
    void setUp() {
        // Domain entities
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

        // Match: avatar1 vs avatar2 in lap 1, field 2 (no referee)
        match =
                new Match(
                        MATCH_ID,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0 /* OPEN */,
                        1 /* setLimit */,
                        1 /* lapNumber */,
                        2 /* fieldNumber */,
                        null,
                        null,
                        null,
                        null);

        // Mock ActivityAssignmentService — by default returns empty result
        activityAssignmentService = mock(ActivityAssignmentService.class);
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new ActivityAssignmentResult(
                                Collections.emptyMap(), Collections.emptyMap()));

        // Use real TimelineCalculationService (it is a pure function)
        assembler =
                new LaufzettelAssembler(
                        new TimelineCalculationService(), activityAssignmentService);
    }

    // =========================================================================
    // AC4: PLAYING row
    // =========================================================================

    @Test
    @DisplayName(
            "AC4: team playing in a round produces a PLAYING row with opponent name and field"
                    + " number")
    void assemblesSingleTeamWithMatchRow() {
        Tournament tournament = tournamentWithoutStartTime();
        List<Team> teams = List.of(team1, team2);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of(PHASE_ID, List.of(avatar1, avatar2));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(match));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> team1Rows = result.get(TEAM1_ID);
        assertThat(team1Rows).hasSize(1);
        LaufzettelRow row = team1Rows.get(0);
        assertThat(row.isPlaying()).as("AC4: team1 must be PLAYING").isTrue();
        assertThat(row.getRoundNumber()).as("AC4: round number must be 1").isEqualTo(1);
        assertThat(row.getOpponentName())
                .as("AC4: opponent name must be team2's description")
                .isEqualTo("Blaue Haie");
        assertThat(row.getFieldNumber()).as("AC4: field number must be '2'").isEqualTo("2");
        assertThat(row.isRefereeing()).isFalse();
        assertThat(row.isActivity()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    @Test
    @DisplayName("AC4: both teams produce PLAYING rows (opponent is each other)")
    void bothTeamsGetPlayingRows() {
        Tournament tournament = tournamentWithoutStartTime();
        List<Team> teams = List.of(team1, team2);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of(PHASE_ID, List.of(avatar1, avatar2));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(match));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        assertThat(result.get(TEAM1_ID).get(0).isPlaying()).isTrue();
        assertThat(result.get(TEAM1_ID).get(0).getOpponentName()).isEqualTo("Blaue Haie");
        assertThat(result.get(TEAM2_ID).get(0).isPlaying()).isTrue();
        assertThat(result.get(TEAM2_ID).get(0).getOpponentName()).isEqualTo("Rote Wölfe");
    }

    // =========================================================================
    // AC5: REFEREEING row
    // =========================================================================

    @Test
    @DisplayName("AC5: team refereeing in a round produces a REFEREEING row with field number")
    void assemblesSingleTeamWithRefereeRow() {
        Tournament tournament = tournamentWithoutStartTime();

        // team3 is the referee for the match
        Match matchWithReferee =
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
                        3 /* field 3 */,
                        TEAM3_ID /* refereeTeamId */,
                        null,
                        null,
                        null);

        List<Team> teams = List.of(team1, team2, team3);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase =
                Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(matchWithReferee));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> team3Rows = result.get(TEAM3_ID);
        assertThat(team3Rows).hasSize(1);
        LaufzettelRow row = team3Rows.get(0);
        assertThat(row.isRefereeing()).as("AC5: team3 must be REFEREEING").isTrue();
        assertThat(row.getRoundNumber()).isEqualTo(1);
        assertThat(row.getFieldNumber()).as("AC5: referee field number must be '3'").isEqualTo("3");
        assertThat(row.isPlaying()).isFalse();
        assertThat(row.isActivity()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    // =========================================================================
    // AC7: ACTIVITY row
    // =========================================================================

    @Test
    @DisplayName("AC7: team assigned an activity in a free lap produces an ACTIVITY row")
    void assemblesSingleTeamWithActivityRow() {
        Tournament tournament = tournamentWithoutStartTime();

        // team3 has no match, is not a referee — but has an activity in lap 1
        UUID activityTypeId = UUID.randomUUID();
        de.vvwt.tm.domain.ActivityType activityType =
                buildActivityType(activityTypeId, "Mannschaftsfoto");

        // Configure mock to assign "Mannschaftsfoto" to team3 in lap 1
        ActivityAssignmentResult assignResult =
                new ActivityAssignmentResult(
                        Map.of(
                                activityType,
                                List.of(
                                        new de.vvwt.tm.domain.activity.ActivityAssignment(
                                                TEAM3_ID, 1, "Mannschaftsfoto"))),
                        Collections.emptyMap());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Team> teams = List.of(team1, team2, team3);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase =
                Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(match));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        List.of(activityType),
                        0);

        List<LaufzettelRow> team3Rows = result.get(TEAM3_ID);
        assertThat(team3Rows).hasSize(1);
        LaufzettelRow row = team3Rows.get(0);
        assertThat(row.isActivity()).as("AC7: team3 must have ACTIVITY row").isTrue();
        assertThat(row.getActivityName()).isEqualTo("Mannschaftsfoto");
        assertThat(row.isPlaying()).isFalse();
        assertThat(row.isRefereeing()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    // =========================================================================
    // AC8: FREE row
    // =========================================================================

    @Test
    @DisplayName("AC8: team not playing, refereeing, or assigned an activity produces a FREE row")
    void assemblesSingleTeamWithFreeRow() {
        Tournament tournament = tournamentWithoutStartTime();

        // team3 has no match, no referee role, no activity — should be FREE
        List<Team> teams = List.of(team1, team2, team3);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase =
                Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(match));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> team3Rows = result.get(TEAM3_ID);
        assertThat(team3Rows).hasSize(1);
        LaufzettelRow row = team3Rows.get(0);
        assertThat(row.isFree())
                .as("AC8: team3 must be FREE (no play, referee, or activity)")
                .isTrue();
        assertThat(row.getRoundNumber()).isEqualTo(1);
        assertThat(row.isPlaying()).isFalse();
        assertThat(row.isRefereeing()).isFalse();
        assertThat(row.isActivity()).isFalse();
    }

    // =========================================================================
    // AC9: Break separator row
    // =========================================================================

    @Test
    @DisplayName("AC9: phase break in tournament produces a break separator row in timeline path")
    void breakSeparatorRowAppended() {
        // Tournament with a planned start time so the timeline path is taken
        Tournament tournament = tournamentWithStartTime(LocalTime.of(10, 0));

        // Two-lap match data so the phase has lapCount=2
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
                        2 /* lap 2 */,
                        1,
                        null,
                        null,
                        null,
                        null);

        // Phase break after lap 1
        de.vvwt.tm.tournament.PhaseBreak phaseBreak = new de.vvwt.tm.tournament.PhaseBreak();
        phaseBreak.setPhaseId(PHASE_ID);
        phaseBreak.setAfterLapNumber(1);
        phaseBreak.setDurationMinutes(15);
        phaseBreak.setLabel("Mittagspause");

        List<Team> teams = List.of(team1, team2);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of(PHASE_ID, List.of(avatar1, avatar2));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(match, match2));
        Map<UUID, List<de.vvwt.tm.tournament.PhaseBreak>> breaksByPhase =
                Map.of(PHASE_ID, List.of(phaseBreak));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        breaksByPhase,
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        // Expected: PLAYING(lap1), BREAK(Mittagspause), PLAYING(lap2)
        assertThat(rows).as("AC9: team rows must include break separator").hasSize(3);
        assertThat(rows.get(0).isPlaying()).isTrue();
        LaufzettelRow breakRow = rows.get(1);
        assertThat(breakRow.isBreak()).as("AC9: middle row must be a break separator").isTrue();
        assertThat(breakRow.getBreakLabel()).isEqualTo("Mittagspause");
        assertThat(breakRow.getBreakTimeWindow())
                .as("AC9: break time window must be non-empty with start time set")
                .isNotEmpty();
        assertThat(rows.get(2).isPlaying()).isTrue();
    }

    // =========================================================================
    // AC10: No start time → empty time windows
    // =========================================================================

    @Test
    @DisplayName("AC10: tournament without plannedStartTime produces rows with empty time windows")
    void noStartTimeProducesEmptyTimeWindows() {
        Tournament tournament = tournamentWithoutStartTime();
        assertThat(assembler.hasTime(tournament))
                .as("AC10: hasTime must be false when plannedStartTime is null")
                .isFalse();

        List<Team> teams = List.of(team1, team2);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of(PHASE_ID, List.of(avatar1, avatar2));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(match));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTimeWindow())
                .as("AC10: time window must be empty string when no start time is set")
                .isEmpty();
    }

    @Test
    @DisplayName("AC10: tournament with plannedStartTime produces rows with non-empty time windows")
    void withStartTimeProducesFilledTimeWindows() {
        Tournament tournament = tournamentWithStartTime(LocalTime.of(9, 0));
        assertThat(assembler.hasTime(tournament))
                .as("AC10: hasTime must be true when plannedStartTime is set")
                .isTrue();

        List<Team> teams = List.of(team1, team2);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of(PHASE_ID, List.of(avatar1, avatar2));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(match));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTimeWindow())
                .as("AC10: time window must be non-empty when start time is set")
                .isNotEmpty()
                .contains("09:00");
    }

    // =========================================================================
    // AC11: Multi-phase schedule has phase header rows
    // =========================================================================

    @Test
    @DisplayName("AC11: two-phase schedule produces phase header rows at the start of each phase")
    void multiPhaseScheduleHasPhaseHeaders() {
        Tournament tournament = tournamentWithoutStartTime();

        UUID phase2Id = UUID.fromString("00000000-0000-0000-0000-000000000099");
        Phase phase2 =
                new Phase(phase2Id, TENANT_ID, TOURNAMENT_ID, 2, "Finale", "PENDING", 0, null);

        UUID avatar1p2 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID avatar2p2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        TeamAvatar avP2T1 =
                new TeamAvatar(
                        avatar1p2, TENANT_ID, TOURNAMENT_ID, phase2Id, 1, 1, TEAM1_ID, null, null);
        TeamAvatar avP2T2 =
                new TeamAvatar(
                        avatar2p2, TENANT_ID, TOURNAMENT_ID, phase2Id, 1, 2, TEAM2_ID, null, null);

        Match matchP2 =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        phase2Id,
                        avatar1p2,
                        avatar2p2,
                        0,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        null);

        List<Team> teams = List.of(team1, team2);
        List<Phase> phases = List.of(phase1, phase2);
        Map<UUID, List<TeamAvatar>> avatarsByPhase =
                Map.of(
                        PHASE_ID, List.of(avatar1, avatar2),
                        phase2Id, List.of(avP2T1, avP2T2));
        Map<UUID, List<Match>> matchesByPhase =
                Map.of(
                        PHASE_ID, List.of(match),
                        phase2Id, List.of(matchP2));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        // Expected for team1: PHASE_HEADER(Vorrunde), PLAYING(lap1), PHASE_HEADER(Finale),
        // PLAYING(lap1)
        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        assertThat(rows).as("AC11: two phases must produce 4 rows (header + match × 2)").hasSize(4);

        assertThat(rows.get(0).isPhaseHeader())
                .as("AC11: first row must be a phase header")
                .isTrue();
        assertThat(rows.get(0).getPhaseHeaderName()).isEqualTo("Vorrunde");

        assertThat(rows.get(1).isPlaying()).isTrue();

        assertThat(rows.get(2).isPhaseHeader())
                .as("AC11: third row must be a phase header for phase 2")
                .isTrue();
        assertThat(rows.get(2).getPhaseHeaderName()).isEqualTo("Finale");

        assertThat(rows.get(3).isPlaying()).isTrue();
    }

    // =========================================================================
    // AC6: Round state priority (PLAYING > REFEREEING)
    // =========================================================================

    @Test
    @DisplayName("AC6: PLAYING takes priority over REFEREEING when a team is both (safety guard)")
    void playingTakesPriorityOverRefereeing() {
        Tournament tournament = tournamentWithoutStartTime();

        // Contrived: team1 is listed as referee AND as a player in the same match
        // (This can't happen in real data per E03S10 design, but the assembler must be safe)
        Match matchWithSelfReferee =
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
                        2,
                        TEAM1_ID /* team1 as referee — intentionally contrived */,
                        null,
                        null,
                        null);

        List<Team> teams = List.of(team1, team2);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of(PHASE_ID, List.of(avatar1, avatar2));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(matchWithSelfReferee));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        LaufzettelRow row = result.get(TEAM1_ID).get(0);
        assertThat(row.isPlaying()).as("AC6: PLAYING must take priority over REFEREEING").isTrue();
        assertThat(row.isRefereeing()).isFalse();
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    @DisplayName("Empty teams list returns empty result without exception")
    void emptyTeamsReturnsEmptyResult() {
        Tournament tournament = tournamentWithoutStartTime();
        List<Phase> phases = List.of(phase1);

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
            "AC4: team description used as display name; fallback to 'Team N' when description is"
                    + " null")
    void teamDisplayNameFallsBackToTeamNumber() {
        Tournament tournament = tournamentWithoutStartTime();

        // team3 has null description — should fall back to "Team 3"
        UUID avatar3InPhase = UUID.fromString("00000000-0000-0000-0000-000000000050");
        UUID avatar1InPhase = UUID.fromString("00000000-0000-0000-0000-000000000051");
        TeamAvatar av3 =
                new TeamAvatar(
                        avatar3InPhase,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        1,
                        1,
                        TEAM3_ID,
                        null,
                        null);
        TeamAvatar av1 =
                new TeamAvatar(
                        avatar1InPhase,
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        1,
                        2,
                        TEAM1_ID,
                        null,
                        null);
        Match m =
                new Match(
                        UUID.randomUUID(),
                        TENANT_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        avatar3InPhase,
                        avatar1InPhase,
                        0,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        null);

        List<Team> teams = List.of(team1, team3);
        List<Phase> phases = List.of(phase1);
        Map<UUID, List<TeamAvatar>> avatarsByPhase = Map.of(PHASE_ID, List.of(av3, av1));
        Map<UUID, List<Match>> matchesByPhase = Map.of(PHASE_ID, List.of(m));

        Map<UUID, List<LaufzettelRow>> result =
                assembler.assemble(
                        tournament,
                        phases,
                        teams,
                        avatarsByPhase,
                        matchesByPhase,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        // team3's opponent is team1 ("Rote Wölfe")
        assertThat(result.get(TEAM3_ID).get(0).getOpponentName()).isEqualTo("Rote Wölfe");
        // team1's opponent is team3 — description is null, so "Team 3"
        assertThat(result.get(TEAM1_ID).get(0).getOpponentName()).isEqualTo("Team 3");
    }

    // =========================================================================
    // Private helpers
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
                null /* no start time */);
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

    private de.vvwt.tm.domain.ActivityType buildActivityType(UUID id, String name) {
        de.vvwt.tm.domain.ActivityType at = new de.vvwt.tm.domain.ActivityType();
        at.setId(id);
        at.setTournamentId(TOURNAMENT_ID);
        at.setName(name);
        at.setAssignmentRule("firstFreeRound");
        return at;
    }
}
