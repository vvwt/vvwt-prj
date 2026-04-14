package de.vvwt.tm.domain;

import de.vvwt.tm.domain.event.LapAdvancedEvent;
import de.vvwt.tm.domain.event.MatchResultChangedEvent;
import de.vvwt.tm.domain.repo.AuditLogRepository;
import de.vvwt.tm.domain.repo.MatchOutcomeRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.domain.rules.ScoringResult;
import de.vvwt.tm.domain.rules.ScoringRule;
import de.vvwt.tm.domain.rules.SetValidationRule;
import de.vvwt.tm.domain.rules.TournamentRuleResolver;
import de.vvwt.tm.domain.rules.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Core cascade recompute service — implements the 13-step {@code registerMatchResult} flow
 * per D-21 (E03S11).
 *
 * <h2>Transaction boundary (AC2)</h2>
 * <p>The {@code registerMatchResult} method is annotated {@code @Transactional} with
 * {@code REPEATABLE_READ} isolation. All 13 steps run inside a single transaction. A failure
 * at any step rolls back the entire operation. Step 12 (event publication) fires after commit
 * via Spring's {@code @TransactionalEventListener(phase = AFTER_COMMIT)} on the listener side.
 *
 * <h2>Invariant maintained (AC8, AC16)</h2>
 * <p>After each cascade run, {@code match.state == matchOutcome.computedState}. Cascade step 6
 * diff-checks and only updates {@code match.state} when it changed, avoiding no-op writes.
 *
 * <h2>GroupTable no-op (AC11)</h2>
 * <p>Step 9 is intentionally absent from the runtime — per D-24 and S-22, the GroupTable
 * is computed on read. No {@code group_table} table is written during the cascade.
 *
 * <h2>Tenant scope (DEC-5, DEC-17)</h2>
 * <p>All repository calls go through tenant-scoped repositories (E03S05). Cross-tenant
 * access is structurally impossible through the repository API.
 *
 * @see SetResultInput
 * @see ValidationException
 * @see MatchResultChangedEvent
 */
@Service
public class CascadeRecomputeService {

    private static final Logger log = LoggerFactory.getLogger(CascadeRecomputeService.class);

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

    public CascadeRecomputeService(MatchRepository matchRepository,
                                   TournamentRepository tournamentRepository,
                                   PhaseRepository phaseRepository,
                                   SetResultRepository setResultRepository,
                                   MatchOutcomeRepository matchOutcomeRepository,
                                   TeamAvatarRatingRepository teamAvatarRatingRepository,
                                   TeamAvatarRepository teamAvatarRepository,
                                   AuditLogRepository auditLogRepository,
                                   TournamentRuleResolver ruleResolver,
                                   ApplicationEventPublisher eventPublisher) {
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
    }

    // ---------------------------------------------------------------------------
    // Primary entry point (AC1, AC2)
    // ---------------------------------------------------------------------------

    /**
     * Executes the full 13-step cascade recompute flow for a single set result (AC1, AC2, D-21).
     *
     * <p>Steps 1–11 execute within a single {@code REPEATABLE_READ} transaction. Step 12
     * (event publication) fires after commit on the listener side. Step 13 returns void.
     *
     * <p>All repository calls carry the active {@link de.vvwt.tm.domain.repo.TenantContext}
     * (AC27, DEC-17).
     *
     * @param input the set result to register (must not be {@code null})
     * @throws ValidationException      if the set score fails the {@link de.vvwt.tm.domain.rules.SetValidationRule} (AC3)
     * @throws IllegalArgumentException if the match or tournament is not found
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void registerMatchResult(SetResultInput input) {
        UUID correlationId = UUID.randomUUID(); // AC25: per-cascade correlation UUID

        log.info("[cascade] START matchId={} setIndex={} team1={} team2={} actor={} correlationId={}",
                input.matchId(), input.setIndex(), input.team1Points(), input.team2Points(),
                input.actorId(), correlationId);

        // -----------------------------------------------------------------------
        // Resolve entities needed throughout the cascade
        // -----------------------------------------------------------------------
        Match match = matchRepository.findById(input.matchId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Match not found: " + input.matchId() + " correlationId=" + correlationId));

        Tournament tournament = tournamentRepository.findById(match.getTournamentId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Tournament not found: " + match.getTournamentId()
                        + " matchId=" + input.matchId() + " correlationId=" + correlationId));

        MatchFormat format = MatchFormat.fromPersistedName(tournament.getMatchFormat());

        // -----------------------------------------------------------------------
        // Step 1 — Validate via SetValidationRule (AC3)
        // -----------------------------------------------------------------------
        SetValidationRule validationRule = ruleResolver.resolveSetValidationRule(tournament);
        ValidationResult validationResult = validationRule.isSetClosed(
                input.team1Points(), input.team2Points(), input.setIndex(), format);

        log.info("[cascade] Step1 validation={} correlationId={}", validationResult, correlationId);

        if (!validationResult.isClosed()) {
            log.warn("[cascade] FAIL Step1: validation rejected — {} correlationId={}", validationResult.getReason(), correlationId);
            throw new ValidationException(validationResult.getReason());
        }

        // Derive set state from validation result winner hint
        SetState setStateNew = validationResult.getWinnerHint()
                .map(ms -> {
                    if (ms == MatchState.FINISHED_WINNER1) return SetState.WINNER1;
                    if (ms == MatchState.FINISHED_WINNER2) return SetState.WINNER2;
                    return SetState.STANDOFF;
                })
                .orElse(SetState.OPEN);

        // -----------------------------------------------------------------------
        // Step 2 — Persist SetResult (INSERT or UPDATE) + audit_log row (AC4)
        // -----------------------------------------------------------------------
        Optional<SetResult> existing = setResultRepository.findByMatchIdAndSetIndex(
                input.matchId(), input.setIndex());

        Integer team1PointsOld = null;
        Integer team2PointsOld = null;
        Integer setStateOld = null;
        boolean isUpdate = existing.isPresent();

        if (isUpdate) {
            SetResult old = existing.get();
            team1PointsOld = old.getTeam1Points();
            team2PointsOld = old.getTeam2Points();
            setStateOld = old.getSetStateCode();

            SetResult updated = new SetResult(
                    input.matchId(), input.setIndex(), match.getTenantId(), match.getPhaseId(),
                    input.team1Points(), input.team2Points(), setStateNew.getLegacyCode(),
                    LocalDateTime.now(), null);
            setResultRepository.update(updated);

            log.info("[cascade] Step2 UPDATE set_result ({},{}) old=({},{}) new=({},{}) correlationId={}",
                    input.matchId(), input.setIndex(),
                    team1PointsOld, team2PointsOld,
                    input.team1Points(), input.team2Points(), correlationId);
        } else {
            LocalDateTime now = LocalDateTime.now();
            SetResult inserted = new SetResult(
                    input.matchId(), input.setIndex(), match.getTenantId(), match.getPhaseId(),
                    input.team1Points(), input.team2Points(), setStateNew.getLegacyCode(),
                    now, now);
            setResultRepository.insert(inserted);

            log.info("[cascade] Step2 INSERT set_result ({},{}) new=({},{}) correlationId={}",
                    input.matchId(), input.setIndex(),
                    input.team1Points(), input.team2Points(), correlationId);
        }

        // Write audit_log row (AC4) — include source distinction (E06S06 AC7, AC8)
        AuditLogEntry auditEntry = new AuditLogEntry(
                UUID.randomUUID(), match.getTenantId(), input.matchId(), input.setIndex(),
                team1PointsOld, team2PointsOld,
                input.team1Points(), input.team2Points(),
                setStateOld, setStateNew.getLegacyCode(),
                input.actorId(), input.reason(),
                LocalDateTime.now(),
                input.sourceType(), input.sourceDeviceId());
        auditLogRepository.save(auditEntry);

        log.debug("[cascade] Step2 audit_log written id={} correlationId={}", auditEntry.getId(), correlationId);

        // -----------------------------------------------------------------------
        // Step 3 — Aggregate all SetResults for the match (AC5)
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

        log.debug("[cascade] Step3 aggregate sets={} t1setsWon={} t2setsWon={} t1balls={} t2balls={} correlationId={}",
                setCount, team1SetsWon, team2SetsWon, team1BallsWon, team2BallsWon, correlationId);

        // -----------------------------------------------------------------------
        // Step 4 — Upsert MatchOutcome (AC6)
        // -----------------------------------------------------------------------
        MatchState derivedState = format.deriveMatchState(team1SetsWon, team2SetsWon, setCount);

        MatchOutcome outcome = new MatchOutcome(
                input.matchId(), match.getTenantId(),
                team1SetsWon, team1BallsWon,
                team2SetsWon, team2BallsWon,
                setCount, derivedState.getLegacyCode(),
                LocalDateTime.now());
        matchOutcomeRepository.save(outcome);

        log.info("[cascade] Step4 MatchOutcome upserted matchId={} derivedState={} correlationId={}",
                input.matchId(), derivedState, correlationId);

        // -----------------------------------------------------------------------
        // Step 5 — Derive match state (AC7)
        //          (derivedState already computed in step 4)
        // -----------------------------------------------------------------------
        log.debug("[cascade] Step5 derivedState={} correlationId={}", derivedState, correlationId);

        // -----------------------------------------------------------------------
        // Step 6 — Diff-check & update Match.state only if changed (AC8)
        // -----------------------------------------------------------------------
        MatchState previousState = match.getMatchState();
        if (previousState != derivedState) {
            match.setMatchState(derivedState);
            matchRepository.save(match);
            log.info("[cascade] Step6 Match.state updated {} -> {} matchId={} correlationId={}",
                    previousState, derivedState, input.matchId(), correlationId);
        } else {
            log.debug("[cascade] Step6 Match.state unchanged ({}) — no-op UPDATE matchId={} correlationId={}",
                    previousState, input.matchId(), correlationId);
        }

        // -----------------------------------------------------------------------
        // Steps 7–8 — Refresh avatar ratings (AC9, AC10)
        // -----------------------------------------------------------------------
        ScoringRule scoringRule = ruleResolver.resolveScoringRule(tournament);

        log.debug("[cascade] Step7 refreshAvatarRating avatar1Id={} correlationId={}",
                match.getMemberAvatar1Id(), correlationId);
        refreshAvatarRating(match.getMemberAvatar1Id(), match.getPhaseId(), scoringRule, format, correlationId);

        log.debug("[cascade] Step8 refreshAvatarRating avatar2Id={} correlationId={}",
                match.getMemberAvatar2Id(), correlationId);
        refreshAvatarRating(match.getMemberAvatar2Id(), match.getPhaseId(), scoringRule, format, correlationId);

        // -----------------------------------------------------------------------
        // Step 9 — GroupTable no-op (AC11)
        // GroupTable is computed on read per D-24 / S-22. No write needed.
        // -----------------------------------------------------------------------

        // -----------------------------------------------------------------------
        // Step 10 — Auto-advance Phase.current_lap_number if all matches in current lap terminal (AC12)
        // -----------------------------------------------------------------------
        Phase phase = phaseRepository.findById(match.getPhaseId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Phase not found: " + match.getPhaseId() + " correlationId=" + correlationId));

        int previousLapNumber = phase.getCurrentLapNumber();
        int newLapNumber = previousLapNumber;

        // Only check matches in the same lap as the match we just scored
        Integer currentMatchLap = match.getLapNumber();
        if (currentMatchLap != null) {
            List<Match> phaseMatches = matchRepository.findByPhaseId(match.getPhaseId());
            boolean allTerminalInLap = phaseMatches.stream()
                    .filter(m -> currentMatchLap.equals(m.getLapNumber()))
                    .allMatch(m -> isTerminalState(m.getMatchState()));

            if (allTerminalInLap) {
                phase.setCurrentLapNumber(previousLapNumber + 1);
                phaseRepository.save(phase);
                newLapNumber = previousLapNumber + 1;
                log.info("[cascade] Step10 lap auto-advanced {} -> {} phaseId={} correlationId={}",
                        previousLapNumber, newLapNumber, phase.getId(), correlationId);
            } else {
                log.debug("[cascade] Step10 lap NOT advanced (not all matches terminal in lap {}) phaseId={} correlationId={}",
                        currentMatchLap, phase.getId(), correlationId);
            }
        } else {
            log.debug("[cascade] Step10 skipped — match has no lapNumber assigned yet matchId={} correlationId={}",
                    input.matchId(), correlationId);
        }

        // -----------------------------------------------------------------------
        // Step 11 — Commit (handled by @Transactional — no explicit action)
        // -----------------------------------------------------------------------

        // -----------------------------------------------------------------------
        // Step 12 — Emit MatchResultChangedEvent AFTER_COMMIT (AC14)
        // Spring defers listener invocation until after commit when listeners use
        // @TransactionalEventListener(phase = AFTER_COMMIT)
        // -----------------------------------------------------------------------
        MatchResultChangedEvent event = new MatchResultChangedEvent(
                this, match.getTenantId(), match.getTournamentId(), match.getPhaseId(), input.matchId(),
                previousState, derivedState, input.actorId(),
                previousLapNumber, newLapNumber, correlationId);
        eventPublisher.publishEvent(event);

        log.info("[cascade] Step12 event published {} correlationId={}", event, correlationId);

        // E07S06 AC4: Publish LapAdvancedEvent when a real lap advance occurred.
        // Only published if previousLapNumber != newLapNumber so display devices
        // know to refresh their match grid for the new lap.
        if (previousLapNumber != newLapNumber) {
            LapAdvancedEvent lapEvent = new LapAdvancedEvent(
                    this, match.getTenantId(), match.getTournamentId(), match.getPhaseId(),
                    previousLapNumber, newLapNumber, correlationId);
            eventPublisher.publishEvent(lapEvent);
            log.info("[cascade] Step12a LapAdvancedEvent published {} -> {} phaseId={} correlationId={}",
                    previousLapNumber, newLapNumber, match.getPhaseId(), correlationId);
        }

        // -----------------------------------------------------------------------
        // Step 13 — Return void (AC15)
        // -----------------------------------------------------------------------
        log.info("[cascade] DONE matchId={} newState={} correlationId={}",
                input.matchId(), derivedState, correlationId);
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Refreshes the {@link TeamAvatarRating} for a given avatar in a phase (AC9/AC10).
     *
     * <p>Reads ALL terminal matches for the avatar in the phase, recomputes aggregates
     * from scratch, and upserts the rating row. This "full-recompute" approach avoids
     * incremental-delta bugs on corrections (per Brief T-11 rationale).
     *
     * @param avatarId      the avatar whose rating to refresh
     * @param phaseId       the phase scoping the query
     * @param scoringRule   the resolved scoring rule for point computation
     * @param format        the match format for tie-break detection
     * @param correlationId logging correlation UUID
     */
    private void refreshAvatarRating(UUID avatarId, UUID phaseId,
                                     ScoringRule scoringRule, MatchFormat format,
                                     UUID correlationId) {
        List<Match> terminalMatches = matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatarId);

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
                log.warn("[cascade] refreshAvatarRating: MatchOutcome not found for terminal match {} — skipping. correlationId={}",
                        m.getId(), correlationId);
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

        double setQuotient = totalSetsLost == 0
                ? (totalSetsWon == 0 ? 0.0 : Double.MAX_VALUE)
                : (double) totalSetsWon / totalSetsLost;
        double ballQuotient = totalBallsLost == 0
                ? (totalBallsWon == 0 ? 0.0 : Double.MAX_VALUE)
                : (double) totalBallsWon / totalBallsLost;

        // Preserve the isWithoutAssessment flag from the existing rating (or load from TeamAvatar)
        boolean isWithoutAssessment = false;
        Optional<TeamAvatarRating> existingRating = teamAvatarRatingRepository.findById(avatarId);
        if (existingRating.isPresent()) {
            isWithoutAssessment = existingRating.get().isWithoutAssessment();
        } else {
            // Rating not yet created — check TeamAvatar (flag is copied at avatar creation)
            Optional<TeamAvatar> teamAvatar = teamAvatarRepository.findById(avatarId);
            // TeamAvatar does not carry isWithoutAssessment directly — it is initialized to false
            // in the rating row at first creation. Default: false.
            isWithoutAssessment = false;
        }

        TeamAvatarRating rating = new TeamAvatarRating(
                avatarId, null, // tenantId will be set by TenantScopedRepository.save()
                matchCount, totalSetCount, totalPoints,
                totalSetsWon, totalSetsLost,
                totalBallsWon, totalBallsLost,
                setQuotient, ballQuotient,
                isWithoutAssessment, LocalDateTime.now());
        teamAvatarRatingRepository.save(rating);

        log.debug("[cascade] refreshAvatarRating avatarId={} matchCount={} points={} setsWon={} setsLost={} correlationId={}",
                avatarId, matchCount, totalPoints, totalSetsWon, totalSetsLost, correlationId);
    }

    /**
     * Returns {@code true} if the given {@link MatchState} is terminal for lap-advance purposes (AC12).
     *
     * <p>Terminal states: FINISHED_WINNER1, FINISHED_WINNER2, FINISHED_STANDOFF, CANCELED.
     * All other states (OPEN, ENABLED, INPROGRESS, ONCHECK) are non-terminal.
     *
     * @param state the match state to check
     * @return {@code true} if terminal
     */
    private boolean isTerminalState(MatchState state) {
        return state == MatchState.FINISHED_WINNER1
                || state == MatchState.FINISHED_WINNER2
                || state == MatchState.FINISHED_STANDOFF
                || state == MatchState.CANCELED;
    }
}
