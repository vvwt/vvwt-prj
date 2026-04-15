package de.vvwt.tm.domain;

import de.vvwt.tm.domain.timeline.PhaseBreakConfig;
import de.vvwt.tm.domain.timeline.PhaseConfig;
import de.vvwt.tm.domain.timeline.TimelineCalculationService;
import de.vvwt.tm.domain.timeline.TimelineEntry;
import de.vvwt.tm.domain.timeline.TimelineEntryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TimelineCalculationService} (E08S03).
 *
 * <p>Covers all acceptance criteria AC3–AC10 via pure unit tests (no Spring context needed —
 * the service has no dependencies).
 *
 * @see <a href="../../../.gaai/project/contexts/artefacts/stories/E08S03.story.md">Story E08S03</a>
 */
class TimelineCalculationServiceTest {

    private TimelineCalculationService service;

    @BeforeEach
    void setUp() {
        service = new TimelineCalculationService();
    }

    // =========================================================================
    // AC3 — Basic single-phase calculation
    // =========================================================================

    /**
     * AC3: single phase, 4 laps, 15-min lap time, 5-min lap break, start 10:00.
     * Expected: Lap 1 10:00–10:15, LapBreak 10:15–10:20, Lap 2 10:20–10:35,
     *           LapBreak 10:35–10:40, Lap 3 10:40–10:55, LapBreak 10:55–11:00,
     *           Lap 4 11:00–11:15.
     * No trailing lap break after last lap (AC6).
     */
    @Test
    void calculate_singlePhase_fourLaps_basicTimeline() {
        PhaseConfig phase = new PhaseConfig(1, 4, 15, 5, List.of());

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(10, 0), List.of(phase), 0);

        // 4 laps + 3 lap breaks = 7 entries
        assertThat(result).hasSize(7);

        assertEntry(result.get(0), 1, 1, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(10, 0), LocalTime.of(10, 15), null);
        assertEntry(result.get(1), 1, 0, TimelineEntryType.LAP_BREAK,
                LocalTime.of(10, 15), LocalTime.of(10, 20), null);
        assertEntry(result.get(2), 1, 2, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(10, 20), LocalTime.of(10, 35), null);
        assertEntry(result.get(3), 1, 0, TimelineEntryType.LAP_BREAK,
                LocalTime.of(10, 35), LocalTime.of(10, 40), null);
        assertEntry(result.get(4), 1, 3, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(10, 40), LocalTime.of(10, 55), null);
        assertEntry(result.get(5), 1, 0, TimelineEntryType.LAP_BREAK,
                LocalTime.of(10, 55), LocalTime.of(11, 0), null);
        assertEntry(result.get(6), 1, 4, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(11, 0), LocalTime.of(11, 15), null);
    }

    // =========================================================================
    // AC4 — Intra-phase break
    // =========================================================================

    /**
     * AC4: same as AC3 but with a 40-min intra-phase break after lap 2.
     * Expected: Lap 1 10:00–10:15, LapBreak 10:15–10:20,
     *           Lap 2 10:20–10:35, IntraPhaseBreak "Mittagspause" 10:35–11:15,
     *           Lap 3 11:15–11:30, LapBreak 11:30–11:35,
     *           Lap 4 11:35–11:50.
     */
    @Test
    void calculate_singlePhase_withIntraPhaseBreakAfterLap2() {
        PhaseBreakConfig lunchBreak = new PhaseBreakConfig(2, 40, "Mittagspause");
        PhaseConfig phase = new PhaseConfig(1, 4, 15, 5, List.of(lunchBreak));

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(10, 0), List.of(phase), 0);

        // 4 laps + 2 lap breaks (laps 1 and 3) + 1 intra-phase break = 7 entries
        assertThat(result).hasSize(7);

        assertEntry(result.get(0), 1, 1, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(10, 0), LocalTime.of(10, 15), null);
        assertEntry(result.get(1), 1, 0, TimelineEntryType.LAP_BREAK,
                LocalTime.of(10, 15), LocalTime.of(10, 20), null);
        assertEntry(result.get(2), 1, 2, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(10, 20), LocalTime.of(10, 35), null);
        // Intra-phase break replaces lap break after lap 2
        assertEntry(result.get(3), 1, 0, TimelineEntryType.INTRA_PHASE_BREAK,
                LocalTime.of(10, 35), LocalTime.of(11, 15), "Mittagspause");
        assertEntry(result.get(4), 1, 3, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(11, 15), LocalTime.of(11, 30), null);
        assertEntry(result.get(5), 1, 0, TimelineEntryType.LAP_BREAK,
                LocalTime.of(11, 30), LocalTime.of(11, 35), null);
        assertEntry(result.get(6), 1, 4, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(11, 35), LocalTime.of(11, 50), null);
    }

    // =========================================================================
    // AC5 — Multi-phase with section break
    // =========================================================================

    /**
     * AC5: two phases with a 10-min section break between them.
     * Phase 1: 2 laps, 20 min each, no lap break.  Phase 2: 2 laps, 15 min each, no lap break.
     * Timeline is continuous; phase 2 starts after the section break.
     */
    @Test
    void calculate_twoPhases_withSectionBreak_continuousTimeline() {
        PhaseConfig phase1 = new PhaseConfig(1, 2, 20, 0, List.of());
        PhaseConfig phase2 = new PhaseConfig(2, 2, 15, 0, List.of());

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(9, 0), List.of(phase1, phase2), 10);

        // Phase 1: 2 laps (no lap breaks); section break; Phase 2: 2 laps (no lap breaks) = 5 entries
        assertThat(result).hasSize(5);

        // Phase 1
        assertEntry(result.get(0), 1, 1, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(9, 0), LocalTime.of(9, 20), null);
        assertEntry(result.get(1), 1, 2, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(9, 20), LocalTime.of(9, 40), null);
        // Section break (phaseNumber = phase 1's number)
        assertEntry(result.get(2), 1, 0, TimelineEntryType.SECTION_BREAK,
                LocalTime.of(9, 40), LocalTime.of(9, 50), null);
        // Phase 2
        assertEntry(result.get(3), 2, 1, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(9, 50), LocalTime.of(10, 5), null);
        assertEntry(result.get(4), 2, 2, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(10, 5), LocalTime.of(10, 20), null);
    }

    /**
     * AC5 variant: section break = 0 means phases are contiguous (no gap entry added).
     */
    @Test
    void calculate_twoPhases_zeroSectionBreak_noGapEntry() {
        PhaseConfig phase1 = new PhaseConfig(1, 1, 20, 0, List.of());
        PhaseConfig phase2 = new PhaseConfig(2, 1, 15, 0, List.of());

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(9, 0), List.of(phase1, phase2), 0);

        // 2 entries — no section break entry when sectionBreakMinutes=0
        assertThat(result).hasSize(2);
        assertEntry(result.get(0), 1, 1, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(9, 0), LocalTime.of(9, 20), null);
        assertEntry(result.get(1), 2, 1, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(9, 20), LocalTime.of(9, 35), null);
    }

    // =========================================================================
    // AC6 — No trailing lap break after last lap
    // =========================================================================

    /**
     * AC6: last lap in a phase is NOT followed by a lap break.
     */
    @Test
    void calculate_lastLap_noTrailingLapBreak() {
        PhaseConfig phase = new PhaseConfig(1, 3, 10, 5, List.of());

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(8, 0), List.of(phase), 0);

        // 3 laps + 2 lap breaks (after laps 1 and 2 only) = 5 entries
        assertThat(result).hasSize(5);
        // Last entry must be a MATCH_ROUND, not a LAP_BREAK
        assertThat(result.get(4).type()).isEqualTo(TimelineEntryType.MATCH_ROUND);
        assertThat(result.get(4).lapNumber()).isEqualTo(3);
    }

    /**
     * AC6: single-lap phase has no lap break at all.
     */
    @Test
    void calculate_singleLapPhase_noLapBreak() {
        PhaseConfig phase = new PhaseConfig(1, 1, 20, 10, List.of());

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(10, 0), List.of(phase), 0);

        assertThat(result).hasSize(1);
        assertEntry(result.get(0), 1, 1, TimelineEntryType.MATCH_ROUND,
                LocalTime.of(10, 0), LocalTime.of(10, 20), null);
    }

    // =========================================================================
    // AC7 — Error handling: null start time and zero-lap phase
    // =========================================================================

    /**
     * AC7: null start time → empty timeline (no error).
     */
    @Test
    void calculate_nullStartTime_returnsEmptyList() {
        PhaseConfig phase = new PhaseConfig(1, 4, 15, 5, List.of());

        List<TimelineEntry> result = service.calculate(null, List.of(phase), 0);

        assertThat(result).isEmpty();
    }

    /**
     * AC7: empty phase list → empty timeline.
     */
    @Test
    void calculate_emptyPhaseList_returnsEmptyList() {
        List<TimelineEntry> result = service.calculate(LocalTime.of(10, 0), List.of(), 0);

        assertThat(result).isEmpty();
    }

    /**
     * AC7: zero-lap phase → single MATCH_ROUND marker with same start and end time.
     */
    @Test
    void calculate_zeroLapPhase_producesMarkerEntry() {
        PhaseConfig phase = new PhaseConfig(1, 0, 15, 5, List.of());

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(10, 0), List.of(phase), 0);

        assertThat(result).hasSize(1);
        TimelineEntry entry = result.get(0);
        assertThat(entry.type()).isEqualTo(TimelineEntryType.MATCH_ROUND);
        assertThat(entry.lapNumber()).isEqualTo(0);
        assertThat(entry.startTime()).isEqualTo(entry.endTime());
    }

    // =========================================================================
    // AC10 — Determinism
    // =========================================================================

    /**
     * AC10: two calls with identical inputs produce identical output.
     */
    @Test
    void calculate_identicalInputs_producesIdenticalOutput() {
        PhaseBreakConfig breakConfig = new PhaseBreakConfig(2, 30, "Pause");
        PhaseConfig phase = new PhaseConfig(1, 4, 15, 5, List.of(breakConfig));
        LocalTime start = LocalTime.of(10, 0);

        List<TimelineEntry> first = service.calculate(start, List.of(phase), 0);
        List<TimelineEntry> second = service.calculate(start, List.of(phase), 0);

        assertThat(first).isEqualTo(second);
    }

    // =========================================================================
    // AC4 edge — intra-phase break at first and last valid lap boundaries
    // =========================================================================

    /**
     * AC4 edge: intra-phase break after lap 1 (the earliest valid boundary).
     */
    @Test
    void calculate_intraPhaseBreak_afterLap1() {
        PhaseBreakConfig earlyBreak = new PhaseBreakConfig(1, 15, "Frühe Pause");
        PhaseConfig phase = new PhaseConfig(1, 3, 10, 5, List.of(earlyBreak));

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(9, 0), List.of(phase), 0);

        // Lap 1 → IntraPhaseBreak → Lap 2 → LapBreak → Lap 3 = 5 entries
        assertThat(result).hasSize(5);
        assertThat(result.get(0).type()).isEqualTo(TimelineEntryType.MATCH_ROUND);
        assertThat(result.get(0).lapNumber()).isEqualTo(1);
        assertThat(result.get(1).type()).isEqualTo(TimelineEntryType.INTRA_PHASE_BREAK);
        assertThat(result.get(1).label()).isEqualTo("Frühe Pause");
        assertThat(result.get(2).lapNumber()).isEqualTo(2);
        assertThat(result.get(3).type()).isEqualTo(TimelineEntryType.LAP_BREAK);
        assertThat(result.get(4).lapNumber()).isEqualTo(3);
    }

    /**
     * AC4 edge: intra-phase break after lap N-1 (the latest valid boundary).
     */
    @Test
    void calculate_intraPhaseBreak_afterLastMinusOneLap() {
        PhaseBreakConfig lateBreak = new PhaseBreakConfig(3, 20, "Späte Pause");
        PhaseConfig phase = new PhaseConfig(1, 4, 10, 5, List.of(lateBreak));

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(9, 0), List.of(phase), 0);

        // Lap1, LapBreak, Lap2, LapBreak, Lap3, IntraPhaseBreak, Lap4 = 7 entries
        assertThat(result).hasSize(7);
        assertThat(result.get(4).lapNumber()).isEqualTo(3);
        assertThat(result.get(5).type()).isEqualTo(TimelineEntryType.INTRA_PHASE_BREAK);
        assertThat(result.get(5).label()).isEqualTo("Späte Pause");
        assertThat(result.get(6).lapNumber()).isEqualTo(4);
        // No trailing entry after last lap
        assertThat(result.get(6).type()).isEqualTo(TimelineEntryType.MATCH_ROUND);
    }

    // =========================================================================
    // Result immutability check
    // =========================================================================

    /**
     * The returned list must be unmodifiable (AC10 — no external state mutation).
     */
    @Test
    void calculate_returnedList_isUnmodifiable() {
        PhaseConfig phase = new PhaseConfig(1, 1, 10, 0, List.of());

        List<TimelineEntry> result = service.calculate(
                LocalTime.of(10, 0), List.of(phase), 0);

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> result.add(null));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private void assertEntry(TimelineEntry entry,
                              int phaseNumber,
                              int lapNumber,
                              TimelineEntryType type,
                              LocalTime startTime,
                              LocalTime endTime,
                              String label) {
        assertThat(entry.phaseNumber()).as("phaseNumber").isEqualTo(phaseNumber);
        assertThat(entry.lapNumber()).as("lapNumber").isEqualTo(lapNumber);
        assertThat(entry.type()).as("type").isEqualTo(type);
        assertThat(entry.startTime()).as("startTime").isEqualTo(startTime);
        assertThat(entry.endTime()).as("endTime").isEqualTo(endTime);
        assertThat(entry.label()).as("label").isEqualTo(label);
    }
}
