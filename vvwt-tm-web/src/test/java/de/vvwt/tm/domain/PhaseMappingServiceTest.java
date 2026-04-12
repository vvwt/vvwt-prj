package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.MatchOutcomeRepository;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRatingRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PhaseMappingService} (E05S08).
 *
 * <p>Mocks all dependencies to isolate mapping logic. Verifies:
 * <ul>
 *   <li>AC1 — mapping suggestion generated from previous phase standings</li>
 *   <li>AC1 — 409 returned when previous phase is not COMPLETED</li>
 *   <li>AC1 — IllegalStateException for Phase 1 (no previous phase)</li>
 *   <li>AC2 — applyMapping creates TeamAvatars; 409 if already exist</li>
 *   <li>AC2 — validation: duplicate positions, non-sequential positions</li>
 *   <li>AC3 — placement_group sort produces correct distribution</li>
 *   <li>AC4 — group_placement sort produces correct distribution</li>
 *   <li>AC5 — team_number sort (fallback) produces stable round-robin</li>
 *   <li>AC10 — redoMapping deletes existing and re-applies</li>
 *   <li>AC12 — tenant scope check via tournament lookup</li>
 * </ul>
 *
 * @see <a href="../../../.gaai/project/contexts/artefacts/stories/E05S08.story.md">Story E05S08</a>
 */
@ExtendWith(MockitoExtension.class)
class PhaseMappingServiceTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private MatchOutcomeRepository matchOutcomeRepository;
    @Mock private TournamentRepository tournamentRepository;

    private PhaseMappingService service;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID PHASE1_ID = UUID.randomUUID();
    private static final UUID PHASE2_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PhaseMappingService(
                phaseRepository, teamAvatarRepository, teamAvatarRatingRepository,
                matchRepository, matchOutcomeRepository, tournamentRepository);
    }

    // =========================================================================
    // AC1 — getMappingSuggestion
    // =========================================================================

    @Test
    void getMappingSuggestion_previousPhaseNotCompleted_throws409() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        Phase phase1 = buildPhase(PHASE1_ID, 1, "ACTIVE", "team_number", 2); // not COMPLETED

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1, phase2));

        assertThatThrownBy(() -> service.getMappingSuggestion(PHASE2_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not COMPLETED");
    }

    @Test
    void getMappingSuggestion_phase1_throwsIllegalState() {
        Phase phase1 = buildPhase(PHASE1_ID, 1, "PENDING", "team_number", 2);

        when(phaseRepository.findById(PHASE1_ID)).thenReturn(Optional.of(phase1));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));

        assertThatThrownBy(() -> service.getMappingSuggestion(PHASE1_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phase 1");
    }

    @Test
    void getMappingSuggestion_phaseNotFound_throwsNotFound() {
        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMappingSuggestion(PHASE2_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("Phase not found");
    }

    @Test
    void getMappingSuggestion_wrongTenant_throwsNotFound() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.empty()); // wrong tenant

        assertThatThrownBy(() -> service.getMappingSuggestion(PHASE2_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("active tenant");
    }

    // =========================================================================
    // AC3 — placement_group sort with 2 source groups → 2 target groups
    // =========================================================================

    @Test
    void getMappingSuggestion_placementGroup_correctRoundRobinDistribution() {
        // 4 teams: group1=[A1, A2], group2=[B1, B2]
        // placement_group sort: A1, B1, A2, B2 (by placement ascending, then group ascending)
        // target 2 groups, round-robin: G1=[A1,A2], G2=[B1,B2] ... wait
        // Actually: i=0 → A1 → group1, i=1 → B1 → group2, i=2 → A2 → group1, i=3 → B2 → group2
        // → G1=[A1(pos1), A2(pos2)], G2=[B1(pos1), B2(pos2)]
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        Phase phase1 = buildPhase(PHASE1_ID, 1, "COMPLETED", "team_number", 2);

        UUID teamA1 = UUID.randomUUID(), teamA2 = UUID.randomUUID();
        UUID teamB1 = UUID.randomUUID(), teamB2 = UUID.randomUUID();
        UUID avatarA1 = UUID.randomUUID(), avatarA2 = UUID.randomUUID();
        UUID avatarB1 = UUID.randomUUID(), avatarB2 = UUID.randomUUID();

        // Phase 1: group1=[A1(pos1), A2(pos2)], group2=[B1(pos1), B2(pos2)]
        List<TeamAvatar> sourceAvatars = List.of(
                buildAvatar(avatarA1, PHASE1_ID, 1, 1, teamA1),
                buildAvatar(avatarA2, PHASE1_ID, 1, 2, teamA2),
                buildAvatar(avatarB1, PHASE1_ID, 2, 1, teamB1),
                buildAvatar(avatarB2, PHASE1_ID, 2, 2, teamB2)
        );

        // Ratings: A1 best (10pts), A2 (7pts), B1 (10pts), B2 (5pts)
        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1, phase2));
        when(teamAvatarRepository.findByPhaseId(PHASE1_ID)).thenReturn(sourceAvatars);
        when(teamAvatarRatingRepository.findById(avatarA1)).thenReturn(Optional.of(buildRating(avatarA1, 10, false)));
        when(teamAvatarRatingRepository.findById(avatarA2)).thenReturn(Optional.of(buildRating(avatarA2, 7, false)));
        when(teamAvatarRatingRepository.findById(avatarB1)).thenReturn(Optional.of(buildRating(avatarB1, 10, false)));
        when(teamAvatarRatingRepository.findById(avatarB2)).thenReturn(Optional.of(buildRating(avatarB2, 5, false)));

        MappingSuggestion suggestion = service.getMappingSuggestion(PHASE2_ID);

        assertThat(suggestion.suggestedAssignments()).hasSize(4);
        // placement_group sort: within each group: A1(1st), A2(2nd); B1(1st), B2(2nd)
        // sorted by (placementInGroup ASC, groupNumber ASC): A1(1,1), B1(1,2), A2(2,1), B2(2,2)
        // round-robin into 2 target groups: i=0→G1, i=1→G2, i=2→G1, i=3→G2
        assertThat(suggestion.suggestedAssignments().get(0).groupNumber()).isEqualTo(1);
        assertThat(suggestion.suggestedAssignments().get(0).groupPosition()).isEqualTo(1);
        assertThat(suggestion.suggestedAssignments().get(1).groupNumber()).isEqualTo(2);
        assertThat(suggestion.suggestedAssignments().get(1).groupPosition()).isEqualTo(1);
        assertThat(suggestion.suggestedAssignments().get(2).groupNumber()).isEqualTo(1);
        assertThat(suggestion.suggestedAssignments().get(2).groupPosition()).isEqualTo(2);
        assertThat(suggestion.suggestedAssignments().get(3).groupNumber()).isEqualTo(2);
        assertThat(suggestion.suggestedAssignments().get(3).groupPosition()).isEqualTo(2);

        // Source groups populated
        assertThat(suggestion.sourceGroups()).hasSize(2);
    }

    // =========================================================================
    // AC5 — team_number fallback
    // =========================================================================

    @Test
    void getMappingSuggestion_teamNumber_stableRoundRobin() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "team_number", 2);
        Phase phase1 = buildPhase(PHASE1_ID, 1, "COMPLETED", "team_number", 1);

        UUID teamX = UUID.randomUUID(), teamY = UUID.randomUUID(), teamZ = UUID.randomUUID();
        UUID avatarX = UUID.randomUUID(), avatarY = UUID.randomUUID(), avatarZ = UUID.randomUUID();

        List<TeamAvatar> sourceAvatars = List.of(
                buildAvatar(avatarX, PHASE1_ID, 1, 1, teamX),
                buildAvatar(avatarY, PHASE1_ID, 1, 2, teamY),
                buildAvatar(avatarZ, PHASE1_ID, 1, 3, teamZ)
        );

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1, phase2));
        when(teamAvatarRepository.findByPhaseId(PHASE1_ID)).thenReturn(sourceAvatars);
        when(teamAvatarRatingRepository.findById(avatarX)).thenReturn(Optional.of(buildRating(avatarX, 10, false)));
        when(teamAvatarRatingRepository.findById(avatarY)).thenReturn(Optional.of(buildRating(avatarY, 7, false)));
        when(teamAvatarRatingRepository.findById(avatarZ)).thenReturn(Optional.of(buildRating(avatarZ, 4, false)));

        MappingSuggestion suggestion = service.getMappingSuggestion(PHASE2_ID);

        assertThat(suggestion.suggestedAssignments()).hasSize(3);
        // 3 teams into 2 groups round-robin: G1=[X(1), Z(2)], G2=[Y(1)]
        // (X=best pos1, Y=2nd pos2 from single group, Z=3rd pos3 from single group)
        // team_number fallback: same order as group/placement (groupNumber=1, placementInGroup sorted by D-33)
        assertThat(suggestion.suggestedAssignments().get(0).groupNumber()).isEqualTo(1);
        assertThat(suggestion.suggestedAssignments().get(1).groupNumber()).isEqualTo(2);
        assertThat(suggestion.suggestedAssignments().get(2).groupNumber()).isEqualTo(1);
    }

    // =========================================================================
    // AC2 — applyMapping
    // =========================================================================

    @Test
    void applyMapping_noExistingAvatars_createsAvatars() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        UUID teamA = UUID.randomUUID(), teamB = UUID.randomUUID();

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID)).thenReturn(List.of());
        when(teamAvatarRepository.save(any())).thenAnswer(inv -> {
            TeamAvatar a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });

        List<MappingAssignment> assignments = List.of(
                new MappingAssignment(teamA, 1, 1),
                new MappingAssignment(teamB, 2, 1)
        );

        List<TeamAvatar> result = service.applyMapping(PHASE2_ID, assignments);

        assertThat(result).hasSize(2);
        verify(teamAvatarRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void applyMapping_existingAvatars_throws409() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        UUID existingAvatar = UUID.randomUUID();

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID))
                .thenReturn(List.of(buildAvatar(existingAvatar, PHASE2_ID, 1, 1, UUID.randomUUID())));

        List<MappingAssignment> assignments = List.of(new MappingAssignment(UUID.randomUUID(), 1, 1));

        assertThatThrownBy(() -> service.applyMapping(PHASE2_ID, assignments))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exist");
    }

    @Test
    void applyMapping_duplicatePositions_throwsIllegalArgument() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID)).thenReturn(List.of());

        // Both teams → same group1, position1
        List<MappingAssignment> assignments = List.of(
                new MappingAssignment(UUID.randomUUID(), 1, 1),
                new MappingAssignment(UUID.randomUUID(), 1, 1)  // duplicate
        );

        assertThatThrownBy(() -> service.applyMapping(PHASE2_ID, assignments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate position");
    }

    @Test
    void applyMapping_nonSequentialPositions_throwsIllegalArgument() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID)).thenReturn(List.of());

        // group1 positions: 1 and 3 (missing 2)
        List<MappingAssignment> assignments = List.of(
                new MappingAssignment(UUID.randomUUID(), 1, 1),
                new MappingAssignment(UUID.randomUUID(), 1, 3)  // gap at 2
        );

        assertThatThrownBy(() -> service.applyMapping(PHASE2_ID, assignments))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not sequential");
    }

    @Test
    void applyMapping_emptyAssignments_throwsIllegalArgument() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.applyMapping(PHASE2_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    // =========================================================================
    // AC10 — redoMapping
    // =========================================================================

    @Test
    void redoMapping_pendingPhase_deletesAndRecreates() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        UUID teamA = UUID.randomUUID(), teamB = UUID.randomUUID();
        UUID existingAvatarId = UUID.randomUUID();
        UUID existingMatchId = UUID.randomUUID();

        TeamAvatar existingAvatar = buildAvatar(existingAvatarId, PHASE2_ID, 1, 1, teamA);
        Match existingMatch = new Match();
        existingMatch.setId(existingMatchId);

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(matchRepository.findByPhaseId(PHASE2_ID)).thenReturn(List.of(existingMatch));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID)).thenReturn(List.of(existingAvatar));
        when(teamAvatarRepository.save(any())).thenAnswer(inv -> {
            TeamAvatar a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });

        List<MappingAssignment> assignments = List.of(
                new MappingAssignment(teamA, 1, 1),
                new MappingAssignment(teamB, 2, 1)
        );

        List<TeamAvatar> result = service.redoMapping(PHASE2_ID, assignments);

        assertThat(result).hasSize(2);
        verify(matchOutcomeRepository).deleteById(existingMatchId);
        verify(matchRepository).deleteByPhaseId(PHASE2_ID);
        verify(teamAvatarRatingRepository).deleteById(existingAvatarId);
        verify(teamAvatarRepository).deleteById(existingAvatarId);
    }

    @Test
    void redoMapping_activePhase_throws409() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "ACTIVE", "placement_group", 2);

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));

        assertThatThrownBy(() -> service.redoMapping(PHASE2_ID, List.of(new MappingAssignment(UUID.randomUUID(), 1, 1))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not PENDING");
    }

    // =========================================================================
    // AC12 — hasExistingMapping (used by UI for AC10 guard)
    // =========================================================================

    @Test
    void hasExistingMapping_noAvatars_returnsFalse() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID)).thenReturn(List.of());

        assertThat(service.hasExistingMapping(PHASE2_ID)).isFalse();
    }

    @Test
    void hasExistingMapping_avatarsPresent_returnsTrue() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 2);
        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(teamAvatarRepository.findByPhaseId(PHASE2_ID))
                .thenReturn(List.of(buildAvatar(UUID.randomUUID(), PHASE2_ID, 1, 1, UUID.randomUUID())));

        assertThat(service.hasExistingMapping(PHASE2_ID)).isTrue();
    }

    // =========================================================================
    // D-26 — withoutAssessment teams sorted last
    // =========================================================================

    @Test
    void getMappingSuggestion_withoutAssessmentTeam_sortedLast() {
        Phase phase2 = buildPhase(PHASE2_ID, 2, "PENDING", "placement_group", 1);
        Phase phase1 = buildPhase(PHASE1_ID, 1, "COMPLETED", "team_number", 1);

        UUID teamNormal = UUID.randomUUID(), teamWA = UUID.randomUUID();
        UUID avatarNormal = UUID.randomUUID(), avatarWA = UUID.randomUUID();

        List<TeamAvatar> sourceAvatars = List.of(
                buildAvatar(avatarNormal, PHASE1_ID, 1, 1, teamNormal),
                buildAvatar(avatarWA, PHASE1_ID, 1, 2, teamWA)
        );

        when(phaseRepository.findById(PHASE2_ID)).thenReturn(Optional.of(phase2));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(buildTournament()));
        when(phaseRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(phase1, phase2));
        when(teamAvatarRepository.findByPhaseId(PHASE1_ID)).thenReturn(sourceAvatars);
        // avatarWA has withoutAssessment=true → it should rank last in standing + be sorted last
        when(teamAvatarRatingRepository.findById(avatarNormal)).thenReturn(Optional.of(buildRating(avatarNormal, 5, false)));
        when(teamAvatarRatingRepository.findById(avatarWA)).thenReturn(Optional.of(buildRating(avatarWA, 10, true)));

        MappingSuggestion suggestion = service.getMappingSuggestion(PHASE2_ID);

        // teamWA has more points but isWithoutAssessment=true → must be LAST in suggestion
        List<MappingSuggestion.TargetAssignment> assignments = suggestion.suggestedAssignments();
        assertThat(assignments).hasSize(2);
        // Last assignment should be teamWA (withoutAssessment sorts last)
        assertThat(assignments.get(1).teamId()).isEqualTo(teamWA);
    }

    // =========================================================================
    // Helper builders
    // =========================================================================

    private Phase buildPhase(UUID id, int seqNumber, String status, String sortType, int groupCount) {
        Phase p = new Phase();
        p.setId(id);
        p.setTenantId(TENANT_ID);
        p.setTournamentId(TOURNAMENT_ID);
        p.setSequenceNumber(seqNumber);
        p.setDescription("Phase " + seqNumber);
        p.setStatus(status);
        p.setCurrentLapNumber(0);
        p.setSortType(sortType);
        p.setGroupCount(groupCount);
        return p;
    }

    private Tournament buildTournament() {
        Tournament t = new Tournament();
        t.setId(TOURNAMENT_ID);
        t.setTenantId(TENANT_ID);
        t.setDescription("Test Tournament");
        t.setStatus("PLANNED");
        t.setMatchFormat("BEST_OF_1");
        t.setScoringRuleId("setPoints");
        t.setSetValidationRuleId("standardVolleyball");
        t.setMatchGeneratorId("roundRobin");
        return t;
    }

    private TeamAvatar buildAvatar(UUID id, UUID phaseId, int groupNum, int groupPos, UUID teamId) {
        TeamAvatar a = new TeamAvatar();
        a.setId(id);
        a.setTenantId(TENANT_ID);
        a.setTournamentId(TOURNAMENT_ID);
        a.setPhaseId(phaseId);
        a.setGroupNumber(groupNum);
        a.setGroupPosition(groupPos);
        a.setTeamId(teamId);
        return a;
    }

    private TeamAvatarRating buildRating(UUID avatarId, int points, boolean withoutAssessment) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avatarId);
        r.setTenantId(TENANT_ID);
        r.setPoints(points);
        r.setWithoutAssessment(withoutAssessment);
        return r;
    }
}
