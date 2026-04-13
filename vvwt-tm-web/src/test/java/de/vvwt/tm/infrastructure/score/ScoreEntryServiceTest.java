package de.vvwt.tm.infrastructure.score;

import de.vvwt.tm.domain.CascadeRecomputeService;
import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.ForbiddenException;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.SetResult;
import de.vvwt.tm.domain.SetResultInput;
import de.vvwt.tm.domain.SetState;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.UnauthorizedException;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.score.dto.MatchScoreResponse;
import de.vvwt.tm.infrastructure.score.dto.SetSubmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScoreEntryService} (E06S06, E06S07).
 *
 * <p>All collaborators are mocked. Tests verify:
 * <ul>
 *   <li>AC1/AC4: Match resolution by field+lap</li>
 *   <li>AC8: Device token validation (401/403 paths)</li>
 *   <li>AC9: Empty Optional returned when no match</li>
 *   <li>AC12: ForbiddenException thrown for wrong field</li>
 *   <li>AC7/AC8: SetResultInput built with sourceType=TABLET and sourceDeviceId</li>
 *   <li>E06S07 AC2: team sets-won counts populated from SetResult rows</li>
 *   <li>E06S07 AC5: tiebreakSwapThreshold populated from ScoringConfig</li>
 *   <li>E06S07 AC6: matchWinner determined from terminal MatchState</li>
 *   <li>E06S07 AC8: matchFormat and maxSets populated from MatchFormat enum</li>
 * </ul>
 */
@DisplayName("ScoreEntryService unit tests (E06S06, E06S07)")
class ScoreEntryServiceTest {

    // -------------------------------------------------------------------------
    // Mocks
    // -------------------------------------------------------------------------
    private DeviceRepository deviceRepository;
    private TournamentRepository tournamentRepository;
    private PhaseRepository phaseRepository;
    private MatchRepository matchRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private TeamRepository teamRepository;
    private SetResultRepository setResultRepository;
    private CascadeRecomputeService cascadeRecomputeService;
    private SimpMessagingTemplate messagingTemplate;
    private ScoringConfig scoringConfig;

    private ScoreEntryService service;

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------
    private static final UUID TENANT_ID   = UUID.randomUUID();
    private static final UUID DEVICE_ID   = UUID.randomUUID();
    private static final String DEV_TOKEN = UUID.randomUUID().toString();
    private static final UUID TOUR_ID     = UUID.randomUUID();
    private static final UUID PHASE_ID    = UUID.randomUUID();
    private static final UUID MATCH_ID    = UUID.randomUUID();
    private static final UUID AVATAR1_ID  = UUID.randomUUID();
    private static final UUID AVATAR2_ID  = UUID.randomUUID();
    private static final UUID TEAM1_ID    = UUID.randomUUID();
    private static final UUID TEAM2_ID    = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceRepository        = mock(DeviceRepository.class);
        tournamentRepository    = mock(TournamentRepository.class);
        phaseRepository         = mock(PhaseRepository.class);
        matchRepository         = mock(MatchRepository.class);
        teamAvatarRepository    = mock(TeamAvatarRepository.class);
        teamRepository          = mock(TeamRepository.class);
        setResultRepository     = mock(SetResultRepository.class);
        cascadeRecomputeService = mock(CascadeRecomputeService.class);
        messagingTemplate       = mock(SimpMessagingTemplate.class);
        scoringConfig           = mock(ScoringConfig.class);

        when(scoringConfig.getTiebreakSwapThreshold()).thenReturn(8);

        service = new ScoreEntryService(
                deviceRepository, tournamentRepository, phaseRepository,
                matchRepository, teamAvatarRepository, teamRepository,
                setResultRepository, cascadeRecomputeService, messagingTemplate,
                scoringConfig);
    }

    // =========================================================================
    // AC8: Device token validation — 401 paths
    // =========================================================================

    @Test
    @DisplayName("AC8: getMatchForField throws UnauthorizedException when token not found")
    void getMatchForField_unknownToken_throws401() {
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMatchForField(1, DEV_TOKEN))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("AC8: getMatchForField throws UnauthorizedException when device is REGISTERED (not ASSIGNED)")
    void getMatchForField_registeredDevice_throws401() {
        Device device = stubDevice(1, Device.STATUS_REGISTERED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.getMatchForField(1, DEV_TOKEN))
                .isInstanceOf(UnauthorizedException.class);
    }

    // =========================================================================
    // AC12: Device token valid but on wrong field — 403
    // =========================================================================

    @Test
    @DisplayName("AC12: getMatchForField throws ForbiddenException when device is on field 2 but request is for field 1")
    void getMatchForField_wrongField_throws403() {
        Device device = stubDevice(2, Device.STATUS_ASSIGNED);  // assigned to field 2
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.getMatchForField(1, DEV_TOKEN))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("field");
    }

    // =========================================================================
    // AC9: No active match — empty Optional returned
    // =========================================================================

    @Test
    @DisplayName("AC9: returns empty Optional when no active tournament exists")
    void getMatchForField_noActiveTournament_returnsEmpty() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));
        when(tournamentRepository.findAll()).thenReturn(Collections.emptyList());

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AC9: returns empty Optional when no active phase exists")
    void getMatchForField_noActivePhase_returnsEmpty() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE");
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(Collections.emptyList());

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AC9: returns empty Optional when no non-terminal match on field+lap")
    void getMatchForField_noNonTerminalMatch_returnsEmpty() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE");
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(Collections.emptyList());

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // AC1, AC4: Match resolved and response populated
    // =========================================================================

    @Test
    @DisplayName("AC1/AC4: returns populated MatchScoreResponse when active match exists")
    void getMatchForField_activeMatch_returnsResponse() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE", MatchFormat.BEST_OF_3.name());
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.ENABLED.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));

        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(Collections.emptyList());

        TeamAvatar avatar1 = stubAvatar(AVATAR1_ID, TEAM1_ID);
        TeamAvatar avatar2 = stubAvatar(AVATAR2_ID, TEAM2_ID);
        when(teamAvatarRepository.findById(AVATAR1_ID)).thenReturn(Optional.of(avatar1));
        when(teamAvatarRepository.findById(AVATAR2_ID)).thenReturn(Optional.of(avatar2));

        Team team1 = stubTeam(TEAM1_ID, "Alpha");
        Team team2 = stubTeam(TEAM2_ID, "Beta");
        when(teamRepository.findById(TEAM1_ID)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(TEAM2_ID)).thenReturn(Optional.of(team2));

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isPresent();
        MatchScoreResponse resp = result.get();
        assertThat(resp.matchId()).isEqualTo(MATCH_ID);
        assertThat(resp.team1Name()).isEqualTo("Alpha");
        assertThat(resp.team2Name()).isEqualTo("Beta");
        assertThat(resp.fieldNumber()).isEqualTo(1);
        assertThat(resp.lapNumber()).isEqualTo(1);
    }

    // =========================================================================
    // AC7, AC8: submitSetResult — SetResultInput.sourceType=TABLET
    // =========================================================================

    @Test
    @DisplayName("AC7/AC8: submitSetResult calls cascadeRecomputeService with sourceType=TABLET")
    void submitSetResult_invokesCascadeWithTabletSource() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        SetSubmitRequest request = new SetSubmitRequest(MATCH_ID, 0, 25, 18, DEV_TOKEN);
        service.submitSetResult(request);

        ArgumentCaptor<SetResultInput> captor = ArgumentCaptor.forClass(SetResultInput.class);
        verify(cascadeRecomputeService).registerMatchResult(captor.capture());

        SetResultInput input = captor.getValue();
        assertThat(input.sourceType()).isEqualTo(SetResultInput.SOURCE_TYPE_TABLET);
        assertThat(input.sourceDeviceId()).isEqualTo(DEVICE_ID.toString());
        assertThat(input.matchId()).isEqualTo(MATCH_ID);
        assertThat(input.setIndex()).isEqualTo(0);
        assertThat(input.team1Points()).isEqualTo(25);
        assertThat(input.team2Points()).isEqualTo(18);
    }

    // =========================================================================
    // E06S07 AC5: tiebreakSwapThreshold from ScoringConfig
    // =========================================================================

    @Test
    @DisplayName("E06S07 AC5: tiebreakSwapThreshold is populated from ScoringConfig")
    void getMatchForField_activeMatch_tiebreakThresholdPopulated() {
        when(scoringConfig.getTiebreakSwapThreshold()).thenReturn(11);

        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE", MatchFormat.BEST_OF_3.name());
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.ENABLED.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(Collections.emptyList());

        stubTeamAvatarsAndTeams();

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isPresent();
        assertThat(result.get().tiebreakSwapThreshold()).isEqualTo(11);
    }

    // =========================================================================
    // E06S07 AC8: matchFormat and maxSets populated from MatchFormat enum
    // =========================================================================

    @Test
    @DisplayName("E06S07 AC8: matchFormat=BEST_OF_3 and maxSets=3 populated from tournament")
    void getMatchForField_activeMatch_matchFormatAndMaxSetsPopulated() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE", MatchFormat.BEST_OF_3.name());
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.ENABLED.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(Collections.emptyList());

        stubTeamAvatarsAndTeams();

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isPresent();
        MatchScoreResponse resp = result.get();
        assertThat(resp.matchFormat()).isEqualTo("BEST_OF_3");
        assertThat(resp.maxSets()).isEqualTo(3);
    }

    // =========================================================================
    // E06S07 AC2: sets-won counts populated from SetResult rows
    // =========================================================================

    @Test
    @DisplayName("E06S07 AC2: team1SetsWon and team2SetsWon counted from closed SetResult rows")
    void getMatchForField_activeMatch_setsWonCountedFromSetResults() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE", MatchFormat.BEST_OF_5.name());
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.INPROGRESS.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));

        // Set 0: team1 won (WINNER1); Set 1: team2 won (WINNER2); Set 2: open (OPEN)
        SetResult set0 = stubSetResult(0, SetState.WINNER1, 25, 18);
        SetResult set1 = stubSetResult(1, SetState.WINNER2, 14, 25);
        SetResult set2 = stubSetResult(2, SetState.OPEN,     7,  5);
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(set0, set1, set2));

        stubTeamAvatarsAndTeams();

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isPresent();
        MatchScoreResponse resp = result.get();
        assertThat(resp.team1SetsWon()).isEqualTo(1);
        assertThat(resp.team2SetsWon()).isEqualTo(1);
        assertThat(resp.setIndex()).isEqualTo(2);        // current open set index
        assertThat(resp.team1Points()).isEqualTo(7);    // in-progress points from OPEN set
        assertThat(resp.team2Points()).isEqualTo(5);
        assertThat(resp.matchDecided()).isFalse();
    }

    // =========================================================================
    // E06S07 AC6: matchWinner populated for terminal matches
    // =========================================================================

    @Test
    @DisplayName("E06S07 AC6: matchWinner=TEAM1 when match is FINISHED_WINNER1")
    void getMatchForField_terminalMatch_matchWinnerTeam1() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE", MatchFormat.BEST_OF_3.name());
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.FINISHED_WINNER1.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));

        SetResult set0 = stubSetResult(0, SetState.WINNER1, 25, 18);
        SetResult set1 = stubSetResult(1, SetState.WINNER1, 25, 20);
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(set0, set1));

        stubTeamAvatarsAndTeams();

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isPresent();
        MatchScoreResponse resp = result.get();
        assertThat(resp.matchDecided()).isTrue();
        assertThat(resp.matchWinner()).isEqualTo("TEAM1");
        assertThat(resp.team1SetsWon()).isEqualTo(2);
        assertThat(resp.team2SetsWon()).isEqualTo(0);
    }

    @Test
    @DisplayName("E06S07 AC6: matchWinner=TEAM2 when match is FINISHED_WINNER2")
    void getMatchForField_terminalMatch_matchWinnerTeam2() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE", MatchFormat.BEST_OF_3.name());
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.FINISHED_WINNER2.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));

        SetResult set0 = stubSetResult(0, SetState.WINNER2, 18, 25);
        SetResult set1 = stubSetResult(1, SetState.WINNER2, 20, 25);
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(set0, set1));

        stubTeamAvatarsAndTeams();

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isPresent();
        MatchScoreResponse resp = result.get();
        assertThat(resp.matchDecided()).isTrue();
        assertThat(resp.matchWinner()).isEqualTo("TEAM2");
    }

    @Test
    @DisplayName("E06S07 AC6: matchWinner=STANDOFF when match is FINISHED_STANDOFF (FIXED_2_SETS)")
    void getMatchForField_terminalMatch_matchWinnerStandoff() {
        Device device = stubDevice(1, Device.STATUS_ASSIGNED);
        when(deviceRepository.findByDeviceToken(DEV_TOKEN)).thenReturn(Optional.of(device));

        Tournament tournament = stubTournament("ACTIVE", MatchFormat.FIXED_2_SETS.name());
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.FINISHED_STANDOFF.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));

        SetResult set0 = stubSetResult(0, SetState.WINNER1, 25, 18);
        SetResult set1 = stubSetResult(1, SetState.WINNER2, 18, 25);
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(set0, set1));

        stubTeamAvatarsAndTeams();

        Optional<MatchScoreResponse> result = service.getMatchForField(1, DEV_TOKEN);

        assertThat(result).isPresent();
        MatchScoreResponse resp = result.get();
        assertThat(resp.matchDecided()).isTrue();
        assertThat(resp.matchWinner()).isEqualTo("STANDOFF");
        assertThat(resp.matchFormat()).isEqualTo("FIXED_2_SETS");
        assertThat(resp.maxSets()).isEqualTo(2);
    }

    // =========================================================================
    // Fixture builders
    // =========================================================================

    private Device stubDevice(int assignedField, String status) {
        Device d = new Device();
        d.setId(DEVICE_ID);
        d.setTenantId(TENANT_ID);
        d.setDeviceToken(DEV_TOKEN);
        d.setAssignedField(assignedField);
        d.setStatus(status);
        return d;
    }

    private Tournament stubTournament(String status) {
        return stubTournament(status, MatchFormat.BEST_OF_1.name());
    }

    private Tournament stubTournament(String status, String matchFormat) {
        Tournament t = new Tournament();
        t.setId(TOUR_ID);
        t.setTenantId(TENANT_ID);
        t.setDescription("Test Tour");
        t.setStatus(status);
        t.setMatchFormat(matchFormat);
        return t;
    }

    private Phase stubPhase(String status, int lapNumber) {
        Phase p = new Phase();
        p.setId(PHASE_ID);
        p.setTenantId(TENANT_ID);
        p.setTournamentId(TOUR_ID);
        p.setStatus(status);
        p.setCurrentLapNumber(lapNumber);
        return p;
    }

    private Match stubMatch(int stateCode, int fieldNumber, int lapNumber) {
        Match m = new Match();
        m.setId(MATCH_ID);
        m.setTenantId(TENANT_ID);
        m.setState(stateCode);
        m.setFieldNumber(fieldNumber);
        m.setLapNumber(lapNumber);
        m.setMemberAvatar1Id(AVATAR1_ID);
        m.setMemberAvatar2Id(AVATAR2_ID);
        return m;
    }

    private TeamAvatar stubAvatar(UUID avatarId, UUID teamId) {
        TeamAvatar a = new TeamAvatar();
        a.setId(avatarId);
        a.setTeamId(teamId);
        return a;
    }

    private Team stubTeam(UUID teamId, String name) {
        Team t = new Team();
        t.setId(teamId);
        t.setDescription(name);
        return t;
    }

    private SetResult stubSetResult(int setIndex, SetState state, int t1Points, int t2Points) {
        SetResult sr = new SetResult();
        sr.setMatchId(MATCH_ID);
        sr.setSetIndex(setIndex);
        sr.setTenantId(TENANT_ID);
        sr.setPhaseId(PHASE_ID);
        sr.setTeam1Points(t1Points);
        sr.setTeam2Points(t2Points);
        sr.setSetState(state);
        return sr;
    }

    /**
     * Shared convenience — stubs the two team avatar + team lookups used by most happy-path tests.
     */
    private void stubTeamAvatarsAndTeams() {
        TeamAvatar avatar1 = stubAvatar(AVATAR1_ID, TEAM1_ID);
        TeamAvatar avatar2 = stubAvatar(AVATAR2_ID, TEAM2_ID);
        when(teamAvatarRepository.findById(AVATAR1_ID)).thenReturn(Optional.of(avatar1));
        when(teamAvatarRepository.findById(AVATAR2_ID)).thenReturn(Optional.of(avatar2));

        Team team1 = stubTeam(TEAM1_ID, "Alpha");
        Team team2 = stubTeam(TEAM2_ID, "Beta");
        when(teamRepository.findById(TEAM1_ID)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(TEAM2_ID)).thenReturn(Optional.of(team2));
    }
}
