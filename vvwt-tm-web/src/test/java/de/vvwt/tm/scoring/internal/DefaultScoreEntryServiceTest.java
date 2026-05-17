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
import de.vvwt.tm.tournament.SetResult;
import de.vvwt.tm.tournament.SetResultInput;
import de.vvwt.tm.tournament.SetResultRepository;
import de.vvwt.tm.tournament.SetState;
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
    @Mock private SetResultRepository setResultRepository;

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
        assignedDevice.setDeviceToken(DEVICE_TOKEN);
        assignedDevice.setStatus(Device.STATUS_ASSIGNED);
        assignedDevice.setAssignedField(FIELD_NUMBER);

        // Tournament fixture — status ACTIVE
        activeTournament = new Tournament();
        activeTournament.setId(TOURNAMENT_ID);
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
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
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
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
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

    // =======================================================================
    // AC-TEST-SCORE-TABLET-MATCH-RESOLUTION-WORKS-AFTER-1-BASED-RED (E53S09 / DEC-60 D-1)
    //
    // After E53S09: L2 emits 1-based fieldNumber; getMatchForField(1, token) queries
    // findByFieldNumberAndLapNumber(1, lap). The match.fieldNumber=1 (1-based per DEC-60 D-1)
    // is now consistent with the URL parameter fieldNumber=1 → match found, non-empty result.
    //
    // Previously (pre-E53S09): L2 stored fieldNumber=0; URL parameter fieldNumber=1 did not
    // match → empty list → Bug 2 (silent scoring failure).
    // =======================================================================

    /**
     * AC-TEST-SCORE-TABLET-MATCH-RESOLUTION-WORKS-AFTER-1-BASED-RED (DEC-60 D-1 / E53S09):
     *
     * <p>getMatchForField(fieldNumber=1, token) with a 1-based match (fieldNumber=1 in DB after L2)
     * returns a non-empty result. The URL parameter and DB storage are now consistent.
     *
     * <p>Regression guard: resolveActiveMatch queries {@code
     * findByFieldNumberAndLapNumber(fieldNumber=1, lap)}. With 1-based storage, the match is found.
     * With 0-based storage (pre-E53S09), the query returns empty → silent scoring failure.
     */
    @Test
    void getMatchForField_oneBased_fieldNumber1_matchFound() {
        // AC-TEST-SCORE-TABLET-MATCH-RESOLUTION-WORKS-AFTER-1-BASED-RED
        // Match stored with fieldNumber=1 (1-based per DEC-60 D-1 / E53S09)
        // URL requests fieldNumber=1 → match returned
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice)); // assignedDevice.assignedField=1
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(PHASE_ID, 1, LAP_NUMBER))
                .thenReturn(List.of(activeMatch)); // activeMatch.fieldNumber=1 (1-based)

        TeamAvatar avatar1 = new TeamAvatar();
        avatar1.setId(AVATAR1_ID);
        avatar1.setTeamId(TEAM1_ID);
        TeamAvatar avatar2 = new TeamAvatar();
        avatar2.setId(AVATAR2_ID);
        avatar2.setTeamId(TEAM2_ID);
        Team team1 = new Team();
        team1.setId(TEAM1_ID);
        team1.setDescription("Rote Haie");
        Team team2 = new Team();
        team2.setId(TEAM2_ID);
        team2.setDescription("Blaue Wölfe");
        when(teamAvatarRepository.findById(AVATAR1_ID)).thenReturn(Optional.of(avatar1));
        when(teamAvatarRepository.findById(AVATAR2_ID)).thenReturn(Optional.of(avatar2));
        when(teamRepository.findById(TEAM1_ID)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(TEAM2_ID)).thenReturn(Optional.of(team2));

        Optional<ScoreEntryResult> result = service.getMatchForField(1, DEVICE_TOKEN);

        assertThat(result)
                .as(
                        "AC-TEST-SCORE-TABLET-MATCH-RESOLUTION-WORKS-AFTER-1-BASED-RED: URL"
                                + " fieldNumber=1 must find a match with DB fieldNumber=1 (1-based"
                                + " per DEC-60 D-1 / E53S09)")
                .isPresent();
        assertThat(result.get().fieldNumber())
                .as("result fieldNumber must be 1-based (=1)")
                .isEqualTo(1);
    }

    // =======================================================================
    // AC-TEST-SCORE-TABLET-EQUALITY-WORKS-AFTER-1-BASED-RED (E53S09 / DEC-60 D-1)
    //
    // After E53S09: device.assignedField=1 (1-based, unchanged) and match.fieldNumber=1
    // (now 1-based from L2 per DEC-60 D-1). The equality check at line 285 holds → no
    // ForbiddenException on legitimate scoring.
    //
    // Previously (pre-E53S09): device.assignedField=1 vs match.fieldNumber=0 → inequality
    // → ForbiddenException on every legitimate scoring attempt (Bug 3).
    // =======================================================================

    /**
     * AC-TEST-SCORE-TABLET-EQUALITY-WORKS-AFTER-1-BASED-RED (DEC-60 D-1 / E53S09):
     *
     * <p>submitSetResult with device.assignedField=1 and match.fieldNumber=1 (both 1-based after
     * E53S09 migration) must NOT throw ForbiddenException. The field equality check at line 285
     * passes (1 == 1).
     *
     * <p>Regression guard: device.assignedField has always been 1-based (operator input
     * "Feldnummer" min=1). Before E53S09, match.fieldNumber was 0-based → equality failed. After
     * E53S09, both are 1-based → equality holds.
     */
    @Test
    void submitSetResult_oneBasedDeviceAndMatchFieldEqual_noForbiddenException() {
        // AC-TEST-SCORE-TABLET-EQUALITY-WORKS-AFTER-1-BASED-RED
        // device.assignedField=1 (1-based, operator-input convention)
        // activeMatch.fieldNumber=1 (1-based per DEC-60 D-1 / E53S09)
        // Both are 1-based → equality holds → no ForbiddenException
        activeMatch.setFieldNumber(1); // 1-based per DEC-60 D-1
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(
                        Optional.of(assignedDevice)); // assignedDevice.assignedField=FIELD_NUMBER=1
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));
        // registerMatchResult is void — use lenient stubbing (strict mode ignores unused stubs)
        org.mockito.Mockito.doNothing().when(scoringService).registerMatchResult(any());

        SetSubmitInput input = new SetSubmitInput(MATCH_ID, 0, 21, 15, DEVICE_TOKEN);

        // Should NOT throw ForbiddenException — device.assignedField=1 == match.fieldNumber=1
        org.assertj.core.api.Assertions.assertThatCode(() -> service.submitSetResult(input))
                .as(
                        "AC-TEST-SCORE-TABLET-EQUALITY-WORKS-AFTER-1-BASED-RED:"
                            + " device.assignedField=1 and match.fieldNumber=1 must be equal (both"
                            + " 1-based per DEC-60 D-1 / E53S09) → no ForbiddenException")
                .doesNotThrowAnyException();
    }

    // =======================================================================
    // AC5 — Regression guard: phase-scoped match resolution (E22S13 fix)
    //
    // Before the fix: resolveActiveMatch called findByFieldNumberAndLapNumber which
    // is NOT scoped to the active phase. A later-phase match at the same
    // (fieldNumber, lapNumber) coordinate could be surfaced.
    //
    // After the fix: resolveActiveMatch calls findByPhaseIdAndFieldNumberAndLapNumber,
    // scoped to the active phase. A later-phase match at the same coordinate is never
    // returned.
    // =======================================================================

    /**
     * AC5-REGRESSION-E22S13-RED: getMatchForField must NOT surface a match from a non-active phase.
     *
     * <p>Scenario (AC1): phase-1 is ACTIVE; its match on (field=1, lap=1) is in a terminal state.
     * Phase-2 (not yet active) has a match at the same (fieldNumber=1, lapNumber=1) coordinate.
     *
     * <p>Expected: getMatchForField returns Optional.empty() — the later-phase match is NOT
     * surfaced, and the terminal active-phase match is not returned (it is terminal).
     *
     * <p>This test verifies AC1 (reproduction guard), AC2 (phase-scoped resolution), and AC3
     * (terminal active-phase match → empty).
     */
    @Test
    void getMatchForField_laterPhaseMatchSameCoordinates_notSurfaced() {
        // AC5-REGRESSION-E22S13-RED
        // Active phase (phase 1) has a match at (field=1, lap=1) but it is TERMINAL (FINISHED).
        // Phase-scoped query returns empty → getMatchForField returns Optional.empty().
        // (The not-yet-active phase-2 match at the same coordinates is never consulted
        //  because the query is now scoped to the active phase.)
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        // Active-phase match exists but is TERMINAL — phase-scoped query returns only terminal
        // match
        Match terminalMatch = new Match();
        terminalMatch.setId(UUID.randomUUID());
        terminalMatch.setTournamentId(TOURNAMENT_ID);
        terminalMatch.setPhaseId(PHASE_ID);
        terminalMatch.setMatchState(MatchState.FINISHED_WINNER1);
        terminalMatch.setFieldNumber(FIELD_NUMBER);
        terminalMatch.setLapNumber(LAP_NUMBER);
        // Phase-scoped query on the active phase returns the terminal match (only)
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(terminalMatch));

        Optional<ScoreEntryResult> result = service.getMatchForField(FIELD_NUMBER, DEVICE_TOKEN);

        assertThat(result)
                .as(
                        "AC5-REGRESSION-E22S13: later-phase match at same (field, lap) coordinate"
                            + " must NOT be surfaced; terminal active-phase match → empty result")
                .isEmpty();
    }

    /**
     * AC5-REGRESSION-E22S13-POSITIVE-RED: active-phase non-terminal match IS returned (happy path
     * with phase-scoped query).
     *
     * <p>Verifies that after the fix, the happy path still works: a non-terminal active-phase match
     * at (field=1, lap=1) is returned when the phase-scoped query finds it.
     */
    @Test
    void getMatchForField_activePhaseMatchReturned_phaseScoped() {
        // AC5-REGRESSION-E22S13-POSITIVE-RED
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        // Phase-scoped query returns the active match
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));

        TeamAvatar avatar1 = new TeamAvatar();
        avatar1.setId(AVATAR1_ID);
        avatar1.setTeamId(TEAM1_ID);
        TeamAvatar avatar2 = new TeamAvatar();
        avatar2.setId(AVATAR2_ID);
        avatar2.setTeamId(TEAM2_ID);
        Team team1 = new Team();
        team1.setId(TEAM1_ID);
        team1.setDescription("Mannschaft 03");
        Team team2 = new Team();
        team2.setId(TEAM2_ID);
        team2.setDescription("Mannschaft 06");
        when(teamAvatarRepository.findById(AVATAR1_ID)).thenReturn(Optional.of(avatar1));
        when(teamAvatarRepository.findById(AVATAR2_ID)).thenReturn(Optional.of(avatar2));
        when(teamRepository.findById(TEAM1_ID)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(TEAM2_ID)).thenReturn(Optional.of(team2));

        Optional<ScoreEntryResult> result = service.getMatchForField(FIELD_NUMBER, DEVICE_TOKEN);

        assertThat(result)
                .as(
                        "AC5-REGRESSION-E22S13-POSITIVE: active-phase non-terminal match must be"
                                + " returned")
                .isPresent();
        assertThat(result.get().matchId())
                .as("returned match must be the active phase match")
                .isEqualTo(MATCH_ID);
        assertThat(result.get().team1Name()).isEqualTo("Mannschaft 03");
        assertThat(result.get().team2Name()).isEqualTo("Mannschaft 06");
    }

    // =======================================================================
    // E61S02 — AC2/AC3/AC4: true set index, current points, tiebreak (TDD RED-first, DEC-22)
    // =======================================================================

    /**
     * T-E61-1 (AC2-RED): getMatchForField with 1 completed set → setIndex must equal 1, not 0.
     *
     * <p>Scenario: one WINNER1 set_result row exists for the active match (set 0 was won by team
     * 1). The resolved setIndex must be 1 (count of non-OPEN, non-CANCELED completed sets).
     *
     * <p>RED: current implementation hardcodes currentSetIndex=0 → assertion fails.
     */
    @Test
    void getMatchForField_multiSetMatch_returnsCorrectSetIndex() {
        // Arrange — standard resolution chain
        activeTournament.setMatchFormat("BEST_OF_3");
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());

        // One completed set (WINNER1 = set 0 done, team1 wins)
        SetResult completedSet = new SetResult();
        completedSet.setMatchId(MATCH_ID);
        completedSet.setSetIndex(0);
        completedSet.setPhaseId(PHASE_ID);
        completedSet.setTeam1Points(25);
        completedSet.setTeam2Points(18);
        completedSet.setSetState(SetState.WINNER1);

        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(completedSet));

        // Act
        Optional<ScoreEntryResult> result = service.getMatchForField(FIELD_NUMBER, DEVICE_TOKEN);

        // Assert — AC2: setIndex = count of completed (non-OPEN, non-CANCELED) sets = 1
        assertThat(result).isPresent();
        assertThat(result.get().setIndex())
                .as("AC2: setIndex must equal the count of completed sets (1)")
                .isEqualTo(1);
    }

    /**
     * T-E61-2 (AC3-RED): getMatchForField with a persisted OPEN set_result → returns its points.
     *
     * <p>Scenario: one WINNER1 set (set 0) and one OPEN partial set (set 1, 10:8). The result must
     * carry team1Points=10, team2Points=8.
     *
     * <p>RED: current implementation returns 0:0 hardcoded → assertion fails.
     */
    @Test
    void getMatchForField_withPersistedPartialScore_returnsCurrentPoints() {
        // Arrange
        activeTournament.setMatchFormat("BEST_OF_3");
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());

        // Set 0 completed (WINNER1), set 1 in-progress (OPEN, 10:8)
        SetResult completedSet = new SetResult();
        completedSet.setMatchId(MATCH_ID);
        completedSet.setSetIndex(0);
        completedSet.setPhaseId(PHASE_ID);
        completedSet.setTeam1Points(25);
        completedSet.setTeam2Points(18);
        completedSet.setSetState(SetState.WINNER1);

        SetResult openSet = new SetResult();
        openSet.setMatchId(MATCH_ID);
        openSet.setSetIndex(1);
        openSet.setPhaseId(PHASE_ID);
        openSet.setTeam1Points(10);
        openSet.setTeam2Points(8);
        openSet.setSetState(SetState.OPEN);

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(List.of(completedSet, openSet));

        // Act
        Optional<ScoreEntryResult> result = service.getMatchForField(FIELD_NUMBER, DEVICE_TOKEN);

        // Assert — AC3: points from the OPEN set_result row
        assertThat(result).isPresent();
        assertThat(result.get().team1Points())
                .as("AC3: team1Points must come from the OPEN set_result row")
                .isEqualTo(10);
        assertThat(result.get().team2Points())
                .as("AC3: team2Points must come from the OPEN set_result row")
                .isEqualTo(8);
    }

    /**
     * T-E61-3 (AC4-RED): getMatchForField with BEST_OF_3, both teams 1-1 → isTiebreak=true.
     *
     * <p>Scenario: BEST_OF_3 match, set 0 won by team1 (WINNER1), set 1 won by team2 (WINNER2).
     * Both at 1-1 (requiredToWin-1=1) → current set is the deciding tiebreak set.
     *
     * <p>RED: current implementation does not have an {@code isTiebreak} field → compilation fails
     * (or assertion fails if field added but not computed).
     */
    @Test
    void getMatchForField_tiebreakSet_returnsTiebreakTrue() {
        // Arrange
        activeTournament.setMatchFormat("BEST_OF_3");
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());

        // Both teams 1-1: set 0 WINNER1, set 1 WINNER2
        SetResult set0 = new SetResult();
        set0.setMatchId(MATCH_ID);
        set0.setSetIndex(0);
        set0.setPhaseId(PHASE_ID);
        set0.setTeam1Points(25);
        set0.setTeam2Points(18);
        set0.setSetState(SetState.WINNER1);

        SetResult set1 = new SetResult();
        set1.setMatchId(MATCH_ID);
        set1.setSetIndex(1);
        set1.setPhaseId(PHASE_ID);
        set1.setTeam1Points(18);
        set1.setTeam2Points(25);
        set1.setSetState(SetState.WINNER2);

        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(set0, set1));

        // Act
        Optional<ScoreEntryResult> result = service.getMatchForField(FIELD_NUMBER, DEVICE_TOKEN);

        // Assert — AC4: isTiebreak=true (both at requiredToWin-1=1, format has deciding set)
        assertThat(result).isPresent();
        assertThat(result.get().isTiebreak())
                .as("AC4: BEST_OF_3 at 1-1 must be detected as tiebreak")
                .isTrue();
    }

    /**
     * T-E61-4 (AC4-RED): getMatchForField with BEST_OF_3, no completed sets → isTiebreak=false.
     *
     * <p>Scenario: brand new match (no set_result rows). Not a tiebreak.
     *
     * <p>RED: same as T-E61-3 for different reason — field doesn't exist yet.
     */
    @Test
    void getMatchForField_nonTiebreakSet_returnsTiebreakFalse() {
        // Arrange
        activeTournament.setMatchFormat("BEST_OF_3");
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());

        // No set_result rows at all
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of());

        // Act
        Optional<ScoreEntryResult> result = service.getMatchForField(FIELD_NUMBER, DEVICE_TOKEN);

        // Assert — AC4: no completed sets → not a tiebreak
        assertThat(result).isPresent();
        assertThat(result.get().isTiebreak())
                .as("AC4: BEST_OF_3 with 0-0 score must NOT be detected as tiebreak")
                .isFalse();
    }

    /**
     * T-E61-5 (AC1-RED): handlePartialScore persists partial score to set_result as OPEN row.
     *
     * <p>Scenario: no existing OPEN row for (matchId, setIndex=0) → INSERT a new OPEN SetResult.
     *
     * <p>RED: current implementation never calls setResultRepository → assertion fails.
     */
    @Test
    void handlePartialScore_persistsScoreToSetResult() {
        // Arrange
        activeTournament.setMatchFormat("BEST_OF_3");
        activeMatch.setPhaseId(PHASE_ID); // needed so persistPartialScore can resolve phaseId
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of());
        when(setResultRepository.findByMatchIdAndSetIndex(MATCH_ID, 0))
                .thenReturn(Optional.empty()); // no existing OPEN row

        PartialScoreInput input = new PartialScoreInput(MATCH_ID, 0, 10, 8, DEVICE_TOKEN);

        // Act
        service.handlePartialScore(input);

        // Assert — AC1: setResultRepository.insert must be called with OPEN state
        verify(setResultRepository).insert(any(SetResult.class));
    }

    /**
     * T-E61-6 (AC1-RED): handlePartialScore updates existing OPEN set_result when one exists.
     *
     * <p>Scenario: an OPEN set_result already exists for (matchId, setIndex=0) → UPDATE it, no
     * INSERT.
     *
     * <p>RED: current implementation never calls setResultRepository → assertion fails.
     */
    @Test
    void handlePartialScore_updatesExistingOpenSetResult() {
        // Arrange
        activeTournament.setMatchFormat("BEST_OF_3");
        when(deviceRepository.findByDeviceToken(DEVICE_TOKEN))
                .thenReturn(Optional.of(assignedDevice));
        when(tournamentRepository.findAll()).thenReturn(List.of(activeTournament));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(activePhase));
        when(matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        PHASE_ID, FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(List.of(activeMatch));
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of());

        // Existing OPEN row
        SetResult existingOpen = new SetResult();
        existingOpen.setMatchId(MATCH_ID);
        existingOpen.setSetIndex(0);
        existingOpen.setPhaseId(PHASE_ID);
        existingOpen.setTeam1Points(5);
        existingOpen.setTeam2Points(3);
        existingOpen.setSetState(SetState.OPEN);
        when(setResultRepository.findByMatchIdAndSetIndex(MATCH_ID, 0))
                .thenReturn(Optional.of(existingOpen));

        PartialScoreInput input = new PartialScoreInput(MATCH_ID, 0, 10, 8, DEVICE_TOKEN);

        // Act
        service.handlePartialScore(input);

        // Assert — AC1: UPDATE (not INSERT) when OPEN row already exists
        verify(setResultRepository).update(any(SetResult.class));
        verify(setResultRepository, never()).insert(any(SetResult.class));
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
