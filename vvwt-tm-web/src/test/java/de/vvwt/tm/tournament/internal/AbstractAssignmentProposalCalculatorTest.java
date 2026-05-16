package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRating;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link AbstractAssignmentProposalCalculator} (AC3, AC8, DEC-22).
 *
 * <p>Same-package test: MAY white-box against the abstract base class per DEC-36 (same-package test
 * typing rule).
 *
 * @see AbstractAssignmentProposalCalculator
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — same-package test typing rule</a>
 * @see <a href="E58S03">E58S03 — AC3</a>
 */
class AbstractAssignmentProposalCalculatorTest {

    // Concrete subclass for testing the abstract base
    private static class ConcreteCalculator extends AbstractAssignmentProposalCalculator {
        @Override
        public String getKeyId() {
            return "test";
        }

        @Override
        public java.util.List<de.vvwt.tm.tournament.TeamAvatarProposal> sortTeams(
                java.util.List<de.vvwt.tm.tournament.TeamAvatar> fromAvatars,
                java.util.Map<java.util.UUID, de.vvwt.tm.tournament.TeamAvatarRating>
                        ratingsByAvatarId,
                java.util.Map<java.util.UUID, de.vvwt.tm.tournament.Team> teamById,
                int groupCount,
                String sortType) {
            return java.util.List.of();
        }
    }

    private final ConcreteCalculator calculator = new ConcreteCalculator();

    // -------------------------------------------------------------------------
    // AC3: requireTeamForDisplay helper
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("requireTeamForDisplay returns Team when avatarId maps to a team")
    void requireTeamForDisplay_avatarWithTeam_returnsTeam() {
        UUID teamId = UUID.randomUUID();
        TeamAvatar avatar = avatarWithTeamId(teamId);
        Team team = teamWithId(teamId);
        Map<UUID, Team> teamById = Map.of(teamId, team);

        Team result = calculator.requireTeamForDisplay(avatar, teamById);

        assertThat(result).isSameAs(team);
    }

    @Test
    @DisplayName("requireTeamForDisplay throws when team cannot be resolved")
    void requireTeamForDisplay_missingTeam_throwsIllegalStateException() {
        UUID teamId = UUID.randomUUID();
        TeamAvatar avatar = avatarWithTeamId(teamId);
        Map<UUID, Team> emptyMap = Map.of();

        assertThatThrownBy(() -> calculator.requireTeamForDisplay(avatar, emptyMap))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(teamId.toString());
    }

    // -------------------------------------------------------------------------
    // AC3: buildProposal helper
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildProposal constructs TeamAvatarProposal with correct fields")
    void buildProposal_validInputs_returnsCorrectProposal() {
        UUID teamId = UUID.randomUUID();
        TeamAvatar fromAvatar = avatarWithTeamId(teamId);
        fromAvatar.setGroupNumber(2);
        fromAvatar.setGroupPosition(3);
        Team team = teamWithId(teamId);
        team.setTeamNumber(7);
        team.setDescription("Test Team");

        TeamAvatarProposal proposal =
                calculator.buildProposal(fromAvatar, team, 1, 2, "team_number");

        // AC10: teamId is passed through from fromAvatar (calculator does NOT write to DB);
        // the teamId in the proposal DTO allows the operator-confirmation handler to identify
        // which team gets which slot when the operator confirms (commitTransition, DEC-59 Clause C)
        assertThat(proposal.teamId()).isEqualTo(teamId);
        assertThat(proposal.teamNumber()).isEqualTo(7);
        assertThat(proposal.teamDescription()).isEqualTo("Test Team");
        assertThat(proposal.groupNumber()).isEqualTo(1);
        assertThat(proposal.groupPosition()).isEqualTo(2);
        assertThat(proposal.sourceGroupNumber()).isEqualTo(2);
        assertThat(proposal.sourceGroupPosition()).isEqualTo(3);
        assertThat(proposal.sortType()).isEqualTo("team_number");
    }

    // -------------------------------------------------------------------------
    // AC3: getPointsOrMin helper
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getPointsOrMin returns points when rating exists")
    void getPointsOrMin_ratingExists_returnsPoints() {
        UUID avatarId = UUID.randomUUID();
        TeamAvatarRating rating = new TeamAvatarRating();
        rating.setAvatarId(avatarId);
        rating.setPoints(42);
        Map<UUID, TeamAvatarRating> ratingsMap = Map.of(avatarId, rating);

        int points = calculator.getPointsOrMin(avatarId, ratingsMap);

        assertThat(points).isEqualTo(42);
    }

    @Test
    @DisplayName("getPointsOrMin returns Integer.MIN_VALUE when no rating")
    void getPointsOrMin_noRating_returnsMinValue() {
        UUID avatarId = UUID.randomUUID();
        Map<UUID, TeamAvatarRating> emptyMap = Map.of();

        int points = calculator.getPointsOrMin(avatarId, emptyMap);

        assertThat(points).isEqualTo(Integer.MIN_VALUE);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TeamAvatar avatarWithTeamId(UUID teamId) {
        TeamAvatar av = new TeamAvatar();
        av.setId(UUID.randomUUID());
        av.setTeamId(teamId);
        av.setGroupNumber(1);
        av.setGroupPosition(1);
        return av;
    }

    private static Team teamWithId(UUID teamId) {
        Team team = new Team();
        team.setId(teamId);
        team.setTeamNumber(1);
        team.setDescription("Team");
        return team;
    }
}
