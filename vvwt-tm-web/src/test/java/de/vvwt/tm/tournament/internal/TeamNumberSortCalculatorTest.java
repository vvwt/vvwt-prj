package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamSortCalculator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link TeamNumberSortCalculator} (AC4, AC5, AC8, DEC-22).
 *
 * <p>Same-package test per DEC-36. Verifies that the round-robin distribution behaviour matches the
 * original {@code DefaultPhaseTransitionService.computeTeamNumber} exactly (AC5).
 *
 * <p>AC10 verification: teamId is passed through from fromAvatar — the calculator does NOT write
 * teamId to DB.
 *
 * @see TeamNumberSortCalculator
 * @see TeamSortCalculator
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC10</a>
 */
@DisplayName("TeamNumberSortCalculator unit tests — E58S03")
class TeamNumberSortCalculatorTest {

    private final TeamSortCalculator calculator = new TeamNumberSortCalculator();

    // -------------------------------------------------------------------------
    // AC4: registered under correct key
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getKeyId() returns 'team_number'")
    void getKeyId_returnsTeamNumber() {
        assertThat(calculator.getKeyId()).isEqualTo("team_number");
    }

    // -------------------------------------------------------------------------
    // AC5: behaviour-equivalent to original computeTeamNumber
    // Round-Robin: avatar at index i → group (i%groupCount)+1, position (i/groupCount)+1
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("sortTeams distributes 4 avatars across 2 groups in round-robin order")
    void sortTeams_fourAvatarsTwoGroups_roundRobinDistribution() {
        UUID t1 = UUID.randomUUID(),
                t2 = UUID.randomUUID(),
                t3 = UUID.randomUUID(),
                t4 = UUID.randomUUID();
        List<TeamAvatar> fromAvatars =
                List.of(
                        avatarAt(1, 1, t1), avatarAt(1, 2, t2),
                        avatarAt(2, 1, t3), avatarAt(2, 2, t4));
        Map<UUID, Team> teamById =
                Map.of(
                        t1, team(t1, 1, "Team 1"),
                        t2, team(t2, 2, "Team 2"),
                        t3, team(t3, 3, "Team 3"),
                        t4, team(t4, 4, "Team 4"));
        Map<UUID, TeamAvatarRating> ratings = Map.of();

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(fromAvatars, ratings, teamById, 2, "team_number");

        // Round-Robin: index 0 → group 1 pos 1, index 1 → group 2 pos 1,
        //              index 2 → group 1 pos 2, index 3 → group 2 pos 2
        assertThat(proposals).hasSize(4);
        assertProposal(proposals.get(0), 1, 1, "team_number");
        assertProposal(proposals.get(1), 2, 1, "team_number");
        assertProposal(proposals.get(2), 1, 2, "team_number");
        assertProposal(proposals.get(3), 2, 2, "team_number");
    }

    @Test
    @DisplayName("sortTeams with single group puts all avatars in group 1")
    void sortTeams_singleGroup_allInGroupOne() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        List<TeamAvatar> fromAvatars = List.of(avatarAt(1, 1, t1), avatarAt(1, 2, t2));
        Map<UUID, Team> teamById =
                Map.of(
                        t1, team(t1, 1, "T1"),
                        t2, team(t2, 2, "T2"));

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(fromAvatars, Map.of(), teamById, 1, "team_number");

        assertThat(proposals).hasSize(2);
        assertProposal(proposals.get(0), 1, 1, "team_number");
        assertProposal(proposals.get(1), 1, 2, "team_number");
    }

    // -------------------------------------------------------------------------
    // AC10: teamId NOT set by calculator
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "sortTeams passes through fromAvatar.teamId — calculator does NOT write to DB (AC10 —"
                    + " DEC-59 Clause C)")
    void sortTeams_allProposalsHaveFromAvatarTeamId() {
        UUID t1 = UUID.randomUUID();
        List<TeamAvatar> fromAvatars = List.of(avatarAt(1, 1, t1));
        Map<UUID, Team> teamById = Map.of(t1, team(t1, 1, "T1"));

        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(fromAvatars, Map.of(), teamById, 1, "team_number");

        // AC10: teamId is passed through from fromAvatar (not written to DB by calculator).
        // The operator-confirmation handler (commitTransition, DEC-59 Clause C) is the sole
        // DB writer for teamId.
        assertThat(proposals).allSatisfy(p -> assertThat(p.teamId()).isEqualTo(t1));
    }

    @Test
    @DisplayName("sortTeams with empty fromAvatars returns empty list")
    void sortTeams_emptyAvatars_returnsEmpty() {
        List<TeamAvatarProposal> proposals =
                calculator.sortTeams(List.of(), Map.of(), Map.of(), 1, "team_number");

        assertThat(proposals).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TeamAvatar avatarAt(int groupNumber, int groupPosition, UUID teamId) {
        TeamAvatar av = new TeamAvatar();
        av.setId(UUID.randomUUID());
        av.setGroupNumber(groupNumber);
        av.setGroupPosition(groupPosition);
        av.setTeamId(teamId);
        return av;
    }

    private static Team team(UUID id, int number, String description) {
        Team t = new Team();
        t.setId(id);
        t.setTeamNumber(number);
        t.setDescription(description);
        return t;
    }

    private static void assertProposal(
            TeamAvatarProposal p, int group, int position, String sortType) {
        assertThat(p.groupNumber()).isEqualTo(group);
        assertThat(p.groupPosition()).isEqualTo(position);
        assertThat(p.sortType()).isEqualTo(sortType);
    }
}
