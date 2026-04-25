package de.vvwt.tm.print;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LaufzettelRow} VO (record) — E24S02.
 *
 * <p>Verifies constructor/accessor contract, factory-style static creators, and record equality
 * semantics per AC-LAUFZETTEL-ROW-TEST-REDFIRST (E24S02).
 *
 * <p>Test authored RED-first per DEC-22 Iron Law: this test was committed before
 * {@code LaufzettelRow} existed, proving the red state.
 */
@DisplayName("LaufzettelRow — VO contract tests (E24S02)")
class LaufzettelRowTest {

    // =========================================================================
    // Phase header row
    // =========================================================================

    @Test
    @DisplayName("phaseHeader: isPhaseHeader true, phaseHeaderName set, other flags false")
    void phaseHeader_setsCorrectFields() {
        LaufzettelRow row = LaufzettelRow.phaseHeader("Vorrunde");
        assertThat(row.isPhaseHeader()).as("isPhaseHeader must be true").isTrue();
        assertThat(row.phaseHeaderName()).as("phaseHeaderName must match").isEqualTo("Vorrunde");
        assertThat(row.isBreak()).isFalse();
        assertThat(row.isPlaying()).isFalse();
        assertThat(row.isRefereeing()).isFalse();
        assertThat(row.isActivity()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    @Test
    @DisplayName("phaseHeader: null phaseName falls back to empty string")
    void phaseHeader_nullFallback() {
        LaufzettelRow row = LaufzettelRow.phaseHeader(null);
        assertThat(row.phaseHeaderName()).isEmpty();
    }

    // =========================================================================
    // Break separator row
    // =========================================================================

    @Test
    @DisplayName("breakRow: isBreak true, breakLabel and breakTimeWindow set")
    void breakRow_setsCorrectFields() {
        LaufzettelRow row = LaufzettelRow.breakRow("Mittagspause", "12:00–12:30");
        assertThat(row.isBreak()).as("isBreak must be true").isTrue();
        assertThat(row.breakLabel()).isEqualTo("Mittagspause");
        assertThat(row.breakTimeWindow()).isEqualTo("12:00–12:30");
        assertThat(row.isPhaseHeader()).isFalse();
        assertThat(row.isPlaying()).isFalse();
    }

    @Test
    @DisplayName("breakRow: null label and timeWindow fall back to empty string")
    void breakRow_nullFallback() {
        LaufzettelRow row = LaufzettelRow.breakRow(null, null);
        assertThat(row.breakLabel()).isEmpty();
        assertThat(row.breakTimeWindow()).isEmpty();
    }

    // =========================================================================
    // Playing row
    // =========================================================================

    @Test
    @DisplayName("playing: isPlaying true, roundNumber, timeWindow, opponentName, fieldNumber set")
    void playing_setsCorrectFields() {
        LaufzettelRow row = LaufzettelRow.playing(3, "10:00–10:15", "Blaue Haie", "2");
        assertThat(row.isPlaying()).isTrue();
        assertThat(row.roundNumber()).isEqualTo(3);
        assertThat(row.timeWindow()).isEqualTo("10:00–10:15");
        assertThat(row.opponentName()).isEqualTo("Blaue Haie");
        assertThat(row.fieldNumber()).isEqualTo("2");
        assertThat(row.isRefereeing()).isFalse();
        assertThat(row.isActivity()).isFalse();
        assertThat(row.isFree()).isFalse();
    }

    @Test
    @DisplayName("playing: null strings fall back to empty string")
    void playing_nullFallback() {
        LaufzettelRow row = LaufzettelRow.playing(1, null, null, null);
        assertThat(row.timeWindow()).isEmpty();
        assertThat(row.opponentName()).isEmpty();
        assertThat(row.fieldNumber()).isEmpty();
    }

    // =========================================================================
    // Refereeing row
    // =========================================================================

    @Test
    @DisplayName("refereeing: isRefereeing true, roundNumber, timeWindow, fieldNumber set")
    void refereeing_setsCorrectFields() {
        LaufzettelRow row = LaufzettelRow.refereeing(2, "09:30–09:45", "3");
        assertThat(row.isRefereeing()).isTrue();
        assertThat(row.roundNumber()).isEqualTo(2);
        assertThat(row.timeWindow()).isEqualTo("09:30–09:45");
        assertThat(row.fieldNumber()).isEqualTo("3");
        assertThat(row.isPlaying()).isFalse();
    }

    // =========================================================================
    // Activity row
    // =========================================================================

    @Test
    @DisplayName("activity: isActivity true, activityName set")
    void activity_setsCorrectFields() {
        LaufzettelRow row = LaufzettelRow.activity(1, "", "Mannschaftsfoto");
        assertThat(row.isActivity()).isTrue();
        assertThat(row.activityName()).isEqualTo("Mannschaftsfoto");
        assertThat(row.isFree()).isFalse();
    }

    // =========================================================================
    // Free row
    // =========================================================================

    @Test
    @DisplayName("free: isFree true, roundNumber set")
    void free_setsCorrectFields() {
        LaufzettelRow row = LaufzettelRow.free(4, "11:00–11:15");
        assertThat(row.isFree()).isTrue();
        assertThat(row.roundNumber()).isEqualTo(4);
        assertThat(row.isPlaying()).isFalse();
    }

    // =========================================================================
    // Record equality (same values → equal)
    // =========================================================================

    @Test
    @DisplayName("record equality: two phaseHeader rows with same name are equal")
    void recordEquality_phaseHeader() {
        LaufzettelRow a = LaufzettelRow.phaseHeader("Finale");
        LaufzettelRow b = LaufzettelRow.phaseHeader("Finale");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("record equality: two playing rows with same fields are equal")
    void recordEquality_playing() {
        LaufzettelRow a = LaufzettelRow.playing(1, "09:00–09:15", "Team A", "1");
        LaufzettelRow b = LaufzettelRow.playing(1, "09:00–09:15", "Team A", "1");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("record equality: rows with different types are not equal")
    void recordEquality_differentTypes_notEqual() {
        LaufzettelRow playing = LaufzettelRow.playing(1, "", "Opp", "1");
        LaufzettelRow free = LaufzettelRow.free(1, "");
        assertThat(playing).isNotEqualTo(free);
    }
}
