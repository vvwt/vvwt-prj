package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.PhaseTransitionService;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
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
 * </ul>
 *
 * @see DefaultPhaseTransitionService
 * @see PhaseTransitionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (Q-1a, RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — same-package test typing rule</a>
 * @see <a href="E48S07">E48S07 — AC-TEST-PROPOSE-*-RED</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultPhaseTransitionService unit tests — E48S07")
class PhaseTransitionServiceTest {

    @Mock private TournamentRepository tournamentRepository;
    @Mock private PhaseRepository phaseRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;
    @Mock private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Mock private PhasePreparationService phasePreparationService;

    private DefaultPhaseTransitionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final UUID TO_PHASE_ID = UUID.randomUUID();
    private static final UUID FROM_PHASE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service =
                new DefaultPhaseTransitionService(
                        tournamentRepository,
                        phaseRepository,
                        teamAvatarRepository,
                        teamAvatarRatingRepository,
                        phasePreparationService,
                        objectMapper);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-TEAM-NUMBER-RED
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-TEAM-NUMBER-RED: 8 Teams, 2 Gruppen, sortType=team_number
     *
     * <p>Expected: Round-Robin after team number ascending
     *
     * <ul>
     *   <li>Team 1 → Gruppe 1 Pos 1
     *   <li>Team 2 → Gruppe 2 Pos 1
     *   <li>Team 3 → Gruppe 1 Pos 2
     *   <li>Team 4 → Gruppe 2 Pos 2
     *   <li>Team 5 → Gruppe 1 Pos 3
     *   <li>Team 6 → Gruppe 2 Pos 3
     *   <li>Team 7 → Gruppe 1 Pos 4
     *   <li>Team 8 → Gruppe 2 Pos 4
     * </ul>
     */
    @Test
    @DisplayName("proposeTransition — sortType=team_number — 8 Teams → 2 Gruppen Round-Robin")
    void proposeTransition_teamNumber_8teams2groups_roundRobin() throws Exception {
        // Given
        Phase toPhase = phaseWithSortType(TO_PHASE_ID, 2, "team_number", 2);
        Phase fromPhase = phase(FROM_PHASE_ID, 1);
        Tournament tournament =
                tournamentWithDraftJson(TOURNAMENT_ID, buildDraftJson(2, "team_number"));

        when(phaseRepository.findById(TO_PHASE_ID)).thenReturn(Optional.of(toPhase));
        when(phaseRepository.findByTournamentIdAndSequenceNumber(TOURNAMENT_ID, 1))
                .thenReturn(Optional.of(fromPhase));
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));

        // 8 teams in fromPhase — team_number is teamNumber field ascending
        List<TeamAvatar> fromAvatars = avatars8Teams(FROM_PHASE_ID);
        when(teamAvatarRepository.findByPhaseId(FROM_PHASE_ID)).thenReturn(fromAvatars);

        // No ratings needed for team_number algorithm
        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 8 proposals, Round-Robin after team number
        assertThat(proposals).hasSize(8);

        // team with teamNumber=1 → group=1, pos=1
        TeamAvatarProposal t1 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamIdForNumber(1)))
                        .findFirst()
                        .orElseThrow();
        assertThat(t1.groupNumber()).isEqualTo(1);
        assertThat(t1.groupPosition()).isEqualTo(1);

        // team with teamNumber=2 → group=2, pos=1
        TeamAvatarProposal t2 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamIdForNumber(2)))
                        .findFirst()
                        .orElseThrow();
        assertThat(t2.groupNumber()).isEqualTo(2);
        assertThat(t2.groupPosition()).isEqualTo(1);

        // team with teamNumber=3 → group=1, pos=2
        TeamAvatarProposal t3 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamIdForNumber(3)))
                        .findFirst()
                        .orElseThrow();
        assertThat(t3.groupNumber()).isEqualTo(1);
        assertThat(t3.groupPosition()).isEqualTo(2);

        // team with teamNumber=4 → group=2, pos=2
        TeamAvatarProposal t4 =
                proposals.stream()
                        .filter(p -> p.teamId().equals(teamIdForNumber(4)))
                        .findFirst()
                        .orElseThrow();
        assertThat(t4.groupNumber()).isEqualTo(2);
        assertThat(t4.groupPosition()).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-PLACEMENT-GROUP-RED
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-PLACEMENT-GROUP-RED: sortType=placement_group
     *
     * <p>Phase N has 2 groups (3 teams each):
     *
     * <ul>
     *   <li>Group 1: A[rank 1], B[rank 2], C[rank 3]
     *   <li>Group 2: D[rank 1], E[rank 2], F[rank 3]
     * </ul>
     *
     * Expected: each team stays in their group, re-sorted by placement.
     *
     * <ul>
     *   <li>Group 1: A Pos 1, B Pos 2, C Pos 3
     *   <li>Group 2: D Pos 1, E Pos 2, F Pos 3
     * </ul>
     */
    @Test
    @DisplayName(
            "proposeTransition — sortType=placement_group — teams stay in group, sorted by rank")
    void proposeTransition_placementGroup_2groups3teams_stayInGroupSortByRank() throws Exception {
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

        // Ratings: A is rank 1 in group 1 (highest points), B rank 2, C rank 3
        // D is rank 1 in group 2, E rank 2, F rank 3
        // compareTo: higher points = lower rank index = better position
        when(teamAvatarRatingRepository.findByAvatarId(avatarA))
                .thenReturn(Optional.of(rating(avatarA, 6))); // 6 points = best
        when(teamAvatarRatingRepository.findByAvatarId(avatarB))
                .thenReturn(Optional.of(rating(avatarB, 4)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarC))
                .thenReturn(Optional.of(rating(avatarC, 2)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarD))
                .thenReturn(Optional.of(rating(avatarD, 6)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarE))
                .thenReturn(Optional.of(rating(avatarE, 4)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarF))
                .thenReturn(Optional.of(rating(avatarF, 2)));

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert
        assertThat(proposals).hasSize(6);

        // Group 1: A pos 1, B pos 2, C pos 3
        assertProposal(proposals, teamA, 1, 1);
        assertProposal(proposals, teamB, 1, 2);
        assertProposal(proposals, teamC, 1, 3);

        // Group 2: D pos 1, E pos 2, F pos 3
        assertProposal(proposals, teamD, 2, 1);
        assertProposal(proposals, teamE, 2, 2);
        assertProposal(proposals, teamF, 2, 3);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PROPOSE-GROUP-PLACEMENT-RED
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-PROPOSE-GROUP-PLACEMENT-RED: sortType=group_placement
     *
     * <p>Phase N has 2 groups, 4 teams each:
     *
     * <ul>
     *   <li>Group 1: A1[rank 1], A2[rank 2], A3[rank 3], A4[rank 4]
     *   <li>Group 2: B1[rank 1], B2[rank 2], B3[rank 3], B4[rank 4]
     * </ul>
     *
     * Expected Phase N+1 (4 groups, 2 teams each):
     *
     * <ul>
     *   <li>Group 1: A1, B1
     *   <li>Group 2: A2, B2
     *   <li>Group 3: A3, B3
     *   <li>Group 4: A4, B4
     * </ul>
     */
    @Test
    @DisplayName(
            "proposeTransition — sortType=group_placement — all rank-1 to group1, all rank-2 to"
                    + " group2")
    void proposeTransition_groupPlacement_2groups4teams_crossGroupDistribution() throws Exception {
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

        // Ratings sorted by group; within each group, descending points = ascending rank
        when(teamAvatarRatingRepository.findByAvatarId(avatarA1))
                .thenReturn(Optional.of(rating(avatarA1, 8)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarA2))
                .thenReturn(Optional.of(rating(avatarA2, 6)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarA3))
                .thenReturn(Optional.of(rating(avatarA3, 4)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarA4))
                .thenReturn(Optional.of(rating(avatarA4, 2)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarB1))
                .thenReturn(Optional.of(rating(avatarB1, 8)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarB2))
                .thenReturn(Optional.of(rating(avatarB2, 6)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarB3))
                .thenReturn(Optional.of(rating(avatarB3, 4)));
        when(teamAvatarRatingRepository.findByAvatarId(avatarB4))
                .thenReturn(Optional.of(rating(avatarB4, 2)));

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 8 proposals, 4 groups of 2
        assertThat(proposals).hasSize(8);

        // All rank-1 finishers → group 1
        assertProposalInGroup(proposals, teamA1, 1);
        assertProposalInGroup(proposals, teamB1, 1);

        // All rank-2 finishers → group 2
        assertProposalInGroup(proposals, teamA2, 2);
        assertProposalInGroup(proposals, teamB2, 2);

        // All rank-3 finishers → group 3
        assertProposalInGroup(proposals, teamA3, 3);
        assertProposalInGroup(proposals, teamB3, 3);

        // All rank-4 finishers → group 4
        assertProposalInGroup(proposals, teamA4, 4);
        assertProposalInGroup(proposals, teamB4, 4);
    }

    /**
     * AC-TEST-PROPOSE-GROUP-PLACEMENT-RED (edge case): unequal group sizes.
     *
     * <p>Group 1 has 3 teams, Group 2 has 2 teams → 2 "rank slots". Delivery choice: truncate to
     * smallest group. Only rank-1 and rank-2 finishers from all groups are distributed; rank-3
     * finisher from Group 1 is dropped (no equivalent rank-3 in Group 2).
     *
     * <p>Expected: 2 groups of 2 (4 proposals total, not 5).
     */
    @Test
    @DisplayName(
            "proposeTransition — sortType=group_placement — unequal group sizes truncates to min")
    void proposeTransition_groupPlacement_unequalGroupSizes_truncatesToMin() throws Exception {
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

        when(teamAvatarRatingRepository.findByAvatarId(avrA1))
                .thenReturn(Optional.of(rating(avrA1, 6)));
        when(teamAvatarRatingRepository.findByAvatarId(avrA2))
                .thenReturn(Optional.of(rating(avrA2, 4)));
        when(teamAvatarRatingRepository.findByAvatarId(avrA3))
                .thenReturn(Optional.of(rating(avrA3, 2)));
        when(teamAvatarRatingRepository.findByAvatarId(avrB1))
                .thenReturn(Optional.of(rating(avrB1, 6)));
        when(teamAvatarRatingRepository.findByAvatarId(avrB2))
                .thenReturn(Optional.of(rating(avrB2, 4)));

        // Act
        List<TeamAvatarProposal> proposals = service.proposeTransition(TO_PHASE_ID);

        // Assert: 4 proposals (truncated; rank-3 from group 1 has no group-2 equivalent)
        assertThat(proposals).hasSize(4);
        assertProposalInGroup(proposals, tmA1, 1);
        assertProposalInGroup(proposals, tmB1, 1);
        assertProposalInGroup(proposals, tmA2, 2);
        assertProposalInGroup(proposals, tmB2, 2);
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
        // fromPhase
        // resolution because draft_json is null (checked immediately after tournament load).

        assertThatThrownBy(() -> service.proposeTransition(TO_PHASE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft_json");
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
            // No ratings needed for team_number algorithm; store teamNumber as a recognisable UUID
            // marker. The implementation reads the team's number from the Team entity or from
            // the avatar's teamId. Since we don't have Team objects in unit tests, the
            // DefaultPhaseTransitionService must accept teamId ordering.
            // For team_number: the service sorts teams by their teamId order among the fromAvatars
            // list OR by a separate teamNumber lookup. Given the story says "by Team-Nummer
            // aufsteigend", we need the service to know team numbers.
            // We pass them in deterministic UUID order by using a sorted list.
            // Simplification: team_number algorithm uses the index position in the sorted avatar
            // list (the existing TeamAvatarRepository returns them in group_number, group_position
            // order, and for fromPhase Phase 1 the positions correspond to team numbers).
            // Actually the service will need Team.teamNumber — let's use a TeamRepository mock.
            // We must add that as a collaborator. For simplicity in this RED test:
            // The service uses the fromAvatars ordered by their avatar.getGroupPosition()
            // (which in Phase 1 corresponds to initial seeding order = team number order).
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
        String json =
                "{"
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
        return json;
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
}
