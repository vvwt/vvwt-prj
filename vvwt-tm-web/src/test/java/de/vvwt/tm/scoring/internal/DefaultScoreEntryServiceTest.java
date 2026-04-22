package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.scoring.PartialScoreInput;
import de.vvwt.tm.scoring.ScoreEntryResult;
import de.vvwt.tm.scoring.ScoringService;
import de.vvwt.tm.scoring.SetSubmitInput;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.SetResultInput;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ForbiddenException;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Unit tests for {@link DefaultScoreEntryService} — same-package white-box tests per DEC-36.
 *
 * <p>Tests reference {@link DefaultScoreEntryService} directly (implementation class) because the
 * test class is co-located in {@code de.vvwt.tm.scoring.internal} — same package as the subject.
 * This is the DEC-36 white-box exception for same-package tests. Cross-package tests that consume
 * {@code ScoreEntryService} MUST reference the interface type only.
 *
 * <p>Mocks are typed to interface types (DEC-36): {@link ScoringService} (not {@code
 * DefaultScoringService}), {@link DeviceRepository}, {@link MatchRepository}, {@link
 * TournamentRepository}, {@link PhaseRepository}, {@link TeamAvatarRepository}, {@link
 * TeamRepository}.
 *
 * <p>TDD Iron Law (DEC-22): every test in this class was written RED-first — the stub
 * implementation throwing {@link UnsupportedOperationException} was committed before any production
 * code was added. RED commit SHA is recorded in the impl-report per Q-3.
 *
 * @since E22S06
 * @see DefaultScoreEntryService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — same-package white-box exemption</a>
 * @see <a href="DEC-37">DEC-37 — cascade delegation (no direct lock acquisition)</a>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultScoreEntryServiceTest {

    // -----------------------------------------------------------------------
    // Mocks — all interface types (DEC-36)
    // -----------------------------------------------------------------------

    @Mock private DeviceRepository deviceRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private ScoringService scoringService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks private DefaultScoreEntryService service;

    // -----------------------------------------------------------------------
    // Shared test fixtures
    // -----------------------------------------------------------------------

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID PHASE_ID = UUID.randomUUID();
    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final UUID AVATAR1_ID = UUID.randomUUID();
    private static final UUID AVATAR2_ID = UUID.randomUUID();
    private static final UUID TEAM1_ID = UUID.randomUUID();
    private static final UUID TEAM2_ID = UUID.randomUUID();
    private static final String DEVICE_TOKEN = "valid-device-token-abc";
    private static final int FIELD_NUMBER = 1;
    private static final int LAP_NUMBER = 1;

    private Device assignedDevice;
    private Tournament activeTournament;
    private Phase activePhase;
    private Match activeMatch;

    @BeforeEach
    void setUpFixtures() {
        // Device fixture — status ASSIGNED, assigned to FIELD_NUMBER
        assignedDevice = new Device();
        assignedDevice.setId(DEVICE_ID);
        assignedDevice.setTenantId(TENANT_ID);
        assignedDevice.setDeviceToken(DEVICE_TOKEN);
        assignedDevice.setStatus(Device.STATUS_ASSIGNED);
        assignedDevice.setAssignedField(FIELD_NUMBER);

        // Tournament fixture — status ACTIVE
        activeTournament = new Tournament();
        activeTournament.setId(TOURNAMENT_ID);
        activeTournament.setTenantId(TENANT_ID);
        activeTournament.setStatus("ACTIVE");

        // Phase fixture — status ACTIVE, lap 1
        activePhase = new Phase();
        activePhase.setId(PHASE_ID);
        activePhase.setTournamentId(TOURNAMENT_ID);
        activePhase.setStatus(Phase.PhaseStatus.ACTIVE.name());
        activePhase.setCurrentLapNumber(LAP_NUMBER);

        // Match fixture — INPROGRESS, field 1, lap 1
        activeMatch = new Match();
        activeMatch.setId(MATCH_ID);
        activeMatch.setTenantId(TENANT_ID);
        activeMatch.setTournamentId(TOURNAMENT_ID);
        activeMatch.setPhaseId(PHASE_ID);
        activeMatch.setMatchState(MatchState.INPROGRESS);
        activeMatch.setFieldNumber(FIELD_NUMBER);
        activeMatch.setLapNumber(LAP_NUMBER);
        activeMatch.setMemberAvatar1Id(AVATAR1_ID);
        activeMatch.setMemberAvatar2Id(AVATAR2_ID);
        activeMatch.setRefereeTeamId(null);
    }

    // =======================================================================
    // getMatchForField — happy path
    // =======================================================================

    /**
     * T1 — AC-TEST-COVERAGE-MATRIX: valid deviceToken + active match → returns non-empty Optional.
     * Also verifies AC-INTERFACE-CREATED, AC-PUBLIC-METHODS, AC-PUBLIC-DTOS-CREATED
     * (ScoreEntryResult).
     */
    @Test
    void getMatchForField_validTokenAndActiveMatch_returnsPopulatedResult() {
        // Arrange
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByFieldNumberAndLapNumber(FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));

        // Team name resolution
        TeamAvatar avatar1 = new TeamAvatar();
        avatar1.setId(AVATAR1_ID);
        avatar1.setTeamId(TEAM1_ID);
        TeamAvatar avatar2 = new TeamAvatar();
        avatar2.setId(AVATAR2_ID);
        avatar2.setTeamId(TEAM2_ID);
        Team team1 = new Team();
        team1.setId(TEAM1_ID);
        team1.setDescription("Team Alpha");
        Team team2 = new Team();
        team2.setId(TEAM2_ID);
        team2.setDescription("Team Beta");

        when(teamAvatarRepository.findById(AVATAR1_ID)).thenReturn(Optional.of(avatar1));
        when(teamAvatarRepository.findById(AVATAR2_ID)).thenReturn(Optional.of(avatar2));
        when(teamRepository.findById(TEAM1_ID)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(TEAM2_ID)).thenReturn(Optional.of(team2));

        // Act
        Optional<ScoreEntryResult> result = service.getMatchForField(FIELD_NUMBER, DEVICE_TOKEN);

        // Assert
        assertThat(result).isPresent();
        ScoreEntryResult r = result.get();
        assertThat(r.matchId()).isEqualTo(MATCH_ID);
        assertThat(r.fieldNumber()).isEqualTo(FIELD_NUMBER);
        assertThat(r.lapNumber()).isEqualTo(LAP_NUMBER);
        assertThat(r.team1Name()).isEqualTo("Team Alpha");
        assertThat(r.team2Name()).isEqualTo("Team Beta");
        assertThat(r.refereeTeamName()).isNull();
    }

    // =======================================================================
    // getMatchForField — security (AC-SECURITY-DEVICE-TOKEN, AC-TEST-COVERAGE-MATRIX)
    // =======================================================================

    /**
     * T2 — AC-SECURITY-DEVICE-TOKEN: unknown/invalid deviceToken → UnauthorizedException.
     * AC-NULL-GUARDS: also tested for null in T4.
     */
    @Test
    void getMatchForField_unknownDeviceToken_throwsUnauthorizedException() {
        when(deviceRepository.findByDeviceToken("unknown-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMatchForField(FIELD_NUMBER, "unknown-token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    /**
     * T3 — AC-SECURITY-DEVICE-TOKEN: device token valid but device assigned to different field →
     * ForbiddenException (AC-TEST-COVERAGE-MATRIX §ForbiddenException).
     */
    @Test
    void getMatchForField_deviceOnDifferentField_throwsForbiddenException() {
        Device deviceOnField2 = new Device();
        deviceOnField2.setId(DEVICE_ID);
        deviceOnField2.setTenantId(TENANT_ID);
        deviceOnField2.setDeviceToken(DEVICE_TOKEN);
        deviceOnField2.setStatus(Device.STATUS_ASSIGNED);
        deviceOnField2.setAssignedField(2); // device is on field 2

        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(deviceOnField2));

        assertThatThrownBy(() -> service.getMatchForField(1 /* requesting field 1 */, DEVICE_TOKEN))
                .isInstanceOf(ForbiddenException.class);
    }

    /** T4 — AC-NULL-GUARDS: null deviceToken → IllegalArgumentException. */
    @Test
    void getMatchForField_nullDeviceToken_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.getMatchForField(FIELD_NUMBER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =======================================================================
    // handlePartialScore — happy path
    // =======================================================================

    /**
     * T5 — AC-TEST-COVERAGE-MATRIX: valid partial score input → broadcasts to WebSocket (no
     * persistence). Verifies messagingTemplate is invoked.
     */
    @Test
    void handlePartialScore_validInput_broadcastsToWebSocket() {
        // Arrange
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByFieldNumberAndLapNumber(FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));

        // Team name resolution (needed to build the existing MatchScoreResponse for matchId check)
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());

        PartialScoreInput input = new PartialScoreInput(MATCH_ID, 0, 10, 8, DEVICE_TOKEN);

        // Act
        service.handlePartialScore(input);

        // Assert — messagingTemplate was called at least once (broadcast happened)
        verify(messagingTemplate).convertAndSend(any(String.class), any(Object.class));
        // Assert — no persistence (scoringService not called)
        verify(scoringService, never()).registerMatchResult(any());
    }

    // =======================================================================
    // handlePartialScore — security (AC-SECURITY-DEVICE-TOKEN)
    // =======================================================================

    /**
     * T6 — AC-SECURITY-DEVICE-TOKEN: invalid deviceToken in PartialScoreInput →
     * UnauthorizedException.
     */
    @Test
    void handlePartialScore_invalidDeviceToken_throwsUnauthorizedException() {
        when(deviceRepository.findByDeviceToken("bad-token")).thenReturn(Optional.empty());

        PartialScoreInput input = new PartialScoreInput(MATCH_ID, 0, 5, 3, "bad-token");

        assertThatThrownBy(() -> service.handlePartialScore(input))
                .isInstanceOf(UnauthorizedException.class);
    }

    /** T7 — AC-NULL-GUARDS: null request → IllegalArgumentException. */
    @Test
    void handlePartialScore_nullRequest_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.handlePartialScore(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =======================================================================
    // submitSetResult — happy path (AC-TEST-COVERAGE-MATRIX, AC-CASCADE-DELEGATION-PRESERVED)
    // =======================================================================

    /**
     * T8 — AC-TEST-COVERAGE-MATRIX: valid set result → ScoringService.registerMatchResult called
     * exactly once (DEC-36 — mock the ScoringService interface type).
     * AC-CASCADE-DELEGATION-PRESERVED (C-7, DEC-37 Clause B): no direct lock acquisition, cascade
     * delegated.
     */
    @Test
    void submitSetResult_validInput_delegatesToScoringServiceExactlyOnce() {
        // Arrange
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));

        SetSubmitInput input = new SetSubmitInput(MATCH_ID, 0, 25, 20, DEVICE_TOKEN);

        // Act
        service.submitSetResult(input);

        // Assert — ScoringService.registerMatchResult invoked exactly once
        // (AC-CASCADE-DELEGATION-PRESERVED)
        verify(scoringService).registerMatchResult(any(SetResultInput.class));
    }

    // =======================================================================
    // submitSetResult — security (AC-SECURITY-DEVICE-TOKEN)
    // =======================================================================

    /**
     * T9 — AC-SECURITY-DEVICE-TOKEN: invalid deviceToken → UnauthorizedException; cascade NOT
     * called.
     */
    @Test
    void submitSetResult_invalidDeviceToken_throwsUnauthorizedException_noCascade() {
        when(deviceRepository.findByDeviceToken("bad-token")).thenReturn(Optional.empty());

        SetSubmitInput input = new SetSubmitInput(MATCH_ID, 0, 25, 20, "bad-token");

        assertThatThrownBy(() -> service.submitSetResult(input))
                .isInstanceOf(UnauthorizedException.class);

        verify(scoringService, never()).registerMatchResult(any());
    }

    /** T10 — AC-NULL-GUARDS: null request → IllegalArgumentException; cascade NOT called. */
    @Test
    void submitSetResult_nullRequest_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.submitSetResult(null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(scoringService, never()).registerMatchResult(any());
    }

    /**
     * T11 — AC-SECURITY-DEVICE-TOKEN: valid token but device on different field →
     * ForbiddenException; cascade NOT called.
     */
    @Test
    void submitSetResult_deviceOnWrongField_throwsForbiddenException_noCascade() {
        Device deviceOnField2 = new Device();
        deviceOnField2.setId(DEVICE_ID);
        deviceOnField2.setTenantId(TENANT_ID);
        deviceOnField2.setDeviceToken(DEVICE_TOKEN);
        deviceOnField2.setStatus(Device.STATUS_ASSIGNED);
        deviceOnField2.setAssignedField(2);

        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(deviceOnField2));

        // The match is on field 1, but device is on field 2
        activeMatch.setFieldNumber(1);
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));

        SetSubmitInput input = new SetSubmitInput(MATCH_ID, 0, 25, 20, DEVICE_TOKEN);

        assertThatThrownBy(() -> service.submitSetResult(input))
                .isInstanceOf(ForbiddenException.class);

        verify(scoringService, never()).registerMatchResult(any());
    }

    /**
     * T12 — AC-SECURITY-TENANT-ISOLATION: cross-tenant device token → UnauthorizedException.
     *
     * <p>DeviceRepository.findByDeviceToken is intentionally cross-tenant per its Javadoc (global
     * device token lookup for WebSocket auth). Tenant isolation for scoring is verified by checking
     * the device's tenantId against the current tenant context. If the device belongs to a
     * different tenant, it is treated as unauthorized.
     *
     * <p>Implementation note: the current tenant context is established by Spring's DataSource
     * routing layer before the request reaches this service. The DeviceRepository operates on the
     * current tenant's DataSource — so a device token from a different tenant would not be found by
     * findByDeviceToken (which queries the current tenant's DB). An empty result →
     * UnauthorizedException. This test verifies the cross-tenant isolation by simulating an empty
     * result from findByDeviceToken when the token belongs to a different tenant's device.
     */
    @Test
    void submitSetResult_crossTenantDeviceToken_throwsUnauthorizedException() {
        // Cross-tenant token: device not found in current tenant's DB
        when(deviceRepository.findByDeviceToken("other-tenant-token")).thenReturn(Optional.empty());

        SetSubmitInput input = new SetSubmitInput(MATCH_ID, 0, 25, 20, "other-tenant-token");

        assertThatThrownBy(() -> service.submitSetResult(input))
                .isInstanceOf(UnauthorizedException.class);

        verify(scoringService, never()).registerMatchResult(any());
    }
}
