package de.vvwt.tm.domain;

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
import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import de.vvwt.tm.domain.rules.ScoringRuleRegistry;
import de.vvwt.tm.domain.rules.SetValidationRuleRegistry;
import de.vvwt.tm.domain.rules.TournamentRuleResolver;
import de.vvwt.tm.domain.rules.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CascadeRecomputeService} with mocked repositories (E03S11, AC17).
 *
 * <p>Verifies the step ordering and rollback semantics without a database.
 * Integration-level correctness (actual SQL, event publishing, lap advance) is covered
 * by {@code CascadeRecomputeServiceIT}.
 */
@ExtendWith(MockitoExtension.class)
class CascadeRecomputeServiceTest {

    // -------------------------------------------------------------------------
    // Mocks
    // -------------------------------------------------------------------------

    @Mock private MatchRepository matchRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private SetResultRepository setResultRepository;
    @Mock private MatchOutcomeRepository matchOutcomeRepository;
    @Mock private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SetValidationRule setValidationRule;
    @Mock private ScoringRule scoringRule;

    private CascadeRecomputeService service;

    // -------------------------------------------------------------------------
    // Shared test data
    // -------------------------------------------------------------------------

    private final UUID tenantId = UUID.randomUUID();
    private final UUID tournamentId = UUID.randomUUID();
    private final UUID phaseId = UUID.randomUUID();
    private final UUID matchId = UUID.randomUUID();
    private final UUID avatar1Id = UUID.randomUUID();
    private final UUID avatar2Id = UUID.randomUUID();
    private final UUID phaseId2 = phaseId; // same phase

    @BeforeEach
    void setUp() {
        when(scoringRule.getBeanId()).thenReturn("setPoints");
        SetValidationRuleRegistry validationRegistry = new SetValidationRuleRegistry(
                java.util.Map.of("standardVolleyball", setValidationRule));
        ScoringRuleRegistry scoringRegistry = new ScoringRuleRegistry(
                java.util.List.of(scoringRule));
        // MatchGeneratorRegistry added in E03S09 (TournamentRuleResolver constructor now requires 3 args).
        // CascadeRecomputeService unit tests do not exercise match generation, so an empty registry is correct.
        MatchGeneratorRegistry matchGeneratorRegistry = new MatchGeneratorRegistry(java.util.List.of());
        TournamentRuleResolver resolver = new TournamentRuleResolver(validationRegistry, scoringRegistry,
                matchGeneratorRegistry);

        service = new CascadeRecomputeService(
                matchRepository, tournamentRepository, phaseRepository,
                setResultRepository, matchOutcomeRepository,
                teamAvatarRatingRepository, teamAvatarRepository,
                auditLogRepository, resolver, eventPublisher);
    }

    // =========================================================================
    // AC17 — Unit test: step sequence fires in order
    // =========================================================================

    @Test
    void registerMatchResult_firesStepsInOrder_firstEntry() {
        // Arrange
        SetResultInput input = SetResultInput.legacy(matchId, 0, 15, 10, null, null);
        Match match = makeMatch(MatchState.OPEN, 3, 1);  // lapNumber=1
        Tournament tournament = makeTournament(MatchFormat.BEST_OF_3);
        Phase phase = makePhase(phaseId, 0);

        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));

        // Step 1: validation passes — set is closed, winner1
        when(setValidationRule.isSetClosed(15, 10, 0, MatchFormat.BEST_OF_3))
                .thenReturn(ValidationResult.winner1());

        // Step 2: no existing set result → INSERT path
        when(setResultRepository.findByMatchIdAndSetIndex(matchId, 0))
                .thenReturn(Optional.empty());

        // Step 3: aggregate — return the newly inserted set
        SetResult sr = makeSetResult(matchId, 0, 15, 10, SetState.WINNER1);
        when(setResultRepository.findByMatchId(matchId)).thenReturn(List.of(sr));

        // Step 4: MatchOutcome save (upsert) — return new outcome
        MatchOutcome savedOutcome = new MatchOutcome(matchId, tenantId, 1, 15, 0, 10, 1,
                MatchState.ONCHECK.getLegacyCode(), null);
        when(matchOutcomeRepository.save(any())).thenReturn(savedOutcome);

        // Step 7/8: no terminal matches yet for either avatar
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar1Id))
                .thenReturn(List.of());
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar2Id))
                .thenReturn(List.of());

        // Existing ratings: none
        when(teamAvatarRatingRepository.findById(avatar1Id)).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.findById(avatar2Id)).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Step 10: phase loaded for lap-advance check
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        // Only the one match in lap 1 — state is OPEN so not all terminal
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));

        // Act
        service.registerMatchResult(input);

        // Assert — key steps fired
        InOrder order = inOrder(setResultRepository, auditLogRepository, matchOutcomeRepository,
                matchRepository, teamAvatarRatingRepository, phaseRepository, eventPublisher);
        order.verify(setResultRepository).findByMatchIdAndSetIndex(matchId, 0);
        order.verify(setResultRepository).insert(any());
        order.verify(auditLogRepository).save(any());
        order.verify(setResultRepository).findByMatchId(matchId);
        order.verify(matchOutcomeRepository).save(any());
        // match state: OPEN -> ONCHECK, so save is called
        order.verify(matchRepository).save(match);
        order.verify(teamAvatarRatingRepository).findById(avatar1Id);
        order.verify(teamAvatarRatingRepository).save(any());
        order.verify(teamAvatarRatingRepository).findById(avatar2Id);
        order.verify(teamAvatarRatingRepository).save(any());
        order.verify(phaseRepository).findById(phaseId);
        order.verify(eventPublisher).publishEvent(any(MatchResultChangedEvent.class));
    }

    @Test
    void registerMatchResult_firesStepsInOrder_correction() {
        // Arrange — second call for same (matchId, setIndex) → UPDATE path
        SetResultInput input = SetResultInput.legacy(matchId, 0, 14, 12, "organizer", "correction");
        Match match = makeMatch(MatchState.ONCHECK, 3, 1);
        Tournament tournament = makeTournament(MatchFormat.BEST_OF_3);
        Phase phase = makePhase(phaseId, 0);

        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(setValidationRule.isSetClosed(14, 12, 0, MatchFormat.BEST_OF_3))
                .thenReturn(ValidationResult.winner1());

        // Existing set result — UPDATE path
        SetResult existing = makeSetResult(matchId, 0, 15, 10, SetState.WINNER1);
        when(setResultRepository.findByMatchIdAndSetIndex(matchId, 0))
                .thenReturn(Optional.of(existing));

        SetResult updatedSr = makeSetResult(matchId, 0, 14, 12, SetState.WINNER1);
        when(setResultRepository.findByMatchId(matchId)).thenReturn(List.of(updatedSr));

        MatchOutcome savedOutcome = new MatchOutcome(matchId, tenantId, 1, 14, 0, 12, 1,
                MatchState.ONCHECK.getLegacyCode(), null);
        when(matchOutcomeRepository.save(any())).thenReturn(savedOutcome);
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar1Id)).thenReturn(List.of());
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar2Id)).thenReturn(List.of());
        when(teamAvatarRatingRepository.findById(avatar1Id)).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.findById(avatar2Id)).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));

        service.registerMatchResult(input);

        // Verify UPDATE path was used (not INSERT)
        verify(setResultRepository).update(any());
        verify(setResultRepository, never()).insert(any());

        // Audit entry carries old values from the existing result
        ArgumentCaptor<AuditLogEntry> auditCaptor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLogEntry auditEntry = auditCaptor.getValue();
        assertThat(auditEntry.getTeam1PointsOld()).isEqualTo(15);
        assertThat(auditEntry.getTeam2PointsOld()).isEqualTo(10);
        assertThat(auditEntry.getTeam1PointsNew()).isEqualTo(14);
        assertThat(auditEntry.getTeam2PointsNew()).isEqualTo(12);
        assertThat(auditEntry.getActorId()).isEqualTo("organizer");
        assertThat(auditEntry.getReason()).isEqualTo("correction");
    }

    // =========================================================================
    // AC17 — Unit test: ValidationException stops cascade, no further steps fire
    // =========================================================================

    @Test
    void registerMatchResult_validationFailure_throwsAndNoFurtherStepsFire() {
        SetResultInput input = SetResultInput.legacy(matchId, 0, 15, 15, null, null);
        Match match = makeMatch(MatchState.OPEN, 3, null);
        Tournament tournament = makeTournament(MatchFormat.BEST_OF_3);

        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(setValidationRule.isSetClosed(15, 15, 0, MatchFormat.BEST_OF_3))
                .thenReturn(ValidationResult.open("no 2-point lead"));

        assertThatThrownBy(() -> service.registerMatchResult(input))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no 2-point lead");

        // After validation failure: no DB writes, no event
        verify(setResultRepository, never()).insert(any());
        verify(setResultRepository, never()).update(any());
        verify(auditLogRepository, never()).save(any());
        verify(matchOutcomeRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // AC17 — Match state diff: no-op UPDATE when state unchanged
    // =========================================================================

    @Test
    void registerMatchResult_noMatchStateUpdate_whenStateUnchanged() {
        // Enter set 1 of BEST_OF_3 when set 0 is already entered — state stays ONCHECK
        SetResultInput input = SetResultInput.legacy(matchId, 1, 8, 15, null, null);
        Match match = makeMatch(MatchState.ONCHECK, 3, 1);
        Tournament tournament = makeTournament(MatchFormat.BEST_OF_3);
        Phase phase = makePhase(phaseId, 0);

        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(setValidationRule.isSetClosed(8, 15, 1, MatchFormat.BEST_OF_3))
                .thenReturn(ValidationResult.winner2());
        when(setResultRepository.findByMatchIdAndSetIndex(matchId, 1)).thenReturn(Optional.empty());

        // Two sets: set0=WINNER1, set1=WINNER2 → 1-1 → ONCHECK (not yet decided)
        SetResult s0 = makeSetResult(matchId, 0, 15, 10, SetState.WINNER1);
        SetResult s1 = makeSetResult(matchId, 1, 8, 15, SetState.WINNER2);
        when(setResultRepository.findByMatchId(matchId)).thenReturn(List.of(s0, s1));

        MatchOutcome savedOutcome = new MatchOutcome(matchId, tenantId, 1, 15, 1, 25, 2,
                MatchState.ONCHECK.getLegacyCode(), null);
        when(matchOutcomeRepository.save(any())).thenReturn(savedOutcome);
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar1Id)).thenReturn(List.of());
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar2Id)).thenReturn(List.of());
        when(teamAvatarRatingRepository.findById(any())).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));

        service.registerMatchResult(input);

        // Match.state was ONCHECK and deriveMatchState(1,1,2) returns ONCHECK → no save
        verify(matchRepository, never()).save(match);
    }

    // =========================================================================
    // AC17 — Event carries correct previous/new state values
    // =========================================================================

    @Test
    void registerMatchResult_eventCarriesCorrectStateValues() {
        SetResultInput input = SetResultInput.legacy(matchId, 2, 15, 5, "player1", null);
        // Match is in ONCHECK (1-1), this is the decisive 3rd set → FINISHED_WINNER1
        Match match = makeMatch(MatchState.ONCHECK, 3, 2);
        Tournament tournament = makeTournament(MatchFormat.BEST_OF_3);
        Phase phase = makePhase(phaseId, 0);

        when(matchRepository.findById(matchId)).thenReturn(Optional.of(match));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(setValidationRule.isSetClosed(15, 5, 2, MatchFormat.BEST_OF_3))
                .thenReturn(ValidationResult.winner1());
        when(setResultRepository.findByMatchIdAndSetIndex(matchId, 2)).thenReturn(Optional.empty());

        // 3 sets: s0=W1, s1=W2, s2=W1 → team1SetsWon=2 → FINISHED_WINNER1
        SetResult s0 = makeSetResult(matchId, 0, 15, 10, SetState.WINNER1);
        SetResult s1 = makeSetResult(matchId, 1, 8, 15, SetState.WINNER2);
        SetResult s2 = makeSetResult(matchId, 2, 15, 5, SetState.WINNER1);
        when(setResultRepository.findByMatchId(matchId)).thenReturn(List.of(s0, s1, s2));

        MatchOutcome savedOutcome = new MatchOutcome(matchId, tenantId, 2, 38, 1, 30, 3,
                MatchState.FINISHED_WINNER1.getLegacyCode(), null);
        when(matchOutcomeRepository.save(any())).thenReturn(savedOutcome);

        // Two terminal matches for each avatar (this match itself now finished)
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar1Id))
                .thenReturn(List.of(match));
        when(matchRepository.findTerminalByPhaseIdAndAvatarId(phaseId, avatar2Id))
                .thenReturn(List.of(match));
        when(matchOutcomeRepository.findById(match.getId())).thenReturn(Optional.of(savedOutcome));
        when(scoringRule.calculatePoints(any(), any())).thenReturn(new ScoringResult(1, 0));
        when(teamAvatarRatingRepository.findById(any())).thenReturn(Optional.empty());
        when(teamAvatarRatingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(match));

        service.registerMatchResult(input);

        ArgumentCaptor<MatchResultChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(MatchResultChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        MatchResultChangedEvent event = eventCaptor.getValue();

        assertThat(event.getPreviousState()).isEqualTo(MatchState.ONCHECK);
        assertThat(event.getNewState()).isEqualTo(MatchState.FINISHED_WINNER1);
        assertThat(event.getMatchId()).isEqualTo(matchId);
        assertThat(event.getActorId()).isEqualTo("player1");
        assertThat(event.getCorrelationId()).isNotNull();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Match makeMatch(MatchState state, int setLimit, Integer lapNumber) {
        Match m = new Match(matchId, tenantId, tournamentId, phaseId,
                avatar1Id, avatar2Id,
                state.getLegacyCode(), setLimit,
                lapNumber, null, null, null, null,
                LocalDateTime.now());
        return m;
    }

    private Tournament makeTournament(MatchFormat format) {
        return new Tournament(tournamentId, tenantId, "Test Tournament",
                format.name(), "setPoints", "standardVolleyball", "roundRobin",
                "ACTIVE", LocalDateTime.now());
    }

    private Phase makePhase(UUID id, int currentLap) {
        return new Phase(id, tenantId, tournamentId, 1, "Vorrunde", "ACTIVE", currentLap,
                LocalDateTime.now());
    }

    private SetResult makeSetResult(UUID matchId, int setIndex,
                                    int t1, int t2, SetState state) {
        return new SetResult(matchId, setIndex, tenantId, phaseId,
                t1, t2, state.getLegacyCode(), null, null);
    }
}
