package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.ScoringResult;
import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.ScoringService;
import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.ValidationResult;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.AuditLogRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchOutcome;
import de.vvwt.tm.tournament.MatchOutcomeRepository;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.SetResult;
import de.vvwt.tm.tournament.SetResultInput;
import de.vvwt.tm.tournament.SetResultRepository;
import de.vvwt.tm.tournament.SetState;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.LapAdvancedEvent;
import de.vvwt.tm.tournament.events.MatchResultChangedEvent;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link ScoringService} — TDD-reconstructed replacement for the legacy
 * {@code de.vvwt.tm.domain.CascadeRecomputeService} (E31S03, DEC-22 Reconstruction-in-Place).
 *
 * <p>Implements the 13-step {@code registerMatchResult} cascade using per-tournament pessimistic DB
 * row-lock ({@code SELECT … FOR UPDATE} via {@link TournamentRepository#findByIdForUpdate(UUID)})
 * as the FIRST action, serialising concurrent score submissions for the same tournament aggregate
 * root per DEC-37 Clause B.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 — implementation in {@code de.vvwt.tm.scoring.internal}; interface {@link
 *       ScoringService} in {@code de.vvwt.tm.scoring} (public package)
 *   <li>DEC-37 Clause B — {@code tournamentRepository.findByIdForUpdate(input.tournamentId())} is
 *       the FIRST action; the returned {@link Tournament} object is used throughout the cascade
 *   <li>DEC-22 — every public method was preceded by a failing test (RED-first per Iron Law)
 *   <li>DEC-21 — all imports are from public tournament package ({@code de.vvwt.tm.tournament.*})
 *       or {@code de.vvwt.tm.scoring.*} / {@code de.vvwt.tm.scoring.internal.*} (same module); no
 *       {@code tournament.internal.*} imports
 * </ul>
 *
 * <h2>Coexistence with legacy (DEC-22 Reconstruction-in-Place)</h2>
 *
 * <p>During E31S03 through E31S04, the legacy {@code de.vvwt.tm.domain.CascadeRecomputeService}
 * continues to exist and function for its existing consumers ({@code ScoreEntryService} wires the
 * legacy class directly by concrete type). This implementation is injected by its interface type
 * ({@code ScoringService}) — no {@code @Primary}, {@code @Profile}, or {@code @ConditionalOn*}
 * needed (per AC-NO-PRIMARY-NO-PROFILE): the two beans have DIFFERENT types from Spring's
 * perspective. Atomic cutover (delete legacy, wire {@code ScoreEntryService} to {@code
 * ScoringService}) is E31S04 scope.
 *
 * <h2>TeamAvatarRating full-recompute pattern (DEC-37 Clause B acknowledgment)</h2>
 *
 * <p>Per Brief T-11 rationale and the legacy class's Javadoc line 515, the avatar-rating refresh
 * reads ALL terminal matches for the avatar in the phase and recomputes aggregates from scratch.
 * This "full-recompute, no incremental delta" pattern is preserved for correction-idempotence: the
 * per-tournament DB lock serialises cascades and eliminates the race, so full-recompute is safe.
 *
 * @since E31S03
 * @see ScoringService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law + Reconstruction-in-Place</a>
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic DB lock</a>
 */
@Service
public class DefaultScoringService implements ScoringService {

    private static final Logger log = LoggerFactory.getLogger(DefaultScoringService.class);

    private final MatchRepository matchRepository;
    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final SetResultRepository setResultRepository;
    private final MatchOutcomeRepository matchOutcomeRepository;
    private final TeamAvatarRatingRepository teamAvatarRatingRepository;
    private final TeamAvatarRepository teamAvatarRepository;
    private final AuditLogRepository auditLogRepository;
    private final TournamentRuleResolver ruleResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final TenantContext tenantContext;

    public DefaultScoringService(
            MatchRepository matchRepository,
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            SetResultRepository setResultRepository,
            MatchOutcomeRepository matchOutcomeRepository,
            TeamAvatarRatingRepository teamAvatarRatingRepository,
            TeamAvatarRepository teamAvatarRepository,
            AuditLogRepository auditLogRepository,
            TournamentRuleResolver ruleResolver,
            ApplicationEventPublisher eventPublisher,
            TenantContext tenantContext) {
        this.matchRepository = matchRepository;
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.setResultRepository = setResultRepository;
        this.matchOutcomeRepository = matchOutcomeRepository;
        this.teamAvatarRatingRepository = teamAvatarRatingRepository;
        this.teamAvatarRepository = teamAvatarRepository;
        this.auditLogRepository = auditLogRepository;
        this.ruleResolver = ruleResolver;
        this.eventPublisher = eventPublisher;
        this.tenantContext = tenantContext;
    }

    // ---------------------------------------------------------------------------
    // Primary entry point — 13-step cascade (AC1, DEC-22, DEC-37)
    // ---------------------------------------------------------------------------

    /**
     * Executes the full 13-step cascade recompute flow for a single set result.
     *
     * <p><b>Lock acquisition (DEC-37 Clause B, AC-LOCK-FIRST-ACTION):</b> The FIRST action is
     * {@code tournamentRepository.findByIdForUpdate(input.tournamentId())} — acquiring a
     * pessimistic DB row-lock on the {@code tournament} table row. All subsequent reads and writes
     * run inside the same transaction, holding this lock until commit or rollback. This serialises
     * concurrent cascade invocations for the same tournament.
     *
     * <p><b>Transaction boundary:</b> {@code @Transactional(READ_COMMITTED)}. Steps 1–11 execute
     * inside this transaction. READ_COMMITTED is required (not REPEATABLE_READ) so that the second
     * concurrent cascade (T2), after waiting on the per-tournament FOR UPDATE lock, sees T1's
     * committed data (e.g., T1's inserted {@code team_avatar_rating} row) and produces an UPDATE
     * rather than a duplicate-key INSERT. Step 12 (event publication) fires after commit.
     *
     * <p><b>TDD attestation (DEC-22):</b> This method was developed strictly RED-first. All test
     * cases in {@code DefaultScoringServiceTest} were written and observed FAILING against a
     * skeleton implementation ({@code throw UnsupportedOperationException}) before the first line
     * of this implementation was authored.
     *
     * @param input the set result to register; must not be {@code null}
     * @throws ValidationException if the set score fails the {@link
     *     de.vvwt.tm.scoring.SetValidationRule}
     * @throws IllegalArgumentException if the referenced match, tournament, or phase does not exist
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void registerMatchResult(SetResultInput input) {
        UUID correlationId = UUID.randomUUID();

        log.info(
                "[scoring-cascade] START matchId={} setIndex={} team1={} team2={} actor={}"
                        + " correlationId={}",
                input.matchId(),
                input.setIndex(),
                input.team1Points(),
                input.team2Points(),
                input.actorId(),
                correlationId);

        // -----------------------------------------------------------------------
        // DEC-37 Clause B — FIRST action: acquire per-tournament pessimistic DB lock
        // (AC-LOCK-FIRST-ACTION: before any other repository read or write)
        // GREEN transition: this single line addition flips CascadeLockIT from RED to GREEN.
        // -----------------------------------------------------------------------
        if (input.tournamentId() == null) {
            throw new IllegalArgumentException(
                    "DefaultScoringService requires SetResultInput.tournamentId() to be non-null"
                            + " for the DEC-37 lock-first contract. Use"
                            + " SetResultInput.withTournament(...) or supply tournamentId in the"
                            + " constructor. Legacy callers that supply null must route through"
                            + " the legacy CascadeRecomputeService until E31S04 cutover.");
        }
        Tournament tournament = tournamentRepository.findByIdForUpdate(input.tournamentId());

        log.debug(
                "[scoring-cascade] lock acquired tournamentId={} correlationId={}",
                tournament.getId(),
                correlationId);

        // -----------------------------------------------------------------------
        // Resolve match (uses the tournament's id from the locked row)
        // -----------------------------------------------------------------------
        Match match =
                matchRepository
                        .findById(input.matchId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Match not found: "
                                                        + input.matchId()
                                                        + " correlationId="
                                                        + correlationId));

        MatchFormat format = MatchFormat.fromPersistedName(tournament.getMatchFormat());

        // -----------------------------------------------------------------------
        // Step 1 — Validate via SetValidationRule (DEC-22 Iron Law)
        // -----------------------------------------------------------------------
        SetValidationRule validationRule = ruleResolver.resolve(tournament).setValidationRule();
        ValidationResult validationResult =
                validationRule.isSetClosed(
                        input.team1Points(), input.team2Points(), input.setIndex(), format);

        log.info(
                "[scoring-cascade] Step1 validation={} correlationId={}",
                validationResult,
                correlationId);

        if (!validationResult.isClosed()) {
            log.warn(
                    "[scoring-cascade] FAIL Step1: validation rejected — {} correlationId={}",
                    validationResult.getReason(),
                    correlationId);
            throw new ValidationException(validationResult.getReason());
        }

        // Derive set state from validation result winner hint
        SetState setStateNew =
                validationResult
                        .getWinnerHint()
                        .map(
                                ms -> {
                                    if (ms == MatchState.FINISHED_WINNER1) return SetState.WINNER1;
                                    if (ms == MatchState.FINISHED_WINNER2) return SetState.WINNER2;
                                    return SetState.STANDOFF;
                                })
                        .orElse(SetState.OPEN);

        // -----------------------------------------------------------------------
        // Step 2 — Persist SetResult (INSERT or UPDATE) + audit_log row
        // -----------------------------------------------------------------------
        Optional<SetResult> existing =
                setResultRepository.findByMatchIdAndSetIndex(input.matchId(), input.setIndex());

        Integer team1PointsOld = null;
        Integer team2PointsOld = null;
        Integer setStateOld = null;
        boolean isUpdate = existing.isPresent();

        if (isUpdate) {
            SetResult old = existing.get();
            team1PointsOld = old.getTeam1Points();
            team2PointsOld = old.getTeam2Points();
            setStateOld = old.getSetStateCode();

            SetResult updated =
                    new SetResult(
                            input.matchId(),
                            input.setIndex(),
                            match.getPhaseId(),
                            input.team1Points(),
                            input.team2Points(),
                            setStateNew.getLegacyCode(),
                            LocalDateTime.now(),
                            null);
            setResultRepository.update(updated);

            log.info(
                    "[scoring-cascade] Step2 UPDATE set_result ({},{}) old=({},{}) new=({},{})"
                            + " correlationId={}",
                    input.matchId(),
                    input.setIndex(),
                    team1PointsOld,
                    team2PointsOld,
                    input.team1Points(),
                    input.team2Points(),
                    correlationId);
        } else {
            LocalDateTime now = LocalDateTime.now();
            SetResult inserted =
                    new SetResult(
                            input.matchId(),
                            input.setIndex(),
                            match.getPhaseId(),
                            input.team1Points(),
                            input.team2Points(),
                            setStateNew.getLegacyCode(),
                            now,
                            now);
            setResultRepository.insert(inserted);

            log.info(
                    "[scoring-cascade] Step2 INSERT set_result ({},{}) new=({},{})"
                            + " correlationId={}",
                    input.matchId(),
                    input.setIndex(),
                    input.team1Points(),
                    input.team2Points(),
                    correlationId);
        }

        // Audit log row — include source distinction per DEC-14
        AuditLogEntry auditEntry =
                new AuditLogEntry(
                        UUID.randomUUID(),
                        input.matchId(),
                        input.setIndex(),
                        team1PointsOld,
                        team2PointsOld,
                        input.team1Points(),
                        input.team2Points(),
                        setStateOld,
                        setStateNew.getLegacyCode(),
                        input.actorId(),
                        input.reason(),
                        LocalDateTime.now(),
                        input.sourceType(),
                        input.sourceDeviceId());
        auditLogRepository.save(auditEntry);

        log.debug(
                "[scoring-cascade] Step2 audit_log written id={} correlationId={}",
                auditEntry.getId(),
                correlationId);

        // -----------------------------------------------------------------------
        // Step 3 — Aggregate all SetResults for the match
        // -----------------------------------------------------------------------
        List<SetResult> allSets = setResultRepository.findByMatchId(input.matchId());
        int team1SetsWon = 0;
        int team2SetsWon = 0;
        int team1BallsWon = 0;
        int team2BallsWon = 0;

        for (SetResult sr : allSets) {
            SetState ss = sr.getSetState();
            if (ss == SetState.WINNER1) team1SetsWon++;
            if (ss == SetState.WINNER2) team2SetsWon++;
            team1BallsWon += sr.getTeam1Points();
            team2BallsWon += sr.getTeam2Points();
        }
        int setCount = allSets.size();

        log.debug(
                "[scoring-cascade] Step3 aggregate sets={} t1setsWon={} t2setsWon={} t1balls={}"
                        + " t2balls={} correlationId={}",
                setCount,
                team1SetsWon,
                team2SetsWon,
                team1BallsWon,
                team2BallsWon,
                correlationId);

        // -----------------------------------------------------------------------
        // Step 4 — Upsert MatchOutcome
        // -----------------------------------------------------------------------
        MatchState derivedState = format.deriveMatchState(team1SetsWon, team2SetsWon, setCount);

        MatchOutcome outcome =
                new MatchOutcome(
                        input.matchId(),
                        team1SetsWon,
                        team1BallsWon,
                        team2SetsWon,
                        team2BallsWon,
                        setCount,
                        derivedState.getLegacyCode(),
                        LocalDateTime.now());
        matchOutcomeRepository.save(outcome);

        log.info(
                "[scoring-cascade] Step4 MatchOutcome upserted matchId={} derivedState={}"
                        + " correlationId={}",
                input.matchId(),
                derivedState,
                correlationId);

        // -----------------------------------------------------------------------
        // Step 5 — Derive match state (already computed in Step 4)
        // -----------------------------------------------------------------------
        log.debug(
                "[scoring-cascade] Step5 derivedState={} correlationId={}",
                derivedState,
                correlationId);

        // -----------------------------------------------------------------------
        // Step 6 — Diff-check & update Match.state only if changed
        // -----------------------------------------------------------------------
        MatchState previousState = match.getMatchState();
        if (previousState != derivedState) {
            match.setMatchState(derivedState);
            matchRepository.save(match);
            log.info(
                    "[scoring-cascade] Step6 Match.state updated {} -> {} matchId={}"
                            + " correlationId={}",
                    previousState,
                    derivedState,
                    input.matchId(),
                    correlationId);
        } else {
            log.debug(
                    "[scoring-cascade] Step6 Match.state unchanged ({}) — no-op UPDATE matchId={}"
                            + " correlationId={}",
                    previousState,
                    input.matchId(),
                    correlationId);
        }

        // -----------------------------------------------------------------------
        // Steps 7–8 — Refresh avatar ratings (full-recompute, no incremental delta)
        // -----------------------------------------------------------------------
        ScoringRule scoringRule = ruleResolver.resolve(tournament).scoringRule();

        log.debug(
                "[scoring-cascade] Step7 refreshAvatarRating avatar1Id={} correlationId={}",
                match.getMemberAvatar1Id(),
                correlationId);
        refreshAvatarRating(
                match.getMemberAvatar1Id(), match.getPhaseId(), scoringRule, format, correlationId);

        log.debug(
                "[scoring-cascade] Step8 refreshAvatarRating avatar2Id={} correlationId={}",
                match.getMemberAvatar2Id(),
                correlationId);
        refreshAvatarRating(
                match.getMemberAvatar2Id(), match.getPhaseId(), scoringRule, format, correlationId);

        // -----------------------------------------------------------------------
        // Step 9 — GroupTable no-op (computed on read per D-24 / S-22)
        // -----------------------------------------------------------------------

        // -----------------------------------------------------------------------
        // Step 10 — Auto-advance Phase.current_lap_number if all matches in current lap terminal
        // -----------------------------------------------------------------------
        Phase phase =
                phaseRepository
                        .findById(match.getPhaseId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Phase not found: "
                                                        + match.getPhaseId()
                                                        + " correlationId="
                                                        + correlationId));

        int previousLapNumber = phase.getCurrentLapNumber();
        int newLapNumber = previousLapNumber;

        Integer currentMatchLap = match.getLapNumber();
        if (currentMatchLap != null) {
            List<Match> phaseMatches = matchRepository.findByPhaseId(match.getPhaseId());
            boolean allTerminalInLap =
                    phaseMatches.stream()
                            .filter(m -> currentMatchLap.equals(m.getLapNumber()))
                            .allMatch(m -> isTerminalState(m.getMatchState()));

            if (allTerminalInLap) {
                phase.setCurrentLapNumber(previousLapNumber + 1);
                phaseRepository.save(phase);
                newLapNumber = previousLapNumber + 1;
                log.info(
                        "[scoring-cascade] Step10 lap auto-advanced {} -> {} phaseId={}"
                                + " correlationId={}",
                        previousLapNumber,
                        newLapNumber,
                        phase.getId(),
                        correlationId);
            } else {
                log.debug(
                        "[scoring-cascade] Step10 lap NOT advanced (not all matches terminal in"
                                + " lap {}) phaseId={} correlationId={}",
                        currentMatchLap,
                        phase.getId(),
                        correlationId);
            }
        } else {
            log.debug(
                    "[scoring-cascade] Step10 skipped — match has no lapNumber assigned yet"
                            + " matchId={} correlationId={}",
                    input.matchId(),
                    correlationId);
        }

        // -----------------------------------------------------------------------
        // Step 11 — Commit (handled by @Transactional — no explicit action)
        // -----------------------------------------------------------------------

        // -----------------------------------------------------------------------
        // Step 12 — Emit MatchResultChangedEvent AFTER_COMMIT
        // -----------------------------------------------------------------------
        MatchResultChangedEvent event =
                new MatchResultChangedEvent(
                        this,
                        tenantContext.current(),
                        match.getTournamentId(),
                        match.getPhaseId(),
                        input.matchId(),
                        previousState,
                        derivedState,
                        input.actorId(),
                        previousLapNumber,
                        newLapNumber,
                        correlationId);
        eventPublisher.publishEvent(event);

        log.info(
                "[scoring-cascade] Step12 event published {} correlationId={}",
                event,
                correlationId);

        if (previousLapNumber != newLapNumber) {
            LapAdvancedEvent lapEvent =
                    new LapAdvancedEvent(
                            this,
                            tenantContext.current(),
                            match.getTournamentId(),
                            match.getPhaseId(),
                            previousLapNumber,
                            newLapNumber,
                            correlationId);
            eventPublisher.publishEvent(lapEvent);
            log.info(
                    "[scoring-cascade] Step12a LapAdvancedEvent published {} -> {} phaseId={}"
                            + " correlationId={}",
                    previousLapNumber,
                    newLapNumber,
                    match.getPhaseId(),
                    correlationId);
        }

        // -----------------------------------------------------------------------
        // Step 13 — Return void
        // -----------------------------------------------------------------------
        log.info(
                "[scoring-cascade] DONE matchId={} newState={} correlationId={}",
                input.matchId(),
                derivedState,
                correlationId);
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Refreshes the {@link TeamAvatarRating} for a given avatar in a phase.
     *
     * <p>Reads ALL terminal matches for the avatar in the phase, recomputes aggregates from scratch
     * (full-recompute, no incremental delta — per Brief T-11 rationale and legacy class Javadoc
     * line 515), and upserts the rating row.
     *
     * @param avatarId the avatar whose rating to refresh
     * @param phaseId the phase scoping the query
     * @param scoringRule the resolved scoring rule for point computation
     * @param format the match format for tie-break detection
     * @param correlationId logging correlation UUID
     */
    private void refreshAvatarRating(
            UUID avatarId,
            UUID phaseId,
            ScoringRule scoringRule,
            MatchFormat format,
            UUID correlationId) {
        List<Match> terminalMatches =
                matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatarId);

        int matchCount = 0;
        int totalSetCount = 0;
        int totalPoints = 0;
        int totalSetsWon = 0;
        int totalSetsLost = 0;
        int totalBallsWon = 0;
        int totalBallsLost = 0;

        for (Match m : terminalMatches) {
            Optional<MatchOutcome> outcomeOpt = matchOutcomeRepository.findById(m.getId());
            if (outcomeOpt.isEmpty()) {
                log.warn(
                        "[scoring-cascade] refreshAvatarRating: MatchOutcome not found for"
                                + " terminal match {} — skipping. correlationId={}",
                        m.getId(),
                        correlationId);
                continue;
            }
            MatchOutcome mo = outcomeOpt.get();

            ScoringResult scoring = scoringRule.calculatePoints(mo, format);

            boolean isAvatar1 = avatarId.equals(m.getMemberAvatar1Id());
            int avatarPoints = isAvatar1 ? scoring.team1Points() : scoring.team2Points();
            int avatarSetsWon = isAvatar1 ? mo.getTeam1SetsWon() : mo.getTeam2SetsWon();
            int avatarSetsLost = isAvatar1 ? mo.getTeam2SetsWon() : mo.getTeam1SetsWon();
            int avatarBallsWon = isAvatar1 ? mo.getTeam1BallsWon() : mo.getTeam2BallsWon();
            int avatarBallsLost = isAvatar1 ? mo.getTeam2BallsWon() : mo.getTeam1BallsWon();

            matchCount++;
            totalSetCount += mo.getSetCount();
            totalPoints += avatarPoints;
            totalSetsWon += avatarSetsWon;
            totalSetsLost += avatarSetsLost;
            totalBallsWon += avatarBallsWon;
            totalBallsLost += avatarBallsLost;
        }

        double setQuotient =
                totalSetsLost == 0
                        ? (totalSetsWon == 0 ? 0.0 : Double.MAX_VALUE)
                        : (double) totalSetsWon / totalSetsLost;
        double ballQuotient =
                totalBallsLost == 0
                        ? (totalBallsWon == 0 ? 0.0 : Double.MAX_VALUE)
                        : (double) totalBallsWon / totalBallsLost;

        // Preserve the isWithoutAssessment flag from the existing rating (or load from TeamAvatar)
        boolean isWithoutAssessment = false;
        Optional<TeamAvatarRating> existingRating = teamAvatarRatingRepository.findById(avatarId);
        if (existingRating.isPresent()) {
            isWithoutAssessment = existingRating.get().isWithoutAssessment();
        } else {
            Optional<TeamAvatar> teamAvatar = teamAvatarRepository.findById(avatarId);
            // TeamAvatar does not carry isWithoutAssessment directly — initialized to false
            isWithoutAssessment = false;
        }

        TeamAvatarRating rating =
                new TeamAvatarRating(
                        avatarId,
                        matchCount,
                        totalSetCount,
                        totalPoints,
                        totalSetsWon,
                        totalSetsLost,
                        totalBallsWon,
                        totalBallsLost,
                        setQuotient,
                        ballQuotient,
                        isWithoutAssessment,
                        LocalDateTime.now());
        teamAvatarRatingRepository.save(rating);

        log.debug(
                "[scoring-cascade] refreshAvatarRating avatarId={} matchCount={} points={}"
                        + " setsWon={} setsLost={} correlationId={}",
                avatarId,
                matchCount,
                totalPoints,
                totalSetsWon,
                totalSetsLost,
                correlationId);
    }

    /**
     * Returns {@code true} if the given {@link MatchState} is terminal for lap-advance purposes.
     *
     * <p>Terminal states: FINISHED_WINNER1, FINISHED_WINNER2, FINISHED_STANDOFF, CANCELED. All
     * other states (OPEN, ENABLED, INPROGRESS, ONCHECK) are non-terminal.
     */
    private boolean isTerminalState(MatchState state) {
        return state == MatchState.FINISHED_WINNER1
                || state == MatchState.FINISHED_WINNER2
                || state == MatchState.FINISHED_STANDOFF
                || state == MatchState.CANCELED;
    }
}
