package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.MatchLockdownService;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.PhaseTransitionService;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.List;
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
 * RED-first unit tests for {@link DefaultPhaseLifecycleService#prepare(UUID, List)} — E48S21.
 *
 * <p>Verifies that {@code prepare(phaseId, slots)} delegates to {@link
 * PhaseTransitionService#commitTransition(UUID, List)} before setting status PREPARED.
 *
 * <p>Same-package test: MAY white-box against implementation class per DEC-36.
 *
 * <p>This test is authored RED-first before production code changes per DEC-22 Iron Law (Q-1a path
 * for legacy code — DefaultPhaseLifecycleService.prepare is existing legacy code receiving new
 * behavior). Tests fail RED because the current {@code prepare(UUID)} signature accepts no slots
 * parameter and does not delegate to commitTransition.
 *
 * <h2>AC Coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-PREPARE-FLOW-GENERATES-MATCHES-RED (AC1): commitTransition called → generates
 *       matches
 *   <li>AC-TEST-NON-PARTICIPATING-TEAMS-EXCLUDED-RED (AC3): exclusion is commitTransition's
 *       responsibility (tested there); this test verifies delegation occurs
 *   <li>AC-ERROR-HANDLING-NO-PARTIAL-PERSISTENCE (AC-ERROR-NO-PARTIAL): if commitTransition throws,
 *       no status flip (phaseRepository.save not called)
 *   <li>AC-TEST-PREPARE-IDEMPOTENT-ON-PREPARED-GREEN (AC6): PREPARED phase → no commitTransition
 *       call (idempotent short-circuit)
 * </ul>
 *
 * @see DefaultPhaseLifecycleService
 * @see PhaseLifecycleService
 * @see PhaseTransitionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — same-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="E48S21">E48S21 — Fix prepare-flow match generation</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPhaseLifecycleService prepare(phaseId, slots) — E48S21 RED-first")
class PrepareFlowMatchGenServiceTest {

    @Mock private TournamentRepository tournamentRepository;

    @Mock private PhaseRepository phaseRepository;

    @Mock private MatchRepository matchRepository;

    @Mock private MatchLockdownService matchLockdownService;

    @Mock private ApplicationEventPublisher eventPublisher;

    // E48S21: new dependency injected into DefaultPhaseLifecycleService
    @Mock private PhaseTransitionService phaseTransitionService;

    private DefaultPhaseLifecycleService service;

    private UUID tournamentId;
    private UUID phaseId;
    private Tournament tournament;

    private static final List<TeamAvatarProposal> SLOTS =
            List.of(new TeamAvatarProposal(UUID.randomUUID(), 1, 1));

    @BeforeEach
    void setUp() {
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();

        tournament = new Tournament();
        tournament.setId(tournamentId);
        tournament.setStatus("ACTIVE");

        // E48S21: constructor must accept phaseTransitionService as last parameter
        service =
                new DefaultPhaseLifecycleService(
                        tournamentRepository,
                        phaseRepository,
                        matchRepository,
                        matchLockdownService,
                        eventPublisher,
                        phaseTransitionService);

        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(tournament);
    }

    // =========================================================================
    // AC1 — prepare(phaseId, slots): PENDING → calls commitTransition → sets PREPARED
    // =========================================================================

    @Test
    @DisplayName(
            "prepare(phaseId, slots) — PENDING phase: delegates to commitTransition then"
                    + " transitions to PREPARED (AC1 RED-first)")
    void prepare_withSlots_pendingPhase_delegatesToCommitTransitionThenPrepared() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.prepare(phaseId, SLOTS);

        // commitTransition must be called with the phaseId + slots (command — verify() correct)
        verify(phaseTransitionService).commitTransition(eq(phaseId), eq(SLOTS));
        assertThat(result.getStatus())
                .as("prepare() on PENDING phase must set status to PREPARED after commitTransition")
                .isEqualTo("PREPARED");
    }

    @Test
    @DisplayName(
            "prepare(phaseId, slots) — publishes PhaseStatusChangedEvent on PENDING → PREPARED")
    void prepare_withSlots_pendingPhase_publishesEvent() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.prepare(phaseId, SLOTS);

        verify(eventPublisher)
                .publishEvent(
                        org.mockito.ArgumentMatchers.argThat(
                                e ->
                                        e instanceof PhaseStatusChangedEvent pse
                                                && "PENDING".equals(pse.getPreviousStatus())
                                                && "PREPARED".equals(pse.getNewStatus())));
    }

    @Test
    @DisplayName(
            "prepare(phaseId, slots) — acquires per-tournament row-lock first (DEC-37 Clause B)")
    void prepare_withSlots_acquiresTournamentLockFirst() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.prepare(phaseId, SLOTS);

        verify(tournamentRepository).findByIdForUpdate(tournamentId);
    }

    // =========================================================================
    // AC-ERROR-HANDLING-NO-PARTIAL-PERSISTENCE
    // =========================================================================

    @Test
    @DisplayName(
            "prepare(phaseId, slots) — if commitTransition throws, phaseRepository.save NOT called"
                    + " (AC-ERROR-HANDLING-NO-PARTIAL-PERSISTENCE)")
    void prepare_commitTransitionThrows_noStatusFlip() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        org.mockito.Mockito.doThrow(
                        new IllegalArgumentException("simulated commitTransition failure"))
                .when(phaseTransitionService)
                .commitTransition(any(), any());

        assertThatThrownBy(() -> service.prepare(phaseId, SLOTS))
                .isInstanceOf(IllegalArgumentException.class);

        // No status flip — phaseRepository.save must NOT be called
        verify(phaseRepository, never()).save(any());
    }

    // =========================================================================
    // AC-TEST-PREPARE-IDEMPOTENT-ON-PREPARED-GREEN (AC6)
    // =========================================================================

    @Test
    @DisplayName(
            "prepare(phaseId, slots) — PREPARED phase: idempotent return, commitTransition NOT"
                    + " called (AC6 GREEN)")
    void prepare_preparedPhase_idempotent_noCommitTransition() {
        Phase phase = preparedPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        Phase result = service.prepare(phaseId, SLOTS);

        assertThat(result.getStatus())
                .as("AC6: prepare() on PREPARED phase returns PREPARED (idempotent)")
                .isEqualTo("PREPARED");
        // commitTransition must NOT be called for idempotent case (no duplicate avatars)
        verify(phaseTransitionService, never()).commitTransition(any(), any());
    }

    // =========================================================================
    // AC-ERROR-HANDLING-WRONG-PHASE-STATUS
    // =========================================================================

    @Test
    @DisplayName(
            "prepare(phaseId, slots) — ACTIVE phase → ConflictException"
                    + " (AC-ERROR-HANDLING-WRONG-PHASE-STATUS)")
    void prepare_activePhase_throwsConflictException() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.prepare(phaseId, SLOTS))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");
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
}
