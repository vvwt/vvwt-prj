// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for TimelineEntry (E21S11).
 *
 * <p>Written RED (against non-existent production class) before implementation. Covers
 * AC-TDD-TimelineEntry.
 *
 * @see TimelineEntry
 */
class TimelineEntryTest {

    private static final LocalTime NINE_AM = LocalTime.of(9, 0);
    private static final LocalTime NINE_TWENTY = LocalTime.of(9, 20);

    @Test
    void constructor_validArguments_fieldsAreAccessible() {
        TimelineEntry entry =
                new TimelineEntry(1, 1, TimelineEntryType.MATCH_ROUND, NINE_AM, NINE_TWENTY, null);

        assertThat(entry.phaseNumber()).isEqualTo(1);
        assertThat(entry.lapNumber()).isEqualTo(1);
        assertThat(entry.type()).isEqualTo(TimelineEntryType.MATCH_ROUND);
        assertThat(entry.startTime()).isEqualTo(NINE_AM);
        assertThat(entry.endTime()).isEqualTo(NINE_TWENTY);
        assertThat(entry.label()).isNull();
    }

    @Test
    void constructor_withLabel_labelAccessible() {
        TimelineEntry entry =
                new TimelineEntry(
                        1, 0, TimelineEntryType.INTRA_PHASE_BREAK, NINE_AM, NINE_TWENTY, "Pause");
        assertThat(entry.label()).isEqualTo("Pause");
    }

    @Test
    void constructor_zeroLapNumber_isPermittedForBreakEntries() {
        TimelineEntry entry =
                new TimelineEntry(1, 0, TimelineEntryType.LAP_BREAK, NINE_AM, NINE_TWENTY, null);
        assertThat(entry.lapNumber()).isZero();
    }

    @Test
    void equality_twoIdenticalInstances_areEqual() {
        TimelineEntry a =
                new TimelineEntry(1, 1, TimelineEntryType.MATCH_ROUND, NINE_AM, NINE_TWENTY, null);
        TimelineEntry b =
                new TimelineEntry(1, 1, TimelineEntryType.MATCH_ROUND, NINE_AM, NINE_TWENTY, null);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void hashCode_twoIdenticalInstances_haveEqualHashCode() {
        TimelineEntry a =
                new TimelineEntry(1, 1, TimelineEntryType.MATCH_ROUND, NINE_AM, NINE_TWENTY, null);
        TimelineEntry b =
                new TimelineEntry(1, 1, TimelineEntryType.MATCH_ROUND, NINE_AM, NINE_TWENTY, null);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equality_differentInstances_areNotEqual() {
        TimelineEntry a =
                new TimelineEntry(1, 1, TimelineEntryType.MATCH_ROUND, NINE_AM, NINE_TWENTY, null);
        TimelineEntry b =
                new TimelineEntry(1, 2, TimelineEntryType.MATCH_ROUND, NINE_AM, NINE_TWENTY, null);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void constructor_nullType_throwsNullPointerException() {
        assertThatThrownBy(() -> new TimelineEntry(1, 1, null, NINE_AM, NINE_TWENTY, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullStartTime_throwsNullPointerException() {
        assertThatThrownBy(
                        () ->
                                new TimelineEntry(
                                        1,
                                        1,
                                        TimelineEntryType.MATCH_ROUND,
                                        null,
                                        NINE_TWENTY,
                                        null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nullEndTime_throwsNullPointerException() {
        assertThatThrownBy(
                        () ->
                                new TimelineEntry(
                                        1, 1, TimelineEntryType.MATCH_ROUND, NINE_AM, null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
