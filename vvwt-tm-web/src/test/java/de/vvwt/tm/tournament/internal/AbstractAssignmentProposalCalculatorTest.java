// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.RankedTeamEntry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link AbstractAssignmentProposalCalculator} (AC3, AC8, DEC-22).
 *
 * <p>Updated from E58S03: tests use the new {@code rank(...)} interface returning {@link
 * RankedTeamEntry} and the {@code buildEntry} helper instead of old {@code buildProposal} /
 * {@code getPointsOrMin} / {@code sortTeams} (E66S01 AC2, AC7).
 *
 * <p>Same-package test: MAY white-box against the abstract base class per DEC-36 (same-package test
 * typing rule).
 *
 * @see AbstractAssignmentProposalCalculator
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — same-package test typing rule</a>
 * @see <a href="E58S03">E58S03 — AC3</a>
 * @see <a href="E66S01">E66S01 — AC2, AC7 (updated to flat ranked list)</a>
 */
class AbstractAssignmentProposalCalculatorTest {

    // Concrete subclass for testing the abstract base
    private static class ConcreteCalculator extends AbstractAssignmentProposalCalculator {
        @Override
        public String getKeyId() {
            return "test";
        }

        @Override
        public List<RankedTeamEntry> rank(
                List<TeamAvatar> fromAvatars,
                Map<UUID, TeamAvatarRating> ratingsByAvatarId,
                Map<UUID, Team> teamById) {
            return List.of();
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
    // AC2/AC7: buildEntry helper (E66S01 — replaces old buildProposal)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buildEntry constructs RankedTeamEntry with correct fields (E66S01 AC2, AC7)")
    void buildEntry_validInputs_returnsCorrectEntry() {
        UUID teamId = UUID.randomUUID();
        TeamAvatar fromAvatar = avatarWithTeamId(teamId);
        fromAvatar.setGroupNumber(2);
        fromAvatar.setGroupPosition(3);
        Team team = teamWithId(teamId);
        team.setTeamNumber(7);
        team.setDescription("Test Team");

        RankedTeamEntry entry = calculator.buildEntry(fromAvatar, team);

        // AC7: teamId is passed through from fromAvatar (calculator does NOT write to DB)
        assertThat(entry.teamId()).isEqualTo(teamId);
        assertThat(entry.teamNumber()).isEqualTo(7);
        assertThat(entry.description()).isEqualTo("Test Team");
        // AC2: source-phase structural coordinates carried
        assertThat(entry.sourceGroupNumber()).isEqualTo(2);
        assertThat(entry.sourceGroupPosition()).isEqualTo(3);
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
