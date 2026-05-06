package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.PartialScoreInput;
import de.vvwt.tm.scoring.ScoreEntryResult;
import de.vvwt.tm.scoring.ScoreEntryService;
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
import de.vvwt.tm.tournament.exceptions.MatchCanceledException;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
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

    /** Constructor injection per DEC-35. */
    public DefaultScoreEntryService(
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
                        request.team2Points());

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
     * Resolves the active non-terminal match for the given field.
     *
     * <p>Traversal: active tournament → active phase → current lap → non-terminal match on field.
     *
     * @param fieldNumber the court field number
     * @return populated result or empty if no match found
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

        // Current lap number from the active phase
        int lapNumber = activePhase.getCurrentLapNumber();

        // Find a non-terminal match on this field+lap
        Match activeMatch = null;
        for (Match m : matchRepository.findByFieldNumberAndLapNumber(fieldNumber, lapNumber)) {
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

        // Current set index defaults to 0 (scoring service manages set progression)
        int currentSetIndex = 0;

        ScoreEntryResult result =
                new ScoreEntryResult(
                        activeMatch.getId(),
                        activeMatch.getFieldNumber(),
                        activeMatch.getLapNumber(),
                        currentSetIndex,
                        team1Name,
                        team2Name,
                        refereeName,
                        0, // team1Points — initial display is 0:0
                        0); // team2Points

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
}
