package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.PartialScoreInput;
import de.vvwt.tm.scoring.ScoreEntryResult;
import de.vvwt.tm.scoring.ScoreEntryService;
import de.vvwt.tm.scoring.ScoringService;
import de.vvwt.tm.scoring.SetSubmitInput;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchFormat;
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
import de.vvwt.tm.tournament.exceptions.MatchCanceledException;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link ScoreEntryService} — TDD-reconstructed scoring-domain service
 * for device-token-authenticated score-entry tablet operations (E22S06, DEC-22
 * Reconstruction-in-Place).
 *
 * <p>Reconstructed from {@code de.vvwt.tm.infrastructure.score.ScoreEntryService} (legacy). The
 * legacy class remains functional until the E22S11 cutover (AC-LEGACY-UNTOUCHED).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 — implementation in {@code de.vvwt.tm.scoring.internal}; interface {@link
 *       ScoreEntryService} in {@code de.vvwt.tm.scoring} (public package); named {@code
 *       Default*Service} per naming canon
 *   <li>DEC-36 — imports ONLY from public interfaces; no {@code tournament.internal.*} or {@code
 *       infrastructure.*} imports (AC-IMPORT-BOUNDARY)
 *   <li>DEC-37 Clause B — {@link #submitSetResult(SetSubmitInput)} delegates to {@link
 *       ScoringService#registerMatchResult} which acquires the per-tournament DB lock; this
 *       implementation MUST NOT call {@code TournamentRepository.findByIdForUpdate} directly
 *       (AC-CASCADE-DELEGATION-PRESERVED)
 *   <li>DEC-22 — TDD Iron Law: all methods were preceded by failing RED tests before the first
 *       implementation line was authored (RED commit SHA: 8f28922)
 * </ul>
 *
 * <h2>Security contract (AC-SECURITY-DEVICE-TOKEN)</h2>
 *
 * <p>Every public method validates the {@code deviceToken} (either as direct parameter or from the
 * request record) BEFORE any business logic runs. Validation sequence:
 *
 * <ol>
 *   <li>Lookup device via {@link DeviceRepository#findByDeviceToken(String)} (tenant-scoped). Token
 *       not found → {@link UnauthorizedException}.
 *   <li>Device status must be {@code ASSIGNED}. Status {@code REGISTERED} / {@code DISCONNECTED} →
 *       {@link UnauthorizedException}.
 *   <li>Device's {@code assignedField} must be non-null. Null → {@link UnauthorizedException}.
 * </ol>
 *
 * <p>For {@link #getMatchForField(int, String)}, additionally:
 *
 * <ol start="4">
 *   <li>Device's {@code assignedField} must equal the requested {@code fieldNumber}. Mismatch →
 *       {@link ForbiddenException}.
 * </ol>
 *
 * <h2>Tenant isolation (AC-SECURITY-TENANT-ISOLATION, DEC-14)</h2>
 *
 * <p>{@link DeviceRepository#findByDeviceToken(String)} queries the current tenant's DataSource
 * (scoped by the thread-bound tenant context established by Spring's DataSource routing layer). A
 * device token belonging to a different tenant's device will not be found → {@link
 * UnauthorizedException}. No explicit tenant-ID comparison is required in this service.
 *
 * <h2>Coexistence with legacy (DEC-22 Reconstruction-in-Place)</h2>
 *
 * <p>The legacy {@code de.vvwt.tm.infrastructure.score.ScoreEntryService} (bean name {@code
 * scoreEntryService}) continues to exist and function. This implementation's default bean name is
 * {@code defaultScoreEntryService} — distinct; no {@code @Primary} / {@code @Profile} /
 * {@code @ConditionalOnProperty} gating required (AC-BOOT-SUCCESS, DEC-21).
 *
 * <h2>TDD attestation (DEC-22)</h2>
 *
 * <p>All test cases in {@code DefaultScoreEntryServiceTest} were written and observed FAILING
 * against the UnsupportedOperationException stub (RED commit: 8f28922) before the first line of
 * this implementation was authored.
 *
 * @since E22S06
 * @see ScoreEntryService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law + Reconstruction-in-Place</a>
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic DB lock</a>
 */
@Service
public class DefaultScoreEntryService implements ScoreEntryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultScoreEntryService.class);

    /** WebSocket topic prefix for per-field partial score broadcasts (preserves legacy pattern). */
    static final String SCORE_TOPIC_PREFIX = "/topic/score/field/";

    // Non-terminal match states (matches eligible to be scored)
    private static final int[] ACTIVE_STATES = {
        MatchState.OPEN.getLegacyCode(),
        MatchState.ENABLED.getLegacyCode(),
        MatchState.INPROGRESS.getLegacyCode(),
        MatchState.ONCHECK.getLegacyCode()
    };

    // -------------------------------------------------------------------------
    // Dependencies — all interface types (DEC-36, AC-IMPORT-BOUNDARY)
    // -------------------------------------------------------------------------

    private final DeviceRepository deviceRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final TeamRepository teamRepository;
    private final ScoringService scoringService;
    private final SimpMessagingTemplate messagingTemplate;
    private final SetResultRepository setResultRepository;

    /** Constructor injection per DEC-35. */
    public DefaultScoreEntryService(
            DeviceRepository deviceRepository,
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            TeamAvatarRepository teamAvatarRepository,
            TeamRepository teamRepository,
            ScoringService scoringService,
            SimpMessagingTemplate messagingTemplate,
            SetResultRepository setResultRepository) {
        this.deviceRepository = deviceRepository;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.teamRepository = teamRepository;
        this.scoringService = scoringService;
        this.messagingTemplate = messagingTemplate;
        this.setResultRepository = setResultRepository;
    }

    // -------------------------------------------------------------------------
    // AC-PUBLIC-METHODS: getMatchForField
    // -------------------------------------------------------------------------

    /**
     * Returns the active match display data for the given field, or empty if none
     * (AC-INTERFACE-CREATED).
     *
     * <p>Validates the device token first (AC-SECURITY-DEVICE-TOKEN), then traverses: active
     * tournament → active phase → current lap → non-terminal match on the field.
     *
     * @param fieldNumber the court field number (1-based)
     * @param deviceToken the tablet's opaque device token (NOT NULL)
     * @return populated result if an active match exists; empty if not
     * @throws UnauthorizedException if the device token is invalid or unassigned
     * @throws ForbiddenException if the device is valid but assigned to a different field
     * @throws IllegalArgumentException if {@code deviceToken} is {@code null}
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ScoreEntryResult> getMatchForField(int fieldNumber, String deviceToken) {
        // AC-NULL-GUARDS
        if (deviceToken == null) {
            throw new IllegalArgumentException("deviceToken must not be null");
        }
        // AC-SECURITY-DEVICE-TOKEN: validate first
        validateDeviceToken(deviceToken, fieldNumber);
        return resolveActiveMatch(fieldNumber);
    }

    // -------------------------------------------------------------------------
    // AC-PUBLIC-METHODS: handlePartialScore
    // -------------------------------------------------------------------------

    /**
     * Accepts a partial (in-progress) score update and broadcasts via WebSocket
     * (AC-INTERFACE-CREATED).
     *
     * <p>Validates the device token inside the request before broadcasting. Score is NOT persisted.
     *
     * @param request the partial score input (NOT NULL)
     * @throws UnauthorizedException if the device token is invalid or unassigned
     * @throws ForbiddenException if the device is valid but not authorized for the match
     * @throws IllegalArgumentException if {@code request} is {@code null}
     */
    @Override
    public void handlePartialScore(PartialScoreInput request) {
        // AC-NULL-GUARDS
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        // AC-SECURITY-DEVICE-TOKEN: validate device first
        Device device = validateDeviceTokenAndReturn(request.deviceToken());
        int fieldNumber = device.getAssignedField();

        // Resolve active match on this field to verify matchId and build existing state
        Optional<ScoreEntryResult> matchOpt = resolveActiveMatch(fieldNumber);
        if (matchOpt.isEmpty()) {
            log.debug(
                    "[E22S06] Partial score received but no active match on field {}", fieldNumber);
            return;
        }
        ScoreEntryResult existing = matchOpt.get();
        if (!existing.matchId().equals(request.matchId())) {
            log.warn(
                    "[E22S06] Partial score matchId={} does not match active match={} on field {}",
                    request.matchId(),
                    existing.matchId(),
                    fieldNumber);
            return;
        }

        // AC1: Persist the partial score as an OPEN set_result row (survives reload + restart)
        persistPartialScore(existing.matchId(), request);

        // Build a partial result with updated scores and broadcast
        ScoreEntryResult partial =
                new ScoreEntryResult(
                        existing.matchId(),
                        existing.fieldNumber(),
                        existing.lapNumber(),
                        request.setIndex(),
                        existing.team1Name(),
                        existing.team2Name(),
                        existing.refereeTeamName(),
                        request.team1Points(),
                        request.team2Points(),
                        existing.isTiebreak());

        String topic = SCORE_TOPIC_PREFIX + fieldNumber;
        try {
            messagingTemplate.convertAndSend(topic, partial);
        } catch (Exception e) {
            log.warn("[E22S06] Failed to broadcast partial score to {}: {}", topic, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // AC-PUBLIC-METHODS: submitSetResult
    // -------------------------------------------------------------------------

    /**
     * Submits a final set result, delegating cascade to {@link ScoringService}
     * (AC-INTERFACE-CREATED, AC-CASCADE-DELEGATION-PRESERVED, DEC-37 Clause B).
     *
     * <p>Validates the device token, resolves the match to obtain {@code tournamentId} required by
     * DEC-37 Clause B, then delegates to {@link ScoringService#registerMatchResult}. This method
     * MUST NOT call {@code TournamentRepository.findByIdForUpdate} directly — that lock is acquired
     * inside {@code DefaultScoringService} as the first action.
     *
     * @param request the set submission input (NOT NULL)
     * @throws UnauthorizedException if the device token is invalid or unassigned
     * @throws ForbiddenException if the device is valid but not assigned to the match's field
     * @throws de.vvwt.tm.tournament.exceptions.ValidationException propagated from ScoringService
     * @throws IllegalArgumentException if {@code request} is {@code null}
     */
    @Override
    @Transactional
    public void submitSetResult(SetSubmitInput request) {
        // AC-NULL-GUARDS
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        // AC-SECURITY-DEVICE-TOKEN: validate device first
        Device device = validateDeviceTokenAndReturn(request.deviceToken());

        // Verify device field authorization against the match's field
        Match match =
                matchRepository
                        .findById(request.matchId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Match not found: " + request.matchId()));

        if (match.getFieldNumber() != null
                && device.getAssignedField() != null
                && !device.getAssignedField().equals(match.getFieldNumber())) {
            throw new ForbiddenException(
                    "Device is assigned to field "
                            + device.getAssignedField()
                            + " but match is on field "
                            + match.getFieldNumber());
        }

        // E48S04 AC-IMPL-SCORE-SERVICE-GUARD: reject score submissions on CANCELED matches
        // Lock-Order: state read is safe here (CANCELED is a terminal state — no write needed).
        // Per AC-ERROR-HANDLING-CANCELED-MATCH-MESSAGE: operator-actionable message.
        if (MatchState.CANCELED == match.getMatchState()) {
            throw new MatchCanceledException(
                    "Match "
                            + request.matchId()
                            + " is CANCELED — score submission rejected."
                            + " Tournament was cancelled.");
        }

        UUID tournamentId = match.getTournamentId();

        // AC-CASCADE-DELEGATION-PRESERVED (C-7, DEC-37 Clause B):
        // Delegate to ScoringService.registerMatchResult — which acquires the per-tournament
        // DB lock as its FIRST action. This method MUST NOT acquire the lock directly.
        SetResultInput input =
                new SetResultInput(
                        tournamentId, // DEC-37 Clause B: tournamentId required for lock-first
                        request.matchId(),
                        request.setIndex(),
                        request.team1Points(),
                        request.team2Points(),
                        device.getId().toString(), // actorId = device UUID
                        "TABLET score entry", // reason
                        SetResultInput.SOURCE_TYPE_TABLET,
                        device.getId().toString()); // sourceDeviceId = device UUID

        log.debug(
                "[E22S06] Submitting set result matchId={} setIndex={} by deviceId={}"
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
     * Validates the device token and asserts field ownership. Throws on failure.
     *
     * <p>AC-SECURITY-DEVICE-TOKEN: first checks token validity, then ASSIGNED status, then field
     * ownership.
     *
     * @param deviceToken the opaque device token
     * @param fieldNumber the expected assigned field
     * @throws UnauthorizedException if token invalid or device not ASSIGNED
     * @throws ForbiddenException if device valid but on a different field
     */
    private void validateDeviceToken(String deviceToken, int fieldNumber) {
        Device device = validateDeviceTokenAndReturn(deviceToken);
        if (device.getAssignedField() == null
                || !Integer.valueOf(fieldNumber).equals(device.getAssignedField())) {
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
     * <p>AC-SECURITY-DEVICE-TOKEN + AC-SECURITY-TENANT-ISOLATION: findByDeviceToken queries the
     * current tenant's DataSource; tokens from other tenants return empty → UnauthorizedException.
     *
     * @param deviceToken the opaque device token
     * @return the validated device
     * @throws UnauthorizedException if the token is invalid, device not ASSIGNED, or no field
     */
    private Device validateDeviceTokenAndReturn(String deviceToken) {
        Optional<Device> deviceOpt = deviceRepository.findByDeviceToken(deviceToken);
        if (deviceOpt.isEmpty()) {
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
     * Resolves the active non-terminal match for the given field, scoped to the active phase.
     *
     * <p>Traversal: active tournament → active phase → current lap → non-terminal match on field
     * within the active phase. The match query is scoped to the active phase's ID to prevent
     * surfacing matches from non-active phases that share the same ({@code fieldNumber}, {@code
     * lapNumber}) coordinate (E22S13, AC2 — phase-scoped match resolution). Lap and field numbers
     * restart per phase (DEC-56/DEC-60), so a coordinate pair exists once in every phase.
     *
     * @param fieldNumber the court field number
     * @return populated result or empty if no active-phase non-terminal match found
     */
    private Optional<ScoreEntryResult> resolveActiveMatch(int fieldNumber) {
        // Find active tournament (DEC-5: at most one per tenant)
        Tournament activeTournament = null;
        for (Tournament t : tournamentRepository.findAll()) {
            if ("ACTIVE".equals(t.getStatus())) {
                activeTournament = t;
                break;
            }
        }
        if (activeTournament == null) {
            log.debug("[E22S06] No active tournament found for field {}", fieldNumber);
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
            log.debug("[E22S06] No active phase found in tournament={}", activeTournament.getId());
            return Optional.empty();
        }

        // Current lap number from the active phase (DEC-65: 1-based running-lap index;
        // 0 = sentinel "no lap running")
        int lapNumber = activePhase.getCurrentLapNumber();

        // Find a non-terminal match on this field+lap — SCOPED TO THE ACTIVE PHASE (E22S13 fix).
        // Using findByPhaseIdAndFieldNumberAndLapNumber instead of findByFieldNumberAndLapNumber
        // prevents surfacing later-phase matches at the same (fieldNumber, lapNumber) coordinate.
        Match activeMatch = null;
        for (Match m :
                matchRepository.findByPhaseIdAndFieldNumberAndLapNumber(
                        activePhase.getId(), fieldNumber, lapNumber)) {
            if (isNonTerminal(m.getMatchState())) {
                activeMatch = m;
                break;
            }
        }
        if (activeMatch == null) {
            log.debug("[E22S06] No non-terminal match on field={} lap={}", fieldNumber, lapNumber);
            return Optional.empty();
        }

        // Resolve team names via TeamAvatar → Team
        String team1Name = resolveTeamName(activeMatch.getMemberAvatar1Id());
        String team2Name = resolveTeamName(activeMatch.getMemberAvatar2Id());
        String refereeName =
                activeMatch.getRefereeTeamId() != null
                        ? resolveRefereeTeamName(activeMatch.getRefereeTeamId())
                        : null;

        // AC2/AC3/AC4: Load set_result rows and derive true setIndex, current points, isTiebreak
        List<SetResult> setResults = setResultRepository.findByMatchId(activeMatch.getId());

        // AC2: setIndex = count of completed (non-OPEN, non-CANCELED) set_result rows
        int currentSetIndex = computeSetIndex(setResults);

        // AC3: current points from the OPEN set_result row for the current set (0:0 if none)
        int team1Points = 0;
        int team2Points = 0;
        for (SetResult sr : setResults) {
            if (sr.getSetIndex() == currentSetIndex && sr.getSetState() == SetState.OPEN) {
                team1Points = sr.getTeam1Points();
                team2Points = sr.getTeam2Points();
                break;
            }
        }

        // AC4: isTiebreak — detect if the current open set is the deciding tiebreak set
        MatchFormat matchFormat = null;
        if (activeTournament.getMatchFormat() != null) {
            matchFormat = MatchFormat.fromPersistedName(activeTournament.getMatchFormat());
        }
        boolean isTiebreak = computeIsTiebreak(matchFormat, setResults);

        ScoreEntryResult result =
                new ScoreEntryResult(
                        activeMatch.getId(),
                        activeMatch.getFieldNumber(),
                        activeMatch.getLapNumber(),
                        currentSetIndex,
                        team1Name,
                        team2Name,
                        refereeName,
                        team1Points,
                        team2Points,
                        isTiebreak);

        return Optional.of(result);
    }

    /**
     * Resolves the team name for a given TeamAvatar ID.
     *
     * @param avatarId the TeamAvatar primary key
     * @return team description; never null
     */
    private String resolveTeamName(UUID avatarId) {
        if (avatarId == null) {
            return "?";
        }
        Optional<TeamAvatar> avatarOpt = teamAvatarRepository.findById(avatarId);
        if (avatarOpt.isEmpty()) {
            return avatarId.toString();
        }
        UUID teamId = avatarOpt.get().getTeamId();
        Optional<Team> teamOpt = teamRepository.findById(teamId);
        return teamOpt.map(Team::getDescription).orElse(avatarId.toString());
    }

    /**
     * Resolves the referee team name from a Team ID.
     *
     * @param refereeTeamId the Team primary key for the referee team
     * @return team description; never null
     */
    private String resolveRefereeTeamName(UUID refereeTeamId) {
        Optional<Team> teamOpt = teamRepository.findById(refereeTeamId);
        return teamOpt.map(Team::getDescription).orElse(refereeTeamId.toString());
    }

    /**
     * Returns {@code true} if the match state is non-terminal (eligible to be scored).
     *
     * @param state the match state
     * @return true if the match can still be scored
     */
    private static boolean isNonTerminal(MatchState state) {
        if (state == null) {
            return false;
        }
        int code = state.getLegacyCode();
        for (int activeCode : ACTIVE_STATES) {
            if (code == activeCode) {
                return true;
            }
        }
        return false;
    }

    /**
     * AC2: Computes the true current set index as the count of completed (non-OPEN, non-CANCELED)
     * set_result rows.
     *
     * @param setResults all set_result rows for the match (may be empty)
     * @return 0-based index of the current open set
     */
    private static int computeSetIndex(List<SetResult> setResults) {
        int count = 0;
        for (SetResult sr : setResults) {
            SetState state = sr.getSetState();
            if (state != SetState.OPEN && state != SetState.CANCELED) {
                count++;
            }
        }
        return count;
    }

    /**
     * AC4: Detects whether the current open set is a deciding tiebreak set.
     *
     * <p>A tiebreak is in play when both teams have won {@code requiredToWin - 1} sets each (from
     * the completed rows) AND the match format has a deciding-set concept (i.e., is not {@code
     * FIXED_2_SETS}).
     *
     * @param format the match format (may be null if not configured)
     * @param setResults all set_result rows for the match
     * @return {@code true} if the current open set is the tiebreak deciding set
     */
    private static boolean computeIsTiebreak(MatchFormat format, List<SetResult> setResults) {
        if (format == null) {
            return false;
        }
        // FIXED_2_SETS has no deciding-set concept
        if (!format.getDecidingSet().isPresent()) {
            return false;
        }
        // BEST_OF_1: requiredToWin=1, so requiredToWin-1=0; both at 0 just means match not started
        if (format.getRequiredToWin() <= 1) {
            return false;
        }
        int requiredToWin = format.getRequiredToWin();
        int team1Wins = 0;
        int team2Wins = 0;
        for (SetResult sr : setResults) {
            SetState state = sr.getSetState();
            if (state == SetState.WINNER1) {
                team1Wins++;
            } else if (state == SetState.WINNER2) {
                team2Wins++;
            }
        }
        return team1Wins == (requiredToWin - 1) && team2Wins == (requiredToWin - 1);
    }

    /**
     * AC1: Persists the partial score as an OPEN {@link SetResult} row (INSERT or UPDATE).
     *
     * <p>If an OPEN row already exists for {@code (matchId, setIndex)}, it is updated. Otherwise, a
     * new OPEN row is inserted. The phaseId is resolved from the match entity to satisfy the {@link
     * SetResult} NOT NULL constraint.
     *
     * @param matchId the match whose partial score is being persisted
     * @param request the partial score input carrying setIndex and team points
     */
    private void persistPartialScore(UUID matchId, PartialScoreInput request) {
        int setIndex = request.setIndex();
        Optional<SetResult> existingOpt =
                setResultRepository.findByMatchIdAndSetIndex(matchId, setIndex);
        if (existingOpt.isPresent()) {
            SetResult existing = existingOpt.get();
            existing.setTeam1Points(request.team1Points());
            existing.setTeam2Points(request.team2Points());
            // Ensure state remains OPEN (do not overwrite WINNER1/WINNER2 rows — cascade owns
            // those)
            if (existing.getSetState() == SetState.OPEN) {
                setResultRepository.update(existing);
            }
        } else {
            // Resolve phaseId from the match (needed for SetResult composite key context)
            UUID phaseId = matchRepository.findById(matchId).map(Match::getPhaseId).orElse(null);
            if (phaseId == null) {
                log.warn("[E61S02] Cannot persist partial score: match {} not found", matchId);
                return;
            }
            SetResult newRow = new SetResult();
            newRow.setMatchId(matchId);
            newRow.setSetIndex(setIndex);
            newRow.setPhaseId(phaseId);
            newRow.setTeam1Points(request.team1Points());
            newRow.setTeam2Points(request.team2Points());
            newRow.setSetState(SetState.OPEN);
            setResultRepository.insert(newRow);
        }
    }
}
