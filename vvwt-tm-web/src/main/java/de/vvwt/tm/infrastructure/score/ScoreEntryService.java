package de.vvwt.tm.infrastructure.score;

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
import de.vvwt.tm.domain.CascadeRecomputeService;
import de.vvwt.tm.domain.UnauthorizedException;
import de.vvwt.tm.domain.repo.DeviceRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.score.dto.MatchScoreResponse;
import de.vvwt.tm.infrastructure.score.dto.PartialScoreRequest;
import de.vvwt.tm.infrastructure.score.dto.SetSubmitRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service layer for the scoring tablet score-entry page (E06S06, E06S07).
 *
 * <h2>Responsibilities (ACs covered)</h2>
 * <ul>
 *   <li>AC1 (E06S06): Resolve the active match for a given field+lap.</li>
 *   <li>AC3 (E06S06): Validate device token and field ownership before any operation.</li>
 *   <li>AC4 (E06S06): Build {@link MatchScoreResponse} from match + team names.</li>
 *   <li>AC5 (E06S06): Accept partial (in-progress) score updates and broadcast via WebSocket.</li>
 *   <li>AC7 (E06S06): Submit final set result to {@link CascadeRecomputeService}.</li>
 *   <li>AC8 (E06S06): Record {@code source_type=TABLET}, {@code source_device_id} in cascade input.</li>
 *   <li>AC9 (E06S06): Return empty Optional when no active match exists for the field (no-match state).</li>
 *   <li>AC12 (E06S06): Throw {@link ForbiddenException} when device is valid but assigned to a different field.</li>
 *   <li>AC2 (E06S07): Populate {@link MatchScoreResponse} with multi-set progression fields
 *       (team1SetsWon, team2SetsWon, matchDecided, matchWinner, currentSetIndex).</li>
 *   <li>AC5 (E06S07): Include tiebreakSwapThreshold from {@link ScoringConfig} in response.</li>
 *   <li>AC8 (E06S07): Include matchFormat and maxSets for set counter display.</li>
 *   <li>AC10 (E06S07): Full match state in response enables client-side state restoration on refresh.</li>
 * </ul>
 *
 * <h2>Token validation (AC8/AC12 E06S06)</h2>
 * <p>A valid device token must:
 * <ol>
 *   <li>Exist in the {@code devices} table and belong to the active tenant.</li>
 *   <li>Have status {@code ASSIGNED} (not merely {@code REGISTERED}).</li>
 *   <li>Have {@code assignedField} equal to the requested field number — else 403 (AC12).</li>
 * </ol>
 * Step 1 failure → 401 {@link UnauthorizedException}.<br>
 * Step 2 failure → 401 {@link UnauthorizedException} (unassigned device).<br>
 * Step 3 failure → 403 {@link ForbiddenException} (valid device, wrong field).
 *
 * <h2>Match resolution (AC1, AC9 E06S06)</h2>
 * <p>The active match is resolved by finding the active tournament → active phase →
 * current lap number → {@code findByFieldNumberAndLapNumber}. If no ACTIVE tournament/phase
 * exists, or no non-terminal match is found for the field+lap, an empty Optional is returned
 * (the template renders the "no match" state — AC9). For E06S07, the response also includes
 * set-won counts aggregated from persisted {@link SetResult} rows.
 *
 * <h2>Partial score broadcast (AC5 E06S06)</h2>
 * <p>Partial updates are broadcast directly to {@code /topic/score/field/{fieldNumber}}
 * via WebSocket so every connected tablet on that field sees live score changes without polling.
 *
 * @see CascadeRecomputeService
 * @see MatchScoreResponse
 * @see ScoringConfig
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S06.story.md">Story E06S06</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S07.story.md">Story E06S07</a>
 */
@Service
public class ScoreEntryService {

    private static final Logger log = LoggerFactory.getLogger(ScoreEntryService.class);

    /** WebSocket topic prefix for per-field partial score broadcasts (AC5 E06S06). */
    static final String SCORE_TOPIC_PREFIX = "/topic/score/field/";

    // -------------------------------------------------------------------------
    // Non-terminal match states (matches eligible to be scored)
    // -------------------------------------------------------------------------

    /** Match states where scoring is allowed (not yet finished or cancelled). */
    private static final int[] ACTIVE_STATES = {
            MatchState.OPEN.getLegacyCode(),
            MatchState.ENABLED.getLegacyCode(),
            MatchState.INPROGRESS.getLegacyCode(),
            MatchState.ONCHECK.getLegacyCode()
    };

    /** Terminal match state codes used to determine matchDecided (E06S07 AC2). */
    private static final int[] TERMINAL_STATES = {
            MatchState.FINISHED_WINNER1.getLegacyCode(),
            MatchState.FINISHED_WINNER2.getLegacyCode(),
            MatchState.FINISHED_STANDOFF.getLegacyCode()
    };

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final DeviceRepository deviceRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final SetResultRepository setResultRepository;
    private final CascadeRecomputeService cascadeRecomputeService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ScoringConfig scoringConfig;

    /**
     * Constructor injection of all collaborators.
     */
    public ScoreEntryService(DeviceRepository deviceRepository,
                             TournamentRepository tournamentRepository,
                             PhaseRepository phaseRepository,
                             MatchRepository matchRepository,
                             TeamAvatarRepository teamAvatarRepository,
                             TeamRepository teamRepository,
                             SetResultRepository setResultRepository,
                             CascadeRecomputeService cascadeRecomputeService,
                             SimpMessagingTemplate messagingTemplate,
                             ScoringConfig scoringConfig) {
        this.deviceRepository = deviceRepository;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.setResultRepository = setResultRepository;
        this.cascadeRecomputeService = cascadeRecomputeService;
        this.messagingTemplate = messagingTemplate;
        this.scoringConfig = scoringConfig;
    }

    // -------------------------------------------------------------------------
    // AC1, AC4, AC9: Match resolution for a field
    // -------------------------------------------------------------------------

    /**
     * Returns the active match display data for the given field, or empty if none (AC1, AC4, AC9).
     *
     * <p>The device token is validated first (AC8/AC12). If validation passes, the service
     * resolves the active tournament → active phase → current lap → non-terminal match on
     * the field. Team names are resolved from {@link TeamAvatar} → {@link Team}.
     *
     * @param fieldNumber the court field number (1-based)
     * @param deviceToken the tablet's opaque device token
     * @return populated response if an active match exists; empty if not (AC9)
     * @throws UnauthorizedException if the device token is invalid or unassigned (AC8)
     * @throws ForbiddenException    if the device is valid but assigned to a different field (AC12)
     */
    @Transactional(readOnly = true)
    public Optional<MatchScoreResponse> getMatchForField(int fieldNumber, String deviceToken) {
        validateDeviceToken(deviceToken, fieldNumber);
        return resolveActiveMatch(fieldNumber);
    }

    // -------------------------------------------------------------------------
    // AC5: Partial score broadcast
    // -------------------------------------------------------------------------

    /**
     * Accepts a partial (in-progress) score update and broadcasts it via WebSocket (AC5).
     *
     * <p>The device token is validated (AC8/AC12) but the score is NOT persisted — partial
     * updates are transient. The current score is broadcast to
     * {@code /topic/score/field/{fieldNumber}} so other connected tablets/displays see it live.
     *
     * @param request the partial score request
     * @throws UnauthorizedException if the device token is invalid or unassigned (AC8)
     * @throws ForbiddenException    if the device is valid but assigned to a different field (AC12)
     */
    public void handlePartialScore(PartialScoreRequest request) {
        // Resolve the device to get field number for broadcasting
        Device device = validateDeviceTokenAndReturn(request.deviceToken());
        int fieldNumber = device.getAssignedField();

        // Validate the device is authorized for the match's field
        Optional<MatchScoreResponse> matchOpt = resolveActiveMatch(fieldNumber);
        if (!matchOpt.isPresent()) {
            // No active match — nothing to broadcast
            log.debug("[E06S06] Partial score received but no active match on field {}", fieldNumber);
            return;
        }
        MatchScoreResponse existing = matchOpt.get();
        if (!existing.matchId().equals(request.matchId())) {
            // matchId mismatch — the tablet is on a different match (stale page)
            log.warn("[E06S06] Partial score matchId={} does not match active match={} on field {}",
                    request.matchId(), existing.matchId(), fieldNumber);
            return;
        }

        // Build a partial response with updated scores and broadcast
        // Carry over all E06S07 multi-set fields from the existing response (they don't change on partial)
        MatchScoreResponse partial = new MatchScoreResponse(
                existing.matchId(),
                existing.fieldNumber(),
                existing.lapNumber(),
                request.setIndex(),
                existing.team1Name(),
                existing.team2Name(),
                existing.refereeTeamName(),
                request.team1Points(),
                request.team2Points(),
                existing.matchFormat(),
                existing.maxSets(),
                existing.tiebreakSwapThreshold(),
                existing.team1SetsWon(),
                existing.team2SetsWon(),
                existing.matchDecided(),
                existing.matchWinner());

        String topic = SCORE_TOPIC_PREFIX + fieldNumber;
        try {
            messagingTemplate.convertAndSend(topic, partial);
        } catch (Exception e) {
            log.warn("[E06S06] Failed to broadcast partial score to {}: {}", topic, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // AC7, AC8: Set submission
    // -------------------------------------------------------------------------

    /**
     * Submits a final set result, invoking the cascade recompute (AC7, AC8).
     *
     * <p>Validates the device token (AC8/AC12), then delegates to
     * {@link CascadeRecomputeService#registerMatchResult} with {@code sourceType=TABLET}
     * and {@code sourceDeviceId} set to the device's UUID string (AC8).
     *
     * @param request the set submission request
     * @throws UnauthorizedException if the device token is invalid or unassigned (AC8)
     * @throws ForbiddenException    if the device is valid but assigned to a different field (AC12)
     * @throws de.vvwt.tm.domain.ValidationException if the set score is invalid (AC6)
     */
    @Transactional
    public void submitSetResult(SetSubmitRequest request) {
        Device device = validateDeviceTokenAndReturn(request.deviceToken());

        SetResultInput input = new SetResultInput(
                request.matchId(),
                request.setIndex(),
                request.team1Points(),
                request.team2Points(),
                device.getId().toString(),   // actorId = device UUID (AC8)
                "TABLET score entry",        // reason
                SetResultInput.SOURCE_TYPE_TABLET,
                device.getId().toString());  // sourceDeviceId = device UUID (AC8)

        log.debug("[E06S06] Submitting set result for matchId={} setIndex={} by deviceId={}",
                request.matchId(), request.setIndex(), device.getId());

        cascadeRecomputeService.registerMatchResult(input);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Validates the device token and asserts field ownership (AC8, AC12).
     * Throws without returning the device.
     *
     * @param deviceToken the opaque device token
     * @param fieldNumber the expected assigned field
     */
    private void validateDeviceToken(String deviceToken, int fieldNumber) {
        Device device = validateDeviceTokenAndReturn(deviceToken);
        if (!Integer.valueOf(fieldNumber).equals(device.getAssignedField())) {
            throw new ForbiddenException(
                    "Device is assigned to field " + device.getAssignedField()
                    + " but requested field " + fieldNumber);
        }
    }

    /**
     * Validates the device token, checks ASSIGNED status, and returns the device.
     *
     * @param deviceToken the opaque device token
     * @return the validated device
     * @throws UnauthorizedException if the token is invalid, belongs to a different tenant, or device is not ASSIGNED
     */
    private Device validateDeviceTokenAndReturn(String deviceToken) {
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
        return device;
    }

    /**
     * Resolves the active non-terminal match for the given field by traversing:
     * active tournament → active phase → current lap → match query.
     *
     * <p>Extended for E06S07 to include multi-set state fields:
     * sets won by each team (from persisted SetResults), match format, maxSets,
     * tiebreak swap threshold, and match-decided flag.
     *
     * @param fieldNumber the court field number
     * @return populated response or empty if no match found
     */
    private Optional<MatchScoreResponse> resolveActiveMatch(int fieldNumber) {
        // Find active tournament (DEC-5: at most one per tenant)
        Tournament activeTournament = null;
        for (Tournament t : tournamentRepository.findAll()) {
            if ("ACTIVE".equals(t.getStatus())) {
                activeTournament = t;
                break;
            }
        }
        if (activeTournament == null) {
            log.debug("[E06S06/E06S07] No active tournament found for field {}", fieldNumber);
            return Optional.empty();
        }

        // Find active phase within the tournament
        Phase activePhase = null;
        for (Phase p : phaseRepository.findByTournamentId(activeTournament.getId())) {
            if (Phase.PhaseStatus.ACTIVE.name().equals(p.getStatus())) {
                activePhase = p;
                break;
            }
        }
        if (activePhase == null) {
            log.debug("[E06S06/E06S07] No active phase found in tournament={}", activeTournament.getId());
            return Optional.empty();
        }

        // Current lap number from the active phase
        int lapNumber = activePhase.getCurrentLapNumber();

        // Find a non-terminal OR terminal match on this field+lap.
        // We include terminal matches so that a page refresh after match completion still returns
        // the match data with matchDecided=true (enabling the client to render the result summary — AC10).
        Match activeMatch = null;
        for (Match m : matchRepository.findByFieldNumberAndLapNumber(fieldNumber, lapNumber)) {
            if (isNonTerminal(m.getState()) || isTerminal(m.getState())) {
                activeMatch = m;
                break;
            }
        }
        if (activeMatch == null) {
            log.debug("[E06S06/E06S07] No scoreable match on field={} lap={}", fieldNumber, lapNumber);
            return Optional.empty();
        }

        // Resolve team names via TeamAvatar → Team
        String team1Name = resolveTeamName(activeMatch.getMemberAvatar1Id());
        String team2Name = resolveTeamName(activeMatch.getMemberAvatar2Id());
        String refereeName = activeMatch.getRefereeTeamId() != null
                ? resolveRefereeTeamName(activeMatch.getRefereeTeamId())
                : null;

        // E06S07 AC2, AC8: resolve match format from tournament
        String matchFormatName = activeTournament.getMatchFormat() != null
                ? activeTournament.getMatchFormat() : MatchFormat.BEST_OF_1.name();
        MatchFormat matchFormat;
        try {
            matchFormat = MatchFormat.fromPersistedName(matchFormatName);
        } catch (IllegalArgumentException ex) {
            log.warn("[E06S07] Unknown matchFormat '{}' for tournament={}, defaulting to BEST_OF_1",
                    matchFormatName, activeTournament.getId());
            matchFormat = MatchFormat.BEST_OF_1;
        }

        // E06S07 AC2: count sets won from persisted SetResult rows
        List<SetResult> setResults = setResultRepository.findByMatchId(activeMatch.getId());
        int team1SetsWon = 0;
        int team2SetsWon = 0;
        int currentSetIndex = 0;
        int currentSetTeam1Points = 0;
        int currentSetTeam2Points = 0;

        for (SetResult sr : setResults) {
            SetState ss = sr.getSetState();
            if (ss == SetState.WINNER1) {
                team1SetsWon++;
            } else if (ss == SetState.WINNER2) {
                team2SetsWon++;
            } else if (ss == SetState.OPEN) {
                // In-progress set: track its index and current points for state restoration (AC10)
                if (sr.getSetIndex() >= currentSetIndex) {
                    currentSetIndex = sr.getSetIndex();
                    currentSetTeam1Points = sr.getTeam1Points();
                    currentSetTeam2Points = sr.getTeam2Points();
                }
            }
        }
        // If no OPEN set found but there are completed sets, the next set to play = total completed
        int completedSets = team1SetsWon + team2SetsWon;
        if (completedSets > 0 && setResults.stream().noneMatch(sr -> sr.getSetState() == SetState.OPEN)) {
            currentSetIndex = completedSets;
            currentSetTeam1Points = 0;
            currentSetTeam2Points = 0;
        }

        // E06S07 AC2, AC6: determine match-decided status from match state
        boolean matchDecided = isTerminal(activeMatch.getState());
        String matchWinner = null;
        if (matchDecided) {
            MatchState state = activeMatch.getMatchState();
            if (state == MatchState.FINISHED_WINNER1) {
                matchWinner = "TEAM1";
            } else if (state == MatchState.FINISHED_WINNER2) {
                matchWinner = "TEAM2";
            } else if (state == MatchState.FINISHED_STANDOFF) {
                matchWinner = "STANDOFF";
            }
        }

        log.debug("[E06S07] resolveActiveMatch field={} lap={} matchId={} format={} sets={}/{} decided={}",
                fieldNumber, lapNumber, activeMatch.getId(), matchFormat,
                team1SetsWon, team2SetsWon, matchDecided);

        MatchScoreResponse response = new MatchScoreResponse(
                activeMatch.getId(),
                activeMatch.getFieldNumber(),
                activeMatch.getLapNumber(),
                currentSetIndex,
                team1Name,
                team2Name,
                refereeName,
                currentSetTeam1Points,
                currentSetTeam2Points,
                // E06S07 multi-set fields
                matchFormat.name(),
                matchFormat.getMaxSets(),
                scoringConfig.getTiebreakSwapThreshold(),
                team1SetsWon,
                team2SetsWon,
                matchDecided,
                matchWinner);

        return Optional.of(response);
    }

    /**
     * Resolves the team name for a given TeamAvatar ID.
     * Falls back to the avatar UUID string if the team cannot be resolved.
     *
     * @param avatarId the TeamAvatar primary key
     * @return team description string; never null
     */
    private String resolveTeamName(UUID avatarId) {
        if (avatarId == null) {
            return "?";
        }
        Optional<TeamAvatar> avatarOpt = teamAvatarRepository.findById(avatarId);
        if (!avatarOpt.isPresent()) {
            return avatarId.toString();
        }
        UUID teamId = avatarOpt.get().getTeamId();
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        return teamOpt.map(Team::getDescription).orElse(avatarId.toString());
    }

    /**
     * Resolves the referee team name directly from a Team ID.
     * Falls back to the team UUID string if not found.
     *
     * @param refereeTeamId the Team primary key for the referee team
     * @return team description; never null
     */
    private String resolveRefereeTeamName(UUID refereeTeamId) {
        Optional<Team> teamOpt = teamRepository.findById(refereeTeamId);
        return teamOpt.map(Team::getDescription).orElse(refereeTeamId.toString());
    }

    /**
     * Returns {@code true} if the match state code is one of the non-terminal (active) states.
     *
     * @param stateCode the legacy integer state code
     * @return true if the match can still be scored
     */
    private static boolean isNonTerminal(int stateCode) {
        for (int code : ACTIVE_STATES) {
            if (code == stateCode) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the match state code is one of the terminal (finished) states.
     *
     * <p>Used by E06S07 to include terminal matches in the response so the tablet can render
     * the match result summary after match completion, even on page refresh (AC10).
     *
     * @param stateCode the legacy integer state code
     * @return true if the match is decided (FINISHED_WINNER1, FINISHED_WINNER2, FINISHED_STANDOFF)
     */
    private static boolean isTerminal(int stateCode) {
        for (int code : TERMINAL_STATES) {
            if (code == stateCode) {
                return true;
            }
        }
        return false;
    }
}
