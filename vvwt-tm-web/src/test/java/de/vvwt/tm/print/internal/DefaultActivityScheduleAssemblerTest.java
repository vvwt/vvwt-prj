package de.vvwt.tm.print.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.AssignmentRule;
import de.vvwt.tm.domain.activity.ActivityAssignment;
import de.vvwt.tm.domain.activity.ActivityAssignmentResult;
import de.vvwt.tm.domain.activity.ActivityAssignmentService;
import de.vvwt.tm.print.ActivityScheduleModel;
import de.vvwt.tm.print.ActivityScheduleRow;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.TimelineEntryType;
import de.vvwt.tm.tournament.Tournament;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Deep-behaviour unit tests for {@link DefaultActivityScheduleAssembler} (E24S03,
 * AC-DEEP-BEHAVIOUR-TESTS-FRESH, AC-SCENARIO-PARITY).
 *
 * <p>Tests are in the same package as the implementation ({@code de.vvwt.tm.print.internal}) —
 * white-box reference to {@code DefaultActivityScheduleAssembler} is permitted per DEC-36
 * same-package rule.
 *
 * <h2>DEC-41 Audit — Legacy test classification</h2>
 *
 * <p>The deleted legacy {@code ActivityScheduleAssemblerTest} (deleted in E24S01 per AC-41-§3) had
 * 10 test methods. Classification per DEC-41 § Decision clause 1 observable criteria:
 *
 * <ul>
 *   <li>No jqwik {@code @Property} annotations (criterion a: absent)
 *   <li>No round-trip / bijection form (criterion b: absent)
 *   <li>No external-spec citation with locator (criterion c: absent)
 *   <li>No named algebraic invariant with quantified body (criterion d: absent)
 * </ul>
 *
 * <p>Classification verdict: <strong>Snapshot-Driven → FORBIDDEN for reuse</strong> per DEC-41 §
 * Decision clause 3 item (3). All tests below are fresh, authored RED-first per DEC-22 Iron Law.
 * Scenario parity documented in impl-report.
 *
 * <h2>Dependency mocking strategy</h2>
 *
 * <ul>
 *   <li>{@code TimelineCalculationService} — mocked via interface ({@code de.vvwt.tm.tournament.*}
 *       public interface), cross-package usage → DEC-36 interface reference required
 *   <li>{@code ActivityAssignmentService} — mocked via interface ({@code
 *       de.vvwt.tm.domain.activity.*} public interface), cross-package → DEC-36
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DefaultActivityScheduleAssemblerTest {

    // Cross-package dependencies → typed as interfaces per DEC-36
    @Mock private TimelineCalculationService timelineCalculationService;
    @Mock private ActivityAssignmentService activityAssignmentService;

    // Same-package subject → white-box reference permitted per DEC-36
    private DefaultActivityScheduleAssembler assembler;

    // ── Shared test fixtures ─────────────────────────────────────────────────

    private final UUID phaseId = UUID.randomUUID();
    private final UUID activityTypeId = UUID.randomUUID();
    private final String activityTypeName = "Fotostudio";

    @BeforeEach
    void setUp() {
        assembler = new DefaultActivityScheduleAssembler(
                timelineCalculationService, activityAssignmentService);
    }

    // ── Scenario 1: empty phases → empty model ───────────────────────────────

    /** Scenario 1: empty phases list → returns empty model. */
    @Test
    void assemble_emptyPhases_returnsEmptyModel() {
        Tournament tournament = tournament(LocalTime.of(9, 0));
        List<Phase> phases = List.of();
        UUID t1 = UUID.randomUUID();
        List<Team> teams = List.of(team(t1, "Team A"));
        ActivityType at = activityType(activityTypeId, activityTypeName);

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams, Map.of(), Map.of(), Map.of(),
                List.of(at), at);

        assertThat(result.rows()).isEmpty();
        assertThat(result.totalAssignedTeams()).isZero();
        assertThat(result.roundCount()).isZero();
    }

    // ── Scenario 2: empty teams → empty model ───────────────────────────────

    /** Scenario 2: empty teams list → returns empty model. */
    @Test
    void assemble_emptyTeams_returnsEmptyModel() {
        Tournament tournament = tournament(LocalTime.of(9, 0));
        List<Phase> phases = List.of(phase(phaseId));
        ActivityType at = activityType(activityTypeId, activityTypeName);

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, List.of(), Map.of(), Map.of(), Map.of(),
                List.of(at), at);

        assertThat(result.rows()).isEmpty();
    }

    // ── Scenario 3: no-start-time path (AC7) ────────────────────────────────

    /**
     * Scenario 3: tournament has no planned start time → no timeline, time windows are empty, no
     * break separators.
     */
    @Test
    void assemble_noStartTime_producesDataRowsWithEmptyTimeWindows() {
        Tournament tournament = tournament(null); // no start time

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);

        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(new ActivityAssignment(t1, 1, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team A"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1)),
                Map.of(),
                List.of(at), at);

        assertThat(result.rows()).hasSize(1);
        ActivityScheduleRow row = result.rows().get(0);
        assertThat(row.isDataRow()).isTrue();
        assertThat(row.getTimeWindow()).isEmpty(); // AC7: empty when no start time
        assertThat(row.getRoundNumber()).isEqualTo(1);
    }

    // ── Scenario 4: basic assembly — single phase, single round ─────────────

    /** Scenario 4: basic assembly with start time, one assigned team in one round. */
    @Test
    void assemble_singlePhaseOneRound_producesDataRow() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);

        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1));

        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(new ActivityAssignment(t1, 1, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team Foto"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1)),
                Map.of(),
                List.of(at), at);

        assertThat(result.rows()).hasSize(1);
        ActivityScheduleRow row = result.rows().get(0);
        assertThat(row.isDataRow()).isTrue();
        assertThat(row.getRoundNumber()).isEqualTo(1);
        assertThat(row.getTimeWindow()).isEqualTo("09:00–09:15");
        assertThat(row.getTeamNames()).isEqualTo("Team Foto");
    }

    // ── Scenario 5: empty rounds omitted (AC3) ───────────────────────────────

    /** Scenario 5: rounds with no assignments are omitted from the output (AC3). */
    @Test
    void assemble_emptyRoundsOmitted_doesNotProduceRowForUnassignedRound() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);
        Match m2 = match(av1Id, av2Id, 2, null);

        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        TimelineEntry round2 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 2,
                LocalTime.of(9, 15), LocalTime.of(9, 30), null);
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1, round2));

        // Only round 1 has assignment; round 2 is empty
        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(new ActivityAssignment(t1, 1, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team A"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1, m2)),
                Map.of(),
                List.of(at), at);

        // Only 1 row (round 2 has no assignment → omitted per AC3)
        long dataRows = result.rows().stream().filter(ActivityScheduleRow::isDataRow).count();
        assertThat(dataRows).isEqualTo(1);
        assertThat(result.rows().get(0).getRoundNumber()).isEqualTo(1);
    }

    // ── Scenario 6: break interleaving (AC4) ─────────────────────────────────

    /** Scenario 6: intra-phase break between assigned rounds produces a break separator (AC4). */
    @Test
    void assemble_intraPhaseBreakBetweenAssignedRounds_interleaveBreakRow() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);
        Match m2 = match(av1Id, av2Id, 3, null);

        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        TimelineEntry lapBreak = timelineEntry(1, TimelineEntryType.LAP_BREAK, 0,
                LocalTime.of(9, 15), LocalTime.of(9, 20), null);
        TimelineEntry intraBreak = timelineEntry(1, TimelineEntryType.INTRA_PHASE_BREAK, 0,
                LocalTime.of(9, 20), LocalTime.of(9, 50), "Mittagspause");
        TimelineEntry round3 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 3,
                LocalTime.of(9, 50), LocalTime.of(10, 5), null);
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1, lapBreak, intraBreak, round3));

        // Rounds 1 and 3 both have assignments
        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(
                        new ActivityAssignment(t1, 1, activityTypeName),
                        new ActivityAssignment(t1, 3, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team Foto"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1, m2)),
                Map.of(),
                List.of(at), at);

        // Expected: data(1), break(Mittagspause), data(3)
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0).isDataRow()).isTrue();
        assertThat(result.rows().get(0).getRoundNumber()).isEqualTo(1);
        assertThat(result.rows().get(1).isBreak()).isTrue();
        assertThat(result.rows().get(1).getBreakLabel()).isEqualTo("Mittagspause");
        assertThat(result.rows().get(2).isDataRow()).isTrue();
        assertThat(result.rows().get(2).getRoundNumber()).isEqualTo(3);
    }

    // ── Scenario 7: lap breaks skipped ───────────────────────────────────────

    /** Scenario 7: LAP_BREAK timeline entries are NOT emitted as break rows. */
    @Test
    void assemble_lapBreaksSkipped_noBreakRowForLapBreak() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);
        Match m2 = match(av1Id, av2Id, 2, null);

        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        TimelineEntry lapBreak = timelineEntry(1, TimelineEntryType.LAP_BREAK, 0,
                LocalTime.of(9, 15), LocalTime.of(9, 20), "Runden-Pause");
        TimelineEntry round2 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 2,
                LocalTime.of(9, 20), LocalTime.of(9, 35), null);
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1, lapBreak, round2));

        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(
                        new ActivityAssignment(t1, 1, activityTypeName),
                        new ActivityAssignment(t1, 2, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team Foto"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1, m2)),
                Map.of(),
                List.of(at), at);

        // LAP_BREAK should not produce a break row
        long breakRows = result.rows().stream().filter(ActivityScheduleRow::isBreak).count();
        assertThat(breakRows).isZero();
        assertThat(result.rows()).hasSize(2);
    }

    // ── Scenario 8: unassigned teams (AC6) ───────────────────────────────────

    /** Scenario 8: unassigned teams are reported in the model, sorted by name (AC6). */
    @Test
    void assemble_unassignedTeams_reportedSortedInModel() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID assignedId = UUID.randomUUID();
        UUID unassigned1 = UUID.randomUUID();
        UUID unassigned2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, assignedId);
        TeamAvatar av2 = teamAvatar(av2Id, unassigned1);
        Match m1 = match(av1Id, av2Id, 1, null);

        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1));

        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(new ActivityAssignment(assignedId, 1, activityTypeName)),
                Set.of(unassigned1, unassigned2));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(
                team(assignedId, "Team C"),
                team(unassigned1, "Team B"),
                team(unassigned2, "Team A"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1)),
                Map.of(),
                List.of(at), at);

        assertThat(result.hasUnassigned()).isTrue();
        assertThat(result.unassignedTeamNames()).containsExactly("Team A", "Team B"); // sorted
    }

    // ── Scenario 9: summary stats (AC5) ─────────────────────────────────────

    /** Scenario 9: summary counts correct — totalAssignedTeams and roundCount (AC5). */
    @Test
    void assemble_summaryCountsAreCorrect() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);
        Match m2 = match(av1Id, av2Id, 2, null);

        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        TimelineEntry round2 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 2,
                LocalTime.of(9, 15), LocalTime.of(9, 30), null);
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1, round2));

        // Round 1: 2 teams assigned; Round 2: 1 team assigned
        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(
                        new ActivityAssignment(t1, 1, activityTypeName),
                        new ActivityAssignment(t2, 1, activityTypeName),
                        new ActivityAssignment(t1, 2, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team A"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1, m2)),
                Map.of(),
                List.of(at), at);

        assertThat(result.totalAssignedTeams()).isEqualTo(3); // 2 in round 1, 1 in round 2
        assertThat(result.roundCount()).isEqualTo(2);
    }

    // ── Scenario 10: all-unassigned → hasUnassigned, no data rows ────────────

    /** Scenario 10: all teams unassigned → hasUnassigned true, no data rows. */
    @Test
    void assemble_allTeamsUnassigned_noDataRowsAndHasUnassignedTrue() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);

        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1));

        // No assignments at all
        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at, List.of(), Set.of(t1, t2));
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team A"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1)),
                Map.of(),
                List.of(at), at);

        assertThat(result.hasUnassigned()).isTrue();
        long dataRows = result.rows().stream().filter(ActivityScheduleRow::isDataRow).count();
        assertThat(dataRows).isZero();
        assertThat(result.unassignedTeamNames()).hasSize(2);
    }

    // ── Scenario 11: break with no data ahead → no break row ─────────────────

    /** Scenario 11: break separator only emitted between two data rows (AC4). */
    @Test
    void assemble_breakWithNoDataAfter_doesNotEmitBreakRow() {
        Tournament tournament = tournament(LocalTime.of(9, 0));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, t1);
        TeamAvatar av2 = teamAvatar(av2Id, t2);
        Match m1 = match(av1Id, av2Id, 1, null);
        Match m2 = match(av1Id, av2Id, 2, null);

        // Only round 1 has an assignment; round 2 has a break before it but round 2 has no assignment
        TimelineEntry round1 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 1,
                LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        TimelineEntry intraBreak = timelineEntry(1, TimelineEntryType.INTRA_PHASE_BREAK, 0,
                LocalTime.of(9, 15), LocalTime.of(9, 45), "Pause");
        TimelineEntry round2 = timelineEntry(1, TimelineEntryType.MATCH_ROUND, 2,
                LocalTime.of(9, 45), LocalTime.of(10, 0), null); // no assignment
        when(timelineCalculationService.calculate(any(), any(), anyInt()))
                .thenReturn(List.of(round1, intraBreak, round2));

        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(new ActivityAssignment(t1, 1, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(team(t1, "Team Foto"), team(t2, "Team B"));

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1, m2)),
                Map.of(),
                List.of(at), at);

        // Break should NOT appear because no assigned data row follows it
        long breakRows = result.rows().stream().filter(ActivityScheduleRow::isBreak).count();
        assertThat(breakRows).isZero();
    }

    // ── Scenario 12: team display name (description vs number) ───────────────

    /** Scenario 12: team display name uses description if set, else "Team {number}". */
    @Test
    void assemble_teamDisplayName_usesDescriptionWhenSet_fallsBackToTeamNumber() {
        Tournament tournament = tournament(null); // no start time for simplicity

        UUID tWithDesc = UUID.randomUUID();
        UUID tNoDesc = UUID.randomUUID();
        UUID av1Id = UUID.randomUUID();
        UUID av2Id = UUID.randomUUID();
        TeamAvatar av1 = teamAvatar(av1Id, tWithDesc);
        TeamAvatar av2 = teamAvatar(av2Id, tNoDesc);
        Match m1 = match(av1Id, av2Id, 1, null);

        Team teamWithDesc = new Team();
        teamWithDesc.setId(tWithDesc);
        teamWithDesc.setTeamNumber(3);
        teamWithDesc.setDescription("Spitzenklasse");

        Team teamNoDesc = new Team();
        teamNoDesc.setId(tNoDesc);
        teamNoDesc.setTeamNumber(7);
        teamNoDesc.setDescription(null);

        ActivityType at = activityType(activityTypeId, activityTypeName);
        ActivityAssignmentResult assignResult = assignmentResult(
                at,
                List.of(
                        new ActivityAssignment(tWithDesc, 1, activityTypeName),
                        new ActivityAssignment(tNoDesc, 1, activityTypeName)),
                Set.of());
        when(activityAssignmentService.assignActivities(any(), any(), any(), anyInt(), any()))
                .thenReturn(assignResult);

        List<Phase> phases = List.of(phase(phaseId));
        List<Team> teams = List.of(teamWithDesc, teamNoDesc);

        ActivityScheduleModel result = assembler.assemble(
                tournament, phases, teams,
                Map.of(phaseId, List.of(av1, av2)),
                Map.of(phaseId, List.of(m1)),
                Map.of(),
                List.of(at), at);

        assertThat(result.rows()).hasSize(1);
        String teamNames = result.rows().get(0).getTeamNames();
        assertThat(teamNames).contains("Spitzenklasse");
        assertThat(teamNames).contains("Team 7");
    }

    // ── Factory helpers ──────────────────────────────────────────────────────

    private Tournament tournament(LocalTime startTime) {
        Tournament t = new Tournament();
        t.setPlannedStartTime(startTime);
        return t;
    }

    private Phase phase(UUID id) {
        Phase p = new Phase();
        p.setId(id);
        p.setSequenceNumber(1);
        return p;
    }

    private Team team(UUID id, String description) {
        Team t = new Team();
        t.setId(id);
        t.setDescription(description);
        t.setTeamNumber(1);
        return t;
    }

    private TeamAvatar teamAvatar(UUID avatarId, UUID teamId) {
        TeamAvatar av = new TeamAvatar();
        av.setId(avatarId);
        av.setTeamId(teamId);
        return av;
    }

    private Match match(UUID avatarId1, UUID avatarId2, int lapNumber, UUID refereeTeamId) {
        Match m = new Match();
        m.setMemberAvatar1Id(avatarId1);
        m.setMemberAvatar2Id(avatarId2);
        m.setLapNumber(lapNumber);
        m.setRefereeTeamId(refereeTeamId);
        return m;
    }

    private ActivityType activityType(UUID id, String name) {
        ActivityType at = new ActivityType();
        at.setId(id);
        at.setName(name);
        at.setAssignmentRule(AssignmentRule.FIRST_FREE_ROUND.name());
        return at;
    }

    /**
     * Creates a {@link TimelineEntry} record.
     * Signature: (phaseNumber, lapNumber, type, startTime, endTime, label)
     */
    private TimelineEntry timelineEntry(
            int phaseNumber,
            TimelineEntryType type,
            int lapNumber,
            LocalTime start,
            LocalTime end,
            String label) {
        return new TimelineEntry(phaseNumber, lapNumber, type, start, end, label);
    }

    private ActivityAssignmentResult assignmentResult(
            ActivityType at,
            List<ActivityAssignment> assignments,
            Set<UUID> unassignedTeamIds) {
        Map<ActivityType, List<ActivityAssignment>> assignmentMap =
                assignments.isEmpty() ? Collections.emptyMap() : Map.of(at, assignments);
        Map<ActivityType, Set<UUID>> unassignedMap =
                unassignedTeamIds.isEmpty() ? Collections.emptyMap() : Map.of(at, unassignedTeamIds);
        return new ActivityAssignmentResult(assignmentMap, unassignedMap);
    }
}
