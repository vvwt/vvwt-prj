// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TeamService;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TeamService} (E21S04, AC-TDD-TeamService).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@code DefaultTeamService} at {@code
 * de.vvwt.tm.tournament.internal.DefaultTeamService} did not exist at commit time — satisfying the
 * DEC-22 Iron Law. (Renamed from {@code TeamService} by E33S02, DEC-35 retrofit.)
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>listTeams — delegates to teamRepository.findByTournamentId
 *   <li>getTeam — returns team or throws NoSuchElementException
 *   <li>createTeam — assigns id, delegates to teamRepository.save
 *   <li>createTeam — throws ConflictException on duplicate team_number
 *   <li>bulkCreateTeams — partial failure does not abort the batch
 *   <li>deleteTeam — throws ConflictException when team has avatar references
 * </ul>
 *
 * @see TeamService
 * @see TeamRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 188)</a>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamService unit tests — E21S04 AC-TDD-TeamService")
class TeamServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TournamentRepository tournamentRepository;

    private DefaultTeamService teamService;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        teamService = new DefaultTeamService(teamRepository, tournamentRepository);
    }

    // -------------------------------------------------------------------------
    // listTeams
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("listTeams() — delegates to repository.findByTournamentId")
    void listTeams_delegatesToRepository() {
        Team t = team(1, "A");
        Tournament tournament = new Tournament();
        tournament.setId(TOURNAMENT_ID);
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(teamRepository.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(t));

        List<Team> result = teamService.listTeams(TOURNAMENT_ID);

        assertThat(result).containsExactly(t);
    }

    // -------------------------------------------------------------------------
    // getTeam
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getTeam() — returns team when found")
    void getTeam_returnsTeamWhenFound() {
        UUID id = UUID.randomUUID();
        Team t = team(1, "A");
        t.setId(id);
        when(teamRepository.findById(id)).thenReturn(Optional.of(t));

        assertThat(teamService.getTeam(TOURNAMENT_ID, id)).isSameAs(t);
    }

    @Test
    @DisplayName("getTeam() — throws NoSuchElementException when not found")
    void getTeam_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(teamRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getTeam(TOURNAMENT_ID, id))
                .isInstanceOf(NoSuchElementException.class);
    }

    // -------------------------------------------------------------------------
    // createTeam
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createTeam() — assigns UUID, delegates to repository.save")
    void createTeam_assignsIdAndSaves() {
        UUID zeroExclude = new UUID(0, 0);
        when(teamRepository.teamNumberExists(TOURNAMENT_ID, 1, zeroExclude)).thenReturn(false);
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Team result = teamService.createTeam(TOURNAMENT_ID, "Alpha", 1, true, false, false);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getDescription()).isEqualTo("Alpha");
        assertThat(result.getTeamNumber()).isEqualTo(1);
        verify(teamRepository).save(any(Team.class));
    }

    @Test
    @DisplayName("createTeam() — throws ConflictException on duplicate team_number")
    void createTeam_throwsConflictOnDuplicateNumber() {
        UUID zeroExclude = new UUID(0, 0);
        when(teamRepository.teamNumberExists(TOURNAMENT_ID, 1, zeroExclude)).thenReturn(true);

        assertThatThrownBy(
                        () -> teamService.createTeam(TOURNAMENT_ID, "Alpha", 1, true, false, false))
                .isInstanceOf(ConflictException.class);
    }

    // -------------------------------------------------------------------------
    // deleteTeam
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("deleteTeam() — throws ConflictException when team has avatar references")
    void deleteTeam_throwsWhenHasAvatarReferences() {
        UUID id = UUID.randomUUID();
        Team t = team(1, "HasAvatars");
        t.setId(id);
        t.setTournamentId(TOURNAMENT_ID);
        when(teamRepository.findById(id)).thenReturn(Optional.of(t));
        when(teamRepository.hasTeamAvatars(id)).thenReturn(true);

        assertThatThrownBy(() -> teamService.deleteTeam(TOURNAMENT_ID, id))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("deleteTeam() — throws NoSuchElementException when team not in given tournament")
    void deleteTeam_throwsWhenWrongTournament() {
        UUID id = UUID.randomUUID();
        Team t = team(1, "OtherTournament");
        t.setId(id);
        t.setTournamentId(UUID.randomUUID()); // different tournament
        when(teamRepository.findById(id)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> teamService.deleteTeam(TOURNAMENT_ID, id))
                .isInstanceOf(NoSuchElementException.class);
    }

    // -------------------------------------------------------------------------
    // bulkCreateTeams
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("bulkCreateTeams() — partial failure does not abort the batch")
    void bulkCreateTeams_partialFailureDoesNotAbortBatch() {
        UUID zeroExclude = new UUID(0, 0);
        // teamNumber 1 is OK, teamNumber 2 conflicts
        when(teamRepository.teamNumberExists(eq(TOURNAMENT_ID), eq(1), eq(zeroExclude)))
                .thenReturn(false);
        when(teamRepository.teamNumberExists(eq(TOURNAMENT_ID), eq(2), eq(zeroExclude)))
                .thenReturn(true);
        when(teamRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<TeamService.BulkCreateRequest> requests =
                List.of(
                        new TeamService.BulkCreateRequest("A", 1, true, false, false),
                        new TeamService.BulkCreateRequest("B", 2, true, false, false));

        List<TeamService.BulkCreateResult> results =
                teamService.bulkCreateTeams(TOURNAMENT_ID, requests);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).isSuccess()).isTrue();
        assertThat(results.get(1).isSuccess()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Team team(int num, String desc) {
        Team t = new Team();
        t.setId(UUID.randomUUID());
        t.setTournamentId(TOURNAMENT_ID);
        t.setTeamNumber(num);
        t.setDescription(desc);
        return t;
    }
}
