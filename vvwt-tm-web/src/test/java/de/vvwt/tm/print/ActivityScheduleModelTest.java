package de.vvwt.tm.print;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActivityScheduleModel} — DTO contract (E24S03,
 * AC-ACTIVITY-SCHEDULE-MODEL-CREATE).
 *
 * <p>Tests are at {@code de.vvwt.tm.print} (same package as subject) — white-box access permitted
 * per DEC-36 same-package rule.
 */
class ActivityScheduleModelTest {

    // ── canonical constructor ────────────────────────────────────────────────

    @Test
    void constructor_storesRows() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(1, "", "Team A");
        ActivityScheduleModel model =
                new ActivityScheduleModel(List.of(row), 1, 1, List.of(), true);
        assertThat(model.rows()).containsExactly(row);
    }

    @Test
    void constructor_storesTotalAssignedTeams() {
        ActivityScheduleModel model = new ActivityScheduleModel(List.of(), 7, 3, List.of(), true);
        assertThat(model.totalAssignedTeams()).isEqualTo(7);
    }

    @Test
    void constructor_storesRoundCount() {
        ActivityScheduleModel model = new ActivityScheduleModel(List.of(), 0, 4, List.of(), false);
        assertThat(model.roundCount()).isEqualTo(4);
    }

    @Test
    void constructor_storesUnassignedTeamNames() {
        ActivityScheduleModel model =
                new ActivityScheduleModel(List.of(), 0, 0, List.of("Team X", "Team Y"), false);
        assertThat(model.unassignedTeamNames()).containsExactly("Team X", "Team Y");
    }

    @Test
    void constructor_storesHasTime() {
        ActivityScheduleModel withTime =
                new ActivityScheduleModel(List.of(), 0, 0, List.of(), true);
        ActivityScheduleModel noTime = new ActivityScheduleModel(List.of(), 0, 0, List.of(), false);
        assertThat(withTime.hasTime()).isTrue();
        assertThat(noTime.hasTime()).isFalse();
    }

    // ── empty() factory ──────────────────────────────────────────────────────

    @Test
    void empty_returnsEmptyRows() {
        assertThat(ActivityScheduleModel.empty().rows()).isEmpty();
    }

    @Test
    void empty_returnsZeroCounts() {
        ActivityScheduleModel m = ActivityScheduleModel.empty();
        assertThat(m.totalAssignedTeams()).isZero();
        assertThat(m.roundCount()).isZero();
    }

    @Test
    void empty_returnsEmptyUnassigned() {
        assertThat(ActivityScheduleModel.empty().unassignedTeamNames()).isEmpty();
    }

    @Test
    void empty_hasTimeFalse() {
        assertThat(ActivityScheduleModel.empty().hasTime()).isFalse();
    }

    // ── hasUnassigned() ──────────────────────────────────────────────────────

    @Test
    void hasUnassigned_trueWhenUnassignedNamesPresent() {
        ActivityScheduleModel model =
                new ActivityScheduleModel(List.of(), 0, 0, List.of("Team Z"), false);
        assertThat(model.hasUnassigned()).isTrue();
    }

    @Test
    void hasUnassigned_falseWhenNoUnassigned() {
        ActivityScheduleModel model = new ActivityScheduleModel(List.of(), 5, 2, List.of(), true);
        assertThat(model.hasUnassigned()).isFalse();
    }

    @Test
    void hasUnassigned_falseForEmpty() {
        assertThat(ActivityScheduleModel.empty().hasUnassigned()).isFalse();
    }
}
