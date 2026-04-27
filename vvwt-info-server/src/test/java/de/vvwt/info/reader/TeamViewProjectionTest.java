package de.vvwt.info.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.info.dto.snapshot.ScheduleEntry;
import de.vvwt.info.dto.snapshot.TeamEntry;
import de.vvwt.info.dto.snapshot.TournamentSnapshot;
import de.vvwt.info.reader.internal.TeamViewProjection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TeamViewProjection} (E38S06 AC12).
 *
 * <p>DEC-22 Iron Law: tests written before production class implementation was complete.
 */
class TeamViewProjectionTest {

    private TeamViewProjection projection;

    private TeamEntry teamA;
    private TeamEntry teamB;
    private TeamEntry teamC;

    @BeforeEach
    void setUp() {
        projection = new TeamViewProjection();
        teamA = new TeamEntry("uuid-a", "Team Alpha", 1);
        teamB = new TeamEntry("uuid-b", "Team Beta", 2);
        teamC = new TeamEntry("uuid-c", "Team Gamma", 3);
    }

    @Test
    void teamA_sees_own_match() {
        var matchAvsB = new ScheduleEntry.Match("m1", "Team Alpha", "Team Beta", 1);
        var matchBvsC = new ScheduleEntry.Match("m2", "Team Beta", "Team Gamma", 2);

        TournamentSnapshot snapshot =
                new TournamentSnapshot(
                        "t-id",
                        "tenant-id",
                        5L,
                        List.of(matchAvsB, matchBvsC),
                        List.of(teamA, teamB, teamC));

        TournamentSnapshot result = projection.project(snapshot, teamA);

        assertThat(result.scheduleEntries()).containsExactly(matchAvsB);
        assertThat(result.scheduleEntries()).doesNotContain(matchBvsC);
    }

    @Test
    void teamA_does_not_see_other_team_match() {
        var matchBvsC = new ScheduleEntry.Match("m1", "Team Beta", "Team Gamma", 1);

        TournamentSnapshot snapshot =
                new TournamentSnapshot(
                        "t-id", "tenant-id", 1L, List.of(matchBvsC), List.of(teamA, teamB, teamC));

        TournamentSnapshot result = projection.project(snapshot, teamA);
        assertThat(result.scheduleEntries()).isEmpty();
    }

    @Test
    void all_teams_see_special_appointments() {
        var sondertermin = new ScheduleEntry.SpecialAppointment("sa1", "Award Ceremony");

        TournamentSnapshot snapshot =
                new TournamentSnapshot(
                        "t-id",
                        "tenant-id",
                        2L,
                        List.of(sondertermin),
                        List.of(teamA, teamB, teamC));

        assertThat(projection.project(snapshot, teamA).scheduleEntries())
                .containsExactly(sondertermin);
        assertThat(projection.project(snapshot, teamB).scheduleEntries())
                .containsExactly(sondertermin);
        assertThat(projection.project(snapshot, teamC).scheduleEntries())
                .containsExactly(sondertermin);
    }

    @Test
    void all_teams_see_pauses() {
        var pause = new ScheduleEntry.Pause("p1", "Lunch Break");

        TournamentSnapshot snapshot =
                new TournamentSnapshot(
                        "t-id", "tenant-id", 3L, List.of(pause), List.of(teamA, teamB, teamC));

        assertThat(projection.project(snapshot, teamA).scheduleEntries()).containsExactly(pause);
        assertThat(projection.project(snapshot, teamB).scheduleEntries()).containsExactly(pause);
    }

    @Test
    void team_list_preserved_unchanged_in_projected_snapshot() {
        TournamentSnapshot snapshot =
                new TournamentSnapshot(
                        "t-id", "tenant-id", 1L, List.of(), List.of(teamA, teamB, teamC));

        TournamentSnapshot result = projection.project(snapshot, teamA);
        assertThat(result.teams()).containsExactlyInAnyOrder(teamA, teamB, teamC);
    }

    @Test
    void metadata_fields_preserved_in_projected_snapshot() {
        TournamentSnapshot snapshot =
                new TournamentSnapshot("tourney-123", "tenant-456", 99L, List.of(), List.of(teamA));

        TournamentSnapshot result = projection.project(snapshot, teamA);
        assertThat(result.tournamentId()).isEqualTo("tourney-123");
        assertThat(result.tenantId()).isEqualTo("tenant-456");
        assertThat(result.sequenceNumber()).isEqualTo(99L);
    }

    @Test
    void team_as_home_team_sees_match() {
        var match = new ScheduleEntry.Match("m1", "Team Alpha", "Team Beta", 1);
        TournamentSnapshot snapshot =
                new TournamentSnapshot("t", "t", 1L, List.of(match), List.of(teamA));

        assertThat(projection.project(snapshot, teamA).scheduleEntries()).contains(match);
    }

    @Test
    void team_as_away_team_sees_match() {
        var match = new ScheduleEntry.Match("m1", "Team Beta", "Team Alpha", 1);
        TournamentSnapshot snapshot =
                new TournamentSnapshot("t", "t", 1L, List.of(match), List.of(teamA));

        assertThat(projection.project(snapshot, teamA).scheduleEntries()).contains(match);
    }
}
