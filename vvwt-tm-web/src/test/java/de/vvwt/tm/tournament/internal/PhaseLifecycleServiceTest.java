package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.MatchLockdownService;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.Phase.PhaseStatus;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentLifecycleSupport;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link DefaultPhaseLifecycleService} — RED-first per DEC-22.
 *
 * <p>Same-package test: MAY white-box against implementation class per DEC-36 (same-package test
 * typing rule). Injected as the implementation type.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-TEST-PHASE-START-RED: start() PENDING → ACTIVE; exception on wrong state; event
 *       published
 *   <li>AC-TEST-PHASE-COMPLETE-ALL-FINISHED-RED: complete() ACTIVE → COMPLETED only when all
 *       finished; exception otherwise
 *   <li>AC-TEST-PHASE-FORCE-COMPLETE-RED: forceComplete() ACTIVE → COMPLETED + void unfinished
 *       matches; event published
 *   <li>AC-TEST-ACTIVATION-GUARD-SIEGEREHRUNG-PREDICATE-ISOLATED-RED (E51S18): transition() with
 *       siegerehrung gameMode, optimize=true, optimized=false → guard ACCEPTS via Clause F OR-term
 *       (DEC-59 Clause F); pre-fix: ConflictException; post-fix: succeeds
 * </ul>
 *
 * @see DefaultPhaseLifecycleService
 * @see PhaseLifecycleService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — same-package test typing rule</a>
 * @see <a href="E48S06">E48S06 — Phase-Lifecycle service</a>
 * @see <a href="E51S18">E51S18 — Clause F activation-guard amendment (DEC-59 Clause F)</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPhaseLifecycleService unit tests — E48S06")
class PhaseLifecycleServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchLockdownService matchLockdownService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private TournamentLifecycleSupport tournamentLifecycleSupport;

    private DefaultPhaseLifecycleService service;

    private UUID tournamentId;
    private UUID phaseId;
    private Tournament tournament;

    @BeforeEach
    void setUp() {
        // E51S18 GREEN: ObjectMapper injected for isSiegerehrungPhase() Clause F guard
        // E48S24 GREEN: TournamentLifecycleSupport injected for D-1b isLastPhase predicate
        ObjectMapper objectMapper = new ObjectMapper();
        service =
                new DefaultPhaseLifecycleService(
                        tournamentRepository,
                        phaseRepository,
                        matchRepository,
                        matchLockdownService,
                        eventPublisher,
                        objectMapper,
                        tournamentLifecycleSupport);

        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();

        tournament = new Tournament();
        tournament.setId(tournamentId);
        tournament.setStatus("ACTIVE");

        // Default: findByIdForUpdate returns a locked tournament
        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(tournament);
        // Default: isLastPhase returns false — most tests do not exercise auto-complete.
        // Lenient stub because tests that don't invoke complete() never reach isLastPhase
        // (E48S24 D-1b — default prevents unexpected tournament-status side-effects in
        // existing tests that set tournament.status="ACTIVE" for other purposes).
        lenient().when(tournamentLifecycleSupport.isLastPhase(any())).thenReturn(false);
    }

    // =========================================================================
    // AC-TEST-PHASE-START-RED: start() PREPARED → ACTIVE (E48S17 refactor)
    // =========================================================================

    @Test
    @DisplayName("start() — ASSIGNED phase (seq=1) transitions to ACTIVE (E51S06 refactor)")
    void start_preparedPhase_transitionsToActive() {
        Phase phase = assignedPhase(); // sequenceNumber = 1, no predecessor check executed; E51S06
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.start(phaseId);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(phaseRepository).save(argThat(p -> "ACTIVE".equals(p.getStatus())));
    }

    @Test
    @DisplayName("start() — publishes PhaseStatusChangedEvent on ASSIGNED→ACTIVE (E51S06)")
    void start_preparedPhase_publishesPhaseStatusChangedEvent() {
        Phase phase = assignedPhase(); // E51S06: ASSIGNED → ACTIVE
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.start(phaseId);

        verify(eventPublisher)
                .publishEvent(
                        argThat(
                                e ->
                                        e.toString().contains("ASSIGNED")
                                                && e.toString().contains("ACTIVE")));
    }

    @Test
    @DisplayName("start() — ACTIVE phase throws ConflictException (invalid transition)")
    void start_activePhase_throwsConflictException() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("start() — PENDING phase throws ConflictException (E51S06: now requires ASSIGNED)")
    void start_pendingPhase_throwsConflictException() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("start() — acquires per-tournament row-lock (DEC-37 Clause B) as first read")
    void start_acquiresTournamentLockFirst() {
        Phase phase = assignedPhase(); // seq=1 → no predecessor check; E51S06
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.start(phaseId);

        verify(tournamentRepository).findByIdForUpdate(tournamentId);
    }

    // =========================================================================
    // AC-TEST-PHASE-COMPLETE-ALL-FINISHED-RED: complete() only when all finished
    // =========================================================================

    @Test
    @DisplayName("complete() — ACTIVE phase with all matches finished → COMPLETED")
    void complete_activePhaseAllFinished_transitionsToCompleted() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.countUnfinishedByPhaseId(phaseId)).thenReturn(0L);
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.complete(phaseId);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(phaseRepository).save(argThat(p -> "COMPLETED".equals(p.getStatus())));
    }

    @Test
    @DisplayName("complete() — ACTIVE phase with unfinished matches → ConflictException")
    void complete_activePhaseWithUnfinishedMatches_throwsConflictException() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.countUnfinishedByPhaseId(phaseId)).thenReturn(3L);

        assertThatThrownBy(() -> service.complete(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("unfinished");
    }

    @Test
    @DisplayName("complete() — PENDING phase throws ConflictException")
    void complete_pendingPhase_throwsConflictException() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.complete(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("complete() — publishes PhaseStatusChangedEvent on ACTIVE→COMPLETED")
    void complete_allFinished_publishesPhaseStatusChangedEvent() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.countUnfinishedByPhaseId(phaseId)).thenReturn(0L);
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.complete(phaseId);

        verify(eventPublisher)
                .publishEvent(
                        argThat(
                                e ->
                                        e.toString().contains("ACTIVE")
                                                && e.toString().contains("COMPLETED")));
    }

    // =========================================================================
    // AC-TEST-PHASE-FORCE-COMPLETE-RED: forceComplete() voids unfinished + COMPLETED
    // =========================================================================

    @Test
    @DisplayName("forceComplete() — ACTIVE phase → COMPLETED + cancels unfinished matches")
    void forceComplete_activePhase_transitionsToCompletedAndCancelsMatches() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.forceComplete(phaseId);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(matchLockdownService).cancelOpenMatchesByTournamentId(tournamentId);
    }

    @Test
    @DisplayName("forceComplete() — PENDING phase throws ConflictException")
    void forceComplete_pendingPhase_throwsConflictException() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.forceComplete(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("forceComplete() — publishes PhaseStatusChangedEvent on ACTIVE→COMPLETED")
    void forceComplete_activePhase_publishesPhaseStatusChangedEvent() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.forceComplete(phaseId);

        verify(eventPublisher)
                .publishEvent(
                        argThat(
                                e ->
                                        e.toString().contains("ACTIVE")
                                                && e.toString().contains("COMPLETED")));
    }

    @Test
    @DisplayName("forceComplete() — acquires per-tournament row-lock (DEC-37 Clause B)")
    void forceComplete_acquiresTournamentLockFirst() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.forceComplete(phaseId);

        verify(tournamentRepository).findByIdForUpdate(tournamentId);
    }

    // =========================================================================
    // AC-GOVERNANCE-NO-AUTO-ADVANCE-IN-CASCADE: cascade does not change phase status
    // (non-DefaultPhaseLifecycleService behavior — verified via MatchRepository)
    // This story's service is phase-status-agnostic from the scoring side (D-12).
    // =========================================================================

    // =========================================================================
    // AC-TEST-ACTIVATION-GUARD-SIEGEREHRUNG-PREDICATE-ISOLATED-RED (E51S18 DEC-59 Clause F)
    // Guard predicate tested in isolation via transition() — synthetic input state.
    // =========================================================================

    /**
     * AC-TEST-ACTIVATION-GUARD-SIEGEREHRUNG-PREDICATE-ISOLATED-RED (E51S18, DEC-59 Clause F).
     *
     * <p>Verifies the Clause F OR-term: a siegerehrung phase with {@code optimize=true} and {@code
     * optimized=false} must NOT be rejected by the activation-guard. The guard predicate is tested
     * IN ISOLATION via {@link DefaultPhaseLifecycleService#transition(UUID, PhaseStatus, String)}.
     *
     * <p><b>Pre-fix (RED):</b> {@code transition(phaseId, ACTIVE, "start")} throws {@link
     * ConflictException} — original DEC-55 D-6 guard {@code !tournament.optimize OR
     * phase.optimized} evaluates to {@code false} for this synthetic input (optimize=true,
     * optimized=false, no siegerehrung OR-term).
     *
     * <p><b>Post-fix (GREEN):</b> Clause F OR-term {@code OR section.gameMode == "siegerehrung"}
     * exempts the phase → {@code transition()} succeeds.
     *
     * <p><b>Note on full operational reachability:</b> This AC verifies the guard predicate in
     * isolation only. Full reachability of siegerehrung → ASSIGNED via real operator-confirmation
     * depends on the siegerehrung proposal algorithm (out-of-scope per Clause C deferral; follow-up
     * Story closes the operational round-trip).
     *
     * @see DefaultPhaseLifecycleService#transition(UUID, PhaseStatus, String)
     * @see <a href="DEC-59">DEC-59 Clause F — activation-guard gameMode OR-term</a>
     * @see <a href="E51S18">E51S18 — operationalize DEC-59</a>
     */
    @Test
    @DisplayName(
            "transition() — siegerehrung ASSIGNED + optimize=true + optimized=false"
                    + " → guard ACCEPTS via Clause F OR-term (E51S18 DEC-59 Clause F RED)")
    void transition_siegerehrungPhase_optimizeEnabled_notOptimized_guardAcceptsClauseF() {
        // Synthetic input: siegerehrung phase, optimize=true, optimized=false, status=ASSIGNED
        // draft_json with one siegerehrung section (sequenceNumber=1 → section index 0)
        tournament.setOptimize(true);
        tournament.setDraftJson(
                "{\"sections\":[{\"gameMode\":\"siegerehrung\",\"groupCount\":1}]}");

        Phase phase = assignedPhase(); // sequenceNumber=1, status=ASSIGNED
        phase.setOptimized(false);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // AC-TEST-ACTIVATION-GUARD-SIEGEREHRUNG-PREDICATE-ISOLATED-RED:
        // Pre-fix: throws ConflictException (no siegerehrung OR-term in guard).
        // Post-fix: does NOT throw (Clause F OR-term exempts siegerehrung from optimize guard).
        assertThatCode(() -> service.transition(phaseId, PhaseStatus.ACTIVE, "start"))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Phase pendingPhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("PENDING");
        p.setSequenceNumber(1);
        p.setDescription("Vorrunde");
        p.setCurrentLapNumber(0);
        return p;
    }

    private Phase preparedPhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("PREPARED");
        p.setSequenceNumber(1);
        p.setDescription("Vorrunde");
        p.setCurrentLapNumber(0);
        return p;
    }

    private Phase activePhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("ACTIVE");
        p.setSequenceNumber(1);
        p.setDescription("Vorrunde");
        p.setCurrentLapNumber(2);
        return p;
    }

    private Phase assignedPhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("ASSIGNED"); // E51S06: start() requires ASSIGNED
        p.setSequenceNumber(1);
        p.setDescription("Vorrunde");
        p.setCurrentLapNumber(0);
        return p;
    }
}
