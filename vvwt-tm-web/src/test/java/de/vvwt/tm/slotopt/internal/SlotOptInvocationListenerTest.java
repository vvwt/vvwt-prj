package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.OptimizePhaseRequestedEvent;
import de.vvwt.tm.tournament.events.SlotOptJobCompletedEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link SlotOptInvocationListener} — E51S04 RED-first.
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-PHASE-OPTIMIZED-FLIP-ON-SUCCESS-RED
 *   <li>AC-TEST-PHASE-OPTIMIZED-FLIP-ON-CANCEL-RED
 *   <li>AC-TEST-LAST-JOB-STATE-TRANSITIONS-RED
 *   <li>AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-QUEUE-LOSS
 * </ul>
 *
 * <h2>DEC-36 cross-package typing</h2>
 *
 * <p>This test class is in package {@code slotopt.internal} — same as the production class.
 * White-box reference to {@link SlotOptInvocationListener} is permitted per DEC-36.
 *
 * <h2>DEC-22 RED-first</h2>
 *
 * <p>Tests written before {@link SlotOptInvocationListener} exists. Fail at compile time until the
 * class is created.
 *
 * @see SlotOptInvocationListener
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament row-lock as first read</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
class SlotOptInvocationListenerTest {

    private TournamentRepository tournamentRepository;
    private PhaseRepository phaseRepository;
    private SlotOptimizationClient slotOptClient;
    private SlotOptimizationJobRegistry jobRegistry;
    private ApplicationEventPublisher eventPublisher;
    private SlotOptInvocationListener listener;

    @BeforeEach
    void setUp() {
        tournamentRepository = mock(TournamentRepository.class);
        phaseRepository = mock(PhaseRepository.class);
        slotOptClient = mock(SlotOptimizationClient.class);
        jobRegistry = mock(SlotOptimizationJobRegistry.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        listener =
                new SlotOptInvocationListener(
                        tournamentRepository,
                        phaseRepository,
                        slotOptClient,
                        jobRegistry,
                        eventPublisher);
    }

    // =========================================================================
    // AC-TEST-PHASE-OPTIMIZED-FLIP-ON-SUCCESS-RED
    // =========================================================================

    /**
     * RED-first: on successful slot-opt completion, {@code phase.optimized=true} is set and a
     * {@code SlotOptJobCompletedEvent} is published.
     */
    @Test
    @DisplayName(
            "On success: phase.optimized=true + SlotOptJobCompletedEvent published"
                    + " — AC-TEST-PHASE-OPTIMIZED-FLIP-ON-SUCCESS-RED")
    void onOptimizeRequested_success_flipsOptimizedAndPublishesCompleted() {
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        OptimizePhaseRequestedEvent event = new OptimizePhaseRequestedEvent(tournamentId, phaseId);

        Tournament tournament = mockTournament(tournamentId);
        Phase phase = mockPhase(phaseId);

        // slotOptClient.optimize() succeeds (no exception)
        listener.onOptimizePhaseRequested(event);

        // phase.optimized must be set to true
        verify(phase).setOptimized(true);
        // phase.last_job_state must be set to 'idle'
        verify(phase).setLastJobState("idle");
        // SlotOptJobCompletedEvent must be published
        verify(eventPublisher).publishEvent(new SlotOptJobCompletedEvent(tournamentId, phaseId));
    }

    // =========================================================================
    // AC-TEST-LAST-JOB-STATE-TRANSITIONS-RED
    // =========================================================================

    /**
     * RED-first: {@code phase.last_job_state} transitions: first set to {@code 'slot_opt_running'}
     * (before invocation), then {@code 'idle'} (on success).
     */
    @Test
    @DisplayName(
            "last_job_state transitions: slot_opt_running → idle on success"
                    + " — AC-TEST-LAST-JOB-STATE-TRANSITIONS-RED")
    void onOptimizeRequested_lastJobStateTransitions_runningThenIdle() {
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        OptimizePhaseRequestedEvent event = new OptimizePhaseRequestedEvent(tournamentId, phaseId);

        Tournament tournament = mockTournament(tournamentId);
        Phase phase = mockPhase(phaseId);

        // Track call order
        java.util.List<String> stateHistory = new java.util.ArrayList<>();
        doAnswer(
                        inv -> {
                            stateHistory.add(inv.getArgument(0));
                            return null;
                        })
                .when(phase)
                .setLastJobState(any());

        listener.onOptimizePhaseRequested(event);

        // last_job_state must be set first to 'slot_opt_running', then to 'idle'
        assertThat(stateHistory)
                .as(
                        "last_job_state must transition: 'slot_opt_running' → 'idle'"
                                + " (AC-TEST-LAST-JOB-STATE-TRANSITIONS-RED)")
                .containsExactly("slot_opt_running", "idle");
    }

    // =========================================================================
    // AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-QUEUE-LOSS
    // =========================================================================

    /**
     * RED-first: if {@code slotOptClient.optimize()} throws, {@code phase.last_job_state='failed'}
     * is persisted AND {@code SlotOptJobCompletedEvent} IS still published (to drain the queue).
     */
    @Test
    @DisplayName(
            "On exception: last_job_state='failed' + SlotOptJobCompletedEvent still published"
                    + " — AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-QUEUE-LOSS")
    void onOptimizeRequested_exception_failsAndStillPublishesCompleted() {
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        OptimizePhaseRequestedEvent event = new OptimizePhaseRequestedEvent(tournamentId, phaseId);

        Tournament tournament = mockTournament(tournamentId);
        Phase phase = mockPhase(phaseId);

        // slotOptClient.optimize() throws (void method — must use doThrow)
        doThrow(new RuntimeException("slot-opt-error-test")).when(slotOptClient).optimize(phaseId);

        listener.onOptimizePhaseRequested(event);

        // phase.last_job_state must be set to 'failed'
        verify(phase).setLastJobState("failed");
        // phase.optimized must NOT be set to true
        verify(phase, never()).setOptimized(true);
        // SlotOptJobCompletedEvent MUST still be published to drain the FIFO
        verify(eventPublisher).publishEvent(new SlotOptJobCompletedEvent(tournamentId, phaseId));
    }

    // =========================================================================
    // AC-IMPL-INVOCATION-LISTENER-IN-SLOTOPT-INTERNAL (last_job_state running)
    // =========================================================================

    /**
     * RED-first: listener sets {@code phase.last_job_state='slot_opt_running'} BEFORE invoking the
     * optimizer. This is the 'running' state signal for E51S07 UI.
     */
    @Test
    @DisplayName(
            "last_job_state set to 'slot_opt_running' before optimize() call"
                    + " — AC-TEST-LAST-JOB-STATE-TRANSITIONS-RED (running precedes invocation)")
    void onOptimizeRequested_setsRunningStateBeforeOptimize() {
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        OptimizePhaseRequestedEvent event = new OptimizePhaseRequestedEvent(tournamentId, phaseId);

        Tournament tournament = mockTournament(tournamentId);
        Phase phase = mockPhase(phaseId);

        java.util.List<String> callOrder = new java.util.ArrayList<>();
        doAnswer(
                        inv -> {
                            callOrder.add("setLastJobState:" + inv.getArgument(0));
                            return null;
                        })
                .when(phase)
                .setLastJobState(any());
        doAnswer(
                        inv -> {
                            callOrder.add("optimize");
                            return null;
                        })
                .when(slotOptClient)
                .optimize(any());

        listener.onOptimizePhaseRequested(event);

        int runningIdx = callOrder.indexOf("setLastJobState:slot_opt_running");
        int optimizeIdx = callOrder.indexOf("optimize");

        assertThat(runningIdx)
                .as("setLastJobState('slot_opt_running') must precede optimize() call")
                .isGreaterThanOrEqualTo(0);
        assertThat(optimizeIdx).as("optimize() must be called").isGreaterThanOrEqualTo(0);
        assertThat(runningIdx)
                .as("setLastJobState('slot_opt_running') must be called BEFORE optimize()")
                .isLessThan(optimizeIdx);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private Tournament mockTournament(UUID tournamentId) {
        Tournament tournament = mock(Tournament.class);
        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(tournament);
        return tournament;
    }

    private Phase mockPhase(UUID phaseId) {
        Phase phase = mock(Phase.class);
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(phase)).thenReturn(phase);
        when(phase.getId()).thenReturn(phaseId);
        return phase;
    }
}
