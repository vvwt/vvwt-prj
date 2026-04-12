package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseAuditLogRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.SetResultRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PhaseLifecycleService} — AC1–AC6, AC11 (E05S07).
 */
@ExtendWith(MockitoExtension.class)
class PhaseLifecycleServiceTest {

    @Mock private PhasePreparationService phasePreparationService;
    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private SetResultRepository setResultRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseAuditLogRepository phaseAuditLogRepository;

    private PhaseLifecycleService service;

    private final UUID tenantId     = UUID.randomUUID();
    private final UUID tournamentId = UUID.randomUUID();
    private final UUID phaseId      = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PhaseLifecycleService(
                phasePreparationService,
                phaseRepository,
                matchRepository,
                setResultRepository,
                teamAvatarRepository,
                teamRepository,
                tournamentRepository,
                phaseAuditLogRepository
        );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Phase pendingPhase() {
        Phase p = new Phase(phaseId, tenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.PENDING.name(), 0, LocalDateTime.now());
        return p;
    }

    private Phase activePhase(int currentLap) {
        return new Phase(phaseId, tenantId, tournamentId, 1,
                "Phase 1", Phase.PhaseStatus.ACTIVE.name(), currentLap, LocalDateTime.now());
    }

    private Tournament plannedTournament() {
        return new Tournament(tournamentId, tenantId, "T1", "BEST_OF_1",
                "rule1", "rule2", "gen1", "PLANNED", LocalDateTime.now());
    }

    /** Creates a match with a given lap, field, and state. */
    private Match matchWithLapAndState(int lapNumber, int fieldNumber, MatchState state) {
        UUID avatar1 = UUID.randomUUID();
        UUID avatar2 = UUID.randomUUID();
        Match m = new Match(UUID.randomUUID(), tenantId, tournamentId, phaseId,
                avatar1, avatar2, state.getLegacyCode(), 1,
                lapNumber, fieldNumber, null, null, null, null);
        return m;
    }

    // =========================================================================
    // prepare — AC1, AC12
    // =========================================================================

    @Test
    void prepare_success_allStepsOk() {
        // Arrange
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(pendingPhase()));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));

        // Act
        PhasePreparationResult result = service.prepare(phaseId);

        // Assert
        assertThat(result.overallSuccess()).isTrue();
        assertThat(result.generateMatchesSuccess()).isTrue();
        assertThat(result.optimizeSlotsSuccess()).isTrue();
        assertThat(result.assignRefereesSuccess()).isTrue();

        verify(phasePreparationService).generateMatches(phaseId);
        verify(phasePreparationService).optimizeSlots(phaseId);
        verify(phasePreparationService).assignReferees(phaseId);
    }

    @Test
    void prepare_generateMatchesFails_stepsSkipped() {
        // Arrange
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(pendingPhase()));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));
        doThrow(new IllegalStateException("no avatars")).when(phasePreparationService).generateMatches(phaseId);

        // Act
        PhasePreparationResult result = service.prepare(phaseId);

        // Assert
        assertThat(result.overallSuccess()).isFalse();
        assertThat(result.generateMatchesSuccess()).isFalse();
        assertThat(result.generateMatchesMessage()).contains("no avatars");
        assertThat(result.optimizeSlotsSuccess()).isFalse();
        assertThat(result.optimizeSlotsMessage()).contains("skipped");
        assertThat(result.assignRefereesSuccess()).isFalse();

        verify(phasePreparationService).generateMatches(phaseId);
        verify(phasePreparationService, never()).optimizeSlots(any());
        verify(phasePreparationService, never()).assignReferees(any());
    }

    @Test
    void prepare_optimizeSlotsFails_step3Skipped() {
        // Arrange
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(pendingPhase()));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));
        doThrow(new RuntimeException("optimizer timeout")).when(phasePreparationService).optimizeSlots(phaseId);

        // Act
        PhasePreparationResult result = service.prepare(phaseId);

        // Assert
        assertThat(result.overallSuccess()).isFalse();
        assertThat(result.generateMatchesSuccess()).isTrue();
        assertThat(result.optimizeSlotsSuccess()).isFalse();
        assertThat(result.optimizeSlotsMessage()).contains("optimizer timeout");
        assertThat(result.assignRefereesSuccess()).isFalse();
        assertThat(result.assignRefereesMessage()).contains("skipped");

        verify(phasePreparationService, never()).assignReferees(any());
    }

    @Test
    void prepare_assignRefereesFails_stepRecorded() {
        // Arrange
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(pendingPhase()));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));
        doThrow(new RuntimeException("no eligible referees")).when(phasePreparationService).assignReferees(phaseId);

        // Act
        PhasePreparationResult result = service.prepare(phaseId);

        // Assert
        assertThat(result.overallSuccess()).isFalse();
        assertThat(result.generateMatchesSuccess()).isTrue();
        assertThat(result.optimizeSlotsSuccess()).isTrue();
        assertThat(result.assignRefereesSuccess()).isFalse();
        assertThat(result.assignRefereesMessage()).contains("no eligible referees");
    }

    // =========================================================================
    // start — AC4, PLANNED → ACTIVE tournament transition
    // =========================================================================

    @Test
    void start_successfullyTransitionsTournamentToActive() {
        // Arrange
        Phase pending = pendingPhase();
        Tournament tournament = plannedTournament();

        when(phaseRepository.findById(phaseId))
                .thenReturn(Optional.of(pending))
                .thenReturn(Optional.of(activePhase(0)));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));

        // Act
        Phase result = service.start(phaseId);

        // Assert
        assertThat(result.getStatus()).isEqualTo(Phase.PhaseStatus.ACTIVE.name());
        verify(phasePreparationService).startPhase(phaseId);

        // Tournament must be set to ACTIVE
        ArgumentCaptor<Tournament> tourCaptor = ArgumentCaptor.forClass(Tournament.class);
        verify(tournamentRepository).save(tourCaptor.capture());
        assertThat(tourCaptor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void start_preconditionFails_throws409() {
        // Arrange
        Phase pending = pendingPhase();
        Tournament tournament = plannedTournament();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(pending));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        doThrow(new IllegalStateException("missing slot coordinates")).when(phasePreparationService).startPhase(phaseId);

        // Act + Assert
        assertThatThrownBy(() -> service.start(phaseId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot start phase");
    }

    // =========================================================================
    // advanceLap — AC5
    // =========================================================================

    @Test
    void advanceLap_noUnfinishedMatches_incrementsLap() {
        // Arrange
        Phase active = activePhase(2);
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(active));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));

        // All matches in lap 2 are terminal
        Match m1 = matchWithLapAndState(2, 1, MatchState.FINISHED_WINNER1);
        Match m2 = matchWithLapAndState(2, 2, MatchState.FINISHED_STANDOFF);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(m1, m2));

        // Act
        Phase result = service.advanceLap(phaseId, false, null);

        // Assert
        assertThat(result.getCurrentLapNumber()).isEqualTo(3);
        verify(phaseRepository).save(argThat(p -> p.getCurrentLapNumber() == 3));
        verify(phaseAuditLogRepository, never()).save(any());
    }

    @Test
    void advanceLap_unfinishedMatchesWithoutForce_throws409() {
        // Arrange
        Phase active = activePhase(1);
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(active));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));

        Match unfinished = matchWithLapAndState(1, 1, MatchState.INPROGRESS);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(unfinished));

        // Act + Assert
        assertThatThrownBy(() -> service.advanceLap(phaseId, false, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("1 unfinished match");

        verify(phaseAuditLogRepository, never()).save(any());
    }

    @Test
    void advanceLap_unfinishedMatchesWithForce_writesAuditLogAndIncrements() {
        // Arrange
        Phase active = activePhase(0);
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(active));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));

        Match m1 = matchWithLapAndState(0, 1, MatchState.ENABLED);
        Match m2 = matchWithLapAndState(0, 2, MatchState.FINISHED_WINNER1);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(m1, m2));

        // Act
        Phase result = service.advanceLap(phaseId, true, "organizer1");

        // Assert: lap incremented
        assertThat(result.getCurrentLapNumber()).isEqualTo(1);

        // Assert: audit log written with correct values
        ArgumentCaptor<PhaseAuditLogEntry> auditCaptor = ArgumentCaptor.forClass(PhaseAuditLogEntry.class);
        verify(phaseAuditLogRepository).save(auditCaptor.capture());
        PhaseAuditLogEntry entry = auditCaptor.getValue();
        assertThat(entry.getAction()).isEqualTo(PhaseLifecycleService.ACTION_FORCE_ADVANCE_LAP);
        assertThat(entry.getLapNumber()).isEqualTo(0);
        assertThat(entry.getUnfinishedMatchCount()).isEqualTo(1);
        assertThat(entry.getActorId()).isEqualTo("organizer1");
    }

    @Test
    void advanceLap_notActive_throws409() {
        // Arrange
        Phase pending = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(pending));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(plannedTournament()));

        // Act + Assert
        assertThatThrownBy(() -> service.advanceLap(phaseId, false, null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("must be ACTIVE");
    }

    // =========================================================================
    // getPhase / getTotalLapCount / buildMatchCounts — AC2
    // =========================================================================

    @Test
    void getTotalLapCount_countDistinctLapNumbers() {
        // Arrange — getTotalLapCount only needs matchRepository (no phase/tournament lookup)
        Match m1 = matchWithLapAndState(0, 1, MatchState.FINISHED_WINNER1);
        Match m2 = matchWithLapAndState(0, 2, MatchState.FINISHED_WINNER2);
        Match m3 = matchWithLapAndState(1, 1, MatchState.FINISHED_STANDOFF);
        Match m4 = matchWithLapAndState(2, 1, MatchState.ENABLED);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(m1, m2, m3, m4));

        // Act
        int count = service.getTotalLapCount(phaseId);

        // Assert
        assertThat(count).isEqualTo(3);  // laps 0, 1, 2
    }

    @Test
    void buildMatchCounts_groupsByState() {
        // Arrange — buildMatchCounts only needs matchRepository (no phase/tournament lookup)
        Match enabled  = matchWithLapAndState(0, 1, MatchState.ENABLED);
        Match inProg   = matchWithLapAndState(0, 2, MatchState.INPROGRESS);
        Match finished = matchWithLapAndState(0, 3, MatchState.FINISHED_WINNER1);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(enabled, inProg, finished));

        // Act
        Map<MatchState, Long> counts = service.buildMatchCounts(phaseId);

        // Assert
        assertThat(counts.get(MatchState.ENABLED)).isEqualTo(1L);
        assertThat(counts.get(MatchState.INPROGRESS)).isEqualTo(1L);
        assertThat(counts.get(MatchState.FINISHED_WINNER1)).isEqualTo(1L);
    }

    // =========================================================================
    // listPhases — AC3
    // =========================================================================

    @Test
    void listPhases_returnsPhasesOrderedBySequenceNumber() {
        // Arrange
        Tournament t = new Tournament(tournamentId, tenantId, "T1", "BEST_OF_1",
                "r", "r", "g", "ACTIVE", LocalDateTime.now());
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(t));

        Phase p2 = new Phase(UUID.randomUUID(), tenantId, tournamentId, 2, "P2",
                Phase.PhaseStatus.PENDING.name(), 0, null);
        Phase p1 = new Phase(UUID.randomUUID(), tenantId, tournamentId, 1, "P1",
                Phase.PhaseStatus.ACTIVE.name(), 1, null);
        when(phaseRepository.findByTournamentId(tournamentId)).thenReturn(List.of(p2, p1));

        // Act
        List<Phase> result = service.listPhases(tournamentId);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSequenceNumber()).isEqualTo(1);
        assertThat(result.get(1).getSequenceNumber()).isEqualTo(2);
    }

    // =========================================================================
    // Tenant scope — AC15
    // =========================================================================

    @Test
    void prepare_tournamentNotVisibleInTenantContext_throws404() {
        // Arrange: phase exists but tournament is NOT found (different tenant, visible as 404)
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> service.prepare(phaseId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Phase not found");
    }
}
