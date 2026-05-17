// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.info.dto.snapshot.ScheduleEntry;
import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.tm.infoportal.internal.DefaultTournamentSnapshotBuilder;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TournamentSnapshotBuilder} (AC1 — DEC-22 RED-first).
 *
 * <p>Tests written RED-first: the production interface and implementation do not exist at the time
 * this test file is committed. The RED state is evidenced by the compilation failure before the
 * production class is introduced.
 *
 * <p>Same-package test (de.vvwt.tm.infoportal test package) — may white-box per DEC-36.
 *
 * @see TournamentSnapshotBuilder
 * @see de.vvwt.tm.infoportal.internal.DefaultTournamentSnapshotBuilder
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E62S01.story.md">
 *     E62S01 AC1–AC9</a>
 */
class TournamentSnapshotBuilderTest {

    private TournamentRepository tournamentRepository;
    private TeamRepository teamRepository;
    private PhaseRepository phaseRepository;
    private MatchRepository matchRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private PhaseBreakRepository phaseBreakRepository;

    private TournamentSnapshotBuilder builder;

    private static final UUID TOURNAMENT_ID =
            UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID_UUID =
            UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final String TENANT_ID = TENANT_ID_UUID.toString();
    private static final long SEQ = 42L;

    @BeforeEach
    void setUp() {
        tournamentRepository = mock(TournamentRepository.class);
        teamRepository = mock(TeamRepository.class);
        phaseRepository = mock(PhaseRepository.class);
        matchRepository = mock(MatchRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        phaseBreakRepository = mock(PhaseBreakRepository.class);

        builder =
                new DefaultTournamentSnapshotBuilder(
                        tournamentRepository,
                        teamRepository,
                        phaseRepository,
                        matchRepository,
                        teamAvatarRepository,
                        phaseBreakRepository);

        // Default stubs — tournament ACTIVE, no phases, no teams
        Tournament tournament = tournament("ACTIVE");
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
    }

    // -------------------------------------------------------------------------
    // AC2 — Team → TeamEntry mapping
    // -------------------------------------------------------------------------

    @Test
    void buildsTeamEntries_fromTournamentTeams() {
        // Arrange
        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        Team team1 = team(team1Id, 1, "Alpha");
        Team team2 = team(team2Id, 2, "Beta");
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team1, team2));

        // Act
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);

        // Assert
        assertThat(snapshot.teams()).hasSize(2);
        assertThat(snapshot.teams())
                .containsExactlyInAnyOrder(
                        new TeamEntry(team1Id.toString(), "Alpha", 1),
                        new TeamEntry(team2Id.toString(), "Beta", 2));
    }

    // -------------------------------------------------------------------------
    // AC5 — teamId security: UUID.toString() form is pinned
    // -------------------------------------------------------------------------

    @Test
    void teamIdIsUuidStringForm() {
        // Arrange
        UUID teamId = UUID.fromString("12345678-abcd-ef01-2345-6789abcdef01");
        Team team = team(teamId, 3, "Gamma");
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team));

        // Act
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);

        // Assert — teamId MUST be the UUID.toString() form, not any other representation
        assertThat(snapshot.teams()).hasSize(1);
        assertThat(snapshot.teams().get(0).teamId())
                .isEqualTo("12345678-abcd-ef01-2345-6789abcdef01");
    }

    // -------------------------------------------------------------------------
    // AC4 — empty teams → empty list (no null, no exception)
    // -------------------------------------------------------------------------

    @Test
    void emptyTeams_yieldsEmptyList() {
        // teams already stubbed as empty in setUp()
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);
        assertThat(snapshot.teams()).isNotNull().isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC3 — ScheduleEntry.Match mapping (avatar → team name, lapNumber = roundNumber)
    // -------------------------------------------------------------------------

    @Test
    void buildsMatchScheduleEntries() {
        // Arrange
        UUID phase1Id = UUID.randomUUID();
        Phase phase1 = phase(phase1Id);

        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        Team team1 = team(team1Id, 1, "Home FC");
        Team team2 = team(team2Id, 2, "Away United");

        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        TeamAvatar avatar1 = avatar(avatar1Id, team1Id);
        TeamAvatar avatar2 = avatar(avatar2Id, team2Id);

        UUID matchId = UUID.randomUUID();
        Match match = match(matchId, TOURNAMENT_ID, phase1Id, avatar1Id, avatar2Id, 3);

        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team1, team2));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1));
        when(teamAvatarRepository.findByPhaseId(phase1Id)).thenReturn(List.of(avatar1, avatar2));
        when(matchRepository.findByPhaseId(phase1Id)).thenReturn(List.of(match));
        when(phaseBreakRepository.findByPhaseId(phase1Id)).thenReturn(List.of());

        // Act
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);

        // Assert
        assertThat(snapshot.scheduleEntries()).hasSize(1);
        ScheduleEntry entry = snapshot.scheduleEntries().get(0);
        assertThat(entry).isInstanceOf(ScheduleEntry.Match.class);
        ScheduleEntry.Match matchEntry = (ScheduleEntry.Match) entry;
        assertThat(matchEntry.id()).isEqualTo(matchId.toString());
        assertThat(matchEntry.homeTeamName()).isEqualTo("Home FC");
        assertThat(matchEntry.awayTeamName()).isEqualTo("Away United");
        assertThat(matchEntry.roundNumber()).isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // AC3 — ScheduleEntry.Pause mapping (PhaseBreak → Pause)
    // -------------------------------------------------------------------------

    @Test
    void buildsPauseScheduleEntries() {
        // Arrange
        UUID phase1Id = UUID.randomUUID();
        Phase phase1 = phase(phase1Id);

        UUID breakId = UUID.randomUUID();
        PhaseBreak phaseBreak = phaseBreak(breakId, phase1Id, 2, "Mittagspause");

        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1));
        when(teamAvatarRepository.findByPhaseId(phase1Id)).thenReturn(List.of());
        when(matchRepository.findByPhaseId(phase1Id)).thenReturn(List.of());
        when(phaseBreakRepository.findByPhaseId(phase1Id)).thenReturn(List.of(phaseBreak));

        // Act
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);

        // Assert
        assertThat(snapshot.scheduleEntries()).hasSize(1);
        ScheduleEntry entry = snapshot.scheduleEntries().get(0);
        assertThat(entry).isInstanceOf(ScheduleEntry.Pause.class);
        ScheduleEntry.Pause pauseEntry = (ScheduleEntry.Pause) entry;
        assertThat(pauseEntry.id()).isEqualTo(breakId.toString());
        assertThat(pauseEntry.label()).isEqualTo("Mittagspause");
    }

    @Test
    void buildsPauseEntry_withDefaultLabel_whenBreakLabelIsNull() {
        // Arrange
        UUID phase1Id = UUID.randomUUID();
        Phase phase1 = phase(phase1Id);

        UUID breakId = UUID.randomUUID();
        PhaseBreak phaseBreak = phaseBreak(breakId, phase1Id, 1, null); // null label

        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1));
        when(teamAvatarRepository.findByPhaseId(phase1Id)).thenReturn(List.of());
        when(matchRepository.findByPhaseId(phase1Id)).thenReturn(List.of());
        when(phaseBreakRepository.findByPhaseId(phase1Id)).thenReturn(List.of(phaseBreak));

        // Act
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);

        // Assert — null label → default "Pause"
        ScheduleEntry.Pause pauseEntry = (ScheduleEntry.Pause) snapshot.scheduleEntries().get(0);
        assertThat(pauseEntry.label()).isEqualTo("Pause");
    }

    // -------------------------------------------------------------------------
    // AC4 — empty schedule → empty list (no null, no exception)
    // -------------------------------------------------------------------------

    @Test
    void emptySchedule_yieldsEmptyList() {
        // phases already stubbed as empty in setUp()
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);
        assertThat(snapshot.scheduleEntries()).isNotNull().isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC3/AC4 — null lapNumber in Match (pre-slot-opt match) → roundNumber 0
    // -------------------------------------------------------------------------

    @Test
    void nullLapNumber_yieldsRoundNumberZero() {
        UUID phase1Id = UUID.randomUUID();
        Phase phase1 = phase(phase1Id);

        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        // No teams registered → avatar names will be empty strings (null-safe)

        UUID matchId = UUID.randomUUID();
        Match match = matchWithNullLap(matchId, TOURNAMENT_ID, phase1Id, avatar1Id, avatar2Id);

        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1));
        when(teamAvatarRepository.findByPhaseId(phase1Id)).thenReturn(List.of());
        when(matchRepository.findByPhaseId(phase1Id)).thenReturn(List.of(match));
        when(phaseBreakRepository.findByPhaseId(phase1Id)).thenReturn(List.of());

        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);

        ScheduleEntry.Match matchEntry = (ScheduleEntry.Match) snapshot.scheduleEntries().get(0);
        assertThat(matchEntry.roundNumber()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // tournamentEnded mapping
    // -------------------------------------------------------------------------

    @Test
    void tournamentEndedTrue_whenCompleted() {
        when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament("COMPLETED")));
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);
        assertThat(snapshot.tournamentEnded()).isTrue();
    }

    @Test
    void tournamentEndedTrue_whenCancelled() {
        when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournament("CANCELLED")));
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);
        assertThat(snapshot.tournamentEnded()).isTrue();
    }

    @Test
    void tournamentEndedFalse_whenActive() {
        // Default tournament stub in setUp() has status ACTIVE
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);
        assertThat(snapshot.tournamentEnded()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Snapshot field pass-through
    // -------------------------------------------------------------------------

    @Test
    void snapshotContainsTournamentIdAndTenantIdAndSeq() {
        TournamentSnapshot snapshot = builder.build(TOURNAMENT_ID, TENANT_ID, SEQ);
        assertThat(snapshot.tournamentId()).isEqualTo(TOURNAMENT_ID.toString());
        assertThat(snapshot.tenantId()).isEqualTo(TENANT_ID);
        assertThat(snapshot.sequenceNumber()).isEqualTo(SEQ);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private Tournament tournament(String status) {
        Tournament t = new Tournament();
        t.setId(TOURNAMENT_ID);
        t.setStatus(status);
        return t;
    }

    private Team team(UUID id, int number, String description) {
        Team t = new Team();
        t.setId(id);
        t.setTournamentId(TOURNAMENT_ID);
        t.setTeamNumber(number);
        t.setDescription(description);
        return t;
    }

    private Phase phase(UUID id) {
        Phase p = new Phase();
        p.setId(id);
        p.setTournamentId(TOURNAMENT_ID);
        return p;
    }

    private TeamAvatar avatar(UUID avatarId, UUID teamId) {
        TeamAvatar a = new TeamAvatar();
        a.setId(avatarId);
        a.setTeamId(teamId);
        return a;
    }

    private Match match(
            UUID id,
            UUID tournamentId,
            UUID phaseId,
            UUID avatar1Id,
            UUID avatar2Id,
            int lapNumber) {
        Match m = new Match();
        m.setId(id);
        m.setTournamentId(tournamentId);
        m.setPhaseId(phaseId);
        m.setMemberAvatar1Id(avatar1Id);
        m.setMemberAvatar2Id(avatar2Id);
        m.setLapNumber(lapNumber);
        return m;
    }

    private Match matchWithNullLap(
            UUID id, UUID tournamentId, UUID phaseId, UUID avatar1Id, UUID avatar2Id) {
        Match m = new Match();
        m.setId(id);
        m.setTournamentId(tournamentId);
        m.setPhaseId(phaseId);
        m.setMemberAvatar1Id(avatar1Id);
        m.setMemberAvatar2Id(avatar2Id);
        m.setLapNumber(null); // pre-slot-opt
        return m;
    }

    private PhaseBreak phaseBreak(UUID id, UUID phaseId, int afterLapNumber, String label) {
        PhaseBreak pb = new PhaseBreak();
        pb.setId(id);
        pb.setPhaseId(phaseId);
        pb.setAfterLapNumber(afterLapNumber);
        pb.setDurationMinutes(15);
        pb.setLabel(label);
        return pb;
    }
}
