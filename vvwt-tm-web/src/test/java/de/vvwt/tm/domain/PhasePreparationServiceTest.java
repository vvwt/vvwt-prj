package de.vvwt.tm.domain;

import de.vvwt.tm.domain.generator.MatchGenerator;
import de.vvwt.tm.domain.referee.RefereeAssigner;
import de.vvwt.tm.domain.referee.RefereeAssignmentReport;
import de.vvwt.tm.domain.repo.MatchOutcomeRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.domain.rules.TournamentRuleResolver;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PhasePreparationService} — AC9 through AC15 (E03S12).
 *
 * <p>Uses Mockito for all dependencies. No database required.
 */
@ExtendWith(MockitoExtension.class)
class PhasePreparationServiceTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchOutcomeRepository matchOutcomeRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentRuleResolver tournamentRuleResolver;
    @Mock private SlotOptimizationClient slotOptimizationClient;
    @Mock private RefereeAssigner refereeAssigner;
    @Mock private MatchGenerator matchGenerator;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PhasePreparationService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID tournamentId = UUID.randomUUID();
    private final UUID phaseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PhasePreparationService(
                phaseRepository, matchRepository, matchOutcomeRepository,
                teamAvatarRepository, tournamentRepository,
                tournamentRuleResolver, slotOptimizationClient, refereeAssigner,
                eventPublisher);
    }

    // =========================================================================
    // AC9 — generateMatches: fresh phase, 6 avatars → 15 matches created
    // =========================================================================

    @Test
    void ac9_generateMatches_freshPhase_6avatars_15matches() {
        Phase phase = pendingPhase();
        Tournament tournament = tournament();
        List<TeamAvatar> avatars = makeAvatars(6);
        List<Match> generatedMatches = makeMatches(15, phase);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(tournamentRuleResolver.resolveMatchGenerator(tournament)).thenReturn(matchGenerator);
        when(matchGenerator.generate(phase, avatars)).thenReturn(generatedMatches);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        when(matchGenerator.getBeanId()).thenReturn("roundRobin");

        service.generateMatches(phaseId);

        verify(matchRepository).saveAll(generatedMatches);
        // Phase status is still PENDING (not changed by generateMatches)
        assertThat(phase.getStatus()).isEqualTo(Phase.PhaseStatus.PENDING.name());
        // Verify match states are OPEN(0)
        for (Match m : generatedMatches) {
            assertThat(m.getMatchState()).isEqualTo(MatchState.OPEN);
        }
    }

    // =========================================================================
    // AC10 — generateMatches: re-run deletes old matches, inserts new
    // =========================================================================

    @Test
    void ac10_generateMatches_rerun_deletesOldMatchesInsertsNew() {
        Phase phase = pendingPhase();
        Tournament tournament = tournament();
        List<TeamAvatar> avatars = makeAvatars(6);
        List<Match> existingMatches = makeMatches(15, phase);
        List<Match> newMatches = makeMatches(15, phase);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(tournamentRepository.findById(tournamentId)).thenReturn(Optional.of(tournament));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(tournamentRuleResolver.resolveMatchGenerator(tournament)).thenReturn(matchGenerator);
        when(matchGenerator.generate(phase, avatars)).thenReturn(newMatches);
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(existingMatches);
        when(matchGenerator.getBeanId()).thenReturn("roundRobin");

        service.generateMatches(phaseId);

        // Old matches' outcomes were deleted
        for (Match existing : existingMatches) {
            verify(matchOutcomeRepository).deleteByMatchId(existing.getId());
        }
        // Old matches deleted by phaseId
        verify(matchRepository).deleteByPhaseId(phaseId);
        // New matches inserted
        verify(matchRepository).saveAll(newMatches);
    }

    // =========================================================================
    // AC11 — optimizeSlots: delegates to slotOptimizationClient
    // =========================================================================

    @Test
    void ac11_optimizeSlots_delegatesToClient() {
        Phase phase = pendingPhase();
        List<Match> matches = makeMatchesWithSlots(15, phase, null, null);  // no slots yet

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        service.optimizeSlots(phaseId);

        verify(slotOptimizationClient).optimize(phaseId);
    }

    @Test
    void ac11_optimizeSlots_failsIfNoMatches() {
        Phase phase = pendingPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.optimizeSlots(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no matches");
    }

    // =========================================================================
    // AC12 — assignReferees: delegates to RefereeAssigner after slot check
    // =========================================================================

    @Test
    void ac12_assignReferees_delegatesToRefereeAssigner() {
        Phase phase = pendingPhase();
        List<Match> matches = makeMatchesWithSlots(15, phase, 0, 0);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(refereeAssigner.assignReferees(phaseId))
                .thenReturn(RefereeAssignmentReport.builder().totalMatches(15).build());

        service.assignReferees(phaseId);

        verify(refereeAssigner).assignReferees(phaseId);
    }

    @Test
    void ac12_assignReferees_failsIfMatchesLackSlots() {
        Phase phase = pendingPhase();
        List<Match> matchesWithoutSlots = makeMatchesWithSlots(3, phase, null, null);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matchesWithoutSlots);

        assertThatThrownBy(() -> service.assignReferees(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no slot coordinates");
    }

    // =========================================================================
    // AC12a — optimizeSlots re-run idempotency (already has slots)
    // =========================================================================

    @Test
    void ac12a_optimizeSlots_rerun_overwritesSlots() {
        Phase phase = pendingPhase();
        // Matches already have slots — re-run should still delegate to client
        List<Match> matchesWithSlots = makeMatchesWithSlots(15, phase, 0, 0);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matchesWithSlots);

        service.optimizeSlots(phaseId);

        // Must still call optimize — client overwrites existing slots
        verify(slotOptimizationClient).optimize(phaseId);
    }

    // =========================================================================
    // AC12b — assignReferees re-run idempotency
    // =========================================================================

    @Test
    void ac12b_assignReferees_rerun_isIdempotent() {
        Phase phase = pendingPhase();
        List<Match> matches = makeMatchesWithSlots(15, phase, 0, 0);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(refereeAssigner.assignReferees(phaseId))
                .thenReturn(RefereeAssignmentReport.builder().totalMatches(15).build());

        service.assignReferees(phaseId);
        service.assignReferees(phaseId);

        // RefereeAssigner called twice (handles re-assignment internally per E03S10 AC3)
        verify(refereeAssigner, times(2)).assignReferees(phaseId);
    }

    // =========================================================================
    // AC13 — startPhase happy path: ACTIVE, lapNumber=0, all ENABLED
    // =========================================================================

    @Test
    void ac13_startPhase_happyPath() {
        Phase phase = pendingPhase();
        List<Match> matches = makeMatchesWithReferees(9, phase);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        service.startPhase(phaseId);

        assertThat(phase.getStatus()).isEqualTo(Phase.PhaseStatus.ACTIVE.name());
        assertThat(phase.getCurrentLapNumber()).isEqualTo(0);
        verify(phaseRepository).save(phase);
        for (Match match : matches) {
            assertThat(match.getMatchState()).isEqualTo(MatchState.ENABLED);
        }
        verify(matchRepository, atLeastOnce()).save(any(Match.class));
    }

    // =========================================================================
    // AC14 — startPhase: missing referees → IllegalStateException
    // =========================================================================

    @Test
    void ac14_startPhase_missingReferee_throws() {
        Phase phase = pendingPhase();
        // Matches have slots but no referee assignments
        List<Match> matches = makeMatchesWithSlots(3, phase, 0, 0);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        assertThatThrownBy(() -> service.startPhase(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("referee assignment")
                .hasMessageContaining("assignReferees");
    }

    // =========================================================================
    // AC15 — startPhase: manual override (referee_description) satisfies precondition
    // =========================================================================

    @Test
    void ac15_startPhase_manualRefereeDescriptionSatisfiesPrecondition() {
        Phase phase = pendingPhase();
        List<Match> matches = makeMatchesWithManualReferees(5, phase);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);

        // Should not throw — referee_description is accepted per D-35
        service.startPhase(phaseId);

        assertThat(phase.getStatus()).isEqualTo(Phase.PhaseStatus.ACTIVE.name());
    }

    // =========================================================================
    // AC17 / AC18 — phase not found and wrong status
    // =========================================================================

    @Test
    void ac17_generateMatches_phaseNotFound_throws() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.generateMatches(phaseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Phase not found");
    }

    @Test
    void ac18_generateMatches_activePhase_throws() {
        Phase activePhase = phase(Phase.PhaseStatus.ACTIVE);
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(activePhase));

        assertThatThrownBy(() -> service.generateMatches(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PENDING")
                .hasMessageContaining("ACTIVE");
    }

    @Test
    void ac7_startPhase_completedPhase_throws() {
        Phase completedPhase = phase(Phase.PhaseStatus.COMPLETED);
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(completedPhase));

        assertThatThrownBy(() -> service.startPhase(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    // =========================================================================
    // Test data factories
    // =========================================================================

    private Phase pendingPhase() {
        return phase(Phase.PhaseStatus.PENDING);
    }

    private Phase phase(Phase.PhaseStatus status) {
        return new Phase(phaseId, tenantId, tournamentId, 1, "Test Phase",
                status.name(), 0, LocalDateTime.now());
    }

    private Tournament tournament() {
        Tournament t = new Tournament();
        t.setId(tournamentId);
        t.setTenantId(tenantId);
        t.setMatchGeneratorId("roundRobin");
        t.setMatchFormat("BEST_OF_3");
        t.setScoringRuleId("setPoints");
        t.setSetValidationRuleId("standardVolleyball");
        t.setStatus("ACTIVE");
        return t;
    }

    private List<TeamAvatar> makeAvatars(int count) {
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            TeamAvatar avatar = new TeamAvatar(
                    UUID.randomUUID(), tenantId, tournamentId, phaseId,
                    0, i, UUID.randomUUID(), null, LocalDateTime.now());
            avatars.add(avatar);
        }
        return avatars;
    }

    private List<Match> makeMatches(int count, Phase phase) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Match match = new Match(UUID.randomUUID(), tenantId, tournamentId, phaseId,
                    UUID.randomUUID(), UUID.randomUUID(),
                    MatchState.OPEN.getLegacyCode(), 3,
                    null, null, null, null, null, LocalDateTime.now());
            matches.add(match);
        }
        return matches;
    }

    private List<Match> makeMatchesWithSlots(int count, Phase phase, Integer lap, Integer field) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Match match = new Match(UUID.randomUUID(), tenantId, tournamentId, phaseId,
                    UUID.randomUUID(), UUID.randomUUID(),
                    MatchState.OPEN.getLegacyCode(), 3,
                    lap, field, null, null, null, LocalDateTime.now());
            matches.add(match);
        }
        return matches;
    }

    /** Matches with slots AND referee_team_id assigned. */
    private List<Match> makeMatchesWithReferees(int count, Phase phase) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Match match = new Match(UUID.randomUUID(), tenantId, tournamentId, phaseId,
                    UUID.randomUUID(), UUID.randomUUID(),
                    MatchState.OPEN.getLegacyCode(), 3,
                    i / 3, i % 3, UUID.randomUUID(), null, null, LocalDateTime.now());
            matches.add(match);
        }
        return matches;
    }

    /** Matches with slots AND referee_description set (manual override, no referee_team_id). */
    private List<Match> makeMatchesWithManualReferees(int count, Phase phase) {
        List<Match> matches = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Match match = new Match(UUID.randomUUID(), tenantId, tournamentId, phaseId,
                    UUID.randomUUID(), UUID.randomUUID(),
                    MatchState.OPEN.getLegacyCode(), 3,
                    i / 2, i % 2, null, "Manual referee", null, LocalDateTime.now());
            matches.add(match);
        }
        return matches;
    }
}
