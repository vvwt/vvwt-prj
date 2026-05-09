package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.MatchLockdownService;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.PhaseStatusChangedEvent;
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
 * RED-first unit tests for the deprecated {@link DefaultPhaseLifecycleService#prepare(UUID)}
 * (zero-arg, E48S17) and the refactored {@link DefaultPhaseLifecycleService#start(UUID)} (now
 * requires PREPARED status) — E48S17.
 *
 * <p>Same-package test: MAY white-box against implementation class per DEC-36.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-TEST-PHASE-PREPARE-RED: prepare() PENDING → PREPARED; 409 on non-PENDING/non-PREPARED
 *   <li>AC-TEST-PHASE-PREPARE-IDEMPOTENT-RED: prepare() idempotent on PREPARED phase
 *   <li>AC-TEST-PHASE-START-PREPARED-REQUIRED-RED: start() 409 when status != PREPARED
 *   <li>AC-TEST-PHASE-START-PREDECESSOR-COMPLETED-RED: start() 409 when predecessor != COMPLETED
 *   <li>AC-TEST-PHASE-START-FIRST-PHASE-NO-PREDECESSOR-GREEN: start() succeeds for seq=1
 * </ul>
 *
 * @see DefaultPhaseLifecycleService
 * @see PhaseLifecycleService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — same-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="E48S17">E48S17 — PREPARED enum + prepare() + start() refactor</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPhaseLifecycleService prepare() + start() refactor — E48S17 RED-first")
class PhaseLifecyclePrepareServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchLockdownService matchLockdownService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DefaultPhaseLifecycleService service;

    private UUID tournamentId;
    private UUID phaseId;
    private Tournament tournament;

    @BeforeEach
    void setUp() {
        // E51S18 GREEN: ObjectMapper injected for isSiegerehrungPhase() Clause F guard
        ObjectMapper objectMapper = new ObjectMapper();
        service =
                new DefaultPhaseLifecycleService(
                        tournamentRepository,
                        phaseRepository,
                        matchRepository,
                        matchLockdownService,
                        eventPublisher,
                        objectMapper);

        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();

        tournament = new Tournament();
        tournament.setId(tournamentId);
        tournament.setStatus("ACTIVE");

        when(tournamentRepository.findByIdForUpdate(tournamentId)).thenReturn(tournament);
    }

    // =========================================================================
    // AC-TEST-PHASE-PREPARE-RED: prepare() PENDING → PREPARED
    // =========================================================================

    @Test
    @DisplayName("prepare() — PENDING phase transitions to PREPARED (AC-TEST-PHASE-PREPARE-RED)")
    void prepare_pendingPhase_transitionsToPrepared() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.prepare(phaseId);

        assertThat(result.getStatus())
                .as("prepare() on PENDING phase must set status to PREPARED")
                .isEqualTo("PREPARED");
        verify(phaseRepository).save(argThat(p -> "PREPARED".equals(p.getStatus())));
    }

    @Test
    @DisplayName("prepare() — publishes PhaseStatusChangedEvent on PENDING→PREPARED")
    void prepare_pendingPhase_publishesPhaseStatusChangedEvent() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.prepare(phaseId);

        verify(eventPublisher)
                .publishEvent(
                        argThat(
                                e ->
                                        e instanceof PhaseStatusChangedEvent pse
                                                && "PENDING".equals(pse.getPreviousStatus())
                                                && "PREPARED".equals(pse.getNewStatus())));
    }

    @Test
    @DisplayName("prepare() — ACTIVE phase throws ConflictException (AC-TEST-PHASE-PREPARE-RED)")
    void prepare_activePhase_throwsConflictException() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.prepare(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName("prepare() — COMPLETED phase throws ConflictException (AC-TEST-PHASE-PREPARE-RED)")
    void prepare_completedPhase_throwsConflictException() {
        Phase phase = completedPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.prepare(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("prepare() — acquires per-tournament row-lock (DEC-37 Clause B)")
    void prepare_acquiresTournamentLockFirst() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.prepare(phaseId);

        verify(tournamentRepository).findByIdForUpdate(tournamentId);
    }

    // =========================================================================
    // AC-TEST-PHASE-PREPARE-IDEMPOTENT-RED: prepare() idempotent on PREPARED phase
    // =========================================================================

    @Test
    @DisplayName(
            "prepare() — already PREPARED phase is idempotent"
                    + " (AC-TEST-PHASE-PREPARE-IDEMPOTENT-RED)")
    void prepare_preparedPhase_isIdempotent() {
        Phase phase = preparedPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        // idempotent: save() is NOT called; early return with existing phase

        Phase result = service.prepare(phaseId);

        assertThat(result.getStatus())
                .as("prepare() on already-PREPARED phase must return PREPARED (idempotent)")
                .isEqualTo("PREPARED");
    }

    @Test
    @DisplayName("prepare() — already PREPARED phase does NOT publish event (idempotent)")
    void prepare_preparedPhase_doesNotPublishEvent() {
        Phase phase = preparedPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        service.prepare(phaseId);

        // No event published on re-prepare (idempotent — no actual transition)
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // AC-TEST-PHASE-START-PREPARED-REQUIRED-RED: start() now requires PREPARED status
    // =========================================================================

    @Test
    @DisplayName(
            "start() — PENDING phase throws ConflictException (now requires PREPARED)"
                    + " (AC-TEST-PHASE-START-PREPARED-REQUIRED-RED)")
    void start_pendingPhase_throwsConflictException() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName(
            "start() — ACTIVE phase throws ConflictException"
                    + " (AC-TEST-PHASE-START-PREPARED-REQUIRED-RED)")
    void start_activePhase_throwsConflictException() {
        Phase phase = activePhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @DisplayName(
            "start() — COMPLETED phase throws ConflictException"
                    + " (AC-TEST-PHASE-START-PREPARED-REQUIRED-RED)")
    void start_completedPhase_throwsConflictException() {
        Phase phase = completedPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName("start() — ASSIGNED phase (seq=1, no predecessor) transitions to ACTIVE (E51S06)")
    void start_preparedPhaseNoPredecessor_transitionsToActive() {
        Phase phase = assignedPhase(); // sequenceNumber = 1, ASSIGNED (E51S06)
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.start(phaseId);

        assertThat(result.getStatus())
                .as("start() on ASSIGNED phase with no predecessor must set status to ACTIVE")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-PHASE-START-PREDECESSOR-COMPLETED-RED
    // =========================================================================

    @Test
    @DisplayName(
            "start() — predecessor ACTIVE throws ConflictException with message"
                    + " (AC-TEST-PHASE-START-PREDECESSOR-COMPLETED-RED)")
    void start_predecessorActive_throwsConflictExceptionWithMessage() {
        UUID predecessorPhaseId = UUID.randomUUID();
        Phase phase = assignedPhaseWithSeq(2); // E51S06: ASSIGNED required for start()
        Phase predecessor = new Phase();
        predecessor.setId(predecessorPhaseId);
        predecessor.setTournamentId(tournamentId);
        predecessor.setSequenceNumber(1);
        predecessor.setStatus("ACTIVE");

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(tournamentId, 1))
                .thenReturn(Optional.of(predecessor));

        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("predecessor")
                .hasMessageContaining("1")
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("COMPLETED");
    }

    @Test
    @DisplayName(
            "start() — predecessor PENDING throws ConflictException"
                    + " (AC-TEST-PHASE-START-PREDECESSOR-COMPLETED-RED)")
    void start_predecessorPending_throwsConflictException() {
        UUID predecessorPhaseId = UUID.randomUUID();
        Phase phase = assignedPhaseWithSeq(2); // E51S06: ASSIGNED required for start()
        Phase predecessor = new Phase();
        predecessor.setId(predecessorPhaseId);
        predecessor.setTournamentId(tournamentId);
        predecessor.setSequenceNumber(1);
        predecessor.setStatus("PENDING");

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(tournamentId, 1))
                .thenReturn(Optional.of(predecessor));

        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("predecessor");
    }

    @Test
    @DisplayName(
            "start() — predecessor COMPLETED allows transition"
                    + " (AC-TEST-PHASE-START-FIRST-PHASE-NO-PREDECESSOR-GREEN)")
    void start_predecessorCompleted_transitionsToActive() {
        UUID predecessorPhaseId = UUID.randomUUID();
        Phase phase = assignedPhaseWithSeq(2); // E51S06: ASSIGNED required for start()
        Phase predecessor = new Phase();
        predecessor.setId(predecessorPhaseId);
        predecessor.setTournamentId(tournamentId);
        predecessor.setSequenceNumber(1);
        predecessor.setStatus("COMPLETED");

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(tournamentId, 1))
                .thenReturn(Optional.of(predecessor));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.start(phaseId);

        assertThat(result.getStatus())
                .as("start() with COMPLETED predecessor must set status to ACTIVE")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // AC-TEST-PHASE-START-FIRST-PHASE-NO-PREDECESSOR-GREEN: seq=1 no predecessor
    // =========================================================================

    @Test
    @DisplayName(
            "start() — seq=1 ASSIGNED phase with no predecessor succeeds (E51S06)"
                    + " (AC-TEST-PHASE-START-FIRST-PHASE-NO-PREDECESSOR-GREEN)")
    void start_preparedFirstPhase_noAncestor_transitionsToActive() {
        Phase phase = assignedPhase(); // E51S06: ASSIGNED required for start()
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(phaseRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Phase result = service.start(phaseId);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
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

    private Phase preparedPhaseWithSeq(int seq) {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("PREPARED");
        p.setSequenceNumber(seq);
        p.setDescription("Phase " + seq);
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

    private Phase completedPhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("COMPLETED");
        p.setSequenceNumber(1);
        p.setDescription("Vorrunde");
        p.setCurrentLapNumber(3);
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

    private Phase assignedPhaseWithSeq(int seq) {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTournamentId(tournamentId);
        p.setStatus("ASSIGNED"); // E51S06: start() requires ASSIGNED
        p.setSequenceNumber(seq);
        p.setDescription("Phase " + seq);
        p.setCurrentLapNumber(0);
        return p;
    }
}
