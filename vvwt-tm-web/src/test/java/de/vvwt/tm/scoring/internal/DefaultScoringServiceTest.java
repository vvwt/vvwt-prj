package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.scoring.ScoringResult;
import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.ValidationResult;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.AuditLogRepository;
import de.vvwt.tm.tournament.Match;
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
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.LapAdvancedEvent;
import de.vvwt.tm.tournament.events.MatchResultChangedEvent;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link DefaultScoringService} — same-package white-box tests per DEC-36.
 *
 * <p>Tests reference {@link DefaultScoringService} directly (implementation class) because the test
 * class is co-located in {@code de.vvwt.tm.scoring.internal} — same package as the subject. This is
 * the DEC-36 white-box exception for same-package tests. Cross-package tests (e.g., CascadeLockIT)
 * MUST reference via {@link de.vvwt.tm.scoring.ScoringService} interface instead.
 *
 * <p>TDD Iron Law (DEC-22): every test in this class was written RED (failing) before the
 * corresponding production code was added to {@code DefaultScoringService}.
 *
 * @since E31S03
 * @see DefaultScoringService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — same-package white-box exemption</a>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultScoringServiceTest {

    // -----------------------------------------------------------------------
    // Mocks — all interfaces (DEC-35), except TournamentRuleResolver
    // (a concrete @Component — no interface, same package as its impl)
    // -----------------------------------------------------------------------

    @Mock private MatchRepository matchRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private SetResultRepository setResultRepository;
    @Mock private MatchOutcomeRepository matchOutcomeRepository;
    @Mock private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private TournamentRuleResolver ruleResolver;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SetValidationRule validationRule;
    @Mock private ScoringRule scoringRule;
    @Mock private TenantContext tenantContext;

    @InjectMocks private DefaultScoringService service;

    // -----------------------------------------------------------------------
    // Shared test fixtures
    // -----------------------------------------------------------------------

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID PHASE_ID = UUID.randomUUID();
    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final UUID AVATAR1_ID = UUID.randomUUID();
    private static final UUID AVATAR2_ID = UUID.randomUUID();

    private Tournament tournament;
    private Match match;
    private Phase phase;
    private SetResultInput input;

    @BeforeEach
    void setUp() {
        tournament = new Tournament();
        tournament.setId(TOURNAMENT_ID);
        tournament.setMatchFormat("BEST_OF_1"); // 1 set needed to win — simplifies test setup
        tournament.setScoringRuleId("standardVolleyball");
        tournament.setSetValidationRuleId("standardVolleyball");

        match = new Match();
        match.setId(MATCH_ID);
        match.setTournamentId(TOURNAMENT_ID);
        match.setPhaseId(PHASE_ID);
        match.setMemberAvatar1Id(AVATAR1_ID);
        match.setMemberAvatar2Id(AVATAR2_ID);
        match.setMatchState(MatchState.INPROGRESS);
        match.setLapNumber(1);

        phase = new Phase();
        phase.setId(PHASE_ID);
        phase.setTournamentId(TOURNAMENT_ID);
        phase.setCurrentLapNumber(0);

        input = SetResultInput.withTournament(TOURNAMENT_ID, MATCH_ID, 0, 25, 15, "actor1", null);
    }

    // -----------------------------------------------------------------------
    // Step 1 — Validation (AC-TDD-RegisterMatchResult-RED)
    // -----------------------------------------------------------------------

    /**
     * Cascade Step 1: when validation rejects the score, {@link ValidationException} is thrown and
     * no repository write occurs (AC-TDD-RegisterMatchResult-RED scenario (b): invalid input →
     * ValidationException).
     *
     * <p>TDD — RED was observed when {@code DefaultScoringService} was a skeleton throwing
     * UnsupportedOperationException.
     */
    @Test
    void registerMatchResult_throwsValidationException_whenScoreNotClosed() {
        // ARRANGE — tournament lock acquired first (DEC-37 Clause B)
        when(tournamentRepository.findByIdForUpdate(TOURNAMENT_ID)).thenReturn(tournament);
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(ruleResolver.resolve(tournament))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRule, validationRule));
        when(validationRule.isSetClosed(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(ValidationResult.open("score not closed"));

        // ACT + ASSERT
        assertThatThrownBy(() -> service.registerMatchResult(input))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("score not closed");

        // No set-result or audit write on validation failure
        verify(setResultRepository, never()).insert(any());
        verify(setResultRepository, never()).update(any());
        verify(auditLogRepository, never()).save(any());
    }

    /**
     * The FIRST action of {@code registerMatchResult} is {@code
     * tournamentRepository.findByIdForUpdate(input.tournamentId())} — before any other repository
     * read (DEC-37 Clause B, AC-LOCK-FIRST-ACTION).
     *
     * <p>This test verifies the lock call happens even when validation throws. The match lookup
     * occurs AFTER the lock.
     */
    @Test
    void registerMatchResult_acquiresLockFirst_beforeAnyOtherRepositoryRead() {
        // ARRANGE — lock returns tournament; match lookup returns match; validation rejects
        when(tournamentRepository.findByIdForUpdate(TOURNAMENT_ID)).thenReturn(tournament);
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(ruleResolver.resolve(tournament))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRule, validationRule));
        when(validationRule.isSetClosed(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(ValidationResult.open("rejected"));

        // ACT — ignore ValidationException
        try {
            service.registerMatchResult(input);
        } catch (ValidationException ignored) {
        }

        // ASSERT — lock was called with the input's tournament id
        verify(tournamentRepository).findByIdForUpdate(TOURNAMENT_ID);
    }

    // -----------------------------------------------------------------------
    // Step 2 — SetResult INSERT + audit_log (AC-TDD-RegisterMatchResult-RED happy path (a))
    // -----------------------------------------------------------------------

    /**
     * Happy path: SetResult is INSERTed (no existing result) and audit_log row is written. Cascade
     * continues through all 13 steps (simplified: single set closes match as WINNER1).
     *
     * <p>TDD — RED observed before Steps 2–13 were implemented.
     */
    @Test
    void registerMatchResult_insertsSetResult_happyPath() {
        // ARRANGE
        arrangeHappyPath_singleSetMatchWinner1();

        // ACT
        service.registerMatchResult(input);

        // ASSERT — Step 2: SetResult inserted
        ArgumentCaptor<SetResult> insertCaptor = ArgumentCaptor.forClass(SetResult.class);
        verify(setResultRepository).insert(insertCaptor.capture());
        SetResult inserted = insertCaptor.getValue();
        assertThat(inserted.getMatchId()).isEqualTo(MATCH_ID);
        assertThat(inserted.getSetIndex()).isEqualTo(0);
        assertThat(inserted.getTeam1Points()).isEqualTo(25);
        assertThat(inserted.getTeam2Points()).isEqualTo(15);

        // ASSERT — Step 2: audit_log written
        verify(auditLogRepository).save(any(AuditLogEntry.class));
    }

    /** SetResult is UPDATED on resubmission (existing result for same match + set index). */
    @Test
    void registerMatchResult_updatesSetResult_onResubmission() {
        // ARRANGE — existing set result present
        when(tournamentRepository.findByIdForUpdate(TOURNAMENT_ID)).thenReturn(tournament);
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(ruleResolver.resolve(tournament))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRule, validationRule));
        when(validationRule.isSetClosed(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(ValidationResult.winner1());

        SetResult existing = new SetResult(MATCH_ID, 0, PHASE_ID, 20, 18, 1, null, null);
        when(setResultRepository.findByMatchIdAndSetIndex(MATCH_ID, 0))
                .thenReturn(Optional.of(existing));
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(existing));
        when(matchOutcomeRepository.findById(MATCH_ID)).thenReturn(Optional.empty());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        when(scoringRule.calculatePoints(any(), any())).thenReturn(new ScoringResult(3, 0));
        when(teamAvatarRatingRepository.findById(any())).thenReturn(Optional.empty());
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(match));
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(any(), any()))
                .thenReturn(Collections.emptyList());

        // ACT
        service.registerMatchResult(input);

        // ASSERT — update called, not insert
        verify(setResultRepository).update(any(SetResult.class));
        verify(setResultRepository, never()).insert(any());
        verify(auditLogRepository).save(any(AuditLogEntry.class));
    }

    // -----------------------------------------------------------------------
    // Steps 3–6 — Aggregate sets → MatchOutcome → match state
    // -----------------------------------------------------------------------

    /** Steps 3–4: MatchOutcome is upserted with correct aggregated set counts. */
    @Test
    void registerMatchResult_upsertsMatchOutcome_happyPath() {
        arrangeHappyPath_singleSetMatchWinner1();

        service.registerMatchResult(input);

        ArgumentCaptor<MatchOutcome> outcomeCaptor = ArgumentCaptor.forClass(MatchOutcome.class);
        verify(matchOutcomeRepository).save(outcomeCaptor.capture());
        MatchOutcome outcome = outcomeCaptor.getValue();
        assertThat(outcome.getMatchId()).isEqualTo(MATCH_ID);
        assertThat(outcome.getTeam1SetsWon()).isEqualTo(1);
        assertThat(outcome.getTeam2SetsWon()).isEqualTo(0);
    }

    /**
     * Step 6 (diff-check): when derived match state equals the current state, no match update is
     * issued.
     */
    @Test
    void registerMatchResult_doesNotUpdateMatchState_whenUnchanged() {
        // Make match state already FINISHED_WINNER1 — same as what will be derived
        match.setMatchState(MatchState.FINISHED_WINNER1);

        arrangeHappyPath_singleSetMatchWinner1();

        service.registerMatchResult(input);

        // Match state did not change → no save
        verify(matchRepository, never()).save(any(Match.class));
    }

    /** Step 6: when derived state differs from current, match is updated. */
    @Test
    void registerMatchResult_updatesMatchState_whenChanged() {
        // match starts as INPROGRESS; cascade derives FINISHED_WINNER1
        match.setMatchState(MatchState.INPROGRESS);

        arrangeHappyPath_singleSetMatchWinner1();

        service.registerMatchResult(input);

        ArgumentCaptor<Match> matchCaptor = ArgumentCaptor.forClass(Match.class);
        verify(matchRepository).save(matchCaptor.capture());
        assertThat(matchCaptor.getValue().getMatchState()).isEqualTo(MatchState.FINISHED_WINNER1);
    }

    // -----------------------------------------------------------------------
    // Steps 7–8 — refreshAvatarRating
    // -----------------------------------------------------------------------

    /**
     * Step 7: {@code refreshAvatarRating} is called for avatar1 (happy path). TeamAvatarRating is
     * saved.
     */
    @Test
    void registerMatchResult_refreshesAvatarRating_forAvatar1() {
        arrangeHappyPath_singleSetMatchWinner1();

        service.registerMatchResult(input);

        // avatar1 was refreshed
        verify(teamAvatarRatingRepository).save(argCaptureForAvatarId(AVATAR1_ID));
    }

    /** Step 8: {@code refreshAvatarRating} is called for avatar2. */
    @Test
    void registerMatchResult_refreshesAvatarRating_forAvatar2() {
        arrangeHappyPath_singleSetMatchWinner1();

        service.registerMatchResult(input);

        verify(teamAvatarRatingRepository).save(argCaptureForAvatarId(AVATAR2_ID));
    }

    // -----------------------------------------------------------------------
    // Step 10 — Phase lap auto-advance
    // -----------------------------------------------------------------------

    /**
     * Step 10: lap number is advanced when ALL matches in the current lap are terminal.
     *
     * <p>AC-TEST-PHASETEST-MIGRATION (E56S01, DEC-65 D-1): Phase has 2 laps; lap 1 finishing is a
     * non-last-lap advance → {@code currentLapNumber} goes from 1 to 2.
     */
    @Test
    void registerMatchResult_advancesLapNumber_whenAllMatchesTerminal() {
        arrangeHappyPath_singleSetMatchWinner1();
        // Phase is ACTIVE in lap 1 (1-based, DEC-65); phase has 2 laps total.
        phase.setCurrentLapNumber(1);
        match.setMatchState(MatchState.INPROGRESS); // starts non-terminal
        // Lap-1 terminal match + open lap-2 match → lapCount=2, currentMatchLap=1 < lapCount →
        // advance
        Match terminalMatch = new Match();
        terminalMatch.setId(MATCH_ID);
        terminalMatch.setMatchState(MatchState.FINISHED_WINNER1);
        terminalMatch.setLapNumber(1);
        Match openLap2 = new Match();
        openLap2.setId(UUID.randomUUID());
        openLap2.setMatchState(MatchState.OPEN);
        openLap2.setLapNumber(2);
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(terminalMatch, openLap2));

        service.registerMatchResult(input);

        ArgumentCaptor<Phase> phaseCaptor = ArgumentCaptor.forClass(Phase.class);
        verify(phaseRepository).save(phaseCaptor.capture());
        assertThat(phaseCaptor.getValue().getCurrentLapNumber()).isEqualTo(2); // advanced lap 1→2
    }

    /** Step 10: lap is NOT advanced when not all matches in the current lap are terminal. */
    @Test
    void registerMatchResult_doesNotAdvanceLap_whenNotAllMatchesTerminal() {
        arrangeHappyPath_singleSetMatchWinner1();

        // Second match in same lap is still INPROGRESS
        Match otherMatch = new Match();
        otherMatch.setId(UUID.randomUUID());
        otherMatch.setMatchState(MatchState.INPROGRESS);
        otherMatch.setLapNumber(1);
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(match, otherMatch));

        service.registerMatchResult(input);

        verify(phaseRepository, never()).save(any(Phase.class));
    }

    // -----------------------------------------------------------------------
    // Step 12 — Event publication
    // -----------------------------------------------------------------------

    /**
     * Step 12: {@link MatchResultChangedEvent} is published after cascade.
     *
     * <p>AC-TEST-PHASETEST-MIGRATION (E56S01): fixture updated to use a 2-lap phase where lap 1
     * finalization is a non-last-lap advance (DEC-65 D-3). Phase seeded with {@code
     * currentLapNumber=1} (ACTIVE, playing lap 1); lap 2 exists (lapCount=2) → lap 1 finalize
     * advances to lap 2, NOT the sentinel. Two events: MatchResultChangedEvent + LapAdvancedEvent.
     */
    @Test
    void registerMatchResult_publishesMatchResultChangedEvent() {
        // AC-TEST-PHASETEST-MIGRATION: 2-lap setup; lap 1 finalization is non-last-lap advance
        phase.setCurrentLapNumber(1); // ACTIVE, lap 1 running (1-based)
        // lap-1 terminal match (being submitted)
        Match terminalLap1 = new Match();
        terminalLap1.setId(MATCH_ID);
        terminalLap1.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap1.setLapNumber(1);
        // lap-2 open match → lapCount=2 (non-last lap for lap 1)
        Match openLap2 = new Match();
        openLap2.setId(UUID.randomUUID());
        openLap2.setMatchState(MatchState.OPEN);
        openLap2.setLapNumber(2);
        arrangeHappyPath_singleSetMatchWinner1();
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(terminalLap1, openLap2));

        service.registerMatchResult(input);

        // Non-last-lap advance → 2 events: MatchResultChangedEvent + LapAdvancedEvent
        ArgumentCaptor<ApplicationEvent> eventCaptor =
                ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .anySatisfy(e -> assertThat(e).isInstanceOf(MatchResultChangedEvent.class));
    }

    /**
     * Step 12: {@link LapAdvancedEvent} is published when a non-last lap is finalized.
     *
     * <p>AC-TEST-PHASETEST-MIGRATION (E56S01): fixture updated to use a 2-lap phase where lap 1
     * finalization advances to lap 2 (DEC-65 D-3 non-last-lap branch). Phase seeded with {@code
     * currentLapNumber=1} (ACTIVE, playing lap 1).
     */
    @Test
    void registerMatchResult_publishesLapAdvancedEvent_whenLapAdvanced() {
        // AC-TEST-PHASETEST-MIGRATION: 2-lap setup; lap 1 finalization → advance to lap 2
        phase.setCurrentLapNumber(1); // ACTIVE, lap 1 running (1-based)
        Match terminalLap1 = new Match();
        terminalLap1.setId(MATCH_ID);
        terminalLap1.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap1.setLapNumber(1);
        Match openLap2 = new Match();
        openLap2.setId(UUID.randomUUID());
        openLap2.setMatchState(MatchState.OPEN);
        openLap2.setLapNumber(2);
        arrangeHappyPath_singleSetMatchWinner1();
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(terminalLap1, openLap2));

        service.registerMatchResult(input);

        // Non-last-lap advance → 2 events: MatchResultChangedEvent + LapAdvancedEvent
        ArgumentCaptor<ApplicationEvent> eventCaptor =
                ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .anySatisfy(e -> assertThat(e).isInstanceOf(LapAdvancedEvent.class));
    }

    // -----------------------------------------------------------------------
    // AC-TEST-LASTLAP-SENTINEL-RED (E56S01, DEC-65 D-3)
    // After finalizing the last lap, currentLapNumber MUST be 0 (sentinel)
    // and status stays ACTIVE. After finalizing a non-last lap K, currentLapNumber == K+1.
    // RED on current HEAD: no sentinel branch → currentLapNumber = lapCount+1 on last lap.
    // -----------------------------------------------------------------------

    /**
     * AC-TEST-LASTLAP-SENTINEL-RED (E56S01, DEC-65 D-3).
     *
     * <p>When the last lap ({@code lapNumber == lapCount}) is finalized (all matches terminal),
     * {@code phase.currentLapNumber} MUST be set to {@code 0} (sentinel) — not {@code lapCount+1}.
     * {@code phase.status} stays {@code ACTIVE} (D-4: no auto-COMPLETED).
     *
     * <p><b>RED on current HEAD:</b> no sentinel branch → {@code setCurrentLapNumber(lapCount+1)},
     * which violates DEC-65 D-3.
     */
    @Test
    void registerMatchResult_lastLapFinalized_setsCurrentLapNumberToSentinel() {
        // ARRANGE: phase in ACTIVE state, playing lap 2 out of 2 (last lap)
        phase.setCurrentLapNumber(2); // ACTIVE, lap 2 running (1-based)
        match.setLapNumber(2); // submitting for lap 2

        // All lap-2 matches terminal after this submission
        Match terminalLap2Match = new Match();
        terminalLap2Match.setId(MATCH_ID);
        terminalLap2Match.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap2Match.setLapNumber(2);

        // Also a lap-1 match (terminal — already done)
        Match terminalLap1Match = new Match();
        terminalLap1Match.setId(UUID.randomUUID());
        terminalLap1Match.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap1Match.setLapNumber(1);

        arrangeForLastLapSentinel(terminalLap2Match, terminalLap1Match);

        // ACT
        service.registerMatchResult(input);

        // ASSERT — DEC-65 D-3: last-lap sentinel = 0
        org.mockito.ArgumentCaptor<Phase> captor = org.mockito.ArgumentCaptor.forClass(Phase.class);
        verify(phaseRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrentLapNumber()).isEqualTo(0);
    }

    /**
     * AC-TEST-LASTLAP-SENTINEL-RED — non-last-lap boundary counter-assertion (E56S01, DEC-65 D-3).
     *
     * <p>When a non-last lap ({@code lapNumber < lapCount}) is finalized, {@code currentLapNumber}
     * advances to {@code currentMatchLap + 1} — NOT the sentinel. The sentinel fires ONLY on the
     * last lap.
     *
     * <p><b>RED on current HEAD:</b> the current code increments from {@code previousLapNumber},
     * which will equal {@code lapCount} instead of the sentinel when seeded with 1-based setup.
     */
    @Test
    void registerMatchResult_nonLastLapFinalized_advancesToNextLap() {
        // ARRANGE: phase in ACTIVE state, playing lap 1 out of 2 (non-last lap)
        phase.setCurrentLapNumber(1); // ACTIVE, lap 1 running (1-based)
        match.setLapNumber(1); // submitting for lap 1

        // lap-1 match now terminal
        Match terminalLap1Match = new Match();
        terminalLap1Match.setId(MATCH_ID);
        terminalLap1Match.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap1Match.setLapNumber(1);

        // lap-2 match not yet started (OPEN) — lapCount = 2
        Match openLap2Match = new Match();
        openLap2Match.setId(UUID.randomUUID());
        openLap2Match.setMatchState(MatchState.OPEN);
        openLap2Match.setLapNumber(2);

        arrangeForLapAdvance(terminalLap1Match, openLap2Match);

        // ACT
        service.registerMatchResult(input);

        // ASSERT — non-last lap K=1: advance to K+1=2 (NOT sentinel)
        org.mockito.ArgumentCaptor<Phase> captor = org.mockito.ArgumentCaptor.forClass(Phase.class);
        verify(phaseRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrentLapNumber()).isEqualTo(2);
    }

    /**
     * AC-TEST-NO-AUTO-COMPLETED-GREEN (E56S01, DEC-65 D-4).
     *
     * <p>After last-lap finalization, {@code phase.status} MUST stay {@code ACTIVE} (not auto-flip
     * to COMPLETED). The scoring cascade MUST NOT invoke any phase-lifecycle transition. This test
     * verifies no {@link de.vvwt.tm.tournament.PhaseLifecycleService#complete(UUID)} or equivalent
     * is called.
     *
     * <p><b>GREEN-only test:</b> verifies that the scoring service does NOT invoke a
     * status-transition on the phase (the correct behavior; tested here as an explicit assertion).
     */
    @Test
    void registerMatchResult_lastLapFinalized_phaseStatusStaysActive() {
        phase.setCurrentLapNumber(2); // ACTIVE, lap 2 running
        match.setLapNumber(2);
        phase.setStatus("ACTIVE");

        Match terminalLap2 = new Match();
        terminalLap2.setId(MATCH_ID);
        terminalLap2.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap2.setLapNumber(2);

        Match terminalLap1 = new Match();
        terminalLap1.setId(UUID.randomUUID());
        terminalLap1.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap1.setLapNumber(1);

        arrangeForLastLapSentinel(terminalLap2, terminalLap1);

        service.registerMatchResult(input);

        // ASSERT — phase status NOT changed to COMPLETED by scoring cascade
        org.mockito.ArgumentCaptor<Phase> captor = org.mockito.ArgumentCaptor.forClass(Phase.class);
        verify(phaseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    /**
     * AC-ERROR-OPERATOR-CORRECTION-WINDOW (E56S01, DEC-65 D-7).
     *
     * <p>After last-lap finalization ({@code currentLapNumber=0}, sentinel), a score correction on
     * a last-lap match triggers the cascade again. The sentinel MUST NOT be advanced to 1 (a
     * re-increment from 0 would be wrong). The correction MUST NOT call {@code setCurrentLapNumber}
     * at all — only the last-lap match is terminal, and the sentinel logic must detect that this is
     * still the last-lap scenario.
     *
     * <p>Concretely: {@code currentMatchLap=2}, {@code lapCount=2} (still last lap) → sentinel-0
     * stays.
     */
    @Test
    void registerMatchResult_scoreCorrectionAfterSentinel_doesNotAdvanceLap() {
        // ARRANGE: sentinel state — last-lap done, currentLapNumber=0
        phase.setCurrentLapNumber(0); // sentinel
        match.setLapNumber(2); // submitting correction for lap 2 match

        // Both laps still terminal (correction doesn't change state)
        Match terminalLap2 = new Match();
        terminalLap2.setId(MATCH_ID);
        terminalLap2.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap2.setLapNumber(2);

        Match terminalLap1 = new Match();
        terminalLap1.setId(UUID.randomUUID());
        terminalLap1.setMatchState(MatchState.FINISHED_WINNER1);
        terminalLap1.setLapNumber(1);

        arrangeForLastLapSentinel(terminalLap2, terminalLap1);

        service.registerMatchResult(input);

        // ASSERT — currentLapNumber stays 0 (sentinel preserved)
        org.mockito.ArgumentCaptor<Phase> captor = org.mockito.ArgumentCaptor.forClass(Phase.class);
        verify(phaseRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrentLapNumber()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Arranges mocks for last-lap sentinel scenario: all matches terminal, lapCount derived. */
    private void arrangeForLastLapSentinel(Match... terminalMatches) {
        when(tournamentRepository.findByIdForUpdate(TOURNAMENT_ID)).thenReturn(tournament);
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(ruleResolver.resolve(tournament))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRule, validationRule));
        when(validationRule.isSetClosed(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(ValidationResult.winner1());

        SetResult wonSet =
                new SetResult(
                        MATCH_ID,
                        0,
                        PHASE_ID,
                        25,
                        15,
                        SetState.WINNER1.getLegacyCode(),
                        null,
                        null);
        when(setResultRepository.findByMatchIdAndSetIndex(MATCH_ID, 0))
                .thenReturn(Optional.empty());
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(wonSet));
        when(matchOutcomeRepository.findById(MATCH_ID)).thenReturn(Optional.empty());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));
        when(scoringRule.calculatePoints(any(), any())).thenReturn(new ScoringResult(3, 0));
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(any(), any()))
                .thenReturn(Collections.emptyList());
        when(teamAvatarRatingRepository.findById(any())).thenReturn(Optional.empty());
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(terminalMatches));
    }

    /** Arranges mocks for a non-last-lap advance scenario. */
    private void arrangeForLapAdvance(Match terminalCurrentLapMatch, Match openNextLapMatch) {
        when(tournamentRepository.findByIdForUpdate(TOURNAMENT_ID)).thenReturn(tournament);
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(ruleResolver.resolve(tournament))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRule, validationRule));
        when(validationRule.isSetClosed(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(ValidationResult.winner1());

        SetResult wonSet =
                new SetResult(
                        MATCH_ID,
                        0,
                        PHASE_ID,
                        25,
                        15,
                        SetState.WINNER1.getLegacyCode(),
                        null,
                        null);
        when(setResultRepository.findByMatchIdAndSetIndex(MATCH_ID, 0))
                .thenReturn(Optional.empty());
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(wonSet));
        when(matchOutcomeRepository.findById(MATCH_ID)).thenReturn(Optional.empty());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));
        when(scoringRule.calculatePoints(any(), any())).thenReturn(new ScoringResult(3, 0));
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(any(), any()))
                .thenReturn(Collections.emptyList());
        when(teamAvatarRatingRepository.findById(any())).thenReturn(Optional.empty());
        when(matchRepository.findByPhaseId(PHASE_ID))
                .thenReturn(List.of(terminalCurrentLapMatch, openNextLapMatch));
    }

    /**
     * Arranges mocks for a single-set BEST_OF_3 match where team1 wins set 0 with 25:15, deriving
     * MatchState.FINISHED_WINNER1.
     */
    private void arrangeHappyPath_singleSetMatchWinner1() {
        when(tournamentRepository.findByIdForUpdate(TOURNAMENT_ID)).thenReturn(tournament);
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));
        when(ruleResolver.resolve(tournament))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRule, validationRule));
        when(validationRule.isSetClosed(anyInt(), anyInt(), anyInt(), any()))
                .thenReturn(ValidationResult.winner1());

        SetResult wonSet =
                new SetResult(
                        MATCH_ID,
                        0,
                        PHASE_ID,
                        25,
                        15,
                        SetState.WINNER1.getLegacyCode(),
                        null,
                        null);
        when(setResultRepository.findByMatchIdAndSetIndex(MATCH_ID, 0))
                .thenReturn(Optional.empty());
        when(setResultRepository.findByMatchId(MATCH_ID)).thenReturn(List.of(wonSet));

        // MatchOutcome already exists (or not — doesn't matter for this path)
        when(matchOutcomeRepository.findById(MATCH_ID)).thenReturn(Optional.empty());

        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        when(scoringRule.calculatePoints(any(), any())).thenReturn(new ScoringResult(3, 0));

        // Avatar rating refresh — no terminal matches for simplicity
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(any(), any()))
                .thenReturn(Collections.emptyList());
        when(teamAvatarRatingRepository.findById(any())).thenReturn(Optional.empty());

        // Phase lap check — only one match; it becomes FINISHED_WINNER1 (terminal)
        Match terminalMatch = new Match();
        terminalMatch.setId(MATCH_ID);
        terminalMatch.setMatchState(MatchState.FINISHED_WINNER1);
        terminalMatch.setLapNumber(1);
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(terminalMatch));
    }

    /**
     * Captures the {@link TeamAvatarRating} save call for the given avatar id.
     *
     * <p>Returns a Mockito argument matcher that captures the argument for later assertion — used
     * to verify that the correct avatar's rating was refreshed.
     */
    private TeamAvatarRating argCaptureForAvatarId(UUID avatarId) {
        // We use argument matching via captor to find the rating for the specific avatarId
        return argThat(
                rating -> {
                    if (!(rating instanceof TeamAvatarRating tar)) return false;
                    return avatarId.equals(tar.getAvatarId());
                });
    }

    /**
     * Delegates to Mockito {@code argThat} — needed to avoid static import collision with AssertJ.
     */
    @SuppressWarnings("SameParameterValue")
    private static <T> T argThat(org.mockito.ArgumentMatcher<T> matcher) {
        return org.mockito.Mockito.argThat(matcher);
    }
}
