package de.vvwt.tm.display;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Constructor smoke tests for the 3 bounded-context-owned query-shape records authored in E25S01
 * per DEC-22 records-no-logic precedent.
 *
 * <p>Per story ACs AC-RED-FIRST-DISPLAY-PHASE-OVERVIEW-RESPONSE,
 * AC-RED-FIRST-DISPLAY-MATCHES-RESPONSE, AC-RED-FIRST-DISPLAY-GROUP-STANDINGS-RESPONSE: each record
 * is tested by (a) instantiating with all fields populated, (b) verifying accessor return values,
 * and (c) verifying the {@code equals} contract for two records with identical fields.
 *
 * <p>No mocks needed — records are pure data carriers with no business logic.
 *
 * @see DisplayPhaseOverviewResponse
 * @see DisplayMatchesResponse
 * @see DisplayGroupStandingsResponse
 * @see DEC-22
 * @see E25S01
 */
class DisplayRecordSmokeTest {

    // =========================================================================
    // AC-RED-FIRST-DISPLAY-PHASE-OVERVIEW-RESPONSE
    // =========================================================================

    /**
     * Smoke test: DisplayPhaseOverviewResponse constructor + accessors + equals contract.
     * (AC-RED-FIRST-DISPLAY-PHASE-OVERVIEW-RESPONSE)
     */
    @Test
    void displayPhaseOverviewResponse_constructorAndAccessors() {
        UUID phaseId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        DisplayPhaseOverviewResponse.GroupSummary group =
                new DisplayPhaseOverviewResponse.GroupSummary(1, 4);

        DisplayPhaseOverviewResponse response =
                new DisplayPhaseOverviewResponse(
                        phaseId, tenantId, "Test Phase", "ACTIVE", 3, 2, 4, false, List.of(group));

        assertThat(response.phaseId()).isEqualTo(phaseId);
        assertThat(response.tenantId()).isEqualTo(tenantId);
        assertThat(response.phaseName()).isEqualTo("Test Phase");
        assertThat(response.phaseStatus()).isEqualTo("ACTIVE");
        assertThat(response.lapCount()).isEqualTo(3);
        assertThat(response.currentLap()).isEqualTo(2);
        assertThat(response.fieldCount()).isEqualTo(4);
        assertThat(response.preparationPreview()).isFalse();
        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).groupNumber()).isEqualTo(1);
        assertThat(response.groups().get(0).teamCount()).isEqualTo(4);
    }

    /**
     * Smoke test: DisplayPhaseOverviewResponse equals contract.
     * (AC-RED-FIRST-DISPLAY-PHASE-OVERVIEW-RESPONSE)
     */
    @Test
    void displayPhaseOverviewResponse_equalsContract() {
        UUID phaseId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        DisplayPhaseOverviewResponse.GroupSummary group =
                new DisplayPhaseOverviewResponse.GroupSummary(1, 2);
        List<DisplayPhaseOverviewResponse.GroupSummary> groups = List.of(group);

        DisplayPhaseOverviewResponse r1 =
                new DisplayPhaseOverviewResponse(
                        phaseId, tenantId, "Phase", "ACTIVE", 2, 1, 3, false, groups);
        DisplayPhaseOverviewResponse r2 =
                new DisplayPhaseOverviewResponse(
                        phaseId, tenantId, "Phase", "ACTIVE", 2, 1, 3, false, groups);

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    // =========================================================================
    // AC-RED-FIRST-DISPLAY-MATCHES-RESPONSE
    // =========================================================================

    /**
     * Smoke test: DisplayMatchesResponse constructor + accessors + equals contract.
     * (AC-RED-FIRST-DISPLAY-MATCHES-RESPONSE)
     */
    @Test
    void displayMatchesResponse_constructorAndAccessors() {
        UUID phaseId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();

        DisplayMatchesResponse.SetResultEntry set =
                new DisplayMatchesResponse.SetResultEntry(0, 21, 15);
        DisplayMatchesResponse.MatchEntry match =
                new DisplayMatchesResponse.MatchEntry(
                        matchId, 2, 1, "Team A", "Team B", List.of(set), "IN_PROGRESS", null); // lapNumber=2, fieldNumber=1

        DisplayMatchesResponse response = new DisplayMatchesResponse(phaseId, 2, List.of(match));

        assertThat(response.phaseId()).isEqualTo(phaseId);
        assertThat(response.lap()).isEqualTo(2);
        assertThat(response.matches()).hasSize(1);

        DisplayMatchesResponse.MatchEntry entry = response.matches().get(0);
        assertThat(entry.matchId()).isEqualTo(matchId);
        assertThat(entry.fieldNumber()).isEqualTo(1);
        assertThat(entry.teamAName()).isEqualTo("Team A");
        assertThat(entry.teamBName()).isEqualTo("Team B");
        assertThat(entry.matchStatus()).isEqualTo("IN_PROGRESS");
        assertThat(entry.refereeTeamName()).isNull();
        assertThat(entry.setResults()).hasSize(1);
        assertThat(entry.setResults().get(0).setIndex()).isEqualTo(0);
        assertThat(entry.setResults().get(0).scoreA()).isEqualTo(21);
        assertThat(entry.setResults().get(0).scoreB()).isEqualTo(15);
    }

    /**
     * Smoke test: DisplayMatchesResponse equals contract. (AC-RED-FIRST-DISPLAY-MATCHES-RESPONSE)
     */
    @Test
    void displayMatchesResponse_equalsContract() {
        UUID phaseId = UUID.randomUUID();
        DisplayMatchesResponse r1 = new DisplayMatchesResponse(phaseId, 1, List.of());
        DisplayMatchesResponse r2 = new DisplayMatchesResponse(phaseId, 1, List.of());

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    // =========================================================================
    // AC-RED-FIRST-DISPLAY-GROUP-STANDINGS-RESPONSE
    // =========================================================================

    /**
     * Smoke test: DisplayGroupStandingsResponse constructor + accessors + equals contract.
     * (AC-RED-FIRST-DISPLAY-GROUP-STANDINGS-RESPONSE)
     */
    @Test
    void displayGroupStandingsResponse_constructorAndAccessors() {
        UUID phaseId = UUID.randomUUID();

        DisplayGroupStandingsResponse.TeamRanking ranking =
                new DisplayGroupStandingsResponse.TeamRanking(1, "Team Alpha", 6, 3, 1, 60, 40);
        DisplayGroupStandingsResponse.GroupStandings standings =
                new DisplayGroupStandingsResponse.GroupStandings(1, List.of(ranking));

        DisplayGroupStandingsResponse response =
                new DisplayGroupStandingsResponse(phaseId, List.of(standings));

        assertThat(response.phaseId()).isEqualTo(phaseId);
        assertThat(response.groups()).hasSize(1);

        DisplayGroupStandingsResponse.GroupStandings group = response.groups().get(0);
        assertThat(group.groupNumber()).isEqualTo(1);
        assertThat(group.rankings()).hasSize(1);

        DisplayGroupStandingsResponse.TeamRanking r = group.rankings().get(0);
        assertThat(r.position()).isEqualTo(1);
        assertThat(r.teamName()).isEqualTo("Team Alpha");
        assertThat(r.points()).isEqualTo(6);
        assertThat(r.setsWon()).isEqualTo(3);
        assertThat(r.setsLost()).isEqualTo(1);
        assertThat(r.ballsWon()).isEqualTo(60);
        assertThat(r.ballsLost()).isEqualTo(40);
    }

    /**
     * Smoke test: DisplayGroupStandingsResponse equals contract.
     * (AC-RED-FIRST-DISPLAY-GROUP-STANDINGS-RESPONSE)
     */
    @Test
    void displayGroupStandingsResponse_equalsContract() {
        UUID phaseId = UUID.randomUUID();
        DisplayGroupStandingsResponse r1 = new DisplayGroupStandingsResponse(phaseId, List.of());
        DisplayGroupStandingsResponse r2 = new DisplayGroupStandingsResponse(phaseId, List.of());

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }
}
