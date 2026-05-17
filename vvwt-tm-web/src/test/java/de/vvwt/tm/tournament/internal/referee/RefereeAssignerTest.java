// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.referee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.RefereeAssigner;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RefereeAssigner} (AC-TDD-RefereeAssigner, E21S08).
 *
 * <p>Verifies: happy-path assignment; conflict avoidance (playing team is NOT assigned as referee);
 * preference weighting (preferred team is chosen over non-preferred); manual override skip.
 *
 * <p>All collaborators mocked per DEC-22 TDD orchestration-logic pattern (E15S03 precedent).
 *
 * <p>Source: inventory row 276 — {@code de.vvwt.tm.tournament.internal.referee.RefereeAssigner}.
 *
 * <p>Related DECs: DEC-22 (TDD Iron Law), DEC-26 (DAO test governance — mocked here, unit level).
 */
class RefereeAssignerTest {

    private PhaseRepository phaseRepository;
    private MatchRepository matchRepository;
    private TeamRepository teamRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private ObjectMapper objectMapper;
    private RefereeAssigner assigner;

    private UUID tenantId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        phaseRepository = mock(PhaseRepository.class);
        matchRepository = mock(MatchRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        objectMapper = new ObjectMapper();
        assigner =
                new DefaultRefereeAssigner(
                        phaseRepository,
                        matchRepository,
                        teamRepository,
                        teamAvatarRepository,
                        objectMapper);

        tenantId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
    }

    // -------------------------------------------------------------------------
    // null phaseId → NPE
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_nullPhaseId_throwsNPE() {
        assertThatThrownBy(() -> assigner.assignReferees(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -------------------------------------------------------------------------
    // Phase not found → IAE
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_phaseNotFound_throwsIAE() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assigner.assignReferees(phaseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(phaseId.toString());
    }

    // -------------------------------------------------------------------------
    // No matches → returns report with totalMatches=0
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_noMatches_returnsZeroReport() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of());

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getTotalMatches()).isZero();
        assertThat(report.getAssignedCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // Match without slot coordinates → ISE
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_matchWithoutSlotCoords_throwsISE() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        Match unslotted = buildMatch(UUID.randomUUID(), UUID.randomUUID(), null, null);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(unslotted));

        assertThatThrownBy(() -> assigner.assignReferees(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slot coordinates");
    }

    // -------------------------------------------------------------------------
    // Happy path: referee assigned to match (playing team NOT assigned)
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_happyPath_assignsEligibleNonPlayingTeam() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        UUID playingTeamId = UUID.randomUUID();
        UUID refereeTeamId = UUID.randomUUID();
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();

        Match match = buildMatch(avatar1Id, avatar2Id, 1, 1);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        // avatar1 → playingTeam, avatar2 → playingTeam (same team for simplicity)
        TeamAvatar ta1 = buildAvatar(avatar1Id, playingTeamId);
        TeamAvatar ta2 = buildAvatar(avatar2Id, playingTeamId);
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(tournamentId, phaseId))
                .thenReturn(List.of(ta1, ta2));

        // Teams: playingTeam (playing), refereeTeam (eligible referee, not playing)
        Team playing = buildTeam(playingTeamId, true, false); // refereeAssignment=false
        Team referee = buildTeam(refereeTeamId, true, true); // refereeAssignment=true
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of(playing, referee));

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getAssignedCount()).isEqualTo(1);
        assertThat(match.getRefereeTeamId()).isEqualTo(refereeTeamId);
    }

    // -------------------------------------------------------------------------
    // Conflict avoidance: playing team NOT assigned as referee
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_conflictAvoidance_playingTeamNotAssigned() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();

        Match match = buildMatch(avatar1Id, avatar2Id, 1, 1);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamAvatar ta1 = buildAvatar(avatar1Id, team1Id);
        TeamAvatar ta2 = buildAvatar(avatar2Id, team2Id);
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(tournamentId, phaseId))
                .thenReturn(List.of(ta1, ta2));

        // Both teams play AND have refereeAssignment=true — but they are playing this lap
        Team t1 = buildTeam(team1Id, true, true);
        Team t2 = buildTeam(team2Id, true, true);
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of(t1, t2));

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        // No eligible referee (both are playing) → noRefereeCount=1, no assignment
        assertThat(report.getNoRefereeCount()).isEqualTo(1);
        assertThat(report.getAssignedCount()).isZero();
        assertThat(match.getRefereeTeamId()).isNull();
    }

    // -------------------------------------------------------------------------
    // Manual override: match with refereeDescription is skipped
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_manualOverride_skipped() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        Match overriddenMatch = buildMatch(UUID.randomUUID(), UUID.randomUUID(), 1, 1);
        overriddenMatch.setRefereeDescription("Manuelle Zuweisung");
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(overriddenMatch));
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(tournamentId, phaseId))
                .thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getOverriddenCount()).isEqualTo(1);
        assertThat(report.getAssignedCount()).isZero();
    }

    // -------------------------------------------------------------------------
    // Preference weighting: preferred team chosen first
    // -------------------------------------------------------------------------

    @Test
    void assignReferees_preferenceWeighting_preferredTeamChosenFirst() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        UUID playingTeamId = UUID.randomUUID();
        UUID preferredRefereeId = UUID.randomUUID();
        UUID otherRefereeId = UUID.randomUUID();
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();

        String prefJson = "{\"preferred\":[\"" + preferredRefereeId + "\"]}";
        Match match = buildMatch(avatar1Id, avatar2Id, 1, 1);
        match.setRefereePreferenceConfig(prefJson);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        TeamAvatar ta1 = buildAvatar(avatar1Id, playingTeamId);
        TeamAvatar ta2 = buildAvatar(avatar2Id, playingTeamId);
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(tournamentId, phaseId))
                .thenReturn(List.of(ta1, ta2));

        Team playing = buildTeam(playingTeamId, true, false);
        Team preferred = buildTeam(preferredRefereeId, true, true);
        Team other = buildTeam(otherRefereeId, true, true);
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(List.of(playing, preferred, other));

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getAssignedCount()).isEqualTo(1);
        assertThat(match.getRefereeTeamId()).isEqualTo(preferredRefereeId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Phase buildPhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("PENDING");
        return p;
    }

    private Match buildMatch(
            UUID avatar1Id, UUID avatar2Id, Integer lapNumber, Integer fieldNumber) {
        Match m = new Match();
        m.setId(UUID.randomUUID());
        m.setTournamentId(tournamentId);
        m.setPhaseId(phaseId);
        m.setMemberAvatar1Id(avatar1Id);
        m.setMemberAvatar2Id(avatar2Id);
        m.setState(MatchState.OPEN.getLegacyCode());
        m.setLapNumber(lapNumber);
        m.setFieldNumber(fieldNumber);
        return m;
    }

    private TeamAvatar buildAvatar(UUID id, UUID teamId) {
        TeamAvatar a = new TeamAvatar();
        a.setId(id);
        a.setPhaseId(phaseId);
        a.setTeamId(teamId);
        return a;
    }

    private Team buildTeam(UUID id, boolean participate, boolean refereeAssignment) {
        Team t = new Team();
        t.setId(id);
        t.setTournamentId(tournamentId);
        t.setParticipate(participate);
        t.setRefereeAssignment(refereeAssignment);
        return t;
    }
}
