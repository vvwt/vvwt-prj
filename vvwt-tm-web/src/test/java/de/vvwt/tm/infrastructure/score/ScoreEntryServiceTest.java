package de.vvwt.tm.infrastructure.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.domain.CascadeRecomputeService;
import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.ForbiddenException;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.SetResultInput;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.UnauthorizedException;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.score.dto.MatchScoreResponse;
import de.vvwt.tm.infrastructure.score.dto.SetSubmitRequest;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for {@link ScoreEntryService} (E06S06).
 *
 * <p>All collaborators are mocked. Tests verify:
 *
 * <ul>
 *   <li>AC1/AC4: Match resolution by field+lap
 *   <li>AC8: Device token validation (401/403 paths)
 *   <li>AC9: Empty Optional returned when no match
 *   <li>AC12: ForbiddenException thrown for wrong field
 *   <li>AC7/AC8: SetResultInput built with sourceType=TABLET and sourceDeviceId
 * </ul>
 */
@DisplayName("ScoreEntryService unit tests (E06S06)")
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
    private CascadeRecomputeService cascadeRecomputeService;
    private SimpMessagingTemplate messagingTemplate;

    private ScoreEntryService service;

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final String DEV_TOKEN = UUID.randomUUID().toString();
    private static final UUID TOUR_ID = UUID.randomUUID();
    private static final UUID PHASE_ID = UUID.randomUUID();
    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final UUID AVATAR1_ID = UUID.randomUUID();
    private static final UUID AVATAR2_ID = UUID.randomUUID();
    private static final UUID TEAM1_ID = UUID.randomUUID();
    private static final UUID TEAM2_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepository.class);
        tournamentRepository = mock(TournamentRepository.class);
        phaseRepository = mock(PhaseRepository.class);
        matchRepository = mock(MatchRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        teamRepository = mock(TeamRepository.class);
        cascadeRecomputeService = mock(CascadeRecomputeService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);

        service =
                new ScoreEntryService(
                        deviceRepository,
                        tournamentRepository,
                        phaseRepository,
                        matchRepository,
                        teamAvatarRepository,
                        teamRepository,
                        cascadeRecomputeService,
                        messagingTemplate);
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
    @DisplayName(
            "AC8: getMatchForField throws UnauthorizedException when device is REGISTERED (not"
                    + " ASSIGNED)")
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
    @DisplayName(
            "AC12: getMatchForField throws ForbiddenException when device is on field 2 but request"
                    + " is for field 1")
    void getMatchForField_wrongField_throws403() {
        Device device = stubDevice(2, Device.STATUS_ASSIGNED); // assigned to field 2
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

        when(matchRepository.findByFieldNumberAndLapNumber(1, 1))
                .thenReturn(Collections.emptyList());

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

        Tournament tournament = stubTournament("ACTIVE");
        when(tournamentRepository.findAll()).thenReturn(List.of(tournament));

        Phase phase = stubPhase("ACTIVE", 1);
        when(phaseRepository.findByTournamentId(TOUR_ID)).thenReturn(List.of(phase));

        Match match = stubMatch(MatchState.ENABLED.getLegacyCode(), 1, 1);
        when(matchRepository.findByFieldNumberAndLapNumber(1, 1)).thenReturn(List.of(match));

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
        Tournament t = new Tournament();
        t.setId(TOUR_ID);
        t.setTenantId(TENANT_ID);
        t.setDescription("Test Tour");
        t.setStatus(status);
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
}
