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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service that determines the current idle/active state of a scoring tablet (E06S08).
 *
 * <h2>Responsibilities (ACs covered)</h2>
 * <ul>
 *   <li>AC1: Detect that the current lap's matches are all terminal → WAITING_FOR_LAP</li>
 *   <li>AC2: Detect non-terminal match → ACTIVE_MATCH (client calls /api/score/match)</li>
 *   <li>AC3: Detect no match scheduled on this field for the current lap → NO_MATCH_ON_FIELD</li>
 *   <li>AC4: Detect phase gap (COMPLETED → PENDING) → PHASE_TRANSITION</li>
 *   <li>AC5: Detect tournament COMPLETED → TOURNAMENT_COMPLETE</li>
 *   <li>AC6: Detect no active tournament (DRAFT/CANCELLED/absent) → TOURNAMENT_NOT_ACTIVE</li>
 *   <li>AC9: Validate device token on every call; invalid/unassigned → UnauthorizedException</li>
 * </ul>
 *
 * <h2>State resolution algorithm</h2>
 * <ol>
 *   <li>Validate device token (AC9)</li>
 *   <li>Find active/completed tournament:
 *       <ul>
 *         <li>None or DRAFT/CANCELLED → TOURNAMENT_NOT_ACTIVE</li>
 *         <li>COMPLETED → TOURNAMENT_COMPLETE</li>
 *       </ul></li>
 *   <li>Inspect phases for the ACTIVE tournament:
 *       <ul>
 *         <li>No ACTIVE phase, has PENDING phase → PHASE_TRANSITION</li>
 *         <li>No ACTIVE phase, no PENDING → TOURNAMENT_COMPLETE (defensive)</li>
 *         <li>No ACTIVE phase, no COMPLETED (all PENDING) → TOURNAMENT_NOT_ACTIVE</li>
 *       </ul></li>
 *   <li>ACTIVE phase found → inspect matches for field+lap:
 *       <ul>
 *         <li>No matches on field → NO_MATCH_ON_FIELD</li>
 *         <li>All matches terminal → WAITING_FOR_LAP</li>
 *         <li>At least one non-terminal → ACTIVE_MATCH</li>
 *       </ul></li>
 * </ol>
 *
 * @see TabletStatusResponse
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S08.story.md">Story E06S08</a>
 */
@Service
public class TabletStatusService {

    private static final Logger log = LoggerFactory.getLogger(TabletStatusService.class);

    /** Match states that allow further scoring (non-terminal). Mirrors ScoreEntryService. */
    private static final int[] NON_TERMINAL_STATES = {
            MatchState.OPEN.getLegacyCode(),
            MatchState.ENABLED.getLegacyCode(),
            MatchState.INPROGRESS.getLegacyCode(),
            MatchState.ONCHECK.getLegacyCode()
    };

    private final DeviceRepository    deviceRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository     phaseRepository;
    private final MatchRepository     matchRepository;

    /**
     * Constructor injection of all collaborators.
     */
    public TabletStatusService(DeviceRepository deviceRepository,
                               TournamentRepository tournamentRepository,
                               PhaseRepository phaseRepository,
                               MatchRepository matchRepository) {
        this.deviceRepository    = deviceRepository;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository     = phaseRepository;
        this.matchRepository     = matchRepository;
    }

    // -------------------------------------------------------------------------
    // Primary API
    // -------------------------------------------------------------------------

    /**
     * Returns the current tablet status for the given field and device token (AC1–AC6, AC9).
     *
     * <p>The device token is validated first (AC9). An invalid or unassigned token throws
     * {@link UnauthorizedException}, which the client uses to redirect to {@code /score/register}.
     *
     * @param fieldNumber the court field number (1-based)
     * @param deviceToken the tablet's opaque device token
     * @return populated {@link TabletStatusResponse} — never {@code null}
     * @throws UnauthorizedException if the device token is invalid or the device is not ASSIGNED (AC9)
     */
    @Transactional(readOnly = true)
    public TabletStatusResponse getTabletStatus(int fieldNumber, String deviceToken) {
        validateDevice(deviceToken);
        return resolveStatus(fieldNumber);
    }

    // -------------------------------------------------------------------------
    // Device validation (AC9)
    // -------------------------------------------------------------------------

    /**
     * Validates that the device token belongs to an ASSIGNED device.
     *
     * @param deviceToken the opaque token from the tablet's localStorage/cookie
     * @throws UnauthorizedException if token is unknown, unassigned, or has no assigned field (AC9)
     */
    private void validateDevice(String deviceToken) {
        Optional<Device> deviceOpt = deviceRepository.findByDeviceToken(deviceToken);
        if (!deviceOpt.isPresent()) {
            throw new UnauthorizedException("Invalid or unknown device token");
        }
        Device device = deviceOpt.get();
        if (!Device.STATUS_ASSIGNED.equals(device.getStatus())) {
            throw new UnauthorizedException("Device is not assigned to a field");
        }
        if (device.getAssignedField() == null) {
            throw new UnauthorizedException("Device has no assigned field");
        }
    }

    // -------------------------------------------------------------------------
    // Status resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves the tablet state by inspecting the tournament, phase, and match hierarchy.
     *
     * @param fieldNumber the court field number
     * @return resolved tablet status
     */
    private TabletStatusResponse resolveStatus(int fieldNumber) {

        // Step 1: Find any tournament that is ACTIVE or COMPLETED
        Tournament activeTournament = null;
        boolean completedTournamentFound = false;

        for (Tournament t : tournamentRepository.findAll()) {
            String s = t.getStatus();
            if ("ACTIVE".equals(s)) {
                activeTournament = t;
                break;
            }
            if ("COMPLETED".equals(s)) {
                completedTournamentFound = true;
            }
        }

        // No active tournament
        if (activeTournament == null) {
            if (completedTournamentFound) {
                log.debug("[E06S08] No ACTIVE tournament; COMPLETED tournament found. field={}", fieldNumber);
                return new TabletStatusResponse(
                        TabletStatusResponse.STATE_TOURNAMENT_COMPLETE, 0, fieldNumber);
            }
            log.debug("[E06S08] No active or completed tournament. field={}", fieldNumber);
            return new TabletStatusResponse(
                    TabletStatusResponse.STATE_TOURNAMENT_NOT_ACTIVE, 0, fieldNumber);
        }

        // Step 2: Inspect phases for the active tournament
        List<Phase> phases = new ArrayList<>();
        for (Phase p : phaseRepository.findByTournamentId(activeTournament.getId())) {
            phases.add(p);
        }

        Phase activePhase = null;
        boolean hasCompletedPhase = false;
        boolean hasPendingPhase   = false;

        for (Phase p : phases) {
            String ps = p.getStatus();
            if (Phase.PhaseStatus.ACTIVE.name().equals(ps)) {
                activePhase = p;
            } else if (Phase.PhaseStatus.COMPLETED.name().equals(ps)) {
                hasCompletedPhase = true;
            } else if (Phase.PhaseStatus.PENDING.name().equals(ps)) {
                hasPendingPhase = true;
            }
        }

        if (activePhase == null) {
            // No ACTIVE phase — classify by which phases exist
            if (hasCompletedPhase && hasPendingPhase) {
                log.debug("[E06S08] Phase gap: COMPLETED + PENDING phases present. field={}", fieldNumber);
                return new TabletStatusResponse(
                        TabletStatusResponse.STATE_PHASE_TRANSITION, 0, fieldNumber);
            }
            if (hasCompletedPhase) {
                // All phases completed → tournament should be COMPLETED but is still ACTIVE (race)
                log.debug("[E06S08] All phases COMPLETED; tournament still ACTIVE (race). field={}", fieldNumber);
                return new TabletStatusResponse(
                        TabletStatusResponse.STATE_TOURNAMENT_COMPLETE, 0, fieldNumber);
            }
            // All phases PENDING → tournament active but not started yet
            log.debug("[E06S08] All phases PENDING; tournament active but not started. field={}", fieldNumber);
            return new TabletStatusResponse(
                    TabletStatusResponse.STATE_TOURNAMENT_NOT_ACTIVE, 0, fieldNumber);
        }

        // Step 3: Inspect matches for this field in the current lap
        int lapNumber = activePhase.getCurrentLapNumber();
        List<Match> fieldMatches = new ArrayList<>();
        for (Match m : matchRepository.findByFieldNumberAndLapNumber(fieldNumber, lapNumber)) {
            fieldMatches.add(m);
        }

        if (fieldMatches.isEmpty()) {
            log.debug("[E06S08] No matches on field={} for lap={}", fieldNumber, lapNumber);
            return new TabletStatusResponse(
                    TabletStatusResponse.STATE_NO_MATCH_ON_FIELD, lapNumber, fieldNumber);
        }

        // Check for any non-terminal match
        for (Match m : fieldMatches) {
            if (isNonTerminal(m.getState())) {
                log.debug("[E06S08] Non-terminal match found on field={} lap={}", fieldNumber, lapNumber);
                return new TabletStatusResponse(
                        TabletStatusResponse.STATE_ACTIVE_MATCH, lapNumber, fieldNumber);
            }
        }

        // All matches terminal → lap complete for this field
        log.debug("[E06S08] All matches terminal on field={} lap={}", fieldNumber, lapNumber);
        return new TabletStatusResponse(
                TabletStatusResponse.STATE_WAITING_FOR_LAP, lapNumber, fieldNumber);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the match state code is non-terminal (scoring is still possible).
     *
     * @param stateCode the legacy integer state code from {@link Match#getState()}
     * @return true if the match can still be scored
     */
    private static boolean isNonTerminal(int stateCode) {
        for (int code : NON_TERMINAL_STATES) {
            if (code == stateCode) {
                return true;
            }
        }
        return false;
    }
}
