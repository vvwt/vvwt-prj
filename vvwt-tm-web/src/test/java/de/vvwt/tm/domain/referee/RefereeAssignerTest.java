package de.vvwt.tm.domain.referee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.tm.domain.repo.TeamAvatarRepository;
import de.vvwt.tm.domain.repo.TeamRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link RefereeAssigner}.
 *
 * <p>Uses Mockito to stub repository calls — no Spring context needed. Tests verify the algorithm's
 * core logic and edge-case handling.
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E03S10.story.md">Story
 *     E03S10 AC7–AC10</a>
 */
@ExtendWith(MockitoExtension.class)
class RefereeAssignerTest {

    @Mock private PhaseRepository phaseRepository;
    @Mock private MatchRepository matchRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private TeamAvatarRepository teamAvatarRepository;

    private RefereeAssigner assigner;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        assigner =
                new RefereeAssigner(
                        phaseRepository,
                        matchRepository,
                        teamRepository,
                        teamAvatarRepository,
                        new ObjectMapper());
    }

    // =========================================================================
    // AC7 — Basic case: 6 teams, 2 fields per lap
    // =========================================================================

    /**
     * AC7: Phase with 6 teams, 2 fields per lap. Each lap: 4 teams play (2 matches), 2 teams are
     * free (on bye). All 6 teams have refereeAssignment=true. Expected: every match assigned a
     * referee; no team both plays and refs in the same lap.
     */
    @Test
    void ac7_basicCase_sixTeams_twoFieldsPerLap() {
        UUID phaseId = UUID.randomUUID();
        Phase phase = buildPhase(phaseId);

        // 6 teams, all eligible to referee
        UUID[] teamIds = buildTeamIds(6);
        List<Team> teams = new ArrayList<>();
        for (UUID teamId : teamIds) {
            teams.add(buildTeam(teamId, TOURNAMENT_ID, true));
        }

        // 6 avatars, one per team
        UUID[] avatarIds = buildAvatarIds(6);
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            avatars.add(buildAvatar(avatarIds[i], phaseId, teamIds[i]));
        }

        // 2 laps × 2 matches per lap
        // Lap 1: team0 vs team1 (field 1), team2 vs team3 (field 2) → team4 and team5 free
        // Lap 2: team2 vs team4 (field 1), team0 vs team5 (field 2) → team1 and team3 free
        UUID matchId1 = UUID.randomUUID();
        UUID matchId2 = UUID.randomUUID();
        UUID matchId3 = UUID.randomUUID();
        UUID matchId4 = UUID.randomUUID();

        List<Match> allMatches =
                List.of(
                        buildMatch(matchId1, phaseId, avatarIds[0], avatarIds[1], 1, 1, null),
                        buildMatch(matchId2, phaseId, avatarIds[2], avatarIds[3], 1, 2, null),
                        buildMatch(matchId3, phaseId, avatarIds[2], avatarIds[4], 2, 1, null),
                        buildMatch(matchId4, phaseId, avatarIds[0], avatarIds[5], 2, 2, null));

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(allMatches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getTotalMatches()).isEqualTo(4);
        assertThat(report.getAssignedCount()).isEqualTo(4);
        assertThat(report.getOverriddenCount()).isEqualTo(0);
        assertThat(report.getNoRefereeCount()).isEqualTo(0);
        assertThat(report.getWarnings()).isEmpty();

        // Verify each match was saved with a non-null refereeTeamId
        for (Match match : allMatches) {
            assertThat(match.getRefereeTeamId())
                    .as("match %s must have a referee assigned", match.getId())
                    .isNotNull();
        }

        // Hard constraint: no team refs while playing in the same lap
        // Lap 1 playing: team0, team1, team2, team3
        Set<UUID> lap1Playing = Set.of(teamIds[0], teamIds[1], teamIds[2], teamIds[3]);
        assertThat(allMatches.get(0).getRefereeTeamId()).isNotIn(lap1Playing);
        assertThat(allMatches.get(1).getRefereeTeamId()).isNotIn(lap1Playing);

        // Lap 2 playing: team2, team4, team0, team5
        Set<UUID> lap2Playing = Set.of(teamIds[0], teamIds[2], teamIds[4], teamIds[5]);
        assertThat(allMatches.get(2).getRefereeTeamId()).isNotIn(lap2Playing);
        assertThat(allMatches.get(3).getRefereeTeamId()).isNotIn(lap2Playing);
    }

    // =========================================================================
    // AC8 — Odd team count with bye
    // =========================================================================

    /**
     * AC8: Phase with 5 teams, 2 fields per lap. Each lap: 2 matches (4 teams playing), 1 team on
     * bye. The bye team is the only candidate — can ref at most 1 match per lap. The other match
     * gets no referee (null refereeTeamId) → warning. Hard constraint must not be violated.
     */
    @Test
    void ac8_oddTeamCount_byeTeamOnlyCandidate() {
        UUID phaseId = UUID.randomUUID();
        Phase phase = buildPhase(phaseId);

        UUID[] teamIds = buildTeamIds(5);
        List<Team> teams = new ArrayList<>();
        for (UUID teamId : teamIds) {
            teams.add(buildTeam(teamId, TOURNAMENT_ID, true));
        }

        UUID[] avatarIds = buildAvatarIds(5);
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            avatars.add(buildAvatar(avatarIds[i], phaseId, teamIds[i]));
        }

        // 1 lap: 2 matches (4 teams play), team4 is on bye → only referee candidate
        UUID matchId1 = UUID.randomUUID();
        UUID matchId2 = UUID.randomUUID();

        // Sort match IDs so we can predict which one gets team4 (the lower UUID comes first in
        // sort)
        List<Match> allMatches =
                List.of(
                        buildMatch(matchId1, phaseId, avatarIds[0], avatarIds[1], 1, 1, null),
                        buildMatch(matchId2, phaseId, avatarIds[2], avatarIds[3], 1, 2, null));

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(allMatches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getTotalMatches()).isEqualTo(2);
        // Exactly 1 of the 2 matches gets team4; the other gets null
        assertThat(report.getAssignedCount()).isEqualTo(1);
        assertThat(report.getNoRefereeCount()).isEqualTo(1);
        assertThat(report.getWarnings()).hasSize(1);

        // Count how many matches have team4 as referee
        long assignedToTeam4 =
                allMatches.stream().filter(m -> teamIds[4].equals(m.getRefereeTeamId())).count();
        assertThat(assignedToTeam4).as("team4 can ref at most 1 match (AC8)").isEqualTo(1);

        // Hard constraint: the one assigned match's referee is NOT playing in lap 1
        Set<UUID> lap1Playing = Set.of(teamIds[0], teamIds[1], teamIds[2], teamIds[3]);
        for (Match match : allMatches) {
            if (match.getRefereeTeamId() != null) {
                assertThat(match.getRefereeTeamId()).isNotIn(lap1Playing);
            }
        }
    }

    // =========================================================================
    // AC9 — refereeAssignment=FALSE excluded
    // =========================================================================

    /**
     * AC9: A team with refereeAssignment=FALSE must never be selected as referee, even if it has a
     * bye.
     */
    @Test
    void ac9_refereeAssignmentFalse_excluded() {
        UUID phaseId = UUID.randomUUID();
        Phase phase = buildPhase(phaseId);

        UUID[] teamIds = buildTeamIds(4);
        List<Team> teams = new ArrayList<>();
        // team0, team1, team2: refereeAssignment=true; team3: refereeAssignment=FALSE
        teams.add(buildTeam(teamIds[0], TOURNAMENT_ID, true));
        teams.add(buildTeam(teamIds[1], TOURNAMENT_ID, true));
        teams.add(buildTeam(teamIds[2], TOURNAMENT_ID, true));
        teams.add(buildTeam(teamIds[3], TOURNAMENT_ID, false));

        UUID[] avatarIds = buildAvatarIds(4);
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            avatars.add(buildAvatar(avatarIds[i], phaseId, teamIds[i]));
        }

        // 1 lap: team0 vs team1, team3 is on bye. Only team2 and team3 are free,
        // but team3 has refereeAssignment=false — so only team2 is eligible.
        // Wait: need to also have team2 in the lap or not:
        // Lap 1: team0 vs team1 (field 1). team2 and team3 have bye.
        // team2 eligible (refereeAssignment=true, not playing) → assigned.
        // team3 not eligible (refereeAssignment=false) → excluded.
        UUID matchId1 = UUID.randomUUID();

        List<Match> allMatches =
                List.of(buildMatch(matchId1, phaseId, avatarIds[0], avatarIds[1], 1, 1, null));

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(allMatches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);
        when(matchRepository.save(any(Match.class))).thenAnswer(inv -> inv.getArgument(0));

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getAssignedCount()).isEqualTo(1);
        assertThat(allMatches.get(0).getRefereeTeamId())
                .as("Assigned referee must be team2 (only eligible non-playing team)")
                .isEqualTo(teamIds[2]);

        // Explicitly verify team3 (refereeAssignment=false) was never used
        for (Match match : allMatches) {
            assertThat(match.getRefereeTeamId())
                    .as("team3 must never be assigned as referee (refereeAssignment=false)")
                    .isNotEqualTo(teamIds[3]);
        }
    }

    // =========================================================================
    // AC10 — Manual override preserved
    // =========================================================================

    /**
     * AC10: A match with refereeDescription pre-set must be left unchanged. The final report must
     * count it as "manually overridden".
     */
    @Test
    void ac10_manualOverridePreserved() {
        UUID phaseId = UUID.randomUUID();
        Phase phase = buildPhase(phaseId);

        UUID[] teamIds = buildTeamIds(4);
        List<Team> teams = new ArrayList<>();
        for (UUID teamId : teamIds) {
            teams.add(buildTeam(teamId, TOURNAMENT_ID, true));
        }

        UUID[] avatarIds = buildAvatarIds(4);
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            avatars.add(buildAvatar(avatarIds[i], phaseId, teamIds[i]));
        }

        // 1 lap, 2 matches. Match1 has a manual override; match2 does not.
        UUID matchId1 = UUID.randomUUID();
        UUID matchId2 = UUID.randomUUID();
        String manualDescription = "Alice Schiedsrichter (extern)";

        Match overriddenMatch =
                buildMatch(matchId1, phaseId, avatarIds[0], avatarIds[1], 1, 1, manualDescription);
        Match normalMatch = buildMatch(matchId2, phaseId, avatarIds[2], avatarIds[3], 1, 2, null);
        // Note: lap1 has all 4 teams playing — no eligible non-playing team for match2.
        // But match1 is already overridden, so only match2 needs assignment.
        // Since all 4 teams play, nobody is free → noRefereeCount=1 for match2.

        List<Match> allMatches = List.of(overriddenMatch, normalMatch);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(allMatches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);
        // Note: no save stubbing needed — all 4 teams play in lap 1, no candidates
        // (overridden match is skipped; normalMatch has no eligible referee).

        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        // Manual override match must NOT have been updated
        assertThat(overriddenMatch.getRefereeTeamId())
                .as("overridden match must have null refereeTeamId (was not set)")
                .isNull();
        assertThat(overriddenMatch.getRefereeDescription())
                .as("refereeDescription must be preserved")
                .isEqualTo(manualDescription);

        assertThat(report.getOverriddenCount()).isEqualTo(1);
        assertThat(report.getTotalMatches()).isEqualTo(2);

        // matchRepository.save should NOT be called for the overridden match
        verify(matchRepository, never()).save(overriddenMatch);
    }

    // =========================================================================
    // Edge case: phase not found → AC12
    // =========================================================================

    @Test
    void phaseNotFound_throwsIllegalArgument() {
        UUID missingPhaseId = UUID.randomUUID();
        when(phaseRepository.findById(missingPhaseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assigner.assignReferees(missingPhaseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(missingPhaseId.toString());
    }

    // =========================================================================
    // Edge case: match with null lapNumber → AC13
    // =========================================================================

    @Test
    void nullLapNumber_throwsIllegalState() {
        UUID phaseId = UUID.randomUUID();
        Phase phase = buildPhase(phaseId);

        UUID[] teamIds = buildTeamIds(2);
        UUID[] avatarIds = buildAvatarIds(2);
        Match badMatch =
                buildMatch(UUID.randomUUID(), phaseId, avatarIds[0], avatarIds[1], null, 1, null);

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of(badMatch));

        assertThatThrownBy(() -> assigner.assignReferees(phaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slot coordinates")
                .hasMessageContaining(phaseId.toString());
    }

    // =========================================================================
    // Edge case: all teams have refereeAssignment=false → AC14
    // =========================================================================

    @Test
    void ac14_allTeamsIneligible_completesWithWarnings() {
        UUID phaseId = UUID.randomUUID();
        Phase phase = buildPhase(phaseId);

        UUID[] teamIds = buildTeamIds(4);
        List<Team> teams = new ArrayList<>();
        for (UUID teamId : teamIds) {
            teams.add(buildTeam(teamId, TOURNAMENT_ID, false)); // all ineligible
        }

        UUID[] avatarIds = buildAvatarIds(4);
        List<TeamAvatar> avatars = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            avatars.add(buildAvatar(avatarIds[i], phaseId, teamIds[i]));
        }

        UUID matchId1 = UUID.randomUUID();
        List<Match> allMatches =
                List.of(buildMatch(matchId1, phaseId, avatarIds[0], avatarIds[1], 1, 1, null));

        when(phaseRepository.findById(phaseId)).thenReturn(Optional.of(phase));
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(allMatches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(teams);

        // Must NOT throw
        RefereeAssignmentReport report = assigner.assignReferees(phaseId);

        assertThat(report.getAssignedCount()).isEqualTo(0);
        assertThat(report.getNoRefereeCount()).isEqualTo(1);
        assertThat(report.getWarnings()).hasSize(1);

        // No match should have been saved (nothing was assigned)
        verify(matchRepository, never()).save(any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Phase buildPhase(UUID phaseId) {
        return new Phase(
                phaseId,
                TENANT_ID,
                TOURNAMENT_ID,
                1,
                "Vorrunde",
                "PENDING",
                0,
                LocalDateTime.now());
    }

    private static Team buildTeam(UUID teamId, UUID tournamentId, boolean refereeAssignment) {
        return new Team(
                teamId,
                TENANT_ID,
                tournamentId,
                1,
                "Team " + teamId,
                true,
                refereeAssignment,
                false,
                LocalDateTime.now());
    }

    private static TeamAvatar buildAvatar(UUID avatarId, UUID phaseId, UUID teamId) {
        return new TeamAvatar(
                avatarId,
                TENANT_ID,
                TOURNAMENT_ID,
                phaseId,
                1,
                1,
                teamId,
                null,
                LocalDateTime.now());
    }

    /** Builds a Match entity. {@code lapNumber} is nullable to support the AC13 null-lap test. */
    private static Match buildMatch(
            UUID matchId,
            UUID phaseId,
            UUID avatar1Id,
            UUID avatar2Id,
            Integer lapNumber,
            Integer fieldNumber,
            String refereeDescription) {
        return new Match(
                matchId,
                TENANT_ID,
                TOURNAMENT_ID,
                phaseId,
                avatar1Id,
                avatar2Id,
                MatchState.OPEN.getLegacyCode(),
                MatchFormat.BEST_OF_3.getMaxSets(),
                lapNumber,
                fieldNumber,
                null,
                refereeDescription,
                null,
                LocalDateTime.now());
    }

    private static UUID[] buildTeamIds(int count) {
        UUID[] ids = new UUID[count];
        for (int i = 0; i < count; i++) ids[i] = UUID.randomUUID();
        return ids;
    }

    private static UUID[] buildAvatarIds(int count) {
        UUID[] ids = new UUID[count];
        for (int i = 0; i < count; i++) ids[i] = UUID.randomUUID();
        return ids;
    }
}
