// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.print;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActivityScheduleRow} — VO contract (E24S03,
 * AC-ACTIVITY-SCHEDULE-ROW-CREATE).
 *
 * <p>Tests are at {@code de.vvwt.tm.print} (same package as subject) — white-box access permitted
 * per DEC-36 same-package rule.
 */
class ActivityScheduleRowTest {

    // ── dataRow factory ──────────────────────────────────────────────────────

    @Test
    void dataRow_setsIsDataRowTrue() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(3, "10:00–10:15", "Team A, Team B");
        assertThat(row.isDataRow()).isTrue();
    }

    @Test
    void dataRow_setsRoundNumber() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(5, "", "Team X");
        assertThat(row.getRoundNumber()).isEqualTo(5);
    }

    @Test
    void dataRow_setsTimeWindow() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(1, "09:00–09:15", "Team A");
        assertThat(row.getTimeWindow()).isEqualTo("09:00–09:15");
    }

    @Test
    void dataRow_setsTeamNames() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(2, "", "Team A, Team B");
        assertThat(row.getTeamNames()).isEqualTo("Team A, Team B");
    }

    @Test
    void dataRow_isNotBreak() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(1, "", "Team A");
        assertThat(row.isBreak()).isFalse();
    }

    @Test
    void dataRow_breakFieldsAreEmpty() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(1, "10:00–10:15", "Team A");
        assertThat(row.getBreakLabel()).isEmpty();
        assertThat(row.getBreakTimeWindow()).isEmpty();
    }

    @Test
    void dataRow_nullTimeWindowBecomesEmpty() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(1, null, "Team A");
        assertThat(row.getTimeWindow()).isEmpty();
    }

    @Test
    void dataRow_nullTeamNamesBecomesEmpty() {
        ActivityScheduleRow row = ActivityScheduleRow.dataRow(1, "", null);
        assertThat(row.getTeamNames()).isEmpty();
    }

    // ── breakRow factory ─────────────────────────────────────────────────────

    @Test
    void breakRow_setsIsBreakTrue() {
        ActivityScheduleRow row = ActivityScheduleRow.breakRow("Mittagspause", "12:00–12:30");
        assertThat(row.isBreak()).isTrue();
    }

    @Test
    void breakRow_setsBreakLabel() {
        ActivityScheduleRow row = ActivityScheduleRow.breakRow("Pause", "");
        assertThat(row.getBreakLabel()).isEqualTo("Pause");
    }

    @Test
    void breakRow_setsBreakTimeWindow() {
        ActivityScheduleRow row = ActivityScheduleRow.breakRow("Pause", "12:00–12:30");
        assertThat(row.getBreakTimeWindow()).isEqualTo("12:00–12:30");
    }

    @Test
    void breakRow_isNotDataRow() {
        ActivityScheduleRow row = ActivityScheduleRow.breakRow("Pause", "");
        assertThat(row.isDataRow()).isFalse();
    }

    @Test
    void breakRow_dataFieldsAreDefault() {
        ActivityScheduleRow row = ActivityScheduleRow.breakRow("Pause", "12:00–12:30");
        assertThat(row.getRoundNumber()).isZero();
        assertThat(row.getTimeWindow()).isEmpty();
        assertThat(row.getTeamNames()).isEmpty();
    }

    @Test
    void breakRow_nullLabelBecomesEmpty() {
        ActivityScheduleRow row = ActivityScheduleRow.breakRow(null, "");
        assertThat(row.getBreakLabel()).isEmpty();
    }

    @Test
    void breakRow_nullTimeWindowBecomesEmpty() {
        ActivityScheduleRow row = ActivityScheduleRow.breakRow("Pause", null);
        assertThat(row.getBreakTimeWindow()).isEmpty();
    }

    // ── default constructor ──────────────────────────────────────────────────

    @Test
    void defaultConstructor_allFieldsAtDefault() {
        ActivityScheduleRow row = new ActivityScheduleRow();
        assertThat(row.isDataRow()).isFalse();
        assertThat(row.getRoundNumber()).isZero();
        assertThat(row.getTimeWindow()).isEmpty();
        assertThat(row.getTeamNames()).isEmpty();
        assertThat(row.isBreak()).isFalse();
        assertThat(row.getBreakLabel()).isEmpty();
        assertThat(row.getBreakTimeWindow()).isEmpty();
    }
}
