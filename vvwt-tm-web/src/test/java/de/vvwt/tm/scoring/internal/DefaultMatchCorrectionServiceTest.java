// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.scoring.MatchCorrectionInput;
import de.vvwt.tm.scoring.MatchCorrectionResult;
import de.vvwt.tm.scoring.ScoringResult;
import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.SetScoreCorrection;
import de.vvwt.tm.scoring.TournamentRuleResolver;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.AuditLogRepository;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchOutcomeRepository;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.SetResult;
import de.vvwt.tm.tournament.SetResultRepository;
import de.vvwt.tm.tournament.SetState;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.IncompleteCorrectionException;
import de.vvwt.tm.tournament.exceptions.MatchStateGuardException;
import de.vvwt.tm.tournament.exceptions.PhaseStateGuardException;
import de.vvwt.tm.tournament.exceptions.StandoffFormatMismatchException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link DefaultMatchCorrectionService} — same-package white-box tests per DEC-36.
 *
 * <p>Tests reference {@link DefaultMatchCorrectionService} directly (implementation class) because
 * the test class is co-located in {@code de.vvwt.tm.scoring.internal} — same package as the
 * subject. This is the DEC-36 white-box exception for same-package tests.
 *
 * <p>TDD Iron Law (DEC-22): every test in this class was written RED (failing) before the
 * corresponding production code was added.
 *
 * @since E48S25, updated E56S02 (DEC-74 operationalization)
 * @see DefaultMatchCorrectionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — same-package white-box exemption</a>
 * @see <a href="DEC-37">DEC-37 Clause B — lock-first contract</a>
 * @see <a href="DEC-74">DEC-74 — path-independent lap-advance (amends DEC-65)</a>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DefaultMatchCorrectionServiceTest — E48S25 TDD unit tests")
class DefaultMatchCorrectionServiceTest {

    // -----------------------------------------------------------------------
    // Mocks
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
    @Mock private ScoringRule scoringRule;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TenantContext tenantContext;

    @InjectMocks private DefaultMatchCorrectionService service;

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
    private Phase activePhase;
    private Match finishedMatch;

    @BeforeEach
    void setUp() {
        tournament =
                new Tournament(
                        TOURNAMENT_ID,
                        "Test tournament",
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        "ACTIVE",
                        LocalDateTime.now(),
                        null,
                        2,
                        4);

        activePhase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 0, LocalDateTime.now());

        finishedMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3, // setLimit for BEST_OF_3
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());

        when(tenantContext.current()).thenReturn(TENANT_ID);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(tournamentRepository.findByIdForUpdate(TOURNAMENT_ID)).thenReturn(tournament);
        // Rule resolver stub for cascade path
        when(scoringRule.calculatePoints(any(), any())).thenReturn(new ScoringResult(2, 1));
        when(ruleResolver.resolve(any()))
                .thenReturn(new TournamentRuleResolver.ResolvedRules(scoringRule, null));
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(any(), any()))
                .thenReturn(Collections.emptyList());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(finishedMatch));
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(activePhase));
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(matchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(matchOutcomeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(setResultRepository.findByMatchIdAndSetIndex(any(), anyInt()))
                .thenReturn(Optional.empty());
        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now()),
                                new SetResult(
                                        MATCH_ID,
                                        1,
                                        PHASE_ID,
                                        25,
                                        15,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));
        when(teamAvatarRatingRepository.findById(any())).thenReturn(Optional.empty());
        when(teamAvatarRepository.findById(any())).thenReturn(Optional.empty());
    }

    // -----------------------------------------------------------------------
    // Guard: PhaseStateGuardException for non-ACTIVE phase
    // (AC-GUARD-PHASE-STATUS)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC-GUARD-PHASE-STATUS: phase PENDING → PhaseStateGuardException before any DB write")
    void guardPhaseState_pending_throwsPhaseStateGuardException() {
        Phase pendingPhase =
                new Phase(
                        PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "PENDING", 0, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(pendingPhase));
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(finishedMatch));

        MatchCorrectionInput input = correctionInput(List.of(new SetScoreCorrection(0, 25, 10)));

        assertThatThrownBy(() -> service.correctMatchSets(input))
                .isInstanceOf(PhaseStateGuardException.class)
                .hasMessageContaining("PENDING");

        // No DB writes must have occurred
        verify(tournamentRepository, never()).findByIdForUpdate(any());
        verify(auditLogRepository, never()).save(any());
        verify(setResultRepository, never()).insert(any());
        verify(setResultRepository, never()).update(any());
    }

    @Test
    @DisplayName(
            "AC-GUARD-PHASE-STATUS: phase COMPLETED → PhaseStateGuardException before any DB write")
    void guardPhaseState_completed_throwsPhaseStateGuardException() {
        Phase completedPhase =
                new Phase(
                        PHASE_ID,
                        TOURNAMENT_ID,
                        1,
                        "Vorrunde",
                        "COMPLETED",
                        0,
                        LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(completedPhase));
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(finishedMatch));

        MatchCorrectionInput input = correctionInput(List.of(new SetScoreCorrection(0, 25, 10)));

        assertThatThrownBy(() -> service.correctMatchSets(input))
                .isInstanceOf(PhaseStateGuardException.class)
                .hasMessageContaining("COMPLETED");

        verify(tournamentRepository, never()).findByIdForUpdate(any());
        verify(auditLogRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Guard: MatchStateGuardException for INPROGRESS/ONCHECK
    // (AC-GUARD-MATCH-STATE)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-GUARD-MATCH-STATE: INPROGRESS → MatchStateGuardException before lock")
    void guardMatchState_inprogress_throwsMatchStateGuardException() {
        Match liveMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.INPROGRESS.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(liveMatch));

        MatchCorrectionInput input = correctionInput(List.of(new SetScoreCorrection(0, 25, 10)));

        assertThatThrownBy(() -> service.correctMatchSets(input))
                .isInstanceOf(MatchStateGuardException.class)
                .hasMessageContaining("INPROGRESS");

        // Lock must NOT have been acquired
        verify(tournamentRepository, never()).findByIdForUpdate(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-GUARD-MATCH-STATE: ONCHECK → MatchStateGuardException before lock")
    void guardMatchState_oncheck_throwsMatchStateGuardException() {
        Match oncheckMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.ONCHECK.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(oncheckMatch));

        MatchCorrectionInput input = correctionInput(List.of(new SetScoreCorrection(0, 25, 10)));

        assertThatThrownBy(() -> service.correctMatchSets(input))
                .isInstanceOf(MatchStateGuardException.class)
                .hasMessageContaining("ONCHECK");

        verify(tournamentRepository, never()).findByIdForUpdate(any());
        verify(auditLogRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Guard: StandoffFormatMismatchException
    // (AC-GUARD-STANDOFF-FORMAT)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-GUARD-STANDOFF-FORMAT: STANDOFF outcome on BEST_OF_3 → 422")
    void guardStandoff_bestOf3_throwsStandoffFormatMismatchException() {
        // BEST_OF_3: team1SetsWon=1, team2SetsWon=1 → would be STANDOFF but not allowed
        // Simulate: two set corrections each won by different teams on a BEST_OF_3 match
        // The impl must detect that submitted scores produce STANDOFF (1:1) and reject.
        // For correction of FINISHED_WINNER1, pass 2 sets: team1 wins set0, team2 wins set1.
        // deriveMatchState(1,1,2) on BEST_OF_3 = ONCHECK (not STANDOFF), so we need all 3:
        // Actually to get STANDOFF on BEST_OF_3, team1SetsWon==team2SetsWon==1, setCount==2
        // But BEST_OF_3 would say "not done yet" for 1-1. The real STANDOFF check:
        // STANDOFF = allowsTies AND setsPlayed==maxSets AND team1==team2.
        // For BEST_OF_3: allowsTies=false → STANDOFF impossible.
        // For this test: we use FIXED_2_SETS format but mark tournament as BEST_OF_3 incorrectly.
        // Actually: we create a tournament with FIXED_2_SETS but the test checks that
        // StandoffFormatMismatchException fires when submitted scores would produce STANDOFF on
        // a non-tie format.
        // Correct scenario: submit scores that produce tie (1:1 sets) on a BEST_OF_3.
        // But BEST_OF_3 deriveMatchState(1,1,2) = ONCHECK, not STANDOFF. The guard must
        // detect that the operator intends a STANDOFF (submitting scores that for FIXED_2_SETS
        // would be standoff, but tournament is BEST_OF_3).
        // Implementation approach: if after upsert of all sets, total sets won is equal for both
        // teams AND setsPlayed==maxSets AND !allowsTies → throw StandoffFormatMismatchException.

        // Scenario: BEST_OF_3 match, operator submits 2 set corrections (set0: 25-0 win team2,
        // set1: 25-0 win team1), giving 1-1. On FIXED_2_SETS this would be STANDOFF.
        // The guard fires because after applying the submitted sets, we have equal sets won AND
        // total sets = maxSets for a tie-capable format count, but format !allowsTies.
        // Simpler: submission of exactly maxSets sets with equal wins = STANDOFF attempt.

        // For BEST_OF_3 tournament (allowsTies=false):
        // Submit 3 sets: team1 wins 1 (set0), team2 wins 1 (set1), team1 wins 1 (set2) → 2:1
        // That's FINISHED_WINNER1 not STANDOFF.
        // Actually the only way to trigger the guard is: submit sets such that the derived
        // state = STANDOFF AND format.allowsTies==false.
        // MatchFormat.BEST_OF_3.deriveMatchState never returns STANDOFF (allowsTies=false).
        // So we must explicitly detect STANDOFF intent in the input scores.
        // The story's AC says: "STANDOFF submitted on non-FIXED_2_SETS → 422".
        // The guard in the implementation detects when submitted sets produce equal setsWon
        // AND setsPlayed == maxSets AND !allowsTies. This is the standoff-format-mismatch guard.
        // For this unit test, we simulate: tournament=BEST_OF_1 (maxSets=1) match,
        // operator submits 2 sets both won by different teams → 1:1 at setsPlayed=2 > maxSets?
        // Actually BEST_OF_3 maxSets=3. Let's use FIXED_2_SETS on format but BEST_OF_3 on
        // tournament to see the mismatch.

        // Simplest valid test:
        // tournament.matchFormat = "BEST_OF_3" (allowsTies=false, maxSets=3)
        // operator submits sets: [0: 25-0 (team2 wins), 1: 0-25 (team1 wins)] → 1:1 equal
        // After applying: setsWon1=1, setsWon2=1, setsPlayed=2.
        // format.deriveMatchState(1,1,2) = ONCHECK for BEST_OF_3 (not STANDOFF).
        // The guard must catch "equal sets won AND setsPlayed=maxSets AND !allowsTies"
        // which is NEVER true for BEST_OF_3 with setsPlayed=2 < maxSets=3.
        // So the guard only fires when setsPlayed==maxSets AND equal setsWon AND !allowsTies.
        // For BEST_OF_3, setsPlayed==3 with equal setsWon=1.5 is impossible.
        //
        // The guard therefore never fires for BEST_OF_N (mathematically impossible).
        // BUT the story says: "STANDOFF for non-tie format → 422".
        // Interpretation: if the operator submits scores on FIXED_2_SETS (ties allowed)
        // but claims the tournament format is non-tie, that would be a config error.
        // More precisely: the check is - if the scores submitted would produce FINISHED_STANDOFF
        // from deriveMatchState AND the tournament format has allowsTies=false → throw.
        // For BEST_OF_3: deriveMatchState never returns STANDOFF → guard never fires.
        // This means the StandoffFormatMismatchException is only reachable when a tournament
        // is actually FIXED_2_SETS but has been misconfigured. The guard protects against that.
        //
        // Correct test: use FIXED_2_SETS tournament but expect the guard NOT to fire (format
        // allows ties). Then create a mock that returns a MatchFormat where allowsTies=false
        // but deriveMatchState returns STANDOFF — but that's impossible via the real enum.
        //
        // Resolution: the StandoffFormatMismatchException guard fires when:
        // - submitted set scores produce a STANDOFF (1:1 on 2 sets) per the submitted data
        // - AND the tournament's MatchFormat does NOT allow ties
        // The implementation detects this by computing setsWon from the submitted sets list
        // BEFORE deriving via MatchFormat, and checking if equal AND format !allowsTies.
        // This is the pre-cascade validation.

        // For a BEST_OF_3 tournament: send 2 corrections where team1 wins one and team2 wins one.
        // The guard fires: setsWon1==setsWon2 AND !allowsTies.
        // Test: BEST_OF_3, submit [set0: 25-10 (team1 wins), set1: 0-25 (team2 wins)] → 1:1.
        // Guard fires → StandoffFormatMismatchException.

        Match finishedW1Match =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(finishedW1Match));
        // tournament already has BEST_OF_3 format (from setUp)

        // Submit 2 sets: one won by team1, one won by team2 → equal setsWon → STANDOFF attempt
        MatchCorrectionInput input =
                correctionInput(
                        List.of(
                                new SetScoreCorrection(0, 25, 10), // team1 wins set 0
                                new SetScoreCorrection(1, 10, 25) // team2 wins set 1
                                ));

        assertThatThrownBy(() -> service.correctMatchSets(input))
                .isInstanceOf(StandoffFormatMismatchException.class);

        verify(tournamentRepository, never()).findByIdForUpdate(any());
        verify(auditLogRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // CANCELED branch: audit-only, no lock, no cascade, no WS event
    // (AC-CANCELED-AUDIT-ONLY)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-CANCELED-AUDIT-ONLY: CANCELED match → auditOnly=true, no lock, no cascade")
    void canceledMatch_auditOnly_noLockNoCascade() {
        Match canceledMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.CANCELED.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(canceledMatch));

        MatchCorrectionInput input = correctionInput(List.of(new SetScoreCorrection(0, 25, 10)));

        MatchCorrectionResult result = service.correctMatchSets(input);

        assertThat(result.auditOnly()).isTrue();
        assertThat(result.newMatchState()).isEqualTo(MatchState.CANCELED);

        // Lock must NOT be acquired for CANCELED
        verify(tournamentRepository, never()).findByIdForUpdate(any());

        // audit_log must be written
        verify(auditLogRepository, times(1)).save(any(AuditLogEntry.class));

        // No cascade writes
        verify(setResultRepository, never()).insert(any());
        verify(setResultRepository, never()).update(any());
        verify(matchRepository, never()).save(any());
        verify(matchOutcomeRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
    }

    @Test
    @DisplayName(
            "AC-CANCELED-AUDIT-ONLY: audit_log entry has sourceType=ADMIN and reason from input")
    void canceledMatch_auditLogEntry_hasAdminSourceAndReason() {
        Match canceledMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.CANCELED.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(canceledMatch));

        MatchCorrectionInput input =
                new MatchCorrectionInput(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        List.of(new SetScoreCorrection(0, 25, 10)),
                        "admin-actor",
                        "Test reason");

        service.correctMatchSets(input);

        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository).save(captor.capture());
        AuditLogEntry entry = captor.getValue();
        assertThat(entry.getSourceType()).isEqualTo("ADMIN");
        assertThat(entry.getReason()).isEqualTo("Test reason");
        assertThat(entry.getActorId()).isEqualTo("admin-actor");
    }

    // -----------------------------------------------------------------------
    // Full cascade path: FINISHED_WINNER1 correction
    // (AC-TEST-CORRECT-SET-FINISHED)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "AC-LOCK-FIRST: DEC-37 Clause B — findByIdForUpdate called ONCE FIRST for"
                    + " FINISHED_WINNER1 correction")
    void finishedW1Correction_lockAcquiredOnce() {
        // tournament status ACTIVE, phase ACTIVE, match FINISHED_WINNER1
        // 2-set terminal submit (BEST_OF_3 requires 2 wins for terminal — E48S28 guard)
        MatchCorrectionInput input = terminalCorrectionInput();

        // Set up aggregate after setResultRepository.findByMatchId for cascade
        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        service.correctMatchSets(input);

        // Lock acquired exactly once
        verify(tournamentRepository, times(1)).findByIdForUpdate(TOURNAMENT_ID);
    }

    @Test
    @DisplayName(
            "AC-AUDIT-LOG-ADMIN: correction for FINISHED match writes audit_log with"
                    + " sourceType=ADMIN")
    void finishedW1Correction_auditLogWritten_adminSourceType() {
        // 2-set terminal submit (E48S28 ONCHECK guard — BEST_OF_3 requires 2 wins for terminal)
        MatchCorrectionInput input =
                new MatchCorrectionInput(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        List.of(
                                new SetScoreCorrection(0, 25, 10),
                                new SetScoreCorrection(1, 25, 15)),
                        "admin",
                        "Correction reason");

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now()),
                                new SetResult(
                                        MATCH_ID,
                                        1,
                                        PHASE_ID,
                                        25,
                                        15,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        service.correctMatchSets(input);

        // 2 sets submitted → 2 audit_log entries (one per set); verify at least one
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository, times(2)).save(captor.capture());
        AuditLogEntry entry = captor.getAllValues().get(0);
        assertThat(entry.getSourceType()).isEqualTo("ADMIN");
        assertThat(entry.getMatchId()).isEqualTo(MATCH_ID);
    }

    // -----------------------------------------------------------------------
    // DEC-74 RED-first tests: correction path advances Phase.currentLapNumber
    // (AC-TEST-CORRECTION-ADVANCES-CURRENT-LAP-RED, AC-TEST-CORRECTION-LAST-LAP-SENTINEL-RED)
    // -----------------------------------------------------------------------

    /**
     * AC-TEST-CORRECTION-ADVANCES-CURRENT-LAP-RED — TDD RED-first (E56S02, DEC-22).
     *
     * <p>ACTIVE phase with lapCount=2, currentLapNumber=1. Phase has two matches in lap 1. After
     * the first match is terminal (incomplete lap), currentLapNumber stays 1. After the last match
     * in lap 1 is terminal, currentLapNumber advances to 2.
     *
     * <p>This test FAILS on HEAD (correction path omits the advance entirely) and passes after the
     * DEC-74 fix.
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-ADVANCES-CURRENT-LAP-RED: last match of current lap terminal via"
                    + " correction → currentLapNumber advances (DEC-74)")
    void correctionLastMatchOfCurrentLap_advancesCurrentLapNumber() {
        // Phase: ACTIVE, currentLapNumber=1, lapCount=2 (two laps in phase)
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 1, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        UUID sibling1Id = UUID.randomUUID();
        UUID sibling2Id = UUID.randomUUID();

        // The match being corrected: lap 1, finishing terminal
        Match matchBeingCorrected =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        1, // lapNumber = 1 (current lap)
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(matchBeingCorrected));

        // After correction: match under test is FINISHED_WINNER1, sibling also terminal → all
        // terminal in lap 1.
        Match siblingTerminal =
                new Match(
                        sibling1Id,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR2_ID,
                        AVATAR1_ID,
                        MatchState.FINISHED_WINNER2.getLegacyCode(),
                        3,
                        1, // lapNumber = 1
                        2,
                        null,
                        null,
                        null,
                        LocalDateTime.now());

        // Match in lap 2 (not yet current)
        Match lap2Match =
                new Match(
                        sibling2Id,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.OPEN.getLegacyCode(),
                        3,
                        2, // lapNumber = 2
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());

        // findByPhaseId returns all three matches (match being corrected + sibling terminal +
        // lap2).
        // Use a SEPARATE Match instance for matchBeingCorrected in the phaseMatches list to avoid
        // shared-object mutation: the cascade may change the Match object's state via
        // match.setMatchState(derivedState) before the lap-advance guard runs.
        Match matchBeingCorrectedSnapshot =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findByPhaseId(PHASE_ID))
                .thenReturn(List.of(matchBeingCorrectedSnapshot, siblingTerminal, lap2Match));

        // Two sets both won by team1 → deriveMatchState(2,0,2) = FINISHED_WINNER1 for BEST_OF_3
        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now()),
                                new SetResult(
                                        MATCH_ID,
                                        1,
                                        PHASE_ID,
                                        25,
                                        15,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // 2-set terminal submit (E48S28 ONCHECK guard — must be terminal for BEST_OF_3)
        service.correctMatchSets(terminalCorrectionInput());

        // After all matches in lap 1 are terminal → currentLapNumber must be 2
        assertThat(phase.getCurrentLapNumber()).isEqualTo(2);
        // phaseRepository.save must have been called (DEC-74 advance fired)
        verify(phaseRepository, times(1)).save(phase);
    }

    /**
     * AC-TEST-CORRECTION-ADVANCES-CURRENT-LAP-RED (early-exit assertion) — incomplete lap does NOT
     * advance.
     *
     * <p>When only one of two lap-1 matches is terminal, the advance must NOT fire.
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-INCOMPLETE-LAP-NO-CHANGE: incomplete lap → currentLapNumber"
                    + " unchanged (DEC-74 D-2 clause (c))")
    void correctionIncompleteLap_doesNotAdvanceCurrentLapNumber() {
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 1, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        UUID siblingId = UUID.randomUUID();
        Match matchBeingCorrected =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        1, // current lap
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(matchBeingCorrected));

        // Sibling in same lap is still OPEN → lap incomplete
        Match siblingOpen =
                new Match(
                        siblingId,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR2_ID,
                        AVATAR1_ID,
                        MatchState.OPEN.getLegacyCode(),
                        3,
                        1, // same lap
                        2,
                        null,
                        null,
                        null,
                        LocalDateTime.now());

        when(matchRepository.findByPhaseId(PHASE_ID))
                .thenReturn(List.of(matchBeingCorrected, siblingOpen));

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // 2-set terminal submit (E48S28 ONCHECK guard — must be terminal for BEST_OF_3)
        service.correctMatchSets(terminalCorrectionInput());

        // Incomplete lap → no advance
        assertThat(phase.getCurrentLapNumber()).isEqualTo(1);
        verify(phaseRepository, never()).save(any());
    }

    /**
     * AC-TEST-CORRECTION-LAST-LAP-SENTINEL-RED — TDD RED-first (E56S02, DEC-22).
     *
     * <p>When the last lap is completed via correction, currentLapNumber must become 0 (sentinel)
     * and phase.status stays ACTIVE (no auto-COMPLETED). MUST FAIL on HEAD.
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-LAST-LAP-SENTINEL-RED: last lap terminal via correction →"
                    + " currentLapNumber=0 sentinel, status ACTIVE (DEC-74 D-4)")
    void correctionLastLapCompleted_writesSentinel0() {
        // Phase: ACTIVE, currentLapNumber=2 (last lap, lapCount=2)
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 2, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        UUID sibling = UUID.randomUUID();
        Match matchBeingCorrected =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        2, // lapNumber = 2 = lapCount (last lap)
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(matchBeingCorrected));

        // Sibling in lap 2, also terminal
        Match siblingTerminal =
                new Match(
                        sibling,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR2_ID,
                        AVATAR1_ID,
                        MatchState.FINISHED_WINNER2.getLegacyCode(),
                        3,
                        2, // lapNumber = 2 (last lap)
                        2,
                        null,
                        null,
                        null,
                        LocalDateTime.now());

        // Separate snapshot instance to avoid shared-object mutation
        Match matchBeingCorrectedLastLapSnapshot =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        2, // lapNumber = 2 = lapCount (last lap)
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findByPhaseId(PHASE_ID))
                .thenReturn(List.of(matchBeingCorrectedLastLapSnapshot, siblingTerminal));

        // Two sets both won by team1 → FINISHED_WINNER1 after cascade
        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now()),
                                new SetResult(
                                        MATCH_ID,
                                        1,
                                        PHASE_ID,
                                        25,
                                        15,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // 2-set terminal submit (E48S28 ONCHECK guard — must be terminal for BEST_OF_3)
        MatchCorrectionResult result = service.correctMatchSets(terminalCorrectionInput());

        // Last-lap finalization → sentinel 0 (DEC-74 D-4)
        assertThat(phase.getCurrentLapNumber()).isEqualTo(0);
        // Phase status stays ACTIVE — no auto-COMPLETED (DEC-65 D-4 / DEC-74 D-4)
        assertThat(phase.getStatus()).isEqualTo("ACTIVE");
        // Not an audit-only result
        assertThat(result.auditOnly()).isFalse();
        // phaseRepository.save must have been called
        verify(phaseRepository, times(1)).save(phase);
    }

    // -----------------------------------------------------------------------
    // DEC-74 regression-guard GREEN tests (already GREEN on HEAD, stay GREEN after fix)
    // (AC-TEST-CORRECTION-NON-CURRENT-LAP-NO-CHANGE-GREEN,
    //  AC-TEST-CORRECTION-FUTURE-LAP-NO-CHANGE-GREEN)
    // -----------------------------------------------------------------------

    /**
     * AC-TEST-CORRECTION-NON-CURRENT-LAP-NO-CHANGE-GREEN — already GREEN on HEAD.
     *
     * <p>Correcting a match in an already-completed earlier lap (lapNumber &lt; currentLapNumber)
     * does NOT change currentLapNumber. Guards against backward regression.
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-NON-CURRENT-LAP-NO-CHANGE-GREEN: correct earlier lap (lapNumber <"
                    + " currentLapNumber) → currentLapNumber unchanged (DEC-74 D-3)")
    void correctionEarlierLap_doesNotChangeCurrentLapNumber() {
        // Phase: ACTIVE, currentLapNumber=3 (lap 3 in play)
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 3, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        // Match being corrected is in lap 1 (already past)
        Match pastLapMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        1, // lap 1 < currentLapNumber=3
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(pastLapMatch));

        // Even if all lap-1 matches are terminal (they already were), guard (b) checks
        // lapNumber==currentLapNumber (1!=3) → advance must NOT fire.
        // findByPhaseId returns lap-1 matches all terminal
        Match sibling =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR2_ID,
                        AVATAR1_ID,
                        MatchState.FINISHED_WINNER2.getLegacyCode(),
                        3,
                        1,
                        2,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findByPhaseId(PHASE_ID)).thenReturn(List.of(pastLapMatch, sibling));

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // 2-set terminal submit (E48S28 ONCHECK guard)
        service.correctMatchSets(terminalCorrectionInput());

        // currentLapNumber must remain 3 — no backward regression
        assertThat(phase.getCurrentLapNumber()).isEqualTo(3);
        verify(phaseRepository, never()).save(any());
    }

    /**
     * AC-TEST-CORRECTION-FUTURE-LAP-NO-CHANGE-GREEN — already GREEN on HEAD.
     *
     * <p>Completing all matches of a future lap (lapNumber &gt; currentLapNumber) does NOT pull the
     * counter forward. Guards against a guard implementation that over-fires on a future lap.
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-FUTURE-LAP-NO-CHANGE-GREEN: complete future lap (lapNumber >"
                    + " currentLapNumber) → currentLapNumber unchanged (DEC-74 D-3)")
    void correctionFutureLapAllTerminal_doesNotAdvanceCurrentLapNumber() {
        // Phase: ACTIVE, currentLapNumber=1, lapCount=3
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 1, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        // Match being corrected is in lap 3 (future lap, not yet current)
        Match futureLapMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        3, // lapNumber=3 > currentLapNumber=1
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(futureLapMatch));

        // All lap-3 matches are terminal, lap-1 match is OPEN (current lap not complete)
        Match lap3Sibling =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR2_ID,
                        AVATAR1_ID,
                        MatchState.FINISHED_WINNER2.getLegacyCode(),
                        3,
                        3,
                        2,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        Match lap1Match =
                new Match(
                        UUID.randomUUID(),
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.OPEN.getLegacyCode(),
                        3,
                        1, // current lap, not yet done
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findByPhaseId(PHASE_ID))
                .thenReturn(List.of(futureLapMatch, lap3Sibling, lap1Match));

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // 2-set terminal submit (E48S28 ONCHECK guard)
        service.correctMatchSets(terminalCorrectionInput());

        // guard (b) lapNumber(3) != currentLapNumber(1) → advance must NOT fire
        assertThat(phase.getCurrentLapNumber()).isEqualTo(1);
        verify(phaseRepository, never()).save(any());
    }

    /**
     * AC-TEST-CANCELED-CORRECTION-NO-CHANGE — CANCELED match correction does not change
     * currentLapNumber (audit-only branch never reaches the guard).
     */
    @Test
    @DisplayName(
            "AC-TEST-CANCELED-CORRECTION-NO-CHANGE: CANCELED match correction does not change"
                    + " currentLapNumber (DEC-74 D-5)")
    void canceledMatchCorrection_doesNotChangeCurrentLapNumber() {
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 2, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        Match canceledMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.CANCELED.getLegacyCode(),
                        3,
                        2, // same as currentLapNumber — would fire if not CANCELED branch
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(canceledMatch));

        MatchCorrectionResult result =
                service.correctMatchSets(
                        correctionInput(List.of(new SetScoreCorrection(0, 25, 10))));

        // CANCELED branch → audit-only, no cascade, no advance
        assertThat(result.auditOnly()).isTrue();
        assertThat(phase.getCurrentLapNumber()).isEqualTo(2);
        // findByPhaseId must NOT be called (canceled path exits before lap-advance guard)
        verify(matchRepository, never()).findByPhaseId(any());
    }

    /**
     * AC-ERROR-NULL-LAPNUMBER — match with null lapNumber does not advance counter (guard clause
     * (a)).
     */
    @Test
    @DisplayName(
            "AC-ERROR-NULL-LAPNUMBER: match with null lapNumber → guard short-circuits, no advance"
                    + " (DEC-74 D-2 clause (a))")
    void nullLapNumber_guardShortCircuits_noAdvance() {
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 1, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        // Match with lapNumber=null (slot-opt not yet run)
        Match matchNullLap =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        null, // null lapNumber
                        null,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(matchNullLap));

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // 2-set terminal submit (E48S28 ONCHECK guard)
        service.correctMatchSets(terminalCorrectionInput());

        // guard (a): lapNumber is null → no advance, no error
        assertThat(phase.getCurrentLapNumber()).isEqualTo(1);
        verify(phaseRepository, never()).save(any());
        verify(matchRepository, never()).findByPhaseId(any());
    }

    /**
     * AC-ERROR-OPERATOR-CORRECTION-WINDOW — after last-lap sentinel (currentLapNumber=0), a
     * correction of a last-lap match does NOT advance off 0 (guard (b): lapNumber is never 0).
     */
    @Test
    @DisplayName(
            "AC-ERROR-OPERATOR-CORRECTION-WINDOW: correction after sentinel (currentLapNumber=0)"
                    + " → currentLapNumber stays 0 (DEC-65 D-7 / DEC-74 D-2)")
    void correctionAfterSentinel_doesNotAdvanceOff0() {
        // Sentinel state: all last-lap matches done, phase still ACTIVE, currentLapNumber=0
        Phase phase =
                new Phase(PHASE_ID, TOURNAMENT_ID, 1, "Vorrunde", "ACTIVE", 0, LocalDateTime.now());
        when(phaseRepository.findById(PHASE_ID)).thenReturn(Optional.of(phase));

        // Re-correction of a last-lap match (lapNumber=2, and currentLapNumber is 0)
        Match match =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.FINISHED_WINNER1.getLegacyCode(),
                        3,
                        2, // lapNumber=2 (last lap, but sentinel 0 means no current lap)
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(match));

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // 2-set terminal submit (E48S28 ONCHECK guard)
        service.correctMatchSets(terminalCorrectionInput());

        // Guard (b): lapNumber=2 != currentLapNumber=0 → no advance
        assertThat(phase.getCurrentLapNumber()).isEqualTo(0);
        verify(phaseRepository, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Nacherfassung: OPEN match → single lock acquisition for batch
    // (AC-TEST-NACHERFASSUNG-OPEN-TO-FINISHED)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("AC-NACHERFASSUNG: OPEN match → lock acquired ONCE, all sets persisted")
    void nacherfassung_openMatch_singleLock() {
        Match openMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.OPEN.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(openMatch));

        // Submit 2 sets in batch: team1 wins both → FINISHED_WINNER1
        MatchCorrectionInput input =
                correctionInput(
                        List.of(
                                new SetScoreCorrection(0, 25, 10),
                                new SetScoreCorrection(1, 25, 15)));

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now()),
                                new SetResult(
                                        MATCH_ID,
                                        1,
                                        PHASE_ID,
                                        25,
                                        15,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        service.correctMatchSets(input);

        // Lock acquired ONCE for the entire batch (DEC-37 Clause B + story AC)
        verify(tournamentRepository, times(1)).findByIdForUpdate(TOURNAMENT_ID);
        // Two audit_log entries (one per set)
        verify(auditLogRepository, times(2)).save(any(AuditLogEntry.class));
    }

    @Test
    @DisplayName("AC-NACHERFASSUNG: ENABLED match → single lock, cascade runs, result FINISHED")
    void nacherfassung_enabledMatch_singleLock() {
        Match enabledMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.ENABLED.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(enabledMatch));

        // 2-set terminal submit (E48S28 ONCHECK guard — BEST_OF_3 requires 2 wins for terminal)
        MatchCorrectionInput input = terminalCorrectionInput();

        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        MatchCorrectionResult result = service.correctMatchSets(input);

        assertThat(result.auditOnly()).isFalse();
        verify(tournamentRepository, times(1)).findByIdForUpdate(TOURNAMENT_ID);
    }

    // -----------------------------------------------------------------------
    // D-9 contract: save() is async-after-commit; save() failure does NOT propagate into TX
    // (AC-IMPL-OBSOLETE-CONTRACT-TEST-REFACTOR)
    // Old contract (AC-ERR-AUDIT-LOG-WRITE-FAILURE-ATOMIC-ROLLBACK) deleted at E55S13 cutover.
    // Post-cutover: audit-write failures produce WARN log + dropped row; SetResult committed OK.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "E55S13 D-9: correctMatchSets completes + returns result when save() is called;"
                    + " save() does not throw (async-after-commit — file IO off request path)")
    void d9_auditSaveCalledAndCorrectionCompletesNormally() {
        // Arrange — save() returns entry (default stub in setUp)
        // 2-set terminal submit (E48S28 ONCHECK guard)
        MatchCorrectionInput input = terminalCorrectionInput();

        // Act — must not throw
        MatchCorrectionResult result = service.correctMatchSets(input);

        // Assert — correction result is present; save() was invoked with correct tournamentId
        // (2 sets submitted → 2 audit entries — atLeastOnce to avoid brittleness on set count)
        assertThat(result).isNotNull();
        verify(auditLogRepository, org.mockito.Mockito.atLeastOnce())
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                e -> TOURNAMENT_ID.equals(e.getTournamentId())));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private MatchCorrectionInput correctionInput(List<SetScoreCorrection> sets) {
        return new MatchCorrectionInput(MATCH_ID, TOURNAMENT_ID, PHASE_ID, sets, "admin", null);
    }

    /**
     * Helper: builds a terminal-resolving correction input for a BEST_OF_3 tournament.
     *
     * <p>Submits 2 sets won by team1 (25-10 each) — {@code FINISHED_WINNER1} for BEST_OF_3. Used in
     * tests that need to bypass the E48S28 ONCHECK pre-write guard to reach the cascade logic.
     */
    private MatchCorrectionInput terminalCorrectionInput() {
        return correctionInput(
                List.of(new SetScoreCorrection(0, 25, 10), new SetScoreCorrection(1, 25, 15)));
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    // -----------------------------------------------------------------------
    // E48S28 — Pre-write ONCHECK guard (AC-TEST-CORRECTION-REJECTS-EMPTY-SUBMIT-RED,
    // AC-TEST-CORRECTION-REJECTS-PARTIAL-NON-WINNING-RED,
    // AC-TEST-CORRECTION-ACCEPTS-TERMINAL-RESOLVING-SUBMIT-GREEN-REGRESSION,
    // AC-TEST-CONTROLLER-IT-CANCELED-PATH-PRESERVED-GREEN-REGRESSION,
    // AC-TEST-DEC74-LAP-ADVANCE-PRESERVED-GREEN-REGRESSION)
    // RED-first per DEC-22 Iron Law. Guard fires BEFORE lock acquisition (AC-GOV-DEC37).
    // -----------------------------------------------------------------------

    /**
     * AC-TEST-CORRECTION-REJECTS-EMPTY-SUBMIT-RED — RED-first TDD (E48S28, DEC-22).
     *
     * <p>Given an OPEN match in an ACTIVE phase with a BEST_OF_3 tournament, an empty submit
     * (sets=empty list) must throw {@link IncompleteCorrectionException} before any DB write. The
     * DEC-37 per-tournament lock must NOT be acquired, no {@code set_result} rows written, no
     * {@code match_outcome} written, no {@code audit_log} written, no {@code
     * MatchResultChangedEvent} published.
     *
     * <p>Test FAILS on {@code staging} HEAD (current code writes ONCHECK to DB).
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-REJECTS-EMPTY-SUBMIT-RED: empty submit on BEST_OF_3 OPEN match →"
                    + " IncompleteCorrectionException, no DB write, no event (E48S28)")
    void emptySubmit_openMatch_bestOf3_throwsIncompleteCorrectionException() {
        // OPEN match in ACTIVE BEST_OF_3 phase
        Match openMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.OPEN.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(openMatch));

        // Empty sets list — submitted (0,0,0) = ONCHECK for every format
        MatchCorrectionInput input = correctionInput(Collections.emptyList());

        assertThatThrownBy(() -> service.correctMatchSets(input))
                .isInstanceOf(IncompleteCorrectionException.class);

        // DEC-37: lock MUST NOT be acquired
        verify(tournamentRepository, never()).findByIdForUpdate(any());
        // No DB writes
        verify(setResultRepository, never()).insert(any());
        verify(setResultRepository, never()).update(any());
        verify(matchOutcomeRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
        verify(matchRepository, never()).save(any());
        // No event published (Brief D-2 invariant)
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * AC-TEST-CORRECTION-REJECTS-EMPTY-SUBMIT-RED — variant with ENABLED match (Nacherfassung
     * path).
     *
     * <p>The guard applies regardless of the initial match state (OPEN, ENABLED, FINISHED_*) — what
     * matters is the submitted sets producing ONCHECK.
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-REJECTS-EMPTY-SUBMIT-RED: empty submit on ENABLED match →"
                    + " IncompleteCorrectionException, no lock (E48S28)")
    void emptySubmit_enabledMatch_throwsIncompleteCorrectionException() {
        Match enabledMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.ENABLED.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(enabledMatch));

        MatchCorrectionInput input = correctionInput(Collections.emptyList());

        assertThatThrownBy(() -> service.correctMatchSets(input))
                .isInstanceOf(IncompleteCorrectionException.class);

        verify(tournamentRepository, never()).findByIdForUpdate(any());
        verify(setResultRepository, never()).insert(any());
        verify(setResultRepository, never()).update(any());
        verify(auditLogRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * Returns all ONCHECK-producing input tuples for each {@link de.vvwt.tm.tournament.MatchFormat}
     * constant, as required by AC-TEST-CORRECTION-REJECTS-PARTIAL-NON-WINNING-RED.
     *
     * <p>The canonical closure: for each format, iterate all {@code (t1, t2)} with {@code t1 + t2 =
     * setsPlayed} from 0 to {@code maxSets}, and collect those for which {@code
     * MatchFormat.deriveMatchState(t1, t2, setsPlayed) == MatchState.ONCHECK}.
     */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
            oncheckTuplesProvider() {
        java.util.stream.Stream.Builder<org.junit.jupiter.params.provider.Arguments> builder =
                java.util.stream.Stream.builder();
        for (de.vvwt.tm.tournament.MatchFormat format :
                de.vvwt.tm.tournament.MatchFormat.values()) {
            int maxSets = format.getMaxSets();
            for (int setsPlayed = 0; setsPlayed <= maxSets; setsPlayed++) {
                for (int t1 = 0; t1 <= setsPlayed; t1++) {
                    int t2 = setsPlayed - t1;
                    try {
                        if (format.deriveMatchState(t1, t2, setsPlayed)
                                == de.vvwt.tm.tournament.MatchState.ONCHECK) {
                            // Build a list of SetScoreCorrection entries that produce this tuple.
                            // t1 sets won by team1 (team1Points > team2Points),
                            // t2 sets won by team2 (team2Points > team1Points).
                            List<SetScoreCorrection> sets = new ArrayList<>();
                            int setIdx = 0;
                            for (int i = 0; i < t1; i++) {
                                sets.add(new SetScoreCorrection(setIdx++, 25, 10));
                            }
                            for (int i = 0; i < t2; i++) {
                                sets.add(new SetScoreCorrection(setIdx++, 10, 25));
                            }
                            builder.add(
                                    org.junit.jupiter.params.provider.Arguments.of(
                                            format, t1, t2, setsPlayed, sets));
                        }
                    } catch (IllegalArgumentException ignored) {
                        // skip invalid argument combinations
                    }
                }
            }
        }
        return builder.build();
    }

    /**
     * AC-TEST-CORRECTION-REJECTS-PARTIAL-NON-WINNING-RED — RED-first parameterized test (E48S28,
     * DEC-22).
     *
     * <p>For every {@link de.vvwt.tm.tournament.MatchFormat} constant and every ONCHECK-producing
     * input tuple (computed via {@link #oncheckTuplesProvider()}), the guard must throw {@link
     * IncompleteCorrectionException} before any DB write, with no DEC-37 lock acquired and no
     * event.
     *
     * <p>Tests FAIL on {@code staging} HEAD.
     */
    @ParameterizedTest(name = "format={0} t1={1} t2={2} setsPlayed={3}")
    @MethodSource("oncheckTuplesProvider")
    @DisplayName(
            "AC-TEST-CORRECTION-REJECTS-PARTIAL-NON-WINNING-RED: all ONCHECK tuples →"
                    + " IncompleteCorrectionException, no lock, no event (E48S28)")
    void partialNonWinningSubmit_allONCHECKTuples_throwsIncompleteCorrectionException(
            de.vvwt.tm.tournament.MatchFormat format,
            int t1Sets,
            int t2Sets,
            int setsPlayed,
            List<SetScoreCorrection> setCorrections) {
        // Arrange: use a tournament with the given format
        Tournament tournamentForFormat =
                new Tournament(
                        TOURNAMENT_ID,
                        "Test tournament",
                        format.name(),
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        "ACTIVE",
                        LocalDateTime.now(),
                        null,
                        2,
                        4);
        when(tournamentRepository.findById(TOURNAMENT_ID))
                .thenReturn(Optional.of(tournamentForFormat));

        Match openMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.OPEN.getLegacyCode(),
                        format.getMaxSets(),
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(openMatch));

        MatchCorrectionInput input = correctionInput(setCorrections);

        // Equal-wins tuples on non-tie formats are also caught by the existing standoff guard
        // (fires before ONCHECK guard). Both are valid pre-write rejections.
        assertThatThrownBy(() -> service.correctMatchSets(input))
                .as(
                        "format=%s t1=%d t2=%d setsPlayed=%d should be rejected pre-write (ONCHECK"
                                + " or standoff guard)",
                        format, t1Sets, t2Sets, setsPlayed)
                .isInstanceOfAny(
                        IncompleteCorrectionException.class, StandoffFormatMismatchException.class);

        // DEC-37: NO lock acquired (both guards fire before lock)
        verify(tournamentRepository, never()).findByIdForUpdate(any());
        // No DB writes (neither guard performs DB writes)
        verify(setResultRepository, never()).insert(any());
        verify(setResultRepository, never()).update(any());
        verify(auditLogRepository, never()).save(any());
        // No event (Brief D-2)
        verify(eventPublisher, never()).publishEvent(any(ApplicationEvent.class));
    }

    /**
     * AC-TEST-CORRECTION-ACCEPTS-TERMINAL-RESOLVING-SUBMIT-GREEN-REGRESSION — GREEN regression.
     *
     * <p>A terminal-resolving submit (BEST_OF_3, team1 wins 2-0) must NOT throw any guard exception
     * and must proceed to update match state to FINISHED_WINNER1.
     */
    @Test
    @DisplayName(
            "AC-TEST-CORRECTION-ACCEPTS-TERMINAL-RESOLVING-SUBMIT-GREEN-REGRESSION: 2-0 on"
                    + " BEST_OF_3 → success, match state updated, MatchResultChangedEvent emitted"
                    + " (E48S28)")
    void terminalResolvingSubmit_bestOf3_twoZero_succeeds() {
        // OPEN match in ACTIVE BEST_OF_3 phase
        Match openMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.OPEN.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(openMatch));

        // Submit 2 sets: team1 wins both → FINISHED_WINNER1
        List<SetScoreCorrection> sets =
                List.of(new SetScoreCorrection(0, 25, 10), new SetScoreCorrection(1, 25, 15));
        MatchCorrectionInput input = correctionInput(sets);

        // setResultRepository.findByMatchId returns the 2 winner1 sets (for deriveMatchState)
        when(setResultRepository.findByMatchId(MATCH_ID))
                .thenReturn(
                        List.of(
                                new SetResult(
                                        MATCH_ID,
                                        0,
                                        PHASE_ID,
                                        25,
                                        10,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now()),
                                new SetResult(
                                        MATCH_ID,
                                        1,
                                        PHASE_ID,
                                        25,
                                        15,
                                        SetState.WINNER1.getLegacyCode(),
                                        LocalDateTime.now(),
                                        LocalDateTime.now())));

        // Must not throw
        MatchCorrectionResult result = service.correctMatchSets(input);

        assertThat(result).isNotNull();
        assertThat(result.auditOnly()).isFalse();
        // Lock WAS acquired (valid path)
        verify(tournamentRepository).findByIdForUpdate(TOURNAMENT_ID);
        // Event WAS published
        verify(eventPublisher).publishEvent(any());
    }

    /**
     * AC-TEST-CONTROLLER-IT-CANCELED-PATH-PRESERVED-GREEN-REGRESSION — GREEN regression.
     *
     * <p>A submit on a CANCELED match (audit-only path) with arbitrary set values must NOT trigger
     * the ONCHECK guard and must return {@code auditOnly=true} and NOT acquire the DEC-37 lock.
     */
    @Test
    @DisplayName(
            "AC-TEST-CONTROLLER-IT-CANCELED-PATH-PRESERVED-GREEN-REGRESSION: CANCELED match →"
                    + " audit-only path unaffected by ONCHECK guard (E48S28)")
    void canceledMatch_emptyOrPartialSets_auditOnlyNotBlocked() {
        Match canceledMatch =
                new Match(
                        MATCH_ID,
                        TOURNAMENT_ID,
                        PHASE_ID,
                        AVATAR1_ID,
                        AVATAR2_ID,
                        MatchState.CANCELED.getLegacyCode(),
                        3,
                        1,
                        1,
                        null,
                        null,
                        null,
                        LocalDateTime.now());
        when(matchRepository.findById(MATCH_ID)).thenReturn(Optional.of(canceledMatch));

        // Empty sets → would be ONCHECK for normal path, but CANCELED bypasses guard
        MatchCorrectionInput input = correctionInput(Collections.emptyList());

        MatchCorrectionResult result = service.correctMatchSets(input);

        assertThat(result.auditOnly()).isTrue();
        // DEC-37: CANCELED path must NOT acquire lock
        verify(tournamentRepository, never()).findByIdForUpdate(any());
    }
}
