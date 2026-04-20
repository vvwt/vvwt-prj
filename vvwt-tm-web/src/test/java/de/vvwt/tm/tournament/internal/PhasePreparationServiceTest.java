package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.internal.referee.RefereeAssigner;
import de.vvwt.tm.tournament.internal.referee.RefereeAssignmentReport;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PhasePreparationService} (AC-TDD-PhasePreparationService +
 * AC-ORCHESTRATION-CONVERGENCE, E21S08).
 *
 * <p>Verifies the full 4-collaborator orchestration convergence per Brief O-8:
 *
 * <ul>
 *   <li>{@link MatchGeneratorRegistry} — from E21S08 (this story)
 *   <li>{@link Phase} aggregate — from E21S03
 *   <li>{@link Match} aggregate persistence ({@link MatchRepository}) — from E21S04/E21S05
 *   <li>Round / referee-pool collaborators ({@link TeamAvatarRepository}, {@link RefereeAssigner})
 *       — from E21S05/E21S08 (this story)
 * </ul>
 *
 * <p>All collaborators mocked per DEC-22 orchestration-logic pattern (E15S03 precedent).
 *
 * <p>Source: inventory row 180 — {@code de.vvwt.tm.tournament.internal.PhasePreparationService}.
 *
 * <p>Related DECs: DEC-21 (Modulith package placement), DEC-22 (TDD Iron Law), DEC-29
 * (compiler-hygiene), DEC-30 (Spotless formatting).
 */
class PhasePreparationServiceTest {

    /*
     * Brief O-8 Convergence map (referenced by AC-ORCHESTRATION-CONVERGENCE):
     *   MatchGeneratorRegistry  → E21S08 (this story, new reconstruction)
     *   PhaseRepository         → E21S03 (Phase aggregate reconstruction)
     *   MatchRepository         → E21S04/E21S05 (Match aggregate + SetResult reconstruction)
     *   TeamAvatarRepository    → E21S04 (TeamAvatar aggregate reconstruction)
     *   RefereeAssigner         → E21S08 (this story, new reconstruction)
     */

    private PhaseRepository phaseRepository;
    private MatchRepository matchRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private MatchGeneratorRegistry matchGeneratorRegistry;
    private RefereeAssigner refereeAssigner;
    private PhasePreparationService service;

    private UUID tenantId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        phaseRepository = mock(PhaseRepository.class);
        matchRepository = mock(MatchRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        matchGeneratorRegistry = mock(MatchGeneratorRegistry.class);
        refereeAssigner = mock(RefereeAssigner.class);

        service =
                new PhasePreparationService(
                        phaseRepository,
                        matchRepository,
                        teamAvatarRepository,
                        matchGeneratorRegistry,
                        refereeAssigner);

        tenantId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
    }

    // -------------------------------------------------------------------------
    // null phaseId → IAE
    // -------------------------------------------------------------------------

    @Test
    void preparePhase_nullPhaseId_throwsIAE() {
        assertThatThrownBy(() -> service.preparePhase(null, "roundRobinNew"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // null generatorKey → IAE
    // -------------------------------------------------------------------------

    @Test
    void preparePhase_nullGeneratorKey_throwsIAE() {
        assertThatThrownBy(() -> service.preparePhase(phaseId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Phase not found → IAE
    // -------------------------------------------------------------------------

    @Test
    void preparePhase_phaseNotFound_throwsIAE() {
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.preparePhase(phaseId, "roundRobinNew"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(phaseId.toString());
    }

    // -------------------------------------------------------------------------
    // Orchestration flow: resolves generator → generates matches → persists → assigns referees
    // -------------------------------------------------------------------------

    @Test
    void preparePhase_orchestration_fullFlow() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        List<TeamAvatar> avatars =
                List.of(buildAvatar(UUID.randomUUID()), buildAvatar(UUID.randomUUID()));
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(any(UUID.class), eq(phaseId)))
                .thenReturn(avatars);

        MatchGenerator generator = mock(MatchGenerator.class);
        when(generator.getBeanId()).thenReturn("roundRobinNew");
        when(matchGeneratorRegistry.get("roundRobinNew")).thenReturn(generator);

        Match generatedMatch = buildMatch();
        when(generator.generate(eq(phase), eq(avatars))).thenReturn(List.of(generatedMatch));
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        RefereeAssignmentReport report =
                RefereeAssignmentReport.builder().totalMatches(1).incrementAssigned().build();
        when(refereeAssigner.assignReferees(phaseId)).thenReturn(report);

        RefereeAssignmentReport result = service.preparePhase(phaseId, "roundRobinNew");

        // Verify generator was resolved and called
        verify(matchGeneratorRegistry).get("roundRobinNew");
        verify(generator).generate(phase, avatars);

        // Verify match was persisted
        verify(matchRepository).save(generatedMatch);

        // Verify referee assignment was invoked
        verify(refereeAssigner).assignReferees(phaseId);

        // Verify report returned
        assertThat(result.getAssignedCount()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Idempotent: existing matches cleared before re-generation
    // -------------------------------------------------------------------------

    @Test
    void preparePhase_existingMatchesCleared() {
        Phase phase = buildPhase();
        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));

        Match existingMatch = buildMatch();
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(existingMatch));

        List<TeamAvatar> avatars = List.of(buildAvatar(UUID.randomUUID()));
        when(teamAvatarRepository.findByTournamentIdAndPhaseId(any(UUID.class), eq(phaseId)))
                .thenReturn(avatars);

        MatchGenerator generator = mock(MatchGenerator.class);
        when(generator.getBeanId()).thenReturn("roundRobinNew");
        when(matchGeneratorRegistry.get("roundRobinNew")).thenReturn(generator);
        when(generator.generate(any(), any())).thenReturn(List.of());

        RefereeAssignmentReport report = RefereeAssignmentReport.builder().totalMatches(0).build();
        when(refereeAssigner.assignReferees(phaseId)).thenReturn(report);

        service.preparePhase(phaseId, "roundRobinNew");

        verify(matchRepository).deleteByPhaseId(phaseId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Phase buildPhase() {
        Phase p = new Phase();
        p.setId(phaseId);
        p.setTenantId(tenantId);
        p.setTournamentId(tournamentId);
        p.setStatus("PENDING");
        return p;
    }

    private Match buildMatch() {
        Match m = new Match();
        m.setId(UUID.randomUUID());
        m.setTenantId(tenantId);
        m.setTournamentId(tournamentId);
        m.setPhaseId(phaseId);
        m.setMemberAvatar1Id(UUID.randomUUID());
        m.setMemberAvatar2Id(UUID.randomUUID());
        m.setState(MatchState.OPEN.getLegacyCode());
        m.setSetLimit(MatchFormat.BEST_OF_3.getMaxSets());
        return m;
    }

    private TeamAvatar buildAvatar(UUID id) {
        TeamAvatar a = new TeamAvatar();
        a.setId(id);
        a.setTenantId(tenantId);
        a.setPhaseId(phaseId);
        a.setTeamId(UUID.randomUUID());
        return a;
    }
}
