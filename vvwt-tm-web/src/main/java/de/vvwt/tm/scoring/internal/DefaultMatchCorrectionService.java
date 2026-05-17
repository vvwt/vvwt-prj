// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.MatchCorrectionInput;
import de.vvwt.tm.scoring.MatchCorrectionResult;
import de.vvwt.tm.scoring.MatchCorrectionService;
import de.vvwt.tm.scoring.SetScoreCorrection;
import de.vvwt.tm.scoring.TournamentRuleResolver;
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
import de.vvwt.tm.tournament.SetResultRepository;
import de.vvwt.tm.tournament.SetState;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.MatchResultChangedEvent;
import de.vvwt.tm.tournament.exceptions.MatchStateGuardException;
import de.vvwt.tm.tournament.exceptions.PhaseStateGuardException;
import de.vvwt.tm.tournament.exceptions.StandoffFormatMismatchException;
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
 * Default implementation of {@link MatchCorrectionService} — operator match-score correction and
 * Nacherfassung cascade (E48S25, DEC-35, DEC-58, updated E56S02 for DEC-74).
 *
 * <p>This service is a peer to {@link DefaultScoringService}, sharing the same repository
 * injections directly. It does NOT call {@code ScoringService.registerMatchResult()} in a loop
 * (which would acquire the DEC-37 lock N times). Instead it acquires the lock ONCE for the entire
 * batch and runs a simplified cascade (Steps 2-8 of the scoring cascade) followed by a
 * forward-only, current-lap-guarded lap-advance (Step 10 per DEC-74).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-35 — implementation in {@code de.vvwt.tm.scoring.internal}; interface in {@code
 *       de.vvwt.tm.scoring}
 *   <li>DEC-37 Clause B — {@code tournamentRepository.findByIdForUpdate(tournamentId)} is the FIRST
 *       action for non-CANCELED paths; CANCELED audit-only path skips the lock; the lap-advance
 *       write is co-committed inside the same transaction (no new lock boundary)
 *   <li>DEC-58 — naming canon: {@code DefaultMatchCorrectionService}
 *   <li>DEC-74 (amends DEC-65 D-3/D-5) — this implementation advances {@code
 *       phase.currentLapNumber} forward-only when the corrected match's {@code lapNumber} equals
 *       the phase's current {@code currentLapNumber} AND every match of the phase with that {@code
 *       lapNumber} is terminal (same match set and terminal predicate as {@link
 *       DefaultScoringService} Step 10); on a fire it advances to {@code lapNumber+1} or to
 *       sentinel {@code 0} if last lap; never backward, never from a non-current lap
 *   <li>DEC-22 — all new tests were RED-first (E56S02); existing tests from E48S25 preserved and
 *       migrated per DEC-74 D-6
 * </ul>
 *
 * <h2>Guard ordering (before any DB write)</h2>
 *
 * <ol>
 *   <li>Match lookup (tenant-scoped; returns empty → {@link java.util.NoSuchElementException})
 *   <li>{@link PhaseStateGuardException} if phase is not {@code ACTIVE}
 *   <li>{@link MatchStateGuardException} if match is {@code INPROGRESS} or {@code ONCHECK}
 *   <li>Standoff pre-check: if submitted sets produce equal setsWon AND {@code
 *       !format.allowsTies()} → {@link StandoffFormatMismatchException}
 * </ol>
 *
 * <h2>CANCELED audit-only path</h2>
 *
 * <p>For CANCELED matches: no DEC-37 lock, no cascade, no WS event, no lap-advance. Only {@code
 * audit_log} rows are written (one per submitted set correction). Returns {@code auditOnly=true}.
 *
 * @since E48S25, updated E56S02 (DEC-74 operationalization)
 * @see MatchCorrectionService
 * @see DefaultScoringService
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout</a>
 * @see <a href="DEC-37">DEC-37 Clause B — pessimistic DB lock</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate</a>
 * @see <a href="DEC-74">DEC-74 — path-independent lap-advance (amends DEC-65 D-3/D-5)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 * @see <a href="E56S02">E56S02 — Operationalize DEC-74</a>
 */
@Service("tmMatchCorrectionService")
public class DefaultMatchCorrectionService implements MatchCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMatchCorrectionService.class);

    private static final String SOURCE_TYPE_ADMIN = "ADMIN";

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

    public DefaultMatchCorrectionService(
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
    // Primary entry point
    // ---------------------------------------------------------------------------

    /**
     * Corrects match set scores and, if the correction completes the lap currently in play,
     * advances {@code phase.currentLapNumber} (DEC-74 — forward-only, current-lap-guarded).
     *
     * <p><b>Guard ordering (before any DB write):</b>
     *
     * <ol>
     *   <li>Match lookup (tenant-scoped)
     *   <li>Phase guard — must be ACTIVE
     *   <li>Match-state guard — must not be INPROGRESS or ONCHECK
     *   <li>Standoff pre-check — equal setsWon on non-tie format → reject
     * </ol>
     *
     * <p><b>DEC-74 lap-advance (Step 10):</b> After the cascade, this method conditionally advances
     * {@code phase.currentLapNumber} — forward-only, guarded to the lap in play:
     *
     * <ul>
     *   <li>(a) {@code match.lapNumber} is non-null; AND
     *   <li>(b) {@code match.lapNumber == phase.currentLapNumber} (lap currently in play); AND
     *   <li>(c) every match of the phase with that {@code lapNumber} is terminal.
     * </ul>
     *
     * On a fire: advances to {@code lapNumber+1}, or writes sentinel {@code 0} if last lap. The
     * counter never moves backward and never advances from a non-current lap (DEC-74 D-3).
     *
     * @param input the correction input
     * @return the correction result
     * @throws PhaseStateGuardException if the phase is not ACTIVE
     * @throws MatchStateGuardException if the match is INPROGRESS or ONCHECK
     * @throws StandoffFormatMismatchException if submitted scores would produce STANDOFF on a
     *     non-tie format
     * @throws java.util.NoSuchElementException if the match is not found for the current tenant
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MatchCorrectionResult correctMatchSets(MatchCorrectionInput input) {
        UUID correlationId = UUID.randomUUID();

        log.info(
                "[correction] START matchId={} phaseId={} tournamentId={} sets={} actor={}"
                        + " correlationId={}",
                input.matchId(),
                input.phaseId(),
                input.tournamentId(),
                input.sets().size(),
                input.actorId(),
                correlationId);

        // -----------------------------------------------------------------------
        // Guard Step 1 — Resolve match (tenant-scoped)
        // -----------------------------------------------------------------------
        Match match =
                matchRepository
                        .findById(input.matchId())
                        .orElseThrow(
                                () ->
                                        new java.util.NoSuchElementException(
                                                "Match not found: "
                                                        + input.matchId()
                                                        + " correlationId="
                                                        + correlationId));

        // -----------------------------------------------------------------------
        // Guard Step 2 — Phase must be ACTIVE (AC-GUARD-PHASE-STATUS)
        // -----------------------------------------------------------------------
        Phase phase =
                phaseRepository
                        .findById(input.phaseId())
                        .orElseThrow(
                                () ->
                                        new java.util.NoSuchElementException(
                                                "Phase not found: "
                                                        + input.phaseId()
                                                        + " correlationId="
                                                        + correlationId));

        if (!"ACTIVE".equals(phase.getStatus())) {
            throw new PhaseStateGuardException(
                    "Phase must be ACTIVE for correction. Current status: "
                            + phase.getStatus()
                            + " phaseId="
                            + input.phaseId()
                            + " correlationId="
                            + correlationId);
        }

        // -----------------------------------------------------------------------
        // Guard Step 3 — Match must not be INPROGRESS or ONCHECK (AC-GUARD-MATCH-STATE)
        // -----------------------------------------------------------------------
        MatchState matchState = match.getMatchState();
        if (matchState == MatchState.INPROGRESS || matchState == MatchState.ONCHECK) {
            throw new MatchStateGuardException(
                    "Correction not allowed for live-scoring match state: "
                            + matchState.name()
                            + " matchId="
                            + input.matchId()
                            + " correlationId="
                            + correlationId);
        }

        // -----------------------------------------------------------------------
        // CANCELED branch: audit-only, no lock, no cascade (AC-CANCELED-AUDIT-ONLY)
        // -----------------------------------------------------------------------
        if (matchState == MatchState.CANCELED) {
            return applyCanceledAuditOnly(input, match, correlationId);
        }

        // -----------------------------------------------------------------------
        // Guard Step 4 — Standoff pre-check (AC-GUARD-STANDOFF-FORMAT)
        // Must happen BEFORE acquiring the lock (no DB writes yet)
        // -----------------------------------------------------------------------
        // Note: we resolve the tournament format from input.tournamentId() without a lock here
        // (read-only lookup) because guard checks must precede the lock per the story's AC
        // that says "guard fires before any DB write" and DEC-37 lock-first applies to the
        // write path only.
        // For the standoff guard, we need the tournament's matchFormat without the FOR UPDATE lock.
        Tournament tournamentForGuard =
                tournamentRepository
                        .findById(input.tournamentId())
                        .orElseThrow(
                                () ->
                                        new java.util.NoSuchElementException(
                                                "Tournament not found: "
                                                        + input.tournamentId()
                                                        + " correlationId="
                                                        + correlationId));

        MatchFormat format = MatchFormat.fromPersistedName(tournamentForGuard.getMatchFormat());

        if (!format.isAllowsTies()) {
            // Count submitted set wins
            int t1Wins = 0;
            int t2Wins = 0;
            for (SetScoreCorrection s : input.sets()) {
                if (s.team1Points() > s.team2Points()) {
                    t1Wins++;
                } else if (s.team2Points() > s.team1Points()) {
                    t2Wins++;
                }
            }
            if (t1Wins == t2Wins && !input.sets().isEmpty()) {
                throw new StandoffFormatMismatchException(
                        "Submitted set scores produce a tied outcome (standoff), but the match"
                                + " format "
                                + format.name()
                                + " does not allow ties. matchId="
                                + input.matchId()
                                + " correlationId="
                                + correlationId);
            }
        }

        // -----------------------------------------------------------------------
        // DEC-37 Clause B — FIRST action: acquire per-tournament pessimistic DB lock
        // (Lock acquired ONCE for the entire batch — AC-NACHERFASSUNG single-lock invariant)
        // -----------------------------------------------------------------------
        Tournament tournament = tournamentRepository.findByIdForUpdate(input.tournamentId());

        log.debug(
                "[correction] lock acquired tournamentId={} correlationId={}",
                tournament.getId(),
                correlationId);

        // -----------------------------------------------------------------------
        // Cascade: upsert SetResult rows + audit_log for each set in the batch
        // -----------------------------------------------------------------------
        LocalDateTime now = LocalDateTime.now();
        for (SetScoreCorrection setCorr : input.sets()) {
            // Derive set state from the submitted score
            // For correction: if t1 > t2 → WINNER1, t2 > t1 → WINNER2, equal → STANDOFF
            // (STANDOFF on non-tie format is already guarded above)
            SetState newSetState;
            if (setCorr.team1Points() > setCorr.team2Points()) {
                newSetState = SetState.WINNER1;
            } else if (setCorr.team2Points() > setCorr.team1Points()) {
                newSetState = SetState.WINNER2;
            } else {
                newSetState = SetState.STANDOFF;
            }

            // SELECT-before-INSERT/UPDATE for old values (for audit_log)
            Optional<SetResult> existing =
                    setResultRepository.findByMatchIdAndSetIndex(
                            input.matchId(), setCorr.setIndex());

            Integer t1Old = null;
            Integer t2Old = null;
            Integer setStateOld = null;

            if (existing.isPresent()) {
                SetResult old = existing.get();
                t1Old = old.getTeam1Points();
                t2Old = old.getTeam2Points();
                setStateOld = old.getSetStateCode();

                SetResult updated =
                        new SetResult(
                                input.matchId(),
                                setCorr.setIndex(),
                                input.phaseId(),
                                setCorr.team1Points(),
                                setCorr.team2Points(),
                                newSetState.getLegacyCode(),
                                now,
                                null);
                setResultRepository.update(updated);
            } else {
                SetResult inserted =
                        new SetResult(
                                input.matchId(),
                                setCorr.setIndex(),
                                input.phaseId(),
                                setCorr.team1Points(),
                                setCorr.team2Points(),
                                newSetState.getLegacyCode(),
                                now,
                                now);
                setResultRepository.insert(inserted);
            }

            // Write audit_log row; tournamentId first (E55S13 AC-IMPL-CALL-SITE-UPDATES)
            AuditLogEntry auditEntry =
                    new AuditLogEntry(
                            input.tournamentId(),
                            UUID.randomUUID(),
                            input.matchId(),
                            setCorr.setIndex(),
                            t1Old,
                            t2Old,
                            setCorr.team1Points(),
                            setCorr.team2Points(),
                            setStateOld,
                            newSetState.getLegacyCode(),
                            input.actorId(),
                            input.reason(),
                            now,
                            SOURCE_TYPE_ADMIN,
                            null);
            auditLogRepository.save(auditEntry);

            log.debug(
                    "[correction] setIndex={} upserted + audit_log written correlationId={}",
                    setCorr.setIndex(),
                    correlationId);
        }

        // -----------------------------------------------------------------------
        // Aggregate all SetResults for match-outcome recompute
        // -----------------------------------------------------------------------
        List<SetResult> allSets = setResultRepository.findByMatchId(input.matchId());
        int team1SetsWon = 0;
        int team2SetsWon = 0;
        int team1Balls = 0;
        int team2Balls = 0;

        for (SetResult sr : allSets) {
            SetState ss = sr.getSetState();
            if (ss == SetState.WINNER1) team1SetsWon++;
            if (ss == SetState.WINNER2) team2SetsWon++;
            team1Balls += sr.getTeam1Points();
            team2Balls += sr.getTeam2Points();
        }
        int setCount = allSets.size();

        // Derive new match state from aggregated set results
        MatchState derivedState = format.deriveMatchState(team1SetsWon, team2SetsWon, setCount);

        // Upsert MatchOutcome
        MatchOutcome outcome =
                new MatchOutcome(
                        input.matchId(),
                        team1SetsWon,
                        team1Balls,
                        team2SetsWon,
                        team2Balls,
                        setCount,
                        derivedState.getLegacyCode(),
                        now);
        matchOutcomeRepository.save(outcome);

        // Update Match.state if changed
        MatchState previousMatchState = match.getMatchState();
        if (previousMatchState != derivedState) {
            match.setMatchState(derivedState);
            matchRepository.save(match);
        }

        // -----------------------------------------------------------------------
        // Refresh avatar ratings (Steps 7-8 of scoring cascade)
        // (Full-recompute, no incremental delta — same pattern as DefaultScoringService)
        // -----------------------------------------------------------------------
        de.vvwt.tm.scoring.ScoringRule scoringRule = ruleResolver.resolve(tournament).scoringRule();
        refreshAvatarRating(
                match.getMemberAvatar1Id(), input.phaseId(), scoringRule, format, correlationId);
        refreshAvatarRating(
                match.getMemberAvatar2Id(), input.phaseId(), scoringRule, format, correlationId);

        // -----------------------------------------------------------------------
        // Step 10 (DEC-74) — forward-only, current-lap-guarded lap advance.
        // Mirrors DefaultScoringService Step 10 allTerminalInLap + sentinel logic.
        // Guard: (a) lapNumber non-null AND (b) lapNumber == currentLapNumber AND (c) all terminal.
        // -----------------------------------------------------------------------
        int previousLapNumber = phase.getCurrentLapNumber();
        int newLapNumber = previousLapNumber;

        Integer matchLapNumber = match.getLapNumber();
        if (matchLapNumber != null && matchLapNumber.equals(previousLapNumber)) {
            // Guard (a) and (b) passed — check (c): all matches in this lap terminal
            List<Match> phaseMatches = matchRepository.findByPhaseId(match.getPhaseId());
            boolean allTerminalInLap =
                    phaseMatches.stream()
                            .filter(m -> matchLapNumber.equals(m.getLapNumber()))
                            .allMatch(m -> isTerminalState(m.getMatchState()));

            if (allTerminalInLap) {
                // Derive lapCount = max(match.lapNumber) for this phase (DEC-65 D-1).
                int lapCount =
                        phaseMatches.stream()
                                .mapToInt(m -> m.getLapNumber() != null ? m.getLapNumber() : 0)
                                .max()
                                .orElse(0);
                boolean isLastLap = matchLapNumber >= lapCount;
                if (isLastLap) {
                    // Last-lap finalization: write sentinel-0; phase stays ACTIVE (DEC-74 D-4).
                    newLapNumber = 0;
                    phase.setCurrentLapNumber(0);
                    log.info(
                            "[correction] Step10 last-lap sentinel written (lap {}/{}) phaseId={}"
                                    + " correlationId={}",
                            matchLapNumber,
                            lapCount,
                            phase.getId(),
                            correlationId);
                } else {
                    // Non-last lap: advance to next lap (DEC-74 D-2).
                    newLapNumber = matchLapNumber + 1;
                    phase.setCurrentLapNumber(newLapNumber);
                    log.info(
                            "[correction] Step10 lap advanced {} -> {} phaseId={}"
                                    + " correlationId={}",
                            previousLapNumber,
                            newLapNumber,
                            phase.getId(),
                            correlationId);
                }
                phaseRepository.save(phase);
            } else {
                log.debug(
                        "[correction] Step10 lap NOT advanced (not all matches terminal in lap {})"
                                + " phaseId={} correlationId={}",
                        matchLapNumber,
                        phase.getId(),
                        correlationId);
            }
        } else {
            log.debug(
                    "[correction] Step10 skipped — guard (a)/(b) not met: matchLapNumber={},"
                            + " currentLapNumber={} matchId={} correlationId={}",
                    matchLapNumber,
                    previousLapNumber,
                    input.matchId(),
                    correlationId);
        }

        // -----------------------------------------------------------------------
        // Emit MatchResultChangedEvent (post-commit)
        // -----------------------------------------------------------------------
        MatchResultChangedEvent event =
                new MatchResultChangedEvent(
                        this,
                        tenantContext.current(),
                        match.getTournamentId(),
                        match.getPhaseId(),
                        input.matchId(),
                        previousMatchState,
                        derivedState,
                        input.actorId(),
                        previousLapNumber,
                        newLapNumber,
                        correlationId);
        eventPublisher.publishEvent(event);

        log.info(
                "[correction] DONE matchId={} prevState={} newState={} correlationId={}",
                input.matchId(),
                previousMatchState,
                derivedState,
                correlationId);

        return new MatchCorrectionResult(derivedState, false);
    }

    // ---------------------------------------------------------------------------
    // CANCELED audit-only path (AC-CANCELED-AUDIT-ONLY)
    // ---------------------------------------------------------------------------

    /**
     * Applies audit-only correction for a CANCELED match.
     *
     * <p>No DEC-37 lock, no cascade, no WS event. Only {@code audit_log} rows are written. Returns
     * {@code auditOnly=true}.
     */
    private MatchCorrectionResult applyCanceledAuditOnly(
            MatchCorrectionInput input, Match match, UUID correlationId) {
        log.info(
                "[correction] CANCELED audit-only matchId={} correlationId={}",
                input.matchId(),
                correlationId);

        LocalDateTime now = LocalDateTime.now();
        for (SetScoreCorrection setCorr : input.sets()) {
            // For audit-only corrections of CANCELED matches, we look up any existing set result
            // for old values but do NOT write to set_result.
            Optional<SetResult> existing =
                    setResultRepository.findByMatchIdAndSetIndex(
                            input.matchId(), setCorr.setIndex());

            Integer t1Old = existing.map(SetResult::getTeam1Points).orElse(null);
            Integer t2Old = existing.map(SetResult::getTeam2Points).orElse(null);
            Integer setStateOld = existing.map(SetResult::getSetStateCode).orElse(null);

            // Derive an indicative set state for the audit log
            SetState newSetState;
            if (setCorr.team1Points() > setCorr.team2Points()) {
                newSetState = SetState.WINNER1;
            } else if (setCorr.team2Points() > setCorr.team1Points()) {
                newSetState = SetState.WINNER2;
            } else {
                newSetState = SetState.STANDOFF;
            }

            // tournamentId first (E55S13 AC-IMPL-CALL-SITE-UPDATES)
            AuditLogEntry auditEntry =
                    new AuditLogEntry(
                            input.tournamentId(),
                            UUID.randomUUID(),
                            input.matchId(),
                            setCorr.setIndex(),
                            t1Old,
                            t2Old,
                            setCorr.team1Points(),
                            setCorr.team2Points(),
                            setStateOld,
                            newSetState.getLegacyCode(),
                            input.actorId(),
                            input.reason(),
                            now,
                            SOURCE_TYPE_ADMIN,
                            null);
            auditLogRepository.save(auditEntry);
        }

        log.info(
                "[correction] CANCELED audit-only DONE matchId={} auditEntries={} correlationId={}",
                input.matchId(),
                input.sets().size(),
                correlationId);

        return new MatchCorrectionResult(MatchState.CANCELED, true);
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Refreshes the {@link de.vvwt.tm.tournament.TeamAvatarRating} for a given avatar in a phase.
     *
     * <p>Full-recompute pattern (same as {@link DefaultScoringService#refreshAvatarRating}) — reads
     * ALL terminal matches, recomputes from scratch. Safe under the per-tournament DB lock.
     */
    private void refreshAvatarRating(
            UUID avatarId,
            UUID phaseId,
            de.vvwt.tm.scoring.ScoringRule scoringRule,
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
                        "[correction] refreshAvatarRating: MatchOutcome not found for terminal"
                                + " match {} — skipping. correlationId={}",
                        m.getId(),
                        correlationId);
                continue;
            }
            MatchOutcome mo = outcomeOpt.get();
            de.vvwt.tm.scoring.ScoringResult scoring = scoringRule.calculatePoints(mo, format);

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

        boolean isWithoutAssessment = false;
        Optional<TeamAvatarRating> existingRating = teamAvatarRatingRepository.findById(avatarId);
        if (existingRating.isPresent()) {
            isWithoutAssessment = existingRating.get().isWithoutAssessment();
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
    }

    /**
     * Returns {@code true} if the given {@link MatchState} is terminal.
     *
     * <p>Terminal states: FINISHED_WINNER1, FINISHED_WINNER2, FINISHED_STANDOFF, CANCELED. Mirrors
     * the identical predicate in {@link DefaultScoringService} per DEC-74 D-2 clause (c).
     */
    private boolean isTerminalState(MatchState state) {
        return state == MatchState.FINISHED_WINNER1
                || state == MatchState.FINISHED_WINNER2
                || state == MatchState.FINISHED_STANDOFF
                || state == MatchState.CANCELED;
    }
}
