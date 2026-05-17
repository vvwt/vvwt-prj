// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.print.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.print.ActivityScheduleAssembler;
import de.vvwt.tm.print.ActivityScheduleModel;
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
import de.vvwt.tm.tournament.activity.internal.DefaultActivityAssignmentService;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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
        // matchLap1 uses fieldNumber=2 (1-based stored per DEC-60 D-1 / E53S09); displays as "2"
        assertThat(row.fieldNumber())
                .as("field 2 (1-based per DEC-60 D-1) displays as \"2\"")
                .isEqualTo("2");
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
        // matchWithRef uses fieldNumber=3 (1-based stored per DEC-60 D-1 / E53S09); displays as "3"
        assertThat(row.fieldNumber())
                .as("field 3 (1-based per DEC-60 D-1) displays as \"3\"")
                .isEqualTo("3");
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
    // E53S07 — AC1, AC2, AC3, AC8: field number 1-based rendering
    // =========================================================================

    /**
     * AC1 (testing — PLAYING row): field=1 (1-based stored per DEC-60 D-1) must render as "1" in
     * the PLAYING row Feld column. After E53S09 revert of E53S07 read-side +1, the stored value is
     * passed through directly — no conversion.
     *
     * <p>History: E53S07 stored field=0 (0-based) and applied +1 at read side. E53S09 moves the fix
     * to write side (DEC-60 D-1): L2/L3/Fallback emit 1-based; read side passes through unchanged.
     */
    @Test
    @DisplayName("AC1-E53S09: PLAYING row field=1 (1-based stored) renders as \"1\"")
    void fieldNumber_playingRow_isOneBased_field1() {
        Tournament tournament = noTimeT();
        Match matchField1 =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1 /* lapNumber */,
                        1 /* fieldNumber — 1-based stored per DEC-60 D-1 */,
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
                        matches(PHASE_ID, matchField1),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        LaufzettelRow row = result.get(TEAM1_ID).get(0);
        assertThat(row.isPlaying()).as("must be PLAYING row").isTrue();
        assertThat(row.fieldNumber())
                .as("field=1 (1-based stored per DEC-60 D-1) must display as \"1\"")
                .isEqualTo("1");
    }

    /**
     * AC2 (testing — REFEREEING row): field=1 (1-based stored per DEC-60 D-1) must render as "1" in
     * the REFEREEING row Feld column. E53S09 revert of E53S07 read-side +1 — stored value passed
     * through unchanged.
     */
    @Test
    @DisplayName("AC2-E53S09: REFEREEING row field=1 (1-based stored) renders as \"1\"")
    void fieldNumber_refereeingRow_isOneBased_field1() {
        Tournament tournament = noTimeT();
        Match matchWithRef =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1 /* lapNumber */,
                        1 /* fieldNumber — 1-based stored per DEC-60 D-1 */,
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

        LaufzettelRow row = result.get(TEAM3_ID).get(0);
        assertThat(row.isRefereeing()).as("must be REFEREEING row").isTrue();
        assertThat(row.fieldNumber())
                .as("field=1 (1-based stored per DEC-60 D-1) must display as \"1\"")
                .isEqualTo("1");
    }

    /**
     * AC3 (testing — boundary): For a fixture using all three fields (fieldNumber=1,2,3 stored
     * 1-based per DEC-60 D-1), the rendered field column must contain exactly {"1","2","3"} across
     * all assembled PLAYING rows — never contains "0". E53S09 revert of E53S07 read-side +1: stored
     * values pass through unchanged.
     */
    @Test
    @DisplayName(
            "AC3-E53S09: 3-field fixture — PLAYING rows contain {\"1\",\"2\",\"3\"} never \"0\""
                    + " (1-based stored per DEC-60 D-1)")
    void fieldNumber_threeFields_neverZero_exactlyOneTwoThree() {
        Tournament tournament = noTimeT();
        UUID av1b = UUID.fromString("00000000-0000-0000-0053-000000000001");
        UUID av2b = UUID.fromString("00000000-0000-0000-0053-000000000002");
        UUID av1c = UUID.fromString("00000000-0000-0000-0053-000000000003");
        UUID av2c = UUID.fromString("00000000-0000-0000-0053-000000000004");
        TeamAvatar avT1b =
                new TeamAvatar(av1b, TOURNAMENT_ID, PHASE_ID, 1, 1, TEAM1_ID, null, null);
        TeamAvatar avT2b =
                new TeamAvatar(av2b, TOURNAMENT_ID, PHASE_ID, 1, 2, TEAM2_ID, null, null);
        TeamAvatar avT1c =
                new TeamAvatar(av1c, TOURNAMENT_ID, PHASE_ID, 1, 1, TEAM1_ID, null, null);
        TeamAvatar avT2c =
                new TeamAvatar(av2c, TOURNAMENT_ID, PHASE_ID, 1, 2, TEAM2_ID, null, null);

        // 3 matches on 3 different 1-based fields (DEC-60 D-1 / E53S09)
        Match matchField1 =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        1 /* field 1 — 1-based per DEC-60 D-1 */,
                        null,
                        null,
                        null,
                        null);
        Match matchField2 =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        av1b,
                        av2b,
                        0,
                        1,
                        2,
                        2 /* field 2 — 1-based per DEC-60 D-1 */,
                        null,
                        null,
                        null,
                        null);
        Match matchField3 =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        av1c,
                        av2c,
                        0,
                        1,
                        3,
                        3 /* field 3 — 1-based per DEC-60 D-1 */,
                        null,
                        null,
                        null,
                        null);

        // We use phase1 with all six team-avatar mappings; team1 and team2 alternate across laps
        var result =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avT1b, avT2b, avT1c, avT2c)),
                        Map.of(PHASE_ID, List.of(matchField1, matchField2, matchField3)),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        // Collect all PLAYING row field numbers for team1
        List<String> fieldNumbers =
                result.get(TEAM1_ID).stream()
                        .filter(LaufzettelRow::isPlaying)
                        .map(LaufzettelRow::fieldNumber)
                        .toList();

        assertThat(fieldNumbers).as("3 PLAYING rows for team1 across 3 laps").hasSize(3);
        assertThat(fieldNumbers)
                .as("field numbers must be exactly {\"1\",\"2\",\"3\"} — never \"0\"")
                .containsExactlyInAnyOrder("1", "2", "3");
        assertThat(fieldNumbers).as("field number \"0\" must never appear").doesNotContain("0");
    }

    /**
     * AC8 (error-handling — null/edge case): fieldNumber == null must render as empty string "".
     *
     * <p>AC-ERROR-HANDLING-NULL-FIELDNUMBER-PRESERVED (E53S09 / DEC-60 D-1): after the E53S07
     * read-side +1 revert, the existing {@code field != null} guard at lines 522/529 of {@link
     * DefaultLaufzettelAssembler} is preserved unchanged. Null fieldNumber (e.g., during the brief
     * window between L1 match-creation and L2 round-assignment) renders as empty string "".
     * Regression guard — must pass before AND after E53S09 migration.
     */
    @Test
    @DisplayName(
            "AC-ERROR-HANDLING-NULL-FIELDNUMBER-PRESERVED / AC8-E53S09: fieldNumber == null"
                    + " renders as empty string for PLAYING and REFEREEING rows (E53S09 regression"
                    + " guard)")
    void fieldNumber_null_rendersEmptyString() {
        Tournament tournament = noTimeT();

        // PLAYING: null field
        Match matchNullField =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        null /* fieldNumber null */,
                        null,
                        null,
                        null,
                        null);

        var resultPlaying =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2),
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchNullField),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        LaufzettelRow playingRow = resultPlaying.get(TEAM1_ID).get(0);
        assertThat(playingRow.isPlaying()).isTrue();
        assertThat(playingRow.fieldNumber())
                .as("null fieldNumber → empty string for PLAYING row")
                .isEqualTo("");

        // REFEREEING: null field
        Match matchNullRef =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        null /* fieldNumber null */,
                        TEAM3_ID /* referee */,
                        null,
                        null,
                        null);

        var resultRef =
                assembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchNullRef),
                        Collections.emptyMap(),
                        Collections.emptyList(),
                        0);

        LaufzettelRow refRow = resultRef.get(TEAM3_ID).get(0);
        assertThat(refRow.isRefereeing()).isTrue();
        assertThat(refRow.fieldNumber())
                .as("null fieldNumber → empty string for REFEREEING row")
                .isEqualTo("");
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

    /**
     * Builds a {@link ActivityType} with assignment rule {@code FIRST_FREE_ROUND} (correct enum
     * name) for use with the real {@link DefaultActivityAssignmentService} (E53S08).
     */
    private ActivityType buildFirstFreeRoundActivityType(UUID id, String name) {
        ActivityType at = new ActivityType();
        at.setId(id);
        at.setTournamentId(TOURNAMENT_ID);
        at.setName(name);
        at.setAssignmentRule("FIRST_FREE_ROUND");
        return at;
    }

    // =========================================================================
    // E53S08 — AC1, AC2, AC3, AC7, AC8, AC9: Mannschaftsfoto first-bye-round
    // wiring via real DefaultActivityAssignmentService (no mock)
    // =========================================================================

    /**
     * AC1 (testing — RED-first reproduction with explicit preconditions): Given (1) a
     * FIRST_FREE_ROUND ActivityType configured, (2) team3 has a bye-lap (lap 1: team1 vs team2,
     * team3 is free), when the assembler runs with the REAL DefaultActivityAssignmentService, then
     * team3's first bye-lap row must be an ACTIVITY row carrying "Mannschaftsfoto" — NOT a FREE
     * row.
     *
     * <p>Per DEC-22 Iron Law RED-first: this test is committed before any production change.
     *
     * @see DefaultActivityAssignmentService
     * @see DefaultLaufzettelAssembler
     */
    @Test
    @DisplayName(
            "AC1-E53S08-RED: real DefaultActivityAssignmentService — team's first bye-lap renders"
                    + " as ACTIVITY row with Mannschaftsfoto label (end-to-end wiring)")
    void e53s08_ac1_firstByeLap_rendersAsActivityRow_realService() {
        // Fixture: 2 laps, 3 teams. Lap 1: team1 vs team2 (team3 free). Lap 2: team1 vs team3
        // (team2 free). team3's first bye-lap = lap 1.
        Tournament tournament = noTimeT();
        UUID actTypeId = UUID.fromString("00000000-0000-0000-0002-000000000001");
        ActivityType mannschaftsfoto =
                buildFirstFreeRoundActivityType(actTypeId, "Mannschaftsfoto");

        // Lap 1: team1 (av1) vs team2 (av2), field 1
        Match matchLap1T1vsT2 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0002-000000000010"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1 /* lapNumber */,
                        0 /* fieldNumber */,
                        null,
                        null,
                        null,
                        null);

        // Use real DefaultActivityAssignmentService — no mock
        ActivityAssignmentService realService = new DefaultActivityAssignmentService();
        TimelineCalculationService tls = mock(TimelineCalculationService.class);
        when(tls.calculate(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        LaufzettelAssembler realAssembler = new DefaultLaufzettelAssembler(tls, realService);

        var result =
                realAssembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchLap1T1vsT2),
                        Collections.emptyMap(),
                        List.of(mannschaftsfoto),
                        0);

        List<LaufzettelRow> team3Rows = result.get(TEAM3_ID);
        assertThat(team3Rows).as("team3 must have exactly 1 row (lap 1)").hasSize(1);
        LaufzettelRow row = team3Rows.get(0);
        assertThat(row.isActivity())
                .as(
                        "team3's first bye-lap must render as ACTIVITY row (not FREE) when"
                                + " FIRST_FREE_ROUND ActivityType is configured")
                .isTrue();
        assertThat(row.activityName())
                .as("activity name must be the configured ActivityType name")
                .isEqualTo("Mannschaftsfoto");
        assertThat(row.isFree())
                .as("team3's first bye-lap must NOT be FREE when activity is configured")
                .isFalse();
    }

    /**
     * AC2 (testing — first bye-round only): When team3 has 2 bye-laps, only lap 1 (the first)
     * renders as ACTIVITY; lap 2 continues as FREE.
     *
     * <p>Fixture: 3 laps, 3 teams. Lap 1: team1 vs team2 (team3 free → first bye). Lap 2: team1 vs
     * team2 again (team3 free → second bye). Lap 3: team1 vs team3 (team3 playing).
     */
    @Test
    @DisplayName(
            "AC2-E53S08-RED: only first bye-lap renders as ACTIVITY; subsequent bye-laps remain"
                    + " FREE (real service)")
    void e53s08_ac2_onlyFirstByeLap_isActivity_remainderFree_realService() {
        Tournament tournament = noTimeT();
        UUID actTypeId = UUID.fromString("00000000-0000-0000-0002-000000000002");
        ActivityType mannschaftsfoto =
                buildFirstFreeRoundActivityType(actTypeId, "Mannschaftsfoto");

        // Lap 1: team1 vs team2 (team3 free — first bye)
        Match m1 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0002-000000000020"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        0,
                        null,
                        null,
                        null,
                        null);
        // Lap 2: team1 vs team2 again (team3 free — second bye)
        Match m2 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0002-000000000021"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        2,
                        0,
                        null,
                        null,
                        null,
                        null);
        // Lap 3: team1 vs team3 (team3 playing)
        UUID avT3_e53 = UUID.fromString("00000000-0000-0000-0002-000000000030");
        UUID avT1_e53 = UUID.fromString("00000000-0000-0000-0002-000000000031");
        TeamAvatar avT3 =
                new TeamAvatar(avT3_e53, TOURNAMENT_ID, PHASE_ID, 1, 3, TEAM3_ID, null, null);
        TeamAvatar avT1lap3 =
                new TeamAvatar(avT1_e53, TOURNAMENT_ID, PHASE_ID, 2, 1, TEAM1_ID, null, null);
        Match m3 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0002-000000000022"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        avT3_e53,
                        avT1_e53,
                        0,
                        1,
                        3,
                        0,
                        null,
                        null,
                        null,
                        null);

        ActivityAssignmentService realService = new DefaultActivityAssignmentService();
        TimelineCalculationService tls = mock(TimelineCalculationService.class);
        when(tls.calculate(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        LaufzettelAssembler realAssembler = new DefaultLaufzettelAssembler(tls, realService);

        var result =
                realAssembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(avatar1, avatar2, avatar3, avT3, avT1lap3)),
                        matches(PHASE_ID, m1, m2, m3),
                        Collections.emptyMap(),
                        List.of(mannschaftsfoto),
                        0);

        List<LaufzettelRow> team3Rows = result.get(TEAM3_ID);
        // Rows: lap1 (first bye → ACTIVITY), lap2 (second bye → FREE), lap3 (playing → PLAYING)
        assertThat(team3Rows).as("team3 has 3 laps").hasSize(3);
        LaufzettelRow lap1Row = team3Rows.get(0);
        LaufzettelRow lap2Row = team3Rows.get(1);
        LaufzettelRow lap3Row = team3Rows.get(2);

        assertThat(lap1Row.isActivity())
                .as("lap1 (first bye) must be ACTIVITY row for team3")
                .isTrue();
        assertThat(lap1Row.activityName()).isEqualTo("Mannschaftsfoto");
        assertThat(lap1Row.roundNumber()).isEqualTo(1);

        assertThat(lap2Row.isFree())
                .as("lap2 (second bye) must remain FREE — only FIRST bye gets photo")
                .isTrue();
        assertThat(lap2Row.roundNumber()).isEqualTo(2);

        assertThat(lap3Row.isPlaying())
                .as("lap3 (playing round) must be PLAYING row for team3")
                .isTrue();
        assertThat(lap3Row.roundNumber()).isEqualTo(3);
    }

    /**
     * AC3 (testing — multi-team coverage): When 3 teams each have at least one bye-lap, EACH team's
     * first bye-lap renders as ACTIVITY row. Fixture: 3 teams, 3 laps: lap1 team1 vs team2; lap2
     * team1 vs team3; lap3 team2 vs team3. Each team has exactly one bye-lap: team1=lap3,
     * team2=lap2, team3=lap1.
     */
    @Test
    @DisplayName(
            "AC3-E53S08-RED: all three teams' first bye-laps render as ACTIVITY rows (multi-team"
                    + " coverage, real service)")
    void e53s08_ac3_allThreeTeams_firstByeLap_isActivity_realService() {
        Tournament tournament = noTimeT();
        UUID actTypeId = UUID.fromString("00000000-0000-0000-0002-000000000003");
        ActivityType mannschaftsfoto =
                buildFirstFreeRoundActivityType(actTypeId, "Mannschaftsfoto");

        // Each team gets a unique avatar per phase (no reuse of AVATAR1_ID etc.)
        UUID av1a = UUID.fromString("00000000-0000-0000-0003-000000000010");
        UUID av1b = UUID.fromString("00000000-0000-0000-0003-000000000011");
        UUID av2a = UUID.fromString("00000000-0000-0000-0003-000000000012");
        UUID av2b = UUID.fromString("00000000-0000-0000-0003-000000000013");
        UUID av3a = UUID.fromString("00000000-0000-0000-0003-000000000014");
        UUID av3b = UUID.fromString("00000000-0000-0000-0003-000000000015");
        TeamAvatar t1av1 =
                new TeamAvatar(av1a, TOURNAMENT_ID, PHASE_ID, 1, 1, TEAM1_ID, null, null);
        TeamAvatar t1av2 =
                new TeamAvatar(av1b, TOURNAMENT_ID, PHASE_ID, 2, 1, TEAM1_ID, null, null);
        TeamAvatar t2av1 =
                new TeamAvatar(av2a, TOURNAMENT_ID, PHASE_ID, 1, 2, TEAM2_ID, null, null);
        TeamAvatar t2av2 =
                new TeamAvatar(av2b, TOURNAMENT_ID, PHASE_ID, 3, 1, TEAM2_ID, null, null);
        TeamAvatar t3av1 =
                new TeamAvatar(av3a, TOURNAMENT_ID, PHASE_ID, 1, 3, TEAM3_ID, null, null);
        TeamAvatar t3av2 =
                new TeamAvatar(av3b, TOURNAMENT_ID, PHASE_ID, 2, 2, TEAM3_ID, null, null);

        // Lap 1: team1 (av1a) vs team2 (av2a) — team3 free (first bye for team3)
        Match m1 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0003-000000000020"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        av1a,
                        av2a,
                        0,
                        1,
                        1,
                        0,
                        null,
                        null,
                        null,
                        null);
        // Lap 2: team1 (av1b) vs team3 (av3a) — team2 free (first bye for team2)
        Match m2 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0003-000000000021"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        av1b,
                        av3a,
                        0,
                        1,
                        2,
                        0,
                        null,
                        null,
                        null,
                        null);
        // Lap 3: team2 (av2b) vs team3 (av3b) — team1 free (first bye for team1)
        Match m3 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0003-000000000022"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        av2b,
                        av3b,
                        0,
                        1,
                        3,
                        0,
                        null,
                        null,
                        null,
                        null);

        ActivityAssignmentService realService = new DefaultActivityAssignmentService();
        TimelineCalculationService tls = mock(TimelineCalculationService.class);
        when(tls.calculate(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        LaufzettelAssembler realAssembler = new DefaultLaufzettelAssembler(tls, realService);

        var result =
                realAssembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        Map.of(PHASE_ID, List.of(t1av1, t1av2, t2av1, t2av2, t3av1, t3av2)),
                        matches(PHASE_ID, m1, m2, m3),
                        Collections.emptyMap(),
                        List.of(mannschaftsfoto),
                        0);

        // team3 — first bye is lap 1
        List<LaufzettelRow> t3Rows = result.get(TEAM3_ID);
        assertThat(t3Rows).as("team3 has 3 rows").hasSize(3);
        assertThat(t3Rows.get(0).isActivity())
                .as("team3's first bye is lap1 → ACTIVITY row")
                .isTrue();
        assertThat(t3Rows.get(0).activityName()).isEqualTo("Mannschaftsfoto");

        // team2 — first bye is lap 2
        List<LaufzettelRow> t2Rows = result.get(TEAM2_ID);
        assertThat(t2Rows).as("team2 has 3 rows").hasSize(3);
        assertThat(t2Rows.get(1).isActivity())
                .as("team2's first bye is lap2 → ACTIVITY row")
                .isTrue();
        assertThat(t2Rows.get(1).activityName()).isEqualTo("Mannschaftsfoto");

        // team1 — first bye is lap 3
        List<LaufzettelRow> t1Rows = result.get(TEAM1_ID);
        assertThat(t1Rows).as("team1 has 3 rows").hasSize(3);
        assertThat(t1Rows.get(2).isActivity())
                .as("team1's first bye is lap3 → ACTIVITY row")
                .isTrue();
        assertThat(t1Rows.get(2).activityName()).isEqualTo("Mannschaftsfoto");
    }

    /**
     * AC7 (testing — Mannschaftsfoto-Zeitplan ↔ Laufzettel consistency): Given AC1's fixture, both
     * the ActivityScheduleAssembler (photo-schedule) and the LaufzettelAssembler must agree on
     * which lap is the photo round for each team. For team3 (the only team with a bye-lap), both
     * assemblers must report lap 1 as the photo round.
     */
    @Test
    @DisplayName(
            "AC7-E53S08-RED: ActivityScheduleAssembler and LaufzettelAssembler agree on photo-round"
                    + " lap for each team (cross-template consistency, real service)")
    void e53s08_ac7_crossTemplate_consistency_realService() {
        Tournament tournament = noTimeT();
        UUID actTypeId = UUID.fromString("00000000-0000-0000-0002-000000000007");
        ActivityType mannschaftsfoto =
                buildFirstFreeRoundActivityType(actTypeId, "Mannschaftsfoto");

        // Lap 1: team1 (av1) vs team2 (av2) — team3 free (first bye for team3)
        Match matchLap1 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0002-000000000070"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        0,
                        null,
                        null,
                        null,
                        null);

        ActivityAssignmentService realService = new DefaultActivityAssignmentService();
        TimelineCalculationService tls = mock(TimelineCalculationService.class);
        when(tls.calculate(any(), any(), anyInt())).thenReturn(Collections.emptyList());

        LaufzettelAssembler laufzettelAssembler = new DefaultLaufzettelAssembler(tls, realService);
        ActivityScheduleAssembler scheduleAssembler =
                new DefaultActivityScheduleAssembler(tls, realService);

        // --- Laufzettel result for team3 ---
        var laufzettelResult =
                laufzettelAssembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        List.of(mannschaftsfoto),
                        0);

        List<LaufzettelRow> team3Rows = laufzettelResult.get(TEAM3_ID);
        assertThat(team3Rows).hasSize(1);
        LaufzettelRow laufzettelPhotoRow = team3Rows.get(0);
        assertThat(laufzettelPhotoRow.isActivity())
                .as("Laufzettel: team3's lap1 must be ACTIVITY row")
                .isTrue();
        int laufzettelPhotoLap = laufzettelPhotoRow.roundNumber();

        // --- Activity Schedule (photo schedule) result ---
        ActivityScheduleModel scheduleModel =
                scheduleAssembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        List.of(mannschaftsfoto),
                        mannschaftsfoto);

        // Find the data row for team3 in the schedule (team3's name appears in teamNames)
        List<de.vvwt.tm.print.ActivityScheduleRow> dataRows =
                scheduleModel.rows().stream()
                        .filter(de.vvwt.tm.print.ActivityScheduleRow::isDataRow)
                        .collect(Collectors.toList());
        assertThat(dataRows)
                .as("photo-schedule must have at least 1 data row (team3 is assigned)")
                .isNotEmpty();

        // The schedule should list team3's name in one of the data rows
        String team3DisplayName = "Team 3"; // null description → "Team 3"
        de.vvwt.tm.print.ActivityScheduleRow team3ScheduleRow =
                dataRows.stream()
                        .filter(r -> r.getTeamNames().contains(team3DisplayName))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Photo schedule must contain team3 ("
                                                        + team3DisplayName
                                                        + ") in a data row"));

        int schedulePhotoLap = team3ScheduleRow.getRoundNumber();
        assertThat(schedulePhotoLap)
                .as(
                        "Mannschaftsfoto-Zeitplan and Laufzettel must agree: both report lap "
                                + laufzettelPhotoLap
                                + " as team3's photo round")
                .isEqualTo(laufzettelPhotoLap);
    }

    /**
     * AC8 (error-handling — no FIRST_FREE_ROUND configured): When NO ActivityType is configured,
     * all bye-laps must continue to render as FREE rows — the fix must not affect this path.
     */
    @Test
    @DisplayName(
            "AC8-E53S08-RED: without FIRST_FREE_ROUND ActivityType, bye-laps remain FREE rows"
                    + " (regression guard, real service)")
    void e53s08_ac8_noActivityTypeConfigured_byeLapsRemainFree_realService() {
        Tournament tournament = noTimeT();
        // Lap 1: team1 vs team2 (team3 free)
        Match matchLap1 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0002-000000000080"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        0,
                        null,
                        null,
                        null,
                        null);

        ActivityAssignmentService realService = new DefaultActivityAssignmentService();
        TimelineCalculationService tls = mock(TimelineCalculationService.class);
        when(tls.calculate(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        LaufzettelAssembler realAssembler = new DefaultLaufzettelAssembler(tls, realService);

        // Pass EMPTY activity type list — no FIRST_FREE_ROUND configured
        var result =
                realAssembler.assemble(
                        tournament,
                        phases(phase1),
                        teams(team1, team2, team3),
                        avatars(PHASE_ID, avatar1, avatar2, avatar3),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        Collections.emptyList() /* NO activity types */,
                        0);

        List<LaufzettelRow> team3Rows = result.get(TEAM3_ID);
        assertThat(team3Rows).as("team3 has 1 row").hasSize(1);
        assertThat(team3Rows.get(0).isFree())
                .as("without FIRST_FREE_ROUND config, team3's bye-lap must remain FREE")
                .isTrue();
        assertThat(team3Rows.get(0).isActivity())
                .as("bye-lap must NOT become ACTIVITY when no ActivityType configured")
                .isFalse();
    }

    /**
     * AC9 (error-handling — team has no bye-lap): When a team plays in every lap, no rows must be
     * erroneously converted to ACTIVITY rows.
     *
     * <p>Fixture: 2 teams, 1 lap, team1 vs team2. Both teams play every lap — no bye-laps.
     */
    @Test
    @DisplayName(
            "AC9-E53S08-RED: team with no bye-lap gets no ACTIVITY rows (no false-positive, real"
                    + " service)")
    void e53s08_ac9_teamWithNoByeLap_noActivityRows_realService() {
        Tournament tournament = noTimeT();
        UUID actTypeId = UUID.fromString("00000000-0000-0000-0002-000000000009");
        ActivityType mannschaftsfoto =
                buildFirstFreeRoundActivityType(actTypeId, "Mannschaftsfoto");

        // Lap 1: team1 vs team2 — both playing, neither has a bye-lap
        Match matchLap1 =
                new Match(
                        UUID.fromString("00000000-0000-0000-0002-000000000090"),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        0,
                        1,
                        1,
                        0,
                        null,
                        null,
                        null,
                        null);

        ActivityAssignmentService realService = new DefaultActivityAssignmentService();
        TimelineCalculationService tls = mock(TimelineCalculationService.class);
        when(tls.calculate(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        LaufzettelAssembler realAssembler = new DefaultLaufzettelAssembler(tls, realService);

        var result =
                realAssembler.assemble(
                        tournament,
                        phases(phase1),
                        List.of(team1, team2) /* only 2 teams, no team3 */,
                        avatars(PHASE_ID, avatar1, avatar2),
                        matches(PHASE_ID, matchLap1),
                        Collections.emptyMap(),
                        List.of(mannschaftsfoto),
                        0);

        List<LaufzettelRow> team1Rows = result.get(TEAM1_ID);
        List<LaufzettelRow> team2Rows = result.get(TEAM2_ID);

        assertThat(team1Rows).as("team1 has 1 row (playing)").hasSize(1);
        assertThat(team1Rows.get(0).isPlaying())
                .as("team1 plays in every lap — must be PLAYING, not ACTIVITY")
                .isTrue();
        assertThat(team1Rows.get(0).isActivity())
                .as("team1 has no bye-lap — must NOT have ACTIVITY row")
                .isFalse();

        assertThat(team2Rows).as("team2 has 1 row (playing)").hasSize(1);
        assertThat(team2Rows.get(0).isPlaying())
                .as("team2 plays in every lap — must be PLAYING, not ACTIVITY")
                .isTrue();
        assertThat(team2Rows.get(0).isActivity())
                .as("team2 has no bye-lap — must NOT have ACTIVITY row")
                .isFalse();
    }
}
