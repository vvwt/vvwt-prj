package de.vvwt.tm.domain;

import de.vvwt.tm.domain.referee.RefereeAssigner;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefereeAssignmentService} — AC1–AC4, AC8, AC9, AC11 (E05S09).
 */
@ExtendWith(MockitoExtension.class)
class RefereeAssignmentServiceTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private RefereeAssigner refereeAssigner;

    private RefereeAssignmentService service;

    private final UUID tenantId     = UUID.randomUUID();
    private final UUID tournamentId = UUID.randomUUID();
    private final UUID phaseId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new RefereeAssignmentService(
                phaseRepository,
                matchRepository,
                teamAvatarRepository,
                teamRepository,
                tournamentRepository,
                refereeAssigner);
    }

    // =========================================================================
    // Test fixtures
    // =========================================================================

    private Phase makePhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTenantId(tenantId);
        p.setTournamentId(tournamentId);
        p.setStatus("PENDING");
        return p;
    }

    private Tournament makeTournament() {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setTenantId(tenantId);
        return t;
    }

    private Team makeTeam(UUID teamId, String description, boolean refereeAssignment) {
        Team t = new Team();
        t.setId(teamId);
        t.setTenantId(tenantId);
        t.setTournamentId(tournamentId);
        t.setDescription(description);
        t.setRefereeAssignment(refereeAssignment);
        return t;
    }

    private TeamAvatar makeAvatar(UUID avatarId, UUID teamId, UUID phaseId, String description) {
        TeamAvatar a = new TeamAvatar();
        a.setId(avatarId);
        a.setTenantId(tenantId);
        a.setTournamentId(tournamentId);
        a.setPhaseId(phaseId);
        a.setTeamId(teamId);
        a.setDescription(description);
        return a;
    }

    private Match makeMatch(UUID matchId, UUID avatar1Id, UUID avatar2Id,
                             Integer lapNumber, Integer fieldNumber,
                             UUID refereeTeamId, String refereeDescription) {
        Match m = new Match();
        m.setId(matchId);
        m.setTenantId(tenantId);
        m.setTournamentId(tournamentId);
        m.setPhaseId(phaseId);
        m.setMemberAvatar1Id(avatar1Id);
        m.setMemberAvatar2Id(avatar2Id);
        m.setLapNumber(lapNumber);
        m.setFieldNumber(fieldNumber);
        m.setRefereeTeamId(refereeTeamId);
        m.setRefereeDescription(refereeDescription);
        m.setState(0); // OPEN
        m.setSetLimit(1);
        return m;
    }

    // =========================================================================
    // AC11 — tenant scope
    // =========================================================================

    @Test
    void getAssignments_phaseNotFound_throws404() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAssignments(phaseId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Phase not found");
    }

    @Test
    void getAssignments_phaseTournamentNotInTenant_throws404() {
        // Phase exists but tournament not found → cross-tenant (AC11)
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(makePhase()));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAssignments(phaseId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Phase not found");
    }

    // =========================================================================
    // AC1 — getAssignments returns correct entries
    // =========================================================================

    @Test
    void getAssignments_returnsEntriesWithCorrectManualOverrideFlag() {
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        UUID refereeTeamId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        Phase phase = makePhase();
        Tournament tournament = makeTournament();
        Team team1 = makeTeam(UUID.randomUUID(), "Team A", false);
        Team team2 = makeTeam(UUID.randomUUID(), "Team B", false);
        Team refereeTeam = makeTeam(refereeTeamId, "Team R", true);

        TeamAvatar avt1 = makeAvatar(avatar1Id, team1.getId(), phaseId, "Team A");
        TeamAvatar avt2 = makeAvatar(avatar2Id, team2.getId(), phaseId, "Team B");

        // One auto-assigned match, one manually overridden
        Match autoMatch = makeMatch(matchId, avatar1Id, avatar2Id, 1, 1, refereeTeamId, null);
        Match manualMatch = makeMatch(UUID.randomUUID(), avatar1Id, avatar2Id, 2, 1, refereeTeamId,
                RefereeAssignmentService.MANUAL_OVERRIDE_SENTINEL);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(autoMatch, manualMatch));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avt1, avt2));
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(List.of(team1, team2, refereeTeam));

        var overview = service.getAssignments(phaseId);

        assertThat(overview.assignments()).hasSize(2);

        var autoEntry = overview.assignments().stream()
                .filter(e -> autoMatch.getId().equals(e.matchId())).findFirst().orElseThrow();
        assertThat(autoEntry.isManualOverride()).isFalse();
        assertThat(autoEntry.refereeTeamName()).isEqualTo("Team R");

        var manualEntry = overview.assignments().stream()
                .filter(e -> !autoMatch.getId().equals(e.matchId())).findFirst().orElseThrow();
        assertThat(manualEntry.isManualOverride()).isTrue();
    }

    // =========================================================================
    // AC7 — team assignment summary
    // =========================================================================

    @Test
    void getAssignments_teamSummaryCountsAssignments() {
        UUID refereeTeamId = UUID.randomUUID();
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();

        Phase phase = makePhase();
        Tournament tournament = makeTournament();
        Team refereeTeam = makeTeam(refereeTeamId, "Referee Team", true);
        Team team1 = makeTeam(UUID.randomUUID(), "Team A", false);
        Team team2 = makeTeam(UUID.randomUUID(), "Team B", false);

        TeamAvatar avt1 = makeAvatar(avatar1Id, team1.getId(), phaseId, "Team A");
        TeamAvatar avt2 = makeAvatar(avatar2Id, team2.getId(), phaseId, "Team B");

        Match m1 = makeMatch(UUID.randomUUID(), avatar1Id, avatar2Id, 1, 1, refereeTeamId, null);
        Match m2 = makeMatch(UUID.randomUUID(), avatar1Id, avatar2Id, 2, 1, refereeTeamId, null);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(m1, m2));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avt1, avt2));
        when(teamRepository.findByTournamentId(tournamentId))
                .thenReturn(List.of(team1, team2, refereeTeam));

        var overview = service.getAssignments(phaseId);

        assertThat(overview.teamSummary()).hasSize(1);
        assertThat(overview.teamSummary().get(0).teamName()).isEqualTo("Referee Team");
        assertThat(overview.teamSummary().get(0).assignedCount()).isEqualTo(2);
    }

    // =========================================================================
    // AC9 — no referee teams configured
    // =========================================================================

    @Test
    void getAssignments_noRefereeTeams_allRefereeTeamsIsEmpty() {
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        Phase phase = makePhase();
        Tournament tournament = makeTournament();
        Team team1 = makeTeam(UUID.randomUUID(), "Team A", false);
        Team team2 = makeTeam(UUID.randomUUID(), "Team B", false);

        TeamAvatar avt1 = makeAvatar(avatar1Id, team1.getId(), phaseId, "Team A");
        TeamAvatar avt2 = makeAvatar(avatar2Id, team2.getId(), phaseId, "Team B");
        Match m1 = makeMatch(UUID.randomUUID(), avatar1Id, avatar2Id, 1, 1, null, null);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(m1));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avt1, avt2));
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of(team1, team2));

        var overview = service.getAssignments(phaseId);

        assertThat(overview.allRefereeTeams()).isEmpty();
        assertThat(overview.assignments().get(0).refereeTeamName()).isNull();
    }

    // =========================================================================
    // AC2 — overrideReferee
    // =========================================================================

    @Test
    void overrideReferee_setsManualSentinelAndTeamId() {
        UUID matchId = UUID.randomUUID();
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        UUID refereeTeamId = UUID.randomUUID();
        UUID refereeAvatarId = UUID.randomUUID();

        Phase phase = makePhase();
        Tournament tournament = makeTournament();
        Team refereeTeam = makeTeam(refereeTeamId, "Referee Team", true);

        TeamAvatar refereeAvatar = makeAvatar(refereeAvatarId, refereeTeamId, phaseId, "Referee Team");
        TeamAvatar avt1 = makeAvatar(avatar1Id, UUID.randomUUID(), phaseId, "Team A");
        TeamAvatar avt2 = makeAvatar(avatar2Id, UUID.randomUUID(), phaseId, "Team B");

        // Referee team is NOT playing in lap 1 (different avatars from avt1/avt2)
        Match match = makeMatch(matchId, avatar1Id, avatar2Id, 1, 1, null, null);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(teamAvatarRepository.findById(refereeAvatarId)).thenReturn(Optional.of(refereeAvatar));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avt1, avt2, refereeAvatar));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));
        when(matchRepository.save(any())).thenReturn(match);
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of(refereeTeam));

        service.overrideReferee(phaseId, matchId, refereeAvatarId);

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getRefereeTeamId()).isEqualTo(refereeTeamId);
        assertThat(saved.getValue().getRefereeDescription())
                .isEqualTo(RefereeAssignmentService.MANUAL_OVERRIDE_SENTINEL);
    }

    @Test
    void overrideReferee_teamPlayingInSameLap_throwsConflict() {
        // Scenario: we try to override the referee for match2 with team A,
        // but team A is already playing in match1 in the same lap 1.
        UUID matchId1 = UUID.randomUUID();
        UUID matchId2 = UUID.randomUUID(); // match to override referee for
        UUID refereeTeamId = UUID.randomUUID();
        UUID refereeAvatarId = UUID.randomUUID();
        UUID otherTeamId = UUID.randomUUID();
        UUID playingAvatar1Id = UUID.randomUUID(); // avatar of refereeTeam in match1
        UUID playingAvatar2Id = UUID.randomUUID(); // avatar of otherTeam in match1
        UUID match2Avatar1Id = UUID.randomUUID();
        UUID match2Avatar2Id = UUID.randomUUID();

        Phase phase = makePhase();
        Tournament tournament = makeTournament();

        // Team with refereeTeamId is playing in match1 (lap 1)
        TeamAvatar refTeamAvatar = makeAvatar(playingAvatar1Id, refereeTeamId, phaseId, "Team A");
        // refereeAvatarId is an alias for the same team in the phase (could be the same or different group)
        // For simplicity, use refTeamAvatar as the referee override target
        TeamAvatar avt2 = makeAvatar(playingAvatar2Id, otherTeamId, phaseId, "Team B");
        TeamAvatar match2Avt1 = makeAvatar(match2Avatar1Id, UUID.randomUUID(), phaseId, "Team C");
        TeamAvatar match2Avt2 = makeAvatar(match2Avatar2Id, UUID.randomUUID(), phaseId, "Team D");

        // match1: Team A vs Team B in lap 1
        Match match1 = makeMatch(matchId1, playingAvatar1Id, playingAvatar2Id, 1, 1, null, null);
        // match2: Team C vs Team D in lap 1 (same lap)
        Match match2 = makeMatch(matchId2, match2Avatar1Id, match2Avatar2Id, 1, 2, null, null);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findById(matchId2)).thenReturn(Optional.of(match2));
        when(teamAvatarRepository.findById(playingAvatar1Id)).thenReturn(Optional.of(refTeamAvatar));
        when(teamAvatarRepository.findByPhaseId(phaseId))
                .thenReturn(List.of(refTeamAvatar, avt2, match2Avt1, match2Avt2));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match1, match2));
        // Need team lookup for the error message
        Team playingTeam = makeTeam(refereeTeamId, "Team A", true);
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of(playingTeam));

        // Try to assign "Team A" as referee for match2 — but Team A is playing in match1 same lap
        assertThatThrownBy(() -> service.overrideReferee(phaseId, matchId2, playingAvatar1Id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("playing in lap");

        verify(matchRepository, never()).save(any());
    }

    @Test
    void overrideReferee_matchNotInPhase_throws404() {
        UUID matchId = UUID.randomUUID();
        UUID otherPhaseId = UUID.randomUUID();

        Phase phase = makePhase();
        Tournament tournament = makeTournament();

        Match match = makeMatch(matchId, UUID.randomUUID(), UUID.randomUUID(), 1, 1, null, null);
        match.setPhaseId(otherPhaseId); // different phase!

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));

        assertThatThrownBy(() -> service.overrideReferee(phaseId, matchId, UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("does not belong to phase");
    }

    // =========================================================================
    // AC3 — clearRefereeOverride
    // =========================================================================

    @Test
    void clearRefereeOverride_setsRefereeFieldsToNull() {
        UUID matchId = UUID.randomUUID();
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        UUID refereeTeamId = UUID.randomUUID();

        Phase phase = makePhase();
        Tournament tournament = makeTournament();

        Match match = makeMatch(matchId, avatar1Id, avatar2Id, 1, 1, refereeTeamId,
                RefereeAssignmentService.MANUAL_OVERRIDE_SENTINEL);

        TeamAvatar avt1 = makeAvatar(avatar1Id, UUID.randomUUID(), phaseId, "Team A");
        TeamAvatar avt2 = makeAvatar(avatar2Id, UUID.randomUUID(), phaseId, "Team B");

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(matchRepository.save(any())).thenReturn(match);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of(avt1, avt2));

        var entry = service.clearRefereeOverride(phaseId, matchId);

        ArgumentCaptor<Match> saved = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(saved.capture());
        assertThat(saved.getValue().getRefereeTeamId()).isNull();
        assertThat(saved.getValue().getRefereeDescription()).isNull();
        assertThat(entry.isManualOverride()).isFalse();
        assertThat(entry.refereeTeamId()).isNull();
    }

    // =========================================================================
    // AC4 — reassignAll
    // =========================================================================

    @Test
    void reassignAll_callsRefereeAssigner() {
        Phase phase = makePhase();
        Tournament tournament = makeTournament();

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(teamRepository.findByTournamentId(tournamentId)).thenReturn(List.of());

        service.reassignAll(phaseId);

        verify(refereeAssigner).assignReferees(phaseId);
    }
}
