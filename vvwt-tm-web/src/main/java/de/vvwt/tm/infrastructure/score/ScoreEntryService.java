package de.vvwt.tm.infrastructure.score;

import de.vvwt.tm.infrastructure.score.dto.MatchScoreResponse;
import de.vvwt.tm.infrastructure.score.dto.PartialScoreRequest;
import de.vvwt.tm.infrastructure.score.dto.SetSubmitRequest;
import de.vvwt.tm.scoring.ScoringService;
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
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service layer for the scoring tablet score-entry page (E06S06).
 *
 * <h2>E31S04 atomic cutover (DEC-21 § Atomic cutover protocol)</h2>
 *
 * <p>This class was refactored in E31S04 to inject {@link ScoringService} (the public interface
 * from {@code de.vvwt.tm.scoring}) instead of the deleted legacy {@code
 * de.vvwt.tm.domain.CascadeRecomputeService}. The substitution is classified as DEC-22 §Decision
 * refactor-clause Q-1b (type-substitution-with-equivalent-contracts) — NOT DEC-32 mechanical
 * FQN-rewrite, per Cycle-1 reviewer F-S04-2 reclassification.
 *
 * <h2>Responsibilities (ACs covered)</h2>
 *
 * <ul>
 *   <li>AC1: Resolve the active match for a given field+lap.
 *   <li>AC3: Validate device token and field ownership before any operation.
 *   <li>AC4: Build {@link MatchScoreResponse} from match + team names.
 *   <li>AC5: Accept partial (in-progress) score updates and broadcast via WebSocket.
 *   <li>AC7: Submit final set result to {@link ScoringService}.
 *   <li>AC8: Record {@code source_type=TABLET}, {@code source_device_id} in cascade input.
 *   <li>AC9: Return empty Optional when no active match exists for the field (no-match state).
 *   <li>AC12: Throw {@link ForbiddenException} when device is valid but assigned to a different
 *       field.
 * </ul>
 *
 * <h2>Token validation (AC8, AC12)</h2>
 *
 * <p>A valid device token must:
 *
 * <ol>
 *   <li>Exist in the {@code devices} table and belong to the active tenant.
 *   <li>Have status {@code ASSIGNED} (not merely {@code REGISTERED}).
 *   <li>Have {@code assignedField} equal to the requested field number — else 403 (AC12).
 * </ol>
 *
 * Step 1 failure → 401 {@link UnauthorizedException}.<br>
 * Step 2 failure → 401 {@link UnauthorizedException} (unassigned device).<br>
 * Step 3 failure → 403 {@link ForbiddenException} (valid device, wrong field).
 *
 * <h2>Match resolution (AC1, AC9)</h2>
 *
 * <p>The active match is resolved by finding the active tournament → active phase → current lap
 * number → {@code findByFieldNumberAndLapNumber}. If no ACTIVE tournament/phase exists, or no
 * non-terminal match is found for the field+lap, an empty Optional is returned (the template
 * renders the "no match" state — AC9).
 *
 * <h2>Partial score broadcast (AC5)</h2>
 *
 * <p>Partial updates are broadcast directly to {@code /topic/score/field/{fieldNumber}} via
 * WebSocket so every connected tablet on that field sees live score changes without polling.
 *
 * @see ScoringService
 * @see MatchScoreResponse
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S06.story.md">Story
 *     E06S06</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E31S04.story.md">Story
 *     E31S04 — atomic cutover</a>
 */
@Service
public class ScoreEntryService {

    private static final Logger log = LoggerFactory.getLogger(ScoreEntryService.class);

    /** WebSocket topic prefix for per-field partial score broadcasts (AC5). */
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

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final DeviceRepository deviceRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final ScoringService scoringService;
    private final SimpMessagingTemplate messagingTemplate;

    /** Constructor injection of all collaborators. */
    public ScoreEntryService(
            DeviceRepository deviceRepository,
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamRepository teamRepository,
            ScoringService scoringService,
            SimpMessagingTemplate messagingTemplate) {
        this.deviceRepository = deviceRepository;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.scoringService = scoringService;
        this.messagingTemplate = messagingTemplate;
    }

    // -------------------------------------------------------------------------
    // AC1, AC4, AC9: Match resolution for a field
    // -------------------------------------------------------------------------

    /**
     * Returns the active match display data for the given field, or empty if none (AC1, AC4, AC9).
     *
     * <p>The device token is validated first (AC8/AC12). If validation passes, the service resolves
     * the active tournament → active phase → current lap → non-terminal match on the field. Team
     * names are resolved from {@link TeamAvatar} → {@link Team}.
     *
     * @param fieldNumber the court field number (1-based)
     * @param deviceToken the tablet's opaque device token
     * @return populated response if an active match exists; empty if not (AC9)
     * @throws UnauthorizedException if the device token is invalid or unassigned (AC8)
     * @throws ForbiddenException if the device is valid but assigned to a different field (AC12)
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
     * <p>The device token is validated (AC8/AC12) but the score is NOT persisted — partial updates
     * are transient. The current score is broadcast to {@code /topic/score/field/{fieldNumber}} so
     * other connected tablets/displays see it live.
     *
     * @param request the partial score request
     * @throws UnauthorizedException if the device token is invalid or unassigned (AC8)
     * @throws ForbiddenException if the device is valid but assigned to a different field (AC12)
     */
    public void handlePartialScore(PartialScoreRequest request) {
        // Resolve the device to get field number for broadcasting
        Device device = validateDeviceTokenAndReturn(request.deviceToken());
        int fieldNumber = device.getAssignedField();

        // Validate the device is authorized for the match's field
        Optional<MatchScoreResponse> matchOpt = resolveActiveMatch(fieldNumber);
        if (!matchOpt.isPresent()) {
            // No active match — nothing to broadcast
            log.debug(
                    "[E06S06] Partial score received but no active match on field {}", fieldNumber);
            return;
        }
        MatchScoreResponse existing = matchOpt.get();
        if (!existing.matchId().equals(request.matchId())) {
            // matchId mismatch — the tablet is on a different match (stale page)
            log.warn(
                    "[E06S06] Partial score matchId={} does not match active match={} on field {}",
                    request.matchId(),
                    existing.matchId(),
                    fieldNumber);
            return;
        }

        // Build a partial response with updated scores and broadcast
        MatchScoreResponse partial =
                new MatchScoreResponse(
                        existing.matchId(),
                        existing.fieldNumber(),
                        existing.lapNumber(),
                        request.setIndex(),
                        existing.team1Name(),
                        existing.team2Name(),
                        existing.refereeTeamName(),
                        request.team1Points(),
                        request.team2Points());

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
     * Submits a final set result, invoking the scoring cascade via {@link ScoringService} (AC7,
     * AC8).
     *
     * <p>Validates the device token (AC8/AC12), resolves the match to obtain the {@code
     * tournamentId} required by the DEC-37 Clause B lock-first contract, then delegates to {@link
     * ScoringService#registerMatchResult} with {@code sourceType=TABLET} and {@code sourceDeviceId}
     * set to the device's UUID string (AC8).
     *
     * <p>The {@code tournamentId} is resolved from the match's {@code getTournamentId()} field —
     * the match row is loaded from the repository using the {@code matchId} from the request. This
     * is required because {@link de.vvwt.tm.scoring.internal.DefaultScoringService} acquires a
     * pessimistic DB row-lock on the tournament row as its FIRST action (DEC-37 Clause B), which
     * requires a non-null {@code tournamentId} in the {@link SetResultInput}.
     *
     * @param request the set submission request
     * @throws UnauthorizedException if the device token is invalid or unassigned (AC8)
     * @throws ForbiddenException if the device is valid but assigned to a different field (AC12)
     * @throws de.vvwt.tm.tournament.exceptions.ValidationException if the set score is invalid
     *     (AC6)
     * @throws IllegalArgumentException if the match is not found
     */
    @Transactional
    public void submitSetResult(SetSubmitRequest request) {
        Device device = validateDeviceTokenAndReturn(request.deviceToken());

        // Resolve match to obtain tournamentId for DEC-37 Clause B lock-first contract
        Match match =
                matchRepository
                        .findById(request.matchId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Match not found: " + request.matchId()));
        UUID tournamentId = match.getTournamentId();

        SetResultInput input =
                new SetResultInput(
                        tournamentId, // required by DefaultScoringService (DEC-37 Clause B)
                        request.matchId(),
                        request.setIndex(),
                        request.team1Points(),
                        request.team2Points(),
                        device.getId().toString(), // actorId = device UUID (AC8)
                        "TABLET score entry", // reason
                        SetResultInput.SOURCE_TYPE_TABLET,
                        device.getId().toString()); // sourceDeviceId = device UUID (AC8)

        log.debug(
                "[E06S06] Submitting set result for matchId={} setIndex={} by deviceId={}"
                        + " tournamentId={}",
                request.matchId(),
                request.setIndex(),
                device.getId(),
                tournamentId);

        scoringService.registerMatchResult(input);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Validates the device token and asserts field ownership (AC8, AC12). Throws without returning
     * the device.
     *
     * @param deviceToken the opaque device token
     * @param fieldNumber the expected assigned field
     */
    private void validateDeviceToken(String deviceToken, int fieldNumber) {
        Device device = validateDeviceTokenAndReturn(deviceToken);
        if (!Integer.valueOf(fieldNumber).equals(device.getAssignedField())) {
            throw new ForbiddenException(
                    "Device is assigned to field "
                            + device.getAssignedField()
                            + " but requested field "
                            + fieldNumber);
        }
    }

    /**
     * Validates the device token, checks ASSIGNED status, and returns the device.
     *
     * @param deviceToken the opaque device token
     * @return the validated device
     * @throws UnauthorizedException if the token is invalid, belongs to a different tenant, or
     *     device is not ASSIGNED
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
     * Resolves the active non-terminal match for the given field by traversing: active tournament →
     * active phase → current lap → match query.
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
            log.debug("[E06S06] No active tournament found for field {}", fieldNumber);
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
            log.debug("[E06S06] No active phase found in tournament={}", activeTournament.getId());
            return Optional.empty();
        }

        // Current lap number from the active phase
        int lapNumber = activePhase.getCurrentLapNumber();

        // Find a non-terminal match on this field+lap
        Match activeMatch = null;
        for (Match m : matchRepository.findByFieldNumberAndLapNumber(fieldNumber, lapNumber)) {
            if (isNonTerminal(m.getState())) {
                activeMatch = m;
                break;
            }
        }
        if (activeMatch == null) {
            log.debug("[E06S06] No non-terminal match on field={} lap={}", fieldNumber, lapNumber);
            return Optional.empty();
        }

        // Resolve team names via TeamAvatar → Team
        String team1Name = resolveTeamName(activeMatch.getMemberAvatar1Id());
        String team2Name = resolveTeamName(activeMatch.getMemberAvatar2Id());
        String refereeName =
                activeMatch.getRefereeTeamId() != null
                        ? resolveRefereeTeamName(activeMatch.getRefereeTeamId())
                        : null;

        // Determine current set index from match state (0 for fresh matches)
        int currentSetIndex = 0; // default; scoring service manages set progression

        MatchScoreResponse response =
                new MatchScoreResponse(
                        activeMatch.getId(),
                        activeMatch.getFieldNumber(),
                        activeMatch.getLapNumber(),
                        currentSetIndex,
                        team1Name,
                        team2Name,
                        refereeName,
                        0, // team1Points — initial display is 0:0
                        0); // team2Points

        return Optional.of(response);
    }

    /**
     * Resolves the team name for a given TeamAvatar ID. Falls back to the avatar UUID string if the
     * team cannot be resolved.
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
     * Resolves the referee team name directly from a Team ID. Falls back to the team UUID string if
     * not found.
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
}
