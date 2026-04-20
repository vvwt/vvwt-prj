package de.vvwt.tm.tournament.internal.referee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RefereeAssignmentReport} (AC-TDD-RefereeAssignmentReport, E21S08).
 *
 * <p>Verifies: immutability (returned list/map are unmodifiable); builder accumulates counts
 * correctly; toString does not throw.
 *
 * <p>Source: inventory row 277 — {@code
 * de.vvwt.tm.tournament.internal.referee.RefereeAssignmentReport}.
 *
 * <p>Related DECs: DEC-22 (TDD Iron Law).
 */
class RefereeAssignmentReportTest {

    // -------------------------------------------------------------------------
    // Builder accumulates counts correctly
    // -------------------------------------------------------------------------

    @Test
    void builder_accumulatesCounts() {
        UUID team1 = UUID.randomUUID();
        UUID team2 = UUID.randomUUID();

        RefereeAssignmentReport report =
                RefereeAssignmentReport.builder()
                        .totalMatches(5)
                        .incrementAssigned()
                        .incrementAssigned()
                        .addOverridden(1)
                        .incrementNoReferee()
                        .recordAssignment(team1)
                        .recordAssignment(team1)
                        .recordAssignment(team2)
                        .addWarning("Lap 1: no referee for match X")
                        .build();

        assertThat(report.getTotalMatches()).isEqualTo(5);
        assertThat(report.getAssignedCount()).isEqualTo(2);
        assertThat(report.getOverriddenCount()).isEqualTo(1);
        assertThat(report.getNoRefereeCount()).isEqualTo(1);
        assertThat(report.getPerTeamAssignmentCount())
                .containsEntry(team1, 2)
                .containsEntry(team2, 1);
        assertThat(report.getWarnings()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Immutability: warnings list is unmodifiable
    // -------------------------------------------------------------------------

    @Test
    void warnings_isUnmodifiable() {
        RefereeAssignmentReport report =
                RefereeAssignmentReport.builder()
                        .totalMatches(0)
                        .addWarning("test warning")
                        .build();

        assertThatThrownBy(() -> report.getWarnings().add("hack"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // Immutability: perTeamAssignmentCount map is unmodifiable
    // -------------------------------------------------------------------------

    @Test
    void perTeamAssignmentCount_isUnmodifiable() {
        UUID team = UUID.randomUUID();
        RefereeAssignmentReport report =
                RefereeAssignmentReport.builder().totalMatches(0).recordAssignment(team).build();

        assertThatThrownBy(() -> report.getPerTeamAssignmentCount().put(UUID.randomUUID(), 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // -------------------------------------------------------------------------
    // Empty report builds correctly
    // -------------------------------------------------------------------------

    @Test
    void builder_emptyReport_defaultZeroCounts() {
        RefereeAssignmentReport report = RefereeAssignmentReport.builder().totalMatches(0).build();

        assertThat(report.getAssignedCount()).isZero();
        assertThat(report.getOverriddenCount()).isZero();
        assertThat(report.getNoRefereeCount()).isZero();
        assertThat(report.getPerTeamAssignmentCount()).isEmpty();
        assertThat(report.getWarnings()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // toString does not throw
    // -------------------------------------------------------------------------

    @Test
    void toString_doesNotThrow() {
        RefereeAssignmentReport report =
                RefereeAssignmentReport.builder().totalMatches(3).incrementAssigned().build();

        assertThat(report.toString()).contains("totalMatches=3", "assignedCount=1");
    }

    // -------------------------------------------------------------------------
    // Builder.getAssignmentCount returns 0 for unknown team
    // -------------------------------------------------------------------------

    @Test
    void builderGetAssignmentCount_unknownTeam_returnsZero() {
        RefereeAssignmentReport.Builder builder = RefereeAssignmentReport.builder().totalMatches(0);

        assertThat(builder.getAssignmentCount(UUID.randomUUID())).isZero();
    }
}
