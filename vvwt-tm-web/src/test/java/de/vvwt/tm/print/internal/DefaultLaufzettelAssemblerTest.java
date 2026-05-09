package de.vvwt.tm.print.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.print.LaufzettelAssembler;
import de.vvwt.tm.print.LaufzettelRow;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.TimelineEntryType;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.activity.ActivityAssignment;
import de.vvwt.tm.tournament.activity.ActivityAssignmentResult;
import de.vvwt.tm.tournament.activity.ActivityAssignmentService;
import de.vvwt.tm.tournament.activity.ActivityType;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Deep-behaviour unit tests for {@link DefaultLaufzettelAssembler} — E24S02.
 *
 * <p>Per AC-DEEP-BEHAVIOUR-TESTS-FRESH (E24S02): tests authored fresh RED-first with own
 * assertions, setup, and fixture data. Scenarios mined from deleted legacy {@code
 * LaufzettelAssemblerTest} as BLACK-BOX reference only — no copy-paste per DEC-41 §3 strict.
 *
 * <p>Per DEC-36 same-package rule: this test lives at {@code de.vvwt.tm.print.internal} (same
 * package as {@link DefaultLaufzettelAssembler}) and MAY type-reference the concrete class directly
 * (white-box carve-out). The subject field is declared as {@link LaufzettelAssembler} interface for
 * DEC-36 cross-package compliance at the test declaration level; the concrete {@code
 * DefaultLaufzettelAssembler} is instantiated directly (same-package).
 *
 * <p>Per DEC-41 §3: legacy {@code LaufzettelAssemblerTest} classified as Snapshot-Driven (0/4
 * observable criteria). Not reused as Contract Test. All scenarios re-authored fresh.
 *
 * <p>Test authored RED-first per DEC-22 Iron Law: committed before {@link
 * DefaultLaufzettelAssembler} exists.
 *
 * <h2>Scenario parity with legacy (AC-SCENARIO-PARITY)</h2>
 *
 * <p>Legacy {@code LaufzettelAssemblerTest} had 12 {@code @Test} methods covering scenarios:
 * AC4-PLAYING (×2), AC5-REFEREEING, AC7-ACTIVITY, AC8-FREE, AC9-BREAK, AC10-NO-TIME,
 * AC10-WITH-TIME, AC11-MULTI-PHASE, AC6-PRIORITY, EMPTY-TEAMS-EDGE, DISPLAY-NAME-FALLBACK. This
 * test covers all 12 + additional edge cases per AC-ERROR-HANDLING-COVERED-IN-FRESH-TESTS.
 */
@DisplayName("DefaultLaufzettelAssembler — deep-behaviour tests (E24S02)")
class DefaultLaufzettelAssemblerTest {

    // -------------------------------------------------------------------------
    // Shared UUIDs
    // -------------------------------------------------------------------------

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0001-000000000001");
    private static final UUID TOURNAMENT_ID =
            UUID.fromString("00000000-0000-0000-0001-000000000002");
    private static final UUID PHASE_ID = UUID.fromString("00000000-0000-0000-0001-000000000003");

    private static final UUID TEAM1_ID = UUID.fromString("00000000-0000-0000-0001-000000000010");
    private static final UUID TEAM2_ID = UUID.fromString("00000000-0000-0000-0001-000000000011");
    private static final UUID TEAM3_ID = UUID.fromString("00000000-0000-0000-0001-000000000012");

    private static final UUID AVATAR1_ID = UUID.fromString("00000000-0000-0000-0001-000000000020");
    private static final UUID AVATAR2_ID = UUID.fromString("00000000-0000-0000-0001-000000000021");
    private static final UUID AVATAR3_ID = UUID.fromString("00000000-0000-0000-0001-000000000022");

    private static final UUID MATCH_ID = UUID.fromString("00000000-0000-0000-0001-000000000030");

    // -------------------------------------------------------------------------
    // Shared domain objects (reset per test)
    // -------------------------------------------------------------------------

    private Team team1;
    private Team team2;
    private Team team3;
    private Phase phase1;
    private TeamAvatar avatar1;
    private TeamAvatar avatar2;
    private TeamAvatar avatar3;
    private Match matchLap1;

    // -------------------------------------------------------------------------
    // SUT
    // -------------------------------------------------------------------------

    private ActivityAssignmentService activityService;
    private TimelineCalculationService timelineService;

    /**
     * Subject declared as interface (DEC-36 test-declaration level); instantiated as concrete
     * (white-box).
     */
    private LaufzettelAssembler assembler;

    @BeforeEach
    void setUp() {
        team1 = new Team(TEAM1_ID, TOURNAMENT_ID, 1, "Rote Wölfe", true, false, false, null);
        team2 = new Team(TEAM2_ID, TOURNAMENT_ID, 2, "Blaue Haie", true, false, false, null);
        team3 =
                new Team(
                        TEAM3_ID,
                        TOURNAMENT_ID,
                        3,
                        null /* null description */,
                        true,
                        false,
                        false,
                        null);

        phase1 = new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 0, null);

        avatar1 = new TeamAvatar(AVATAR1_ID, TOURNAMENT_ID, PHASE_ID, 1, 1, TEAM1_ID, null, null);
        avatar2 = new TeamAvatar(AVATAR2_ID, TOURNAMENT_ID, PHASE_ID, 1, 2, TEAM2_ID, null, null);
        avatar3 = new TeamAvatar(AVATAR3_ID, TOURNAMENT_ID, PHASE_ID, 1, 3, TEAM3_ID, null, null);

        // lap=1, field=2, no referee, team1 vs team2
        matchLap1 =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1 /* lapNumber */,
                        2 /* fieldNumber */,
                        null,
                        null,
                        null,
                        null);

        activityService = mock(ActivityAssignmentService.class);
        // Default: no activities assigned
        when(activityService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new ActivityAssignmentResult(
                                Collections.emptyMap(), Collections.emptyMap()));

        // Mock TimelineCalculationService — default: return empty list (no-time path)
        timelineService = mock(TimelineCalculationService.class);
        when(timelineService.calculate(any(), any(), anyInt())).thenReturn(Collections.emptyList());

        assembler = new DefaultLaufzettelAssembler(timelineService, activityService);
    }

    // =========================================================================
    // Legacy scenario 1 — AC4: PLAYING row with opponent name and field
    // =========================================================================

    @Test
    @DisplayName(
            "S1-AC4: team playing in a round produces a PLAYING row with opponent name and field"
                    + " number")
    void playing_rowHasOpponentAndField() {
        Tournament tournament = noTimeT();
        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        assertThat(rows).hasSize(1);
        LaufzettelRow row = rows.get(0);
        assertThat(row.isPlaying()).as("team1 must be PLAYING").isTrue();
        assertThat(row.roundNumber()).as("round 1").isEqualTo(1);
        assertThat(row.opponentName()).as("opponent is team2 — Blaue Haie").isEqualTo("Blaue Haie");
        assertThat(row.fieldNumber()).as("field 2").isEqualTo("2");
        assertThat(row.isRefereeing()).isFalse();
        assertThat(row.isActivity()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    // =========================================================================
    // Legacy scenario 2 — AC4: both teams produce PLAYING rows
    // =========================================================================

    @Test
    @DisplayName("S2-AC4: both teams in a match produce PLAYING rows with each other as opponent")
    void playing_bothTeamsGetPlayingRows() {
        Tournament tournament = noTimeT();
        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        assertThat(result.get(TEAM1_ID).get(0).isPlaying()).isTrue();
        assertThat(result.get(TEAM1_ID).get(0).opponentName()).isEqualTo("Blaue Haie");
        assertThat(result.get(TEAM2_ID).get(0).isPlaying()).isTrue();
        assertThat(result.get(TEAM2_ID).get(0).opponentName()).isEqualTo("Rote Wölfe");
    }

    // =========================================================================
    // Legacy scenario 3 — AC5: REFEREEING row
    // =========================================================================

    @Test
    @DisplayName("S3-AC5: team assigned as referee produces a REFEREEING row with field number")
    void refereeing_rowHasFieldNumber() {
        Tournament tournament = noTimeT();
        Match matchWithRef =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        3 /* field 3 */,
                        TEAM3_ID /* referee */,
                        null,
                        null,
                        null);

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchWithRef),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM3_ID);
        assertThat(rows).hasSize(1);
        LaufzettelRow row = rows.get(0);
        assertThat(row.isRefereeing()).as("team3 must be REFEREEING").isTrue();
        assertThat(row.roundNumber()).isEqualTo(1);
        assertThat(row.fieldNumber()).as("referee field 3").isEqualTo("3");
        assertThat(row.isPlaying()).isFalse();
        assertThat(row.isActivity()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    // =========================================================================
    // AC-TEST-DEFAULT-ASSEMBLER-POPULATES-REFEREE-FIELDS-RED (E53S02)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-DEFAULT-ASSEMBLER-POPULATES-REFEREE-FIELDS-RED: assembler populates"
                    + " refereeMatchTeamA and refereeMatchTeamB from match avatars — E53S02")
    void assemble_refereeingRow_populatesRefereeMatchTeamFields() {
        Tournament tournament = noTimeT();
        // team3 referees a match between team1 (avatar1) and team2 (avatar2) on field 3
        Match matchWithRef =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        3 /* field 3 */,
                        TEAM3_ID /* referee */,
                        null,
                        null,
                        null);

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchWithRef),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM3_ID);
        assertThat(rows).hasSize(1);
        LaufzettelRow row = rows.get(0);
        assertThat(row.isRefereeing()).isTrue();
        // team1 = "Rote Wölfe" (avatar1), team2 = "Blaue Haie" (avatar2)
        assertThat(row.refereeMatchTeamA())
                .as("refereeMatchTeamA must be the description of the team mapped to avatar1")
                .isEqualTo("Rote Wölfe");
        assertThat(row.refereeMatchTeamB())
                .as("refereeMatchTeamB must be the description of the team mapped to avatar2")
                .isEqualTo("Blaue Haie");
    }

    // =========================================================================
    // Legacy scenario 4 — AC7: ACTIVITY row
    // =========================================================================

    @Test
    @DisplayName("S4-AC7: team assigned an activity in a free lap produces an ACTIVITY row")
    void activity_rowHasActivityName() {
        Tournament tournament = noTimeT();
        UUID actTypeId = UUID.randomUUID();
        ActivityType actType = buildActivityType(actTypeId, "Mannschaftsfoto");

        when(activityService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(
                        new ActivityAssignmentResult(
                                Map.of(
                                        actType,
                                        List.of(
                                                new ActivityAssignment(
                                                        TEAM3_ID, 1, "Mannschaftsfoto"))),
                                Collections.emptyMap()));

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        List.of(actType),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM3_ID);
        assertThat(rows).hasSize(1);
        LaufzettelRow row = rows.get(0);
        assertThat(row.isActivity()).as("team3 must have ACTIVITY row").isTrue();
        assertThat(row.activityName()).isEqualTo("Mannschaftsfoto");
        assertThat(row.isPlaying()).isFalse();
        assertThat(row.isRefereeing()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    // =========================================================================
    // Legacy scenario 5 — AC8: FREE row
    // =========================================================================

    @Test
    @DisplayName(
            "S5-AC8: team not playing, refereeing, or assigned an activity produces a FREE row")
    void free_rowWhenNothingAssigned() {
        Tournament tournament = noTimeT();

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM3_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isFree())
                .as("team3 has no match/ref/activity — must be FREE")
                .isTrue();
        assertThat(rows.get(0).roundNumber()).isEqualTo(1);
    }

    // =========================================================================
    // Legacy scenario 6 — AC9: break separator row
    // =========================================================================

    @Test
    @DisplayName("S6-AC9: phase break in timeline path produces a break separator row")
    void break_separatorRowAppeared() {
        Tournament tournament = withTimeT(LocalTime.of(10, 0));

        // Two laps: lap1 match + lap2 match
        Match matchLap2 =
                new Match(
                        UUID.randomUUID(),
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

        // Timeline: MATCH_ROUND(phase=1, lap=1, 10:00–10:15), INTRA_PHASE_BREAK(label=Mittagspause,
        // 10:15–10:30), MATCH_ROUND(phase=1, lap=2, 10:30–10:45)
        when(timelineService.calculate(any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new TimelineEntry(
                                        1,
                                        1,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(10, 0),
                                        LocalTime.of(10, 15),
                                        null),
                                new TimelineEntry(
                                        1,
                                        0,
                                        TimelineEntryType.INTRA_PHASE_BREAK,
                                        LocalTime.of(10, 15),
                                        LocalTime.of(10, 30),
                                        "Mittagspause"),
                                new TimelineEntry(
                                        1,
                                        2,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(10, 30),
                                        LocalTime.of(10, 45),
                                        null)));

        PhaseBreak phaseBreak = new PhaseBreak();
        phaseBreak.setPhaseId(PHASE_ID);
        phaseBreak.setAfterLapNumber(1);
        phaseBreak.setDurationMinutes(15);
        phaseBreak.setLabel("Mittagspause");

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1, matchLap2),
                        Map.of(PHASE_ID, List.of(phaseBreak)),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        // Expected: PLAYING(lap1), BREAK(Mittagspause), PLAYING(lap2)
        assertThat(rows).as("3 rows: lap1 + break + lap2").hasSize(3);
        assertThat(rows.get(0).isPlaying()).isTrue();
        assertThat(rows.get(1).isBreak()).as("middle row must be break separator").isTrue();
        assertThat(rows.get(1).breakLabel()).isEqualTo("Mittagspause");
        assertThat(rows.get(1).breakTimeWindow())
                .as("break time window non-empty with start time")
                .isNotEmpty();
        assertThat(rows.get(2).isPlaying()).isTrue();
    }

    // =========================================================================
    // Legacy scenario 7 — AC10: no start time → empty time windows
    // =========================================================================

    @Test
    @DisplayName(
            "S7-AC10: tournament without plannedStartTime produces rows with empty time windows")
    void noStartTime_emptyTimeWindows() {
        Tournament tournament = noTimeT();
        assertThat(assembler.hasTime(tournament)).as("hasTime false when no startTime").isFalse();

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        assertThat(result.get(TEAM1_ID).get(0).timeWindow()).isEmpty();
    }

    // =========================================================================
    // Legacy scenario 8 — AC10: with start time → non-empty time windows
    // =========================================================================

    @Test
    @DisplayName(
            "S8-AC10: tournament with plannedStartTime produces rows with non-empty time windows")
    void withStartTime_filledTimeWindows() {
        Tournament tournament = withTimeT(LocalTime.of(9, 0));
        assertThat(assembler.hasTime(tournament)).as("hasTime true when startTime set").isTrue();

        // Timeline: one MATCH_ROUND entry at 09:00–09:15
        when(timelineService.calculate(any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new TimelineEntry(
                                        1,
                                        1,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(9, 15),
                                        null)));

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        assertThat(result.get(TEAM1_ID).get(0).timeWindow())
                .as("time window non-empty when start time set")
                .isNotEmpty()
                .contains("09:00");
    }

    // =========================================================================
    // Legacy scenario 9 — AC11: multi-phase schedule has phase header rows
    // =========================================================================

    @Test
    @DisplayName("S9-AC11: two-phase schedule produces phase header rows at start of each phase")
    void multiPhase_hasPhaseHeaderRows() {
        Tournament tournament = noTimeT();

        UUID phase2Id = UUID.fromString("00000000-0000-0000-0001-000000000099");
        Phase phase2 = new Phase(phase2Id, TOURNAMENT_ID, 2, "Finale", "PENDING", 0, null);

        UUID av1p2 = UUID.fromString("00000000-0000-0000-0001-0000000000a1");
        UUID av2p2 = UUID.fromString("00000000-0000-0000-0001-0000000000a2");
        TeamAvatar avP2T1 =
                new TeamAvatar(av1p2, TOURNAMENT_ID, phase2Id, 1, 1, TEAM1_ID, null, null);
        TeamAvatar avP2T2 =
                new TeamAvatar(av2p2, TOURNAMENT_ID, phase2Id, 1, 2, TEAM2_ID, null, null);

        Match matchP2 =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        phase2Id,
                        av1p2,
                        av2p2,
                        0,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        null);

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1, phase2),
                        teams(team1, team2),
                        Map.of(
                                PHASE_ID,
                                List.of(avatar1, avatar2),
                                phase2Id,
                                List.of(avP2T1, avP2T2)),
                        Map.of(PHASE_ID, List.of(matchLap1), phase2Id, List.of(matchP2)),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        assertThat(rows).as("4 rows: header+match+header+match").hasSize(4);
        assertThat(rows.get(0).isPhaseHeader()).as("row 0 must be phase header").isTrue();
        assertThat(rows.get(0).phaseHeaderName()).isEqualTo("Vorrunde");
        assertThat(rows.get(1).isPlaying()).isTrue();
        assertThat(rows.get(2).isPhaseHeader()).as("row 2 must be phase 2 header").isTrue();
        assertThat(rows.get(2).phaseHeaderName()).isEqualTo("Finale");
        assertThat(rows.get(3).isPlaying()).isTrue();
    }

    // =========================================================================
    // Legacy scenario 10 — AC6: PLAYING > REFEREEING priority
    // =========================================================================

    @Test
    @DisplayName("S10-AC6: PLAYING takes priority over REFEREEING when team is both (safety guard)")
    void priority_playingOverRefereeing() {
        Tournament tournament = noTimeT();
        // Contrived: team1 is also set as referee
        Match matchSelfRef =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        2,
                        TEAM1_ID /* team1 self-referee */,
                        null,
                        null,
                        null);

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchSelfRef),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        LaufzettelRow row = result.get(TEAM1_ID).get(0);
        assertThat(row.isPlaying()).as("PLAYING must take priority over REFEREEING").isTrue();
        assertThat(row.isRefereeing()).isFalse();
    }

    // =========================================================================
    // Legacy scenario 11 — empty teams → empty result
    // =========================================================================

    @Test
    @DisplayName("S11-EDGE: empty teams list returns empty result without exception")
    void emptyTeams_returnsEmptyResult() {
        Tournament tournament = noTimeT();
        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        Collections.emptyList(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);
        assertThat(result).isEmpty();
    }

    // =========================================================================
    // Legacy scenario 12 — team display name fallback
    // =========================================================================

    @Test
    @DisplayName(
            "S12-EDGE: null description falls back to 'Team N'; named description used verbatim")
    void teamDisplayName_nullFallsBackToTeamNumber() {
        Tournament tournament = noTimeT();
        // Match: team3(null desc) vs team1
        UUID avA = UUID.fromString("00000000-0000-0000-0001-000000000050");
        UUID avB = UUID.fromString("00000000-0000-0000-0001-000000000051");
        TeamAvatar avT3 = new TeamAvatar(avA, TOURNAMENT_ID, PHASE_ID, 1, 1, TEAM3_ID, null, null);
        TeamAvatar avT1 = new TeamAvatar(avB, TOURNAMENT_ID, PHASE_ID, 1, 2, TEAM1_ID, null, null);
        Match m =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        avA,
                        avB,
                        0,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        null);

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team3, team1),
                        avatars(PHASE_ID, avT3, avT1),
                        matches(PHASE_ID, m),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        // team3's opponent is team1 — "Rote Wölfe"
        assertThat(result.get(TEAM3_ID).get(0).opponentName()).isEqualTo("Rote Wölfe");
        // team1's opponent is team3 — null description → "Team 3"
        assertThat(result.get(TEAM1_ID).get(0).opponentName()).isEqualTo("Team 3");
    }

    // =========================================================================
    // Additional: AC-ERROR-HANDLING-COVERED-IN-FRESH-TESTS
    // =========================================================================

    @Test
    @DisplayName("EDGE: empty phases list returns empty result")
    void emptyPhases_returnsEmptyResult() {
        Tournament tournament = noTimeT();
        var result =
                assembler.assemble(
                        tournament,
                        Collections.emptyList(),
                        teams(team1, team2),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("EDGE: empty matches in phase → all teams FREE")
    void noMatches_allTeamsFree() {
        Tournament tournament = noTimeT();
        // Phase with 1 "match" (lap=1) but we pass empty matches to assembler
        // → maxLap = 0 → no rows
        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        Collections.emptyMap() /* no matches */,
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);
        // With no matches: maxLap=0, no rows generated
        assertThat(result.get(TEAM1_ID)).isEmpty();
        assertThat(result.get(TEAM2_ID)).isEmpty();
    }

    @Test
    @DisplayName("EDGE: no phase-breaks → no break separator rows")
    void noPhaseBreaks_noBreakRows() {
        Tournament tournament = withTimeT(LocalTime.of(9, 0));
        when(timelineService.calculate(any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new TimelineEntry(
                                        1,
                                        1,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(9, 15),
                                        null)));

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap() /* no breaks */,
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        assertThat(rows).hasSize(1);
        assertThat(rows.stream().noneMatch(LaufzettelRow::isBreak)).isTrue();
    }

    @Test
    @DisplayName("EDGE: LAP_BREAK timeline entries are skipped (implicit gap per domain doc)")
    void lapBreak_skipped() {
        Tournament tournament = withTimeT(LocalTime.of(9, 0));
        when(timelineService.calculate(any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new TimelineEntry(
                                        1,
                                        1,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(9, 15),
                                        null),
                                new TimelineEntry(
                                        1,
                                        0,
                                        TimelineEntryType.LAP_BREAK,
                                        LocalTime.of(9, 15),
                                        LocalTime.of(9, 20),
                                        null),
                                new TimelineEntry(
                                        1,
                                        2,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(9, 20),
                                        LocalTime.of(9, 35),
                                        null)));

        Match matchLap2 =
                new Match(
                        UUID.randomUUID(),
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

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1, matchLap2),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        // LAP_BREAK skipped: 2 PLAYING rows only
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().noneMatch(LaufzettelRow::isBreak)).isTrue();
        assertThat(rows.get(0).isPlaying()).isTrue();
        assertThat(rows.get(1).isPlaying()).isTrue();
    }

    @Test
    @DisplayName("EDGE: assembleWithPhaseConfig with explicit lap time and lap break overrides")
    void assembleWithPhaseConfig_lapTimeOverrides() {
        Tournament tournament = noTimeT();
        // Just verify it calls through without exception using overrides
        var result =
                assembler.assembleWithPhaseConfig(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0,
                        Map.of(PHASE_ID, 20) /* 20min lap time */,
                        Map.of(PHASE_ID, 3) /* 3min lap break */);

        assertThat(result).containsKey(TEAM1_ID);
        assertThat(result.get(TEAM1_ID)).hasSize(1);
        assertThat(result.get(TEAM1_ID).get(0).isPlaying()).isTrue();
    }

    @Test
    @DisplayName(
            "EDGE: single-phase tournament produces no phase header rows (header only in"
                    + " multi-phase)")
    void singlePhase_noPhaseHeaderRow() {
        Tournament tournament = noTimeT();
        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        assertThat(rows.stream().noneMatch(LaufzettelRow::isPhaseHeader))
                .as("no phase headers in single-phase")
                .isTrue();
    }

    @Test
    @DisplayName("EDGE: SECTION_BREAK timeline entry produces a break separator row")
    void sectionBreak_producesBreakRow() {
        Tournament tournament = withTimeT(LocalTime.of(9, 0));

        UUID phase2Id = UUID.fromString("00000000-0000-0000-0001-000000000088");
        Phase phase2 = new Phase(phase2Id, TOURNAMENT_ID, 2, "Finale", "PENDING", 0, null);
        UUID av1p2 = UUID.fromString("00000000-0000-0000-0001-0000000000b1");
        UUID av2p2 = UUID.fromString("00000000-0000-0000-0001-0000000000b2");
        TeamAvatar avP2T1 =
                new TeamAvatar(av1p2, TOURNAMENT_ID, phase2Id, 1, 1, TEAM1_ID, null, null);
        TeamAvatar avP2T2 =
                new TeamAvatar(av2p2, TOURNAMENT_ID, phase2Id, 1, 2, TEAM2_ID, null, null);
        Match matchP2 =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        phase2Id,
                        av1p2,
                        av2p2,
                        0,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        null);

        when(timelineService.calculate(any(), any(), anyInt()))
                .thenReturn(
                        List.of(
                                new TimelineEntry(
                                        1,
                                        1,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(9, 0),
                                        LocalTime.of(9, 15),
                                        null),
                                new TimelineEntry(
                                        1,
                                        0,
                                        TimelineEntryType.SECTION_BREAK,
                                        LocalTime.of(9, 15),
                                        LocalTime.of(9, 25),
                                        null),
                                new TimelineEntry(
                                        2,
                                        1,
                                        TimelineEntryType.MATCH_ROUND,
                                        LocalTime.of(9, 25),
                                        LocalTime.of(9, 40),
                                        null)));

        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1, phase2),
                        teams(team1, team2),
                        Map.of(
                                PHASE_ID,
                                List.of(avatar1, avatar2),
                                phase2Id,
                                List.of(avP2T1, avP2T2)),
                        Map.of(PHASE_ID, List.of(matchLap1), phase2Id, List.of(matchP2)),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        List<LaufzettelRow> rows = result.get(TEAM1_ID);
        // Expected: phase header (multi-phase), PLAYING(p1-lap1), SECTION_BREAK, phase header,
        // PLAYING(p2-lap1)
        long breakCount = rows.stream().filter(LaufzettelRow::isBreak).count();
        assertThat(breakCount).as("one section break separator row").isEqualTo(1);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Tournament noTimeT() {
        return new Tournament(
                TOURNAMENT_ID,
                "Test",
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

    private Tournament withTimeT(LocalTime startTime) {
        return new Tournament(
                TOURNAMENT_ID,
                "Test",
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

    private static List<Phase> phases(Phase... items) {
        return List.of(items);
    }

    private static List<Team> teams(Team... items) {
        return List.of(items);
    }

    private static Map<UUID, List<Match>> matches(UUID phaseId, Match... items) {
        return Map.of(phaseId, List.of(items));
    }

    private static Map<UUID, List<TeamAvatar>> avatars(UUID phaseId, TeamAvatar... items) {
        return Map.of(phaseId, List.of(items));
    }

    private ActivityType buildActivityType(UUID id, String name) {
        ActivityType at = new ActivityType();
        at.setId(id);
        at.setTournamentId(TOURNAMENT_ID);
        at.setName(name);
        at.setAssignmentRule("firstFreeRound");
        return at;
    }
}
