package de.vvwt.tm.infrastructure.score;

import de.vvwt.tm.domain.Device;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.UnauthorizedException;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.score.dto.TabletStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TabletStatusService} (E06S08).
 *
 * <p>All collaborators are mocked. Tests cover every state branch of the resolution algorithm:
 * <ul>
 *   <li>AC1:  WAITING_FOR_LAP — all matches on field in current lap are terminal</li>
 *   <li>AC2:  ACTIVE_MATCH — non-terminal match found on this field</li>
 *   <li>AC3:  NO_MATCH_ON_FIELD — no match scheduled on this field in current lap</li>
 *   <li>AC4:  PHASE_TRANSITION — COMPLETED phase + PENDING phase found</li>
 *   <li>AC5:  TOURNAMENT_COMPLETE — tournament status=COMPLETED</li>
 *   <li>AC6:  TOURNAMENT_NOT_ACTIVE — no tournament or DRAFT status</li>
 *   <li>AC9:  UnauthorizedException for invalid / unassigned device token</li>
 * </ul>
 */
@DisplayName("TabletStatusService unit tests (E06S08)")
class TabletStatusServiceTest {

    // -------------------------------------------------------------------------
    // Mocks
    // -------------------------------------------------------------------------

    private DeviceRepository     deviceRepository;
    private TournamentRepository tournamentRepository;
    private PhaseRepository      phaseRepository;
    private MatchRepository      matchRepository;

    private TabletStatusService service;

    // -------------------------------------------------------------------------
    // Test fixtures
    // -------------------------------------------------------------------------

    private static final String VALID_TOKEN   = "valid-token";
    private static final int    FIELD_NUMBER  = 2;
    private static final int    LAP_NUMBER    = 3;

    private final UUID tenantId     = UUID.randomUUID();
    private final UUID tournamentId = UUID.randomUUID();
    private final UUID phaseId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        deviceRepository     = mock(DeviceRepository.class);
        tournamentRepository = mock(TournamentRepository.class);
        phaseRepository      = mock(PhaseRepository.class);
        matchRepository      = mock(MatchRepository.class);

        service = new TabletStatusService(
                deviceRepository, tournamentRepository, phaseRepository, matchRepository);
    }

    // -------------------------------------------------------------------------
    // Helpers — fixture builders
    // -------------------------------------------------------------------------

    /** Builds an assigned device with the given field number. */
    private Device assignedDevice(int fieldNumber) {
        Device device = new Device();
        device.setStatus(Device.STATUS_ASSIGNED);
        device.setAssignedField(fieldNumber);
        return device;
    }

    /** Builds a tournament with the given status. */
    private Tournament tournament(String status) {
        return new Tournament(tournamentId, tenantId, "Test Tournament",
                "BEST_OF_3", "defaultScoringRule", "defaultSetValidationRule",
                "roundRobinMatchGenerator", status, null);
    }

    /** Builds a phase with the given status and lap number. */
    private Phase phase(String status, int lapNumber) {
        return new Phase(phaseId, tenantId, tournamentId, 1,
                "Vorrunde", status, lapNumber, null);
    }

    /** Builds a match with the given state code. */
    private Match match(int stateCode) {
        return new Match(UUID.randomUUID(), tenantId, tournamentId, phaseId,
                UUID.randomUUID(), UUID.randomUUID(),
                stateCode, 3,
                LAP_NUMBER, FIELD_NUMBER,
                null, null, null, null);
    }

    /** Configures mocks with a valid assigned device. */
    private void givenValidDevice() {
        when(deviceRepository.findByDeviceToken(VALID_TOKEN))
                .thenReturn(Optional.of(assignedDevice(FIELD_NUMBER)));
    }

    // -------------------------------------------------------------------------
    // AC9: Device token validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC9: unknown device token throws UnauthorizedException")
    void unknownTokenThrowsUnauthorized() {
        when(deviceRepository.findByDeviceToken("bad-token"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTabletStatus(FIELD_NUMBER, "bad-token"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid or unknown device token");
    }

    @Test
    @DisplayName("AC9: unassigned device token throws UnauthorizedException")
    void unassignedDeviceThrowsUnauthorized() {
        Device unassigned = new Device();
        unassigned.setStatus("REGISTERED");
        unassigned.setAssignedField(null);

        when(deviceRepository.findByDeviceToken(VALID_TOKEN))
                .thenReturn(Optional.of(unassigned));

        assertThatThrownBy(() -> service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("not assigned");
    }

    @Test
    @DisplayName("AC9: device with null assignedField throws UnauthorizedException")
    void deviceWithNullFieldThrowsUnauthorized() {
        Device deviceNoField = new Device();
        deviceNoField.setStatus(Device.STATUS_ASSIGNED);
        deviceNoField.setAssignedField(null);

        when(deviceRepository.findByDeviceToken(VALID_TOKEN))
                .thenReturn(Optional.of(deviceNoField));

        assertThatThrownBy(() -> service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("no assigned field");
    }

    // -------------------------------------------------------------------------
    // AC6: TOURNAMENT_NOT_ACTIVE — no tournament or DRAFT
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC6: no tournament in DB → TOURNAMENT_NOT_ACTIVE")
    void noTournamentReturnsNotActive() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(Collections.emptyList());

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_TOURNAMENT_NOT_ACTIVE);
        assertThat(result.fieldNumber()).isEqualTo(FIELD_NUMBER);
    }

    @Test
    @DisplayName("AC6: only DRAFT tournament → TOURNAMENT_NOT_ACTIVE")
    void draftTournamentReturnsNotActive() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("DRAFT")));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_TOURNAMENT_NOT_ACTIVE);
    }

    // -------------------------------------------------------------------------
    // AC5: TOURNAMENT_COMPLETE
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC5: tournament status=COMPLETED → TOURNAMENT_COMPLETE")
    void completedTournamentReturnsComplete() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("COMPLETED")));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_TOURNAMENT_COMPLETE);
        assertThat(result.lapNumber()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // AC4: PHASE_TRANSITION
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC4: active tournament, COMPLETED phase + PENDING phase → PHASE_TRANSITION")
    void phaseGapReturnsPhaseTransition() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase completed = phase(Phase.PhaseStatus.COMPLETED.name(), 0);
        Phase pending   = phase(Phase.PhaseStatus.PENDING.name(), 0);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Arrays.asList(completed, pending));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_PHASE_TRANSITION);
    }

    @Test
    @DisplayName("AC4/AC5: active tournament, all phases COMPLETED → TOURNAMENT_COMPLETE (defensive)")
    void allPhasesCompletedReturnsComplete() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase completed = phase(Phase.PhaseStatus.COMPLETED.name(), 5);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Collections.singletonList(completed));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_TOURNAMENT_COMPLETE);
    }

    @Test
    @DisplayName("AC6: active tournament, all phases PENDING → TOURNAMENT_NOT_ACTIVE")
    void allPhasesPendingReturnsNotActive() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase pending = phase(Phase.PhaseStatus.PENDING.name(), 0);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Collections.singletonList(pending));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_TOURNAMENT_NOT_ACTIVE);
    }

    // -------------------------------------------------------------------------
    // AC3: NO_MATCH_ON_FIELD
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC3: active phase, no matches on this field → NO_MATCH_ON_FIELD")
    void noMatchesOnFieldReturnsNoMatch() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase activePhase = phase(Phase.PhaseStatus.ACTIVE.name(), LAP_NUMBER);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Collections.singletonList(activePhase));

        when(matchRepository.findByFieldNumberAndLapNumber(FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(Collections.emptyList());

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_NO_MATCH_ON_FIELD);
        assertThat(result.lapNumber()).isEqualTo(LAP_NUMBER);
        assertThat(result.fieldNumber()).isEqualTo(FIELD_NUMBER);
    }

    // -------------------------------------------------------------------------
    // AC1: WAITING_FOR_LAP
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC1: active phase, all matches on field are terminal → WAITING_FOR_LAP")
    void allMatchesTerminalReturnsWaitingForLap() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase activePhase = phase(Phase.PhaseStatus.ACTIVE.name(), LAP_NUMBER);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Collections.singletonList(activePhase));

        Match terminalMatch = match(MatchState.FINISHED_WINNER1.getLegacyCode());
        when(matchRepository.findByFieldNumberAndLapNumber(FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(Collections.singletonList(terminalMatch));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_WAITING_FOR_LAP);
        assertThat(result.lapNumber()).isEqualTo(LAP_NUMBER);
    }

    // -------------------------------------------------------------------------
    // AC2: ACTIVE_MATCH
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AC2: active phase, non-terminal match on field → ACTIVE_MATCH")
    void nonTerminalMatchReturnsActiveMatch() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase activePhase = phase(Phase.PhaseStatus.ACTIVE.name(), LAP_NUMBER);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Collections.singletonList(activePhase));

        Match openMatch = match(MatchState.OPEN.getLegacyCode());
        when(matchRepository.findByFieldNumberAndLapNumber(FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(Collections.singletonList(openMatch));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_ACTIVE_MATCH);
        assertThat(result.lapNumber()).isEqualTo(LAP_NUMBER);
    }

    @Test
    @DisplayName("AC2: INPROGRESS match counts as non-terminal → ACTIVE_MATCH")
    void inProgressMatchReturnsActiveMatch() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase activePhase = phase(Phase.PhaseStatus.ACTIVE.name(), LAP_NUMBER);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Collections.singletonList(activePhase));

        Match inProgressMatch = match(MatchState.INPROGRESS.getLegacyCode());
        when(matchRepository.findByFieldNumberAndLapNumber(FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(Collections.singletonList(inProgressMatch));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_ACTIVE_MATCH);
    }

    @Test
    @DisplayName("AC1+AC2: mixed terminal and non-terminal matches → ACTIVE_MATCH")
    void mixedMatchesOneNonTerminalReturnsActiveMatch() {
        givenValidDevice();
        when(tournamentRepository.findAll()).thenReturn(
                Collections.singletonList(tournament("ACTIVE")));

        Phase activePhase = phase(Phase.PhaseStatus.ACTIVE.name(), LAP_NUMBER);
        when(phaseRepository.findByTournamentId(tournamentId))
                .thenReturn(Collections.singletonList(activePhase));

        Match terminalMatch    = match(MatchState.FINISHED_WINNER2.getLegacyCode());
        Match nonTerminalMatch = match(MatchState.ENABLED.getLegacyCode());
        when(matchRepository.findByFieldNumberAndLapNumber(FIELD_NUMBER, LAP_NUMBER))
                .thenReturn(Arrays.asList(terminalMatch, nonTerminalMatch));

        TabletStatusResponse result = service.getTabletStatus(FIELD_NUMBER, VALID_TOKEN);

        assertThat(result.state()).isEqualTo(TabletStatusResponse.STATE_ACTIVE_MATCH);
    }
}
