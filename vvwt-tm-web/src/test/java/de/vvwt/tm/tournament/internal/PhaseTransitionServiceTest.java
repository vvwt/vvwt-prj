// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.PhaseTransitionService;
import de.vvwt.tm.tournament.RefereeAssigner;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.Team2AvatarDistributorRegistry;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TeamSortCalculatorRegistry;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultPhaseTransitionService} — RED-first per DEC-22.
 *
 * <p>Same-package test: MAY white-box against implementation class per DEC-36 (same-package test
 * typing rule).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-TEST-PROPOSE-TEAM-NUMBER-RED: proposeTransition with sortType=team_number
 *   <li>AC-TEST-PROPOSE-PLACEMENT-GROUP-RED: proposeTransition with sortType=placement_group
 *   <li>AC-TEST-PROPOSE-GROUP-PLACEMENT-RED: proposeTransition with sortType=group_placement
 *   <li>AC-TEST-PROPOSE-GROUP-PLACEMENT-RED (edge case): unequal group sizes
 *   <li>AC-ERROR-HANDLING-DRAFT-JSON-NULL: draft_json null → IllegalArgumentException
 *   <li>AC3 (E66S01): Phase 2+ honors distributionMode from draft section
 * </ul>
 *
 * @see DefaultPhaseTransitionService
 * @see PhaseTransitionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (Q-1a, RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — same-package test typing rule</a>
 * @see <a href="E48S07">E48S07 — AC-TEST-PROPOSE-*-RED</a>
 * @see <a href="E66S01">E66S01 — AC3: Phase 2+ distributionMode honored</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPhaseTransitionService unit tests — E48S07, E66S01")
class PhaseTransitionServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private RefereeAssigner refereeAssigner;
    @Mock private PhaseLifecycleService phaseLifecycleService;
    @Mock private Team2AvatarDistributorRegistry distributorRegistry;
    @Mock private TeamSortCalculatorRegistry sortRegistry;

    private DefaultPhaseTransitionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID TO_PHASE_ID = UUID.randomUUID();
    private static final UUID FROM_PHASE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // E58S02: stub sequential distributor for Phase-1 and Phase-2+ paths (default
        // distributionMode)
        SequentialTeam2AvatarDistributor sequentialDistributor =
                new SequentialTeam2AvatarDistributor();
        lenient().when(distributorRegistry.get("sequential")).thenReturn(sequentialDistributor);
        // E66S01 AC3: stub round_robin distributor for Phase-2+ distributionMode tests
        RoundRobinTeam2AvatarDistributor roundRobinDistributor =
                new RoundRobinTeam2AvatarDistributor();
        lenient().when(distributorRegistry.get("round_robin")).thenReturn(roundRobinDistributor);

        // E58S03 AC5 / E66S01 AC4: wire real calculator instances for Phase-2+ paths
        lenient().when(sortRegistry.get("team_number")).thenReturn(new TeamNumberSortCalculator());
        lenient()
                .when(sortRegistry.get("placement_group"))
                .thenReturn(new PlacementGroupSortCalculator());
        lenient()
                .when(sortRegistry.get("group_placement"))
                .thenReturn(new GroupPlacementSortCalculator());

        service =
                new DefaultPhaseTransitionService(
                        tournamentRepository,
                        phaseRepository,
                        teamAvatarRepository,
                        teamAvatarRatingRepository,
                        objectMapper,
                        teamRepository,
                        refereeAssigner,
                        phaseLifecycleService,
                        distributorRegistry,
                        sortRegistry);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-TEAM-NUMBER-RED
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-TEAM-NUMBER-RED: 8 Teams, 2 Gruppen, sortType=team_number (Phase 2+).
     *
     * <p>TeamNumberSortCalculator sorts avatars by teamNumber ASC and produces a flat ranked list.
     * Sequential distributor fills G1 first: teams with lower teamNumber go to G1, higher to G2.
     *
     * <p>E66S01: now uses rank() + distribute() pipeline (DEC-77 D-1).
     */
    @Test
    @DisplayName("proposeTransition — sortType=team_number — 8 Teams → 2 Gruppen Sequential")
    void proposeTransition_teamNumber_8teams2groups_sequential() throws Exception {
        // Given
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "team_number", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "team_number"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 8 avatars in fromPhase
        List<TeamAvatar> fromAvatars = avatars8Teams(FROM_PHASE_ID);
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        // E48S20: teamRepository.findById stubs needed for buildTeamLookup (Phase 2+ path)
        for (int i = 1; i <= 8; i++) {
            UUID tid = teamIdForNumber(i);
            when(teamRepository.findById(tid)).thenReturn(Optional.of(teamWithNumber(tid, i)));
        }

        // E58S03 AC7: findByPhaseId bulk-load — no ratings needed for team_number algorithm
        lenient()
                .when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(List.of());

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 8 proposals
        // TeamNumberSortCalculator sorts by teamNumber ASC → [T1, T2, T3, T4, T5, T6, T7, T8]
        // Sequential distribute(8, 2): positionsPerGroup=4 → G1=T1..T4, G2=T5..T8
        assertThat(proposals).hasSize(8);

        // team with teamNumber=1 → G1 pos1
        assertProposal(proposals, teamIdForNumber(1), 1, 1);
        // team with teamNumber=2 → G1 pos2
        assertProposal(proposals, teamIdForNumber(2), 1, 2);
        // team with teamNumber=3 → G1 pos3
        assertProposal(proposals, teamIdForNumber(3), 1, 3);
        // team with teamNumber=4 → G1 pos4
        assertProposal(proposals, teamIdForNumber(4), 1, 4);
        // team with teamNumber=5 → G2 pos1
        assertProposal(proposals, teamIdForNumber(5), 2, 1);
        // team with teamNumber=6 → G2 pos2
        assertProposal(proposals, teamIdForNumber(6), 2, 2);
        // team with teamNumber=7 → G2 pos3
        assertProposal(proposals, teamIdForNumber(7), 2, 3);
        // team with teamNumber=8 → G2 pos4
        assertProposal(proposals, teamIdForNumber(8), 2, 4);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-PLACEMENT-GROUP-RED (E66S01: rank-major + sequential)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-PLACEMENT-GROUP-RED: sortType=placement_group (E66S01 DEC-77 D-2).
     *
     * <p>Phase N has 2 groups (3 teams each):
     *
     * <ul>
     *   <li>Group 1: A[6pts], B[4pts], C[2pts]
     *   <li>Group 2: D[6pts], E[4pts], F[2pts]
     * </ul>
     *
     * <p>DEC-77 D-2 rank-major interleaving: rank-1 from each group, then rank-2, then rank-3.
     * PlacementGroupSortCalculator produces flat: [A, D, B, E, C, F]. Sequential distribute(6, 2):
     * positionsPerGroup=3 → G1=[A,D,B], G2=[E,C,F].
     *
     * <p>Updated from E58S03: old behavior was "teams stay in same source group". New behavior per
     * DEC-77 D-2 is rank-major interleaving followed by sequential distribution.
     */
    @Test
    @DisplayName(
            "proposeTransition — sortType=placement_group — rank-major interleaving + sequential"
                    + " distribution (E66S01, DEC-77 D-2)")
    void proposeTransition_placementGroup_2groups3teams_rankMajorInterleaving() throws Exception {
        // Given
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "placement_group", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "placement_group"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 6 avatars in fromPhase: 3 per group
        UUID avatarA = UUID.randomUUID();
        UUID avatarB = UUID.randomUUID();
        UUID avatarC = UUID.randomUUID();
        UUID avatarD = UUID.randomUUID();
        UUID avatarE = UUID.randomUUID();
        UUID avatarF = UUID.randomUUID();
        UUID teamA = UUID.randomUUID();
        UUID teamB = UUID.randomUUID();
        UUID teamC = UUID.randomUUID();
        UUID teamD = UUID.randomUUID();
        UUID teamE = UUID.randomUUID();
        UUID teamF = UUID.randomUUID();

        List<TeamAvatar> fromAvatars =
                List.of(
                        avatar(avatarA, FROM_PHASE_ID, teamA, 1, 1),
                        avatar(avatarB, FROM_PHASE_ID, teamB, 1, 2),
                        avatar(avatarC, FROM_PHASE_ID, teamC, 1, 3),
                        avatar(avatarD, FROM_PHASE_ID, teamD, 2, 1),
                        avatar(avatarE, FROM_PHASE_ID, teamE, 2, 2),
                        avatar(avatarF, FROM_PHASE_ID, teamF, 2, 3));
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        when(teamRepository.findById(teamA)).thenReturn(Optional.of(teamWithNumber(teamA, 1)));
        when(teamRepository.findById(teamB)).thenReturn(Optional.of(teamWithNumber(teamB, 2)));
        when(teamRepository.findById(teamC)).thenReturn(Optional.of(teamWithNumber(teamC, 3)));
        when(teamRepository.findById(teamD)).thenReturn(Optional.of(teamWithNumber(teamD, 4)));
        when(teamRepository.findById(teamE)).thenReturn(Optional.of(teamWithNumber(teamE, 5)));
        when(teamRepository.findById(teamF)).thenReturn(Optional.of(teamWithNumber(teamF, 6)));

        // Ratings: A=6pts (rank-1 G1), B=4pts (rank-2 G1), C=2pts (rank-3 G1)
        //          D=6pts (rank-1 G2), E=4pts (rank-2 G2), F=2pts (rank-3 G2)
        when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(
                        List.of(
                                rating(avatarA, 6),
                                rating(avatarB, 4),
                                rating(avatarC, 2),
                                rating(avatarD, 6),
                                rating(avatarE, 4),
                                rating(avatarF, 2)));

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 6 proposals
        // Rank-major flat: [A, D, B, E, C, F]
        // Sequential distribute(6, 2): positionsPerGroup=3 → G1=positions 1-3, G2=positions 1-3
        // Index 0=A → G1p1, 1=D → G1p2, 2=B → G1p3, 3=E → G2p1, 4=C → G2p2, 5=F → G2p3
        assertThat(proposals).hasSize(6);

        assertProposal(proposals, teamA, 1, 1); // rank-1 G1 → G1 pos1
        assertProposal(proposals, teamD, 1, 2); // rank-1 G2 → G1 pos2
        assertProposal(proposals, teamB, 1, 3); // rank-2 G1 → G1 pos3
        assertProposal(proposals, teamE, 2, 1); // rank-2 G2 → G2 pos1
        assertProposal(proposals, teamC, 2, 2); // rank-3 G1 → G2 pos2
        assertProposal(proposals, teamF, 2, 3); // rank-3 G2 → G2 pos3
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-GROUP-PLACEMENT-RED (E66S01: group-major + sequential)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-GROUP-PLACEMENT-RED: sortType=group_placement (E66S01 DEC-77 D-2).
     *
     * <p>Phase N has 2 groups, 4 teams each:
     *
     * <ul>
     *   <li>Group 1: A1[rank 1], A2[rank 2], A3[rank 3], A4[rank 4]
     *   <li>Group 2: B1[rank 1], B2[rank 2], B3[rank 3], B4[rank 4]
     * </ul>
     *
     * <p>DEC-77 D-2 group-major ordering: all of G1 in placement order, then all of G2.
     * GroupPlacementSortCalculator produces flat: [A1, A2, A3, A4, B1, B2, B3, B4]. Sequential
     * distribute(8, 4): positionsPerGroup=2 → G1=[A1,A2], G2=[A3,A4], G3=[B1,B2], G4=[B3,B4].
     *
     * <p>Updated from E58S03: old test expected round-robin-style cross-group placement. New
     * behavior per DEC-77 D-2 is group-major flat list + sequential distribution.
     */
    @Test
    @DisplayName(
            "proposeTransition — sortType=group_placement — group-major flat list + sequential"
                    + " distribution (E66S01, DEC-77 D-2)")
    void proposeTransition_groupPlacement_2groups4teams_groupMajorSequential() throws Exception {
        // Given
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "group_placement", 4);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(4, "group_placement"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        UUID avatarA1 = UUID.randomUUID();
        UUID avatarA2 = UUID.randomUUID();
        UUID avatarA3 = UUID.randomUUID();
        UUID avatarA4 = UUID.randomUUID();
        UUID avatarB1 = UUID.randomUUID();
        UUID avatarB2 = UUID.randomUUID();
        UUID avatarB3 = UUID.randomUUID();
        UUID avatarB4 = UUID.randomUUID();
        UUID teamA1 = UUID.randomUUID();
        UUID teamA2 = UUID.randomUUID();
        UUID teamA3 = UUID.randomUUID();
        UUID teamA4 = UUID.randomUUID();
        UUID teamB1 = UUID.randomUUID();
        UUID teamB2 = UUID.randomUUID();
        UUID teamB3 = UUID.randomUUID();
        UUID teamB4 = UUID.randomUUID();

        List<TeamAvatar> fromAvatars =
                List.of(
                        avatar(avatarA1, FROM_PHASE_ID, teamA1, 1, 1),
                        avatar(avatarA2, FROM_PHASE_ID, teamA2, 1, 2),
                        avatar(avatarA3, FROM_PHASE_ID, teamA3, 1, 3),
                        avatar(avatarA4, FROM_PHASE_ID, teamA4, 1, 4),
                        avatar(avatarB1, FROM_PHASE_ID, teamB1, 2, 1),
                        avatar(avatarB2, FROM_PHASE_ID, teamB2, 2, 2),
                        avatar(avatarB3, FROM_PHASE_ID, teamB3, 2, 3),
                        avatar(avatarB4, FROM_PHASE_ID, teamB4, 2, 4));
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        when(teamRepository.findById(teamA1)).thenReturn(Optional.of(teamWithNumber(teamA1, 1)));
        when(teamRepository.findById(teamA2)).thenReturn(Optional.of(teamWithNumber(teamA2, 2)));
        when(teamRepository.findById(teamA3)).thenReturn(Optional.of(teamWithNumber(teamA3, 3)));
        when(teamRepository.findById(teamA4)).thenReturn(Optional.of(teamWithNumber(teamA4, 4)));
        when(teamRepository.findById(teamB1)).thenReturn(Optional.of(teamWithNumber(teamB1, 5)));
        when(teamRepository.findById(teamB2)).thenReturn(Optional.of(teamWithNumber(teamB2, 6)));
        when(teamRepository.findById(teamB3)).thenReturn(Optional.of(teamWithNumber(teamB3, 7)));
        when(teamRepository.findById(teamB4)).thenReturn(Optional.of(teamWithNumber(teamB4, 8)));

        // Ratings: within each source group, descending points = ascending placement rank
        when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(
                        List.of(
                                rating(avatarA1, 8),
                                rating(avatarA2, 6),
                                rating(avatarA3, 4),
                                rating(avatarA4, 2),
                                rating(avatarB1, 8),
                                rating(avatarB2, 6),
                                rating(avatarB3, 4),
                                rating(avatarB4, 2)));

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 8 proposals, 4 groups of 2
        // Group-major flat: [A1, A2, A3, A4, B1, B2, B3, B4]
        // Sequential distribute(8, 4): positionsPerGroup=2
        //   i=0 A1 → G1p1, i=1 A2 → G1p2
        //   i=2 A3 → G2p1, i=3 A4 → G2p2
        //   i=4 B1 → G3p1, i=5 B2 → G3p2
        //   i=6 B3 → G4p1, i=7 B4 → G4p2
        assertThat(proposals).hasSize(8);

        assertProposal(proposals, teamA1, 1, 1); // G1-rank1 → G1p1
        assertProposal(proposals, teamA2, 1, 2); // G1-rank2 → G1p2
        assertProposal(proposals, teamA3, 2, 1); // G1-rank3 → G2p1
        assertProposal(proposals, teamA4, 2, 2); // G1-rank4 → G2p2
        assertProposal(proposals, teamB1, 3, 1); // G2-rank1 → G3p1
        assertProposal(proposals, teamB2, 3, 2); // G2-rank2 → G3p2
        assertProposal(proposals, teamB3, 4, 1); // G2-rank3 → G4p1
        assertProposal(proposals, teamB4, 4, 2); // G2-rank4 → G4p2
    }

    /**
     * AC-TEST-PROPOSE-GROUP-PLACEMENT-RED (edge case): unequal group sizes (E66S01).
     *
     * <p>Group 1 has 3 teams, Group 2 has 2 teams → 5 total. GroupPlacementSortCalculator returns
     * all 5 (no truncation). Sequential distribute(5, 2): positionsPerGroup=3 → G1=[A1,A2,A3],
     * G2=[B1,B2]. 5 proposals total.
     *
     * <p>Updated from E58S03: old behavior truncated to min-group-size (4 proposals). New behavior
     * per DEC-77 D-1/D-2: calculator returns all teams; distributor determines placement.
     */
    @Test
    @DisplayName(
            "proposeTransition — sortType=group_placement — unequal group sizes: all 5 teams"
                    + " distributed sequentially (E66S01)")
    void proposeTransition_groupPlacement_unequalGroupSizes_allTeamsDistributed() throws Exception {
        // Given
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "group_placement", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "group_placement"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        UUID avrA1 = UUID.randomUUID();
        UUID avrA2 = UUID.randomUUID();
        UUID avrA3 = UUID.randomUUID();
        UUID avrB1 = UUID.randomUUID();
        UUID avrB2 = UUID.randomUUID();
        UUID tmA1 = UUID.randomUUID();
        UUID tmA2 = UUID.randomUUID();
        UUID tmA3 = UUID.randomUUID();
        UUID tmB1 = UUID.randomUUID();
        UUID tmB2 = UUID.randomUUID();

        List<TeamAvatar> fromAvatars =
                List.of(
                        avatar(avrA1, FROM_PHASE_ID, tmA1, 1, 1),
                        avatar(avrA2, FROM_PHASE_ID, tmA2, 1, 2),
                        avatar(avrA3, FROM_PHASE_ID, tmA3, 1, 3),
                        avatar(avrB1, FROM_PHASE_ID, tmB1, 2, 1),
                        avatar(avrB2, FROM_PHASE_ID, tmB2, 2, 2));
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        when(teamRepository.findById(tmA1)).thenReturn(Optional.of(teamWithNumber(tmA1, 1)));
        when(teamRepository.findById(tmA2)).thenReturn(Optional.of(teamWithNumber(tmA2, 2)));
        when(teamRepository.findById(tmA3)).thenReturn(Optional.of(teamWithNumber(tmA3, 3)));
        when(teamRepository.findById(tmB1)).thenReturn(Optional.of(teamWithNumber(tmB1, 4)));
        when(teamRepository.findById(tmB2)).thenReturn(Optional.of(teamWithNumber(tmB2, 5)));

        when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(
                        List.of(
                                rating(avrA1, 6),
                                rating(avrA2, 4),
                                rating(avrA3, 2),
                                rating(avrB1, 6),
                                rating(avrB2, 4)));

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 5 proposals (all teams — no truncation in new design)
        // Group-major flat: [A1, A2, A3, B1, B2]
        // Sequential distribute(5, 2): positionsPerGroup=3 → G1=[A1,A2,A3], G2=[B1,B2]
        assertThat(proposals).hasSize(5);
        assertProposal(proposals, tmA1, 1, 1);
        assertProposal(proposals, tmA2, 1, 2);
        assertProposal(proposals, tmA3, 1, 3);
        assertProposal(proposals, tmB1, 2, 1);
        assertProposal(proposals, tmB2, 2, 2);
    }

    // -------------------------------------------------------------------------
    // AC3 (E66S01): Phase 2+ honors distributionMode
    // -------------------------------------------------------------------------

    /**
     * AC3 (E66S01): Phase 2+ with sortType=team_number and distributionMode=round_robin produces
     * round-robin distribution.
     *
     * <p>DEC-77 D-1: distribution is decoupled from sort; Phase 2+ now honors distributionMode from
     * the draft section (previously Phase 2+ ignored distributionMode and always used the old
     * calculator's built-in logic).
     *
     * <p>4 teams, 2 groups with round_robin: RoundRobin distribute(4, 2): i=0 → G1p1, i=1 → G2p1,
     * i=2 → G1p2, i=3 → G2p2.
     */
    @Test
    @DisplayName("proposeTransition — Phase 2+ — distributionMode=round_robin honored (E66S01 AC3)")
    void proposeTransition_phase2Plus_roundRobinDistributionMode_honored() throws Exception {
        // Given: Phase 2+ with sortType=team_number, distributionMode=round_robin
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "team_number", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        // Build draft_json with distributionMode=round_robin
        Tournament tournament =
                tournamentWithDraftJson(
                        TOURNAMENT_ID,
                        buildDraftJsonWithDistributionMode(2, "team_number", "round_robin"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID();
        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();

        // fromAvatars: 4 teams — all in group 1 for simplicity; teamNumbers determine sort order
        List<TeamAvatar> fromAvatars =
                List.of(
                        avatar(av1, FROM_PHASE_ID, t1, 1, 1),
                        avatar(av2, FROM_PHASE_ID, t2, 1, 2),
                        avatar(av3, FROM_PHASE_ID, t3, 1, 3),
                        avatar(av4, FROM_PHASE_ID, t4, 1, 4));
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        // teamNumbers: t1=1, t2=2, t3=3, t4=4
        Team team1 = teamWithNumber(t1, 1);
        Team team2 = teamWithNumber(t2, 2);
        Team team3 = teamWithNumber(t3, 3);
        Team team4 = teamWithNumber(t4, 4);
        when(teamRepository.findById(t1)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(t2)).thenReturn(Optional.of(team2));
        when(teamRepository.findById(t3)).thenReturn(Optional.of(team3));
        when(teamRepository.findById(t4)).thenReturn(Optional.of(team4));

        lenient()
                .when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(List.of());

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 4 proposals with round-robin distribution
        // TeamNumberSortCalculator ranks: [T1, T2, T3, T4] (sorted by teamNumber ASC)
        // RoundRobin distribute(4, 2): i=0 → G1p1, i=1 → G2p1, i=2 → G1p2, i=3 → G2p2
        assertThat(proposals).hasSize(4);
        assertProposal(proposals, t1, 1, 1); // T1 → G1p1
        assertProposal(proposals, t2, 2, 1); // T2 → G2p1
        assertProposal(proposals, t3, 1, 2); // T3 → G1p2
        assertProposal(proposals, t4, 2, 2); // T4 → G2p2
    }

    // -------------------------------------------------------------------------
    // AC-ERROR-HANDLING-DRAFT-JSON-NULL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("proposeTransition — draft_json is null → IllegalArgumentException")
    void proposeTransition_draftJsonNull_throwsIllegalArgumentException() {
        Phase toPhase = phase(TO_PHASE_ID, 2);
        toPhase.setTournamentId(TOURNAMENT_ID);
        Tournament tournament = new Tournament();
        tournament.setId(TOURNAMENT_ID);
        tournament.setDraftJson(null);

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        // No stub for findByTournamentIdAndSequenceNumber — service throws before reaching
        // fromPhase resolution because draft_json is null (checked immediately after tournament
        // load).

        assertThatThrownBy(() -> service.proposeTransition(TO_PHASE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft_json");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-TRANSITION-PHASE-1-RED (E48S18)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-TRANSITION-PHASE-1-RED: Phase 1 (sequenceNumber=1), sortType=team_number, 8
     * participating Teams → Sequential proposal (default distributionMode, E51S15).
     *
     * <p>Fixture: 8 teams with teamNumber 1-8 (all participate=true), groupCount=2. Draft_json has
     * no {@code distributionMode} field → defaults to {@code "sequential"} (E51S15). Expected:
     *
     * <ul>
     *   <li>positionsPerGroup = ceil(8/2) = 4
     *   <li>Team 1 → group 1, pos 1
     *   <li>Team 2 → group 1, pos 2
     *   <li>Team 3 → group 1, pos 3
     *   <li>Team 4 → group 1, pos 4
     *   <li>Team 5 → group 2, pos 1
     *   <li>Team 6 → group 2, pos 2
     *   <li>Team 7 → group 2, pos 3
     *   <li>Team 8 → group 2, pos 4
     * </ul>
     *
     * <p>DEC-22 Iron Law: test written RED-first before production code change. Updated E51S15 to
     * reflect sequential-default (absent distributionMode → "sequential").
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 1 (sequenceNumber=1) — 8 Teams → Sequential 2 Gruppen"
                    + " (AC-TEST-PROPOSE-TRANSITION-PHASE-1-RED, E51S15)")
    void proposeTransition_phase1_roundRobin_8teams2groups() throws Exception {
        // Given
        UUID phase1Id = UUID.randomUUID();
        Phase toPhase = phase(phase1Id, 1); // sequenceNumber = 1 = Phase 1
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJsonPhase1(2, "team_number"));

        when(phaseRepository.findById(phase1Id)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 8 participating teams ordered by teamNumber ASC (TeamRepository contract)
        List<Team> teams = participatingTeams8(TOURNAMENT_ID);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(phase1Id);

        // Assert: 8 proposals (all participating teams)
        // Default distributionMode="sequential" (absent in draft_json → null → "sequential",
        // E51S15)
        // positionsPerGroup = ceil(8/2) = 4 → G1 gets teams 1-4, G2 gets teams 5-8
        assertThat(proposals).hasSize(8);

        // Team with teamNumber=1 (first in sorted list) → group 1, pos 1
        assertProposal(proposals, teamUuidForNumber(teams, 1), 1, 1);
        // Team with teamNumber=2 → group 1, pos 2
        assertProposal(proposals, teamUuidForNumber(teams, 2), 1, 2);
        // Team with teamNumber=3 → group 1, pos 3
        assertProposal(proposals, teamUuidForNumber(teams, 3), 1, 3);
        // Team with teamNumber=4 → group 1, pos 4
        assertProposal(proposals, teamUuidForNumber(teams, 4), 1, 4);
        // Team with teamNumber=5 → group 2, pos 1
        assertProposal(proposals, teamUuidForNumber(teams, 5), 2, 1);
        // Team with teamNumber=6 → group 2, pos 2
        assertProposal(proposals, teamUuidForNumber(teams, 6), 2, 2);
        // Team with teamNumber=7 → group 2, pos 3
        assertProposal(proposals, teamUuidForNumber(teams, 7), 2, 3);
        // Team with teamNumber=8 → group 2, pos 4
        assertProposal(proposals, teamUuidForNumber(teams, 8), 2, 4);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-TRANSITION-NON-PARTICIPATING-EXCLUDED-RED (E48S18)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-TRANSITION-NON-PARTICIPATING-EXCLUDED-RED: Teams with participate=false are
     * excluded from Phase-1 proposals.
     *
     * <p>Fixture: 8 teams, 2 with participate=false → only 6 proposals.
     *
     * <p>DEC-22 Iron Law: test written RED-first before production code change.
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 1 — non-participating teams excluded"
                    + " (AC-TEST-PROPOSE-TRANSITION-NON-PARTICIPATING-EXCLUDED-RED)")
    void proposeTransition_phase1_nonParticipatingExcluded() throws Exception {
        // Given
        UUID phase1Id = UUID.randomUUID();
        Phase toPhase = phase(phase1Id, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJsonPhase1(2, "team_number"));

        when(phaseRepository.findById(phase1Id)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 8 teams: teams 3 and 7 are referees (participate=false)
        List<Team> allTeams = mixedParticipationTeams(TOURNAMENT_ID);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(allTeams);

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(phase1Id);

        // Assert: only 6 proposals (not 8)
        assertThat(proposals).hasSize(6);

        // Non-participating teams must NOT appear
        UUID nonParticipatingTeam3 = teamUuidForNumber(allTeams, 3);
        UUID nonParticipatingTeam7 = teamUuidForNumber(allTeams, 7);
        assertThat(proposals).noneMatch(p -> p.teamId().equals(nonParticipatingTeam3));
        assertThat(proposals).noneMatch(p -> p.teamId().equals(nonParticipatingTeam7));
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-TRANSITION-PHASE-2-PLUS-UNCHANGED-GREEN (E48S18)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-TRANSITION-PHASE-2-PLUS-UNCHANGED-GREEN: sequenceNumber > 1 (Phase 2+) still
     * uses the existing Phase-N-Avatar path.
     *
     * <p>This test verifies the Phase 2+ behavior is NOT broken by the Phase-1-Branch refactoring.
     * It should be GREEN before AND after the production code change.
     *
     * <p>DEC-22: regression safety for existing behavior.
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 2 (sequenceNumber=2) — existing avatar path unchanged"
                    + " (AC-TEST-PROPOSE-TRANSITION-PHASE-2-PLUS-UNCHANGED-GREEN)")
    void proposeTransition_phase2Plus_existingAvatarPath_unchanged() throws Exception {
        // Given — same setup as the existing team_number test but verified as regression
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "team_number", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "team_number"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        List<TeamAvatar> fromAvatars = avatars8Teams(FROM_PHASE_ID);
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        // E48S20: teamRepository.findById stubs needed for buildTeamLookup (Phase 2+ path)
        for (int i = 1; i <= 8; i++) {
            UUID tid = teamIdForNumber(i);
            when(teamRepository.findById(tid)).thenReturn(Optional.of(teamWithNumber(tid, i)));
        }

        // E58S03 AC7: findByPhaseId bulk-load — no ratings needed for team_number algorithm
        lenient()
                .when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(List.of());

        // Act — Phase 2 must still use the avatar-based path
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 8 proposals from fromAvatars
        assertThat(proposals).hasSize(8);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-FIRST-PHASE-WRONG-SORTTYPE-RED (E48S18)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-FIRST-PHASE-WRONG-SORTTYPE-RED: Phase 1 with sortType ≠ team_number → throws
     * IllegalStateException with operator-actionable message.
     *
     * <p>Defense-in-depth against E48S16-Invariant bypass.
     *
     * <p>DEC-22 Iron Law: test written RED-first before production code change.
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 1 — sortType=placement_group → IllegalStateException"
                    + " (AC-TEST-FIRST-PHASE-WRONG-SORTTYPE-RED)")
    void proposeTransition_phase1_wrongSortType_throwsIllegalStateException() throws Exception {
        // Given: Phase 1 with sortType=placement_group (wrong for Phase 1)
        UUID phase1Id = UUID.randomUUID();
        Phase toPhase = phase(phase1Id, 1);
        // draft_json for Phase 1 with sortType=placement_group instead of team_number
        String wrongSortTypeDraftJson = buildDraftJsonPhase1(2, "placement_group");
        Tournament tournament = tournamentWithDraftJson(TOURNAMENT_ID, wrongSortTypeDraftJson);

        when(phaseRepository.findById(phase1Id)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        // Note: teamRepository is NOT stubbed — the service throws before reaching it
        // (sortType validation happens before team lookup per
        // AC-TEST-FIRST-PHASE-WRONG-SORTTYPE-RED)

        // Act + Assert
        assertThatThrownBy(() -> service.proposeTransition(phase1Id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team_number");
    }

    // -------------------------------------------------------------------------
    // AC-TEST-DTO-FIELDS-PHASE1-RED (E48S20)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-DTO-FIELDS-PHASE1-RED: Phase-1 proposal includes teamNumber, teamDescription, and
     * null source fields.
     *
     * <p>Given: 2 participating teams. Expected: proposals contain teamNumber and teamDescription
     * from the Team entity; sourceGroupNumber and sourceGroupPosition are null.
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 1 — proposal contains teamNumber, teamDescription, null"
                    + " source fields (AC-TEST-DTO-FIELDS-PHASE1-RED)")
    void proposeTransition_phase1_proposalContainsDisplayAndNullSourceFields() throws Exception {
        UUID phase1Id = UUID.randomUUID();
        Phase toPhase = phase(phase1Id, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJsonPhase1(2, "team_number"));

        when(phaseRepository.findById(phase1Id)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        Team t1 = new Team();
        t1.setId(teamId1);
        t1.setTournamentId(TOURNAMENT_ID);
        t1.setTeamNumber(5);
        t1.setDescription("TSV Erbach");
        t1.setParticipate(true);
        Team t2 = new Team();
        t2.setId(teamId2);
        t2.setTournamentId(TOURNAMENT_ID);
        t2.setTeamNumber(7);
        t2.setDescription("SV Blau-Weiß");
        t2.setParticipate(true);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(t1, t2));

        List<TeamAvatarProposal> proposals = service.proposeTransition(phase1Id);

        assertThat(proposals).hasSize(2);
        TeamAvatarProposal p1 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamId1))
                        .findFirst()
                        .orElseThrow();
        assertThat(p1.teamNumber()).isEqualTo(5);
        assertThat(p1.teamDescription()).isEqualTo("TSV Erbach");
        assertThat(p1.sourceGroupNumber()).isNull();
        assertThat(p1.sourceGroupPosition()).isNull();

        TeamAvatarProposal p2 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamId2))
                        .findFirst()
                        .orElseThrow();
        assertThat(p2.teamNumber()).isEqualTo(7);
        assertThat(p2.teamDescription()).isEqualTo("SV Blau-Weiß");
        assertThat(p2.sourceGroupNumber()).isNull();
        assertThat(p2.sourceGroupPosition()).isNull();
    }

    // -------------------------------------------------------------------------
    // AC-TEST-DTO-FIELDS-PHASE2PLUS-RED (E48S20)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-DTO-FIELDS-PHASE2PLUS-RED: Phase-2+ proposal includes teamNumber, teamDescription,
     * and non-null sourceGroupNumber, sourceGroupPosition from the fromPhase avatar.
     *
     * <p>Given: 2 fromPhase avatars (group=1 pos=1, group=2 pos=1); sortType=team_number. Expected:
     * proposals contain teamNumber/teamDescription from Team; sourceGroupNumber and
     * sourceGroupPosition match the fromPhase avatar's structural identity.
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 2+ — proposal contains display fields and source slot"
                    + " (AC-TEST-DTO-FIELDS-PHASE2PLUS-RED)")
    void proposeTransition_phase2Plus_proposalContainsDisplayAndSourceFields() throws Exception {
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "team_number", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "team_number"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        UUID avId1 = UUID.randomUUID();
        UUID avId2 = UUID.randomUUID();
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();

        List<TeamAvatar> fromAvatars =
                List.of(
                        avatar(avId1, FROM_PHASE_ID, teamId1, 1, 1),
                        avatar(avId2, FROM_PHASE_ID, teamId2, 2, 1));
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        Team team1 = new Team();
        team1.setId(teamId1);
        team1.setTournamentId(TOURNAMENT_ID);
        team1.setTeamNumber(3);
        team1.setDescription("TSV Erbach");
        team1.setParticipate(true);
        Team team2 = new Team();
        team2.setId(teamId2);
        team2.setTournamentId(TOURNAMENT_ID);
        team2.setTeamNumber(7);
        team2.setDescription("SV Rot-Weiß");
        team2.setParticipate(true);
        when(teamRepository.findById(teamId1)).thenReturn(Optional.of(team1));
        when(teamRepository.findById(teamId2)).thenReturn(Optional.of(team2));

        // E58S03 AC7: bulk-load via findByPhaseId — no ratings needed for team_number
        lenient()
                .when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(List.of());

        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        assertThat(proposals).hasSize(2);
        TeamAvatarProposal p1 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamId1))
                        .findFirst()
                        .orElseThrow();
        assertThat(p1.teamNumber()).isEqualTo(3);
        assertThat(p1.teamDescription()).isEqualTo("TSV Erbach");
        assertThat(p1.sourceGroupNumber()).isEqualTo(1);
        assertThat(p1.sourceGroupPosition()).isEqualTo(1);

        TeamAvatarProposal p2 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamId2))
                        .findFirst()
                        .orElseThrow();
        assertThat(p2.teamNumber()).isEqualTo(7);
        assertThat(p2.teamDescription()).isEqualTo("SV Rot-Weiß");
        assertThat(p2.sourceGroupNumber()).isEqualTo(2);
        assertThat(p2.sourceGroupPosition()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-DTO-NO-UUID-LEAKAGE-RED (E48S20)
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-DTO-NO-UUID-LEAKAGE-RED: teamId UUID must NOT appear in teamDescription.
     *
     * <p>Guard: teamDescription must be a human-readable label (team name/club), not a UUID string
     * representation. This test verifies that the service populates teamDescription from
     * Team.description, not from Team.id.
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 1 — teamDescription must not contain teamId UUID"
                    + " (AC-TEST-DTO-NO-UUID-LEAKAGE-RED)")
    void proposeTransition_phase1_teamDescriptionIsNotUuid() throws Exception {
        UUID phase1Id = UUID.randomUUID();
        Phase toPhase = phase(phase1Id, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJsonPhase1(1, "team_number"));

        when(phaseRepository.findById(phase1Id)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        team.setTournamentId(TOURNAMENT_ID);
        team.setTeamNumber(1);
        team.setDescription("TSV Erbach");
        team.setParticipate(true);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team));

        List<TeamAvatarProposal> proposals = service.proposeTransition(phase1Id);

        assertThat(proposals).hasSize(1);
        TeamAvatarProposal p = proposals.get(0);
        // teamDescription must be the human-readable label, not the UUID string
        assertThat(p.teamDescription()).isEqualTo("TSV Erbach");
        assertThat(p.teamDescription()).doesNotContain(teamId.toString());
    }

    // -------------------------------------------------------------------------
    // AC-ERROR-MISSING-TEAM-DEFENSE (E48S20)
    // -------------------------------------------------------------------------

    /**
     * AC-ERROR-MISSING-TEAM-DEFENSE: Phase-1 team with null description → IllegalStateException.
     *
     * <p>Defense against corrupt data: if a participating Team has null description, the service
     * throws IllegalStateException with the offending teamId.
     */
    @Test
    @DisplayName(
            "proposeTransition — Phase 1 — Team.description is null → IllegalStateException"
                    + " (AC-ERROR-MISSING-TEAM-DEFENSE)")
    void proposeTransition_phase1_nullDescription_throwsIllegalStateException() throws Exception {
        UUID phase1Id = UUID.randomUUID();
        Phase toPhase = phase(phase1Id, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJsonPhase1(1, "team_number"));

        when(phaseRepository.findById(phase1Id)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        UUID teamId = UUID.randomUUID();
        Team team = new Team();
        team.setId(teamId);
        team.setTournamentId(TOURNAMENT_ID);
        team.setTeamNumber(1);
        team.setDescription(null); // deliberately corrupt
        team.setParticipate(true);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(team));

        assertThatThrownBy(() -> service.proposeTransition(phase1Id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    // Fixed team UUIDs keyed by team number for team_number algorithm test
    private static final java.util.Map<Integer, UUID> TEAM_IDS_BY_NUMBER =
            new java.util.HashMap<>();

    static {
        for (int i = 1; i <= 8; i++) {
            TEAM_IDS_BY_NUMBER.put(i, UUID.randomUUID());
        }
    }

    private UUID teamIdForNumber(int teamNumber) {
        return TEAM_IDS_BY_NUMBER.get(teamNumber);
    }

    private List<TeamAvatar> avatars8Teams(UUID phaseId) {
        List<TeamAvatar> avatars = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            UUID avId = UUID.randomUUID();
            TeamAvatar av = new TeamAvatar();
            av.setId(avId);
            av.setPhaseId(phaseId);
            av.setTournamentId(TOURNAMENT_ID);
            av.setTeamId(teamIdForNumber(i));
            av.setGroupNumber(1);
            av.setGroupPosition(i);
            avatars.add(av);
        }
        return avatars;
    }

    private TeamAvatar avatar(UUID id, UUID phaseId, UUID teamId, int group, int pos) {
        TeamAvatar av = new TeamAvatar();
        av.setId(id);
        av.setPhaseId(phaseId);
        av.setTournamentId(TOURNAMENT_ID);
        av.setTeamId(teamId);
        av.setGroupNumber(group);
        av.setGroupPosition(pos);
        return av;
    }

    private TeamAvatarRating rating(UUID avatarId, int points) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avatarId);
        r.setPoints(points);
        r.setMatchCount(3);
        r.setSetCount(6);
        r.setSetsWon(3);
        r.setSetsLost(3);
        r.setBallsWon(30);
        r.setBallsLost(30);
        r.setSetQuotient(points > 0 ? 1.0 : 0.0);
        r.setBallQuotient(1.0);
        r.setWithoutAssessment(false);
        r.setUpdatedAt(LocalDateTime.now());
        return r;
    }

    private Phase phase(UUID id, int sequenceNumber) {
        Phase p = new Phase();
        p.setId(id);
        p.setTournamentId(TOURNAMENT_ID);
        p.setSequenceNumber(sequenceNumber);
        p.setDescription("Phase " + sequenceNumber);
        p.setStatus("COMPLETED");
        return p;
    }

    private Phase phaseWithSortType(UUID id, int sequenceNumber, String sortType, int groupCount) {
        Phase p = phase(id, sequenceNumber);
        // sortType and groupCount are stored in the tournament's draft_json, not on the Phase.
        // The toPhase just needs sequenceNumber and tournamentId.
        return p;
    }

    private Tournament tournamentWithDraftJson(UUID id, String draftJson) {
        Tournament t = new Tournament();
        t.setId(id);
        t.setDraftJson(draftJson);
        return t;
    }

    private String buildDraftJson(int groupCount, String sortType) throws Exception {
        // Build minimal draft_json containing a section for toPhase (sequenceNumber=2)
        // The service reads sections[index].sortType and sections[index].groupCount
        // Section sectionNumber=2 maps to Phase sequenceNumber=2 (1-indexed)
        return "{"
                + "\"sections\": ["
                + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                + "   \"groupCount\": "
                + groupCount
                + ", \"gameMode\": \"roundRobin\","
                + "   \"lapBreakTimeMinutes\": 5, \"sectionBreakTimeMinutes\": 10,"
                + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                + "  {\"sectionNumber\": 2, \"sortType\": \""
                + sortType
                + "\","
                + "   \"groupCount\": "
                + groupCount
                + ", \"gameMode\": \"roundRobin\","
                + "   \"lapBreakTimeMinutes\": 5, \"sectionBreakTimeMinutes\": 10,"
                + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                + "]"
                + "}";
    }

    /**
     * Builds a draft_json for Phase 2 with the given distributionMode on the section 2 entry. Used
     * for AC3 distributionMode tests.
     */
    private String buildDraftJsonWithDistributionMode(
            int groupCount, String sortType, String distributionMode) {
        return "{"
                + "\"sections\": ["
                + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                + "   \"groupCount\": "
                + groupCount
                + ", \"gameMode\": \"roundRobin\","
                + "   \"lapBreakTimeMinutes\": 5, \"sectionBreakTimeMinutes\": 10,"
                + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                + "  {\"sectionNumber\": 2, \"sortType\": \""
                + sortType
                + "\","
                + "   \"groupCount\": "
                + groupCount
                + ", \"distributionMode\": \""
                + distributionMode
                + "\","
                + "   \"gameMode\": \"roundRobin\","
                + "   \"lapBreakTimeMinutes\": 5, \"sectionBreakTimeMinutes\": 10,"
                + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                + "]"
                + "}";
    }

    private void assertProposal(
            List<TeamAvatarProposal> proposals, UUID teamId, int group, int pos) {
        TeamAvatarProposal p =
                proposals.stream()
                        .filter(prop -> prop.teamId().equals(teamId))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No proposal for teamId=" + teamId));
        assertThat(p.groupNumber())
                .as("teamId=%s should be in group %d", teamId, group)
                .isEqualTo(group);
        assertThat(p.groupPosition())
                .as("teamId=%s should be at position %d", teamId, pos)
                .isEqualTo(pos);
    }

    private void assertProposalInGroup(
            List<TeamAvatarProposal> proposals, UUID teamId, int expectedGroup) {
        TeamAvatarProposal p =
                proposals.stream()
                        .filter(prop -> prop.teamId().equals(teamId))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No proposal for teamId=" + teamId));
        assertThat(p.groupNumber())
                .as("teamId=%s should be in group %d", teamId, expectedGroup)
                .isEqualTo(expectedGroup);
    }

    // -------------------------------------------------------------------------
    // E48S18 helper methods
    // -------------------------------------------------------------------------

    /**
     * Builds a draft_json with a single section for Phase 1 (sectionNumber=1) with the given
     * sortType and groupCount. Used by Phase-1-Branch tests.
     */
    private String buildDraftJsonPhase1(int groupCount, String sortType) {
        return "{"
                + "\"sections\": ["
                + "  {\"sectionNumber\": 1, \"sortType\": \""
                + sortType
                + "\","
                + "   \"groupCount\": "
                + groupCount
                + ", \"gameMode\": \"roundRobin\","
                + "   \"lapBreakTimeMinutes\": 5, \"sectionBreakTimeMinutes\": 10,"
                + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                + "]"
                + "}";
    }

    /**
     * Creates 8 participating teams (teamNumber 1-8, all participate=true) ordered by teamNumber
     * ascending (matching {@code TeamRepository.findByTournamentId} contract).
     */
    private List<Team> participatingTeams8(UUID tournamentId) {
        List<Team> teams = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            Team t = new Team();
            t.setId(UUID.randomUUID());
            t.setTournamentId(tournamentId);
            t.setTeamNumber(i);
            t.setDescription("Team " + i);
            t.setParticipate(true);
            teams.add(t);
        }
        return teams;
    }

    /** Creates 8 teams where teams 3 and 7 have participate=false. */
    private List<Team> mixedParticipationTeams(UUID tournamentId) {
        List<Team> teams = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            Team t = new Team();
            t.setId(UUID.randomUUID());
            t.setTournamentId(tournamentId);
            t.setTeamNumber(i);
            t.setDescription("Team " + i);
            t.setParticipate(i != 3 && i != 7); // teams 3 and 7 are non-participating
            teams.add(t);
        }
        return teams;
    }

    /**
     * Creates a minimal Team entity with the given id, teamNumber, and a non-null description. Used
     * for E48S20 teamRepository.findById stubs in Phase-2+ tests.
     */
    private Team teamWithNumber(UUID id, int teamNumber) {
        Team t = new Team();
        t.setId(id);
        t.setTournamentId(TOURNAMENT_ID);
        t.setTeamNumber(teamNumber);
        t.setDescription("Team " + teamNumber);
        t.setParticipate(true);
        return t;
    }

    /** Returns the UUID of the team with the given teamNumber from a list. */
    private UUID teamUuidForNumber(List<Team> teams, int teamNumber) {
        return teams.stream()
                .filter(t -> t.getTeamNumber() == teamNumber)
                .map(Team::getId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No team with teamNumber=" + teamNumber));
    }

    // =========================================================================
    // E51S13 — sortType population (RED-first per DEC-22 Iron Law)
    // =========================================================================

    /**
     * AC-TEST-PHASE-1-PROPOSAL-SORTTYPE-TEAM-NUMBER-RED: every Phase-1 proposal has {@code sortType
     * = "team_number"}.
     *
     * <p>DEC-22: test written before {@code sortType} field exists — fails (RED) until the field is
     * added and populated in {@link
     * de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService#rankPhase1Teams}.
     *
     * @see <a href="E51S13">E51S13 — Bug 2a sortType-driven source-pane label</a>
     */
    @Test
    @DisplayName(
            "proposeTransition Phase-1: every proposal has sortType='team_number'"
                    + " (AC-TEST-PHASE-1-PROPOSAL-SORTTYPE-TEAM-NUMBER-RED)")
    void proposeTransition_phase1_allProposals_haveSortTypeTeamNumber() throws Exception {
        UUID phase1Id = UUID.randomUUID();
        Phase toPhase = phase(phase1Id, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJsonPhase1(2, "team_number"));

        when(phaseRepository.findById(phase1Id)).thenReturn(Optional.of(toPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 4 participating teams
        List<Team> teams = participatingTeams8(TOURNAMENT_ID).subList(0, 4);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);

        List<TeamAvatarProposal> proposals = service.proposeTransition(phase1Id);

        assertThat(proposals).isNotEmpty();
        for (TeamAvatarProposal p : proposals) {
            assertThat(p.sortType())
                    .as("Phase-1 proposal for team=%s must have sortType='team_number'", p.teamId())
                    .isEqualTo("team_number");
        }
    }

    /**
     * AC-TEST-PHASE-2-PROPOSAL-SORTTYPE-PLACEMENT-GROUP-RED: every Phase-2+ proposal computed via
     * the placement_group branch has {@code sortType = "placement_group"}.
     *
     * <p>DEC-22: RED-first — fails until {@link
     * de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService#buildProposals} populates
     * sortType from {@code toSection.getSortType()}.
     *
     * @see <a href="E51S13">E51S13 — AC-TEST-PHASE-2-PROPOSAL-SORTTYPE-PLACEMENT-GROUP-RED</a>
     */
    @Test
    @DisplayName(
            "proposeTransition Phase-2+ sortType=placement_group: proposals carry sortType"
                    + " (AC-TEST-PHASE-2-PROPOSAL-SORTTYPE-PLACEMENT-GROUP-RED)")
    void proposeTransition_phase2_placementGroup_allProposals_haveSortTypePlacementGroup()
            throws Exception {
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "placement_group", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "placement_group"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 4 avatars in 2 groups
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID();
        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();
        List<TeamAvatar> fromAvatars =
                List.of(
                        avatar(av1, FROM_PHASE_ID, t1, 1, 1),
                        avatar(av2, FROM_PHASE_ID, t2, 1, 2),
                        avatar(av3, FROM_PHASE_ID, t3, 2, 1),
                        avatar(av4, FROM_PHASE_ID, t4, 2, 2));
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        when(teamRepository.findById(t1)).thenReturn(Optional.of(teamWithNumber(t1, 1)));
        when(teamRepository.findById(t2)).thenReturn(Optional.of(teamWithNumber(t2, 2)));
        when(teamRepository.findById(t3)).thenReturn(Optional.of(teamWithNumber(t3, 3)));
        when(teamRepository.findById(t4)).thenReturn(Optional.of(teamWithNumber(t4, 4)));

        when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(
                        List.of(rating(av1, 6), rating(av2, 4), rating(av3, 6), rating(av4, 4)));

        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        assertThat(proposals).isNotEmpty();
        for (TeamAvatarProposal p : proposals) {
            assertThat(p.sortType())
                    .as("placement_group proposal must carry sortType='placement_group'")
                    .isEqualTo("placement_group");
        }
    }

    /**
     * AC-TEST-PHASE-2-PROPOSAL-SORTTYPE-GROUP-PLACEMENT-RED: every Phase-2+ proposal computed via
     * the group_placement branch has {@code sortType = "group_placement"}.
     *
     * <p>DEC-22: RED-first.
     *
     * @see <a href="E51S13">E51S13 — AC-TEST-PHASE-2-PROPOSAL-SORTTYPE-GROUP-PLACEMENT-RED</a>
     */
    @Test
    @DisplayName(
            "proposeTransition Phase-2+ sortType=group_placement: proposals carry sortType"
                    + " (AC-TEST-PHASE-2-PROPOSAL-SORTTYPE-GROUP-PLACEMENT-RED)")
    void proposeTransition_phase2_groupPlacement_allProposals_haveSortTypeGroupPlacement()
            throws Exception {
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "group_placement", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "group_placement"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 4 avatars in 2 groups (2 per group — equal size)
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID();
        UUID t4 = UUID.randomUUID();
        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();
        List<TeamAvatar> fromAvatars =
                List.of(
                        avatar(av1, FROM_PHASE_ID, t1, 1, 1),
                        avatar(av2, FROM_PHASE_ID, t2, 1, 2),
                        avatar(av3, FROM_PHASE_ID, t3, 2, 1),
                        avatar(av4, FROM_PHASE_ID, t4, 2, 2));
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        when(teamRepository.findById(t1)).thenReturn(Optional.of(teamWithNumber(t1, 1)));
        when(teamRepository.findById(t2)).thenReturn(Optional.of(teamWithNumber(t2, 2)));
        when(teamRepository.findById(t3)).thenReturn(Optional.of(teamWithNumber(t3, 3)));
        when(teamRepository.findById(t4)).thenReturn(Optional.of(teamWithNumber(t4, 4)));

        when(teamAvatarRatingRepository.findByPhaseId(FROM_PHASE_ID))
                .thenReturn(
                        List.of(rating(av1, 6), rating(av2, 4), rating(av3, 6), rating(av4, 4)));

        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        assertThat(proposals).isNotEmpty();
        for (TeamAvatarProposal p : proposals) {
            assertThat(p.sortType())
                    .as("group_placement proposal must carry sortType='group_placement'")
                    .isEqualTo("group_placement");
        }
    }
}
