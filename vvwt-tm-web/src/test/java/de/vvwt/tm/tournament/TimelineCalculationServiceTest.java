package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.internal.DefaultTimelineCalculationService;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for TimelineCalculationService (E21S11).
 *
 * <p>Written RED (against non-existent production class) before implementation.
 *
 * <p>Property-based tests cover invariants per AC-TDD-TimelineCalculationService-PROPERTY-BASED:
 *
 * <ol>
 *   <li>Monotone time ordering
 *   <li>Total duration matches inputs
 *   <li>Break count matches configured breaks
 *   <li>Empty-input contract
 * </ol>
 *
 * <p>Parameterised example test covers AC-LEGACY-OUTPUT-PARITY. Error-handling tests cover
 * AC-SERVICE-NULL-INPUT and AC-SERVICE-INCONSISTENT-INPUT.
 *
 * @see TimelineCalculationService
 */
class TimelineCalculationServiceTest {

    private final TimelineCalculationService service = new DefaultTimelineCalculationService();
    private static final LocalTime START = LocalTime.of(9, 0);

    // -------------------------------------------------------------------------
    // AC: Property 4 — Empty-input contract
    // -------------------------------------------------------------------------

    @Test
    void calculate_nullStartTime_returnsEmptyList() {
        List<PhaseConfig> phases = List.of(new PhaseConfig(1, 3, 20, 2, Collections.emptyList()));
        List<TimelineEntry> result = service.calculate(null, phases, 15);
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void calculate_emptyPhases_returnsEmptyList() {
        List<TimelineEntry> result = service.calculate(START, Collections.emptyList(), 0);
        assertThat(result).isNotNull().isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-SERVICE-NULL-INPUT
    // -------------------------------------------------------------------------

    @Test
    void calculate_nullPhases_throwsException() {
        assertThatThrownBy(() -> service.calculate(START, null, 0))
                .isInstanceOf(
                        RuntimeException.class); // NullPointerException or IllegalArgumentException
    }

    // -------------------------------------------------------------------------
    // AC-SERVICE-INCONSISTENT-INPUT
    // -------------------------------------------------------------------------

    @Test
    void calculate_phaseBreakAfterLapNumberExceedsLapCount_throwsIllegalArgumentException() {
        // phase has 2 laps but break configured after lap 3 — out of bounds
        PhaseBreakConfig invalidBreak = new PhaseBreakConfig(3, 10, null);
        PhaseConfig phase = new PhaseConfig(1, 2, 20, 0, List.of(invalidBreak));

        assertThatThrownBy(() -> service.calculate(START, List.of(phase), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("afterLapNumber");
    }

    // -------------------------------------------------------------------------
    // AC-LEGACY-OUTPUT-PARITY
    // Fixture: two phases based on legacy LaufzettelAssembler usage pattern
    //   Phase 1: lapCount=3, lapTimeMinutes=20, lapBreakMinutes=2, noBreaks
    //   Phase 2: lapCount=2, lapTimeMinutes=20, lapBreakMinutes=2, noBreaks
    //   sectionBreakMinutes=15
    // Expected entries:
    //   Phase 1: 3 MATCH_ROUND + 2 LAP_BREAK = 5 entries
    //   SECTION_BREAK = 1 entry
    //   Phase 2: 2 MATCH_ROUND + 1 LAP_BREAK = 3 entries
    //   Total = 9 entries
    // Expected total duration: 3×20 + 2×2 + 15 + 2×20 + 1×2 = 121 minutes
    // -------------------------------------------------------------------------

    @Test
    void calculate_legacyFixture_entryCountAndTotalDurationMatch() {
        List<PhaseConfig> phases =
                List.of(
                        new PhaseConfig(1, 3, 20, 2, Collections.emptyList()),
                        new PhaseConfig(2, 2, 20, 2, Collections.emptyList()));

        List<TimelineEntry> result = service.calculate(START, phases, 15);

        assertThat(result).hasSize(9);

        // Total duration: first entry starts at 09:00; last entry ends at 09:00 + 121 minutes
        TimelineEntry first = result.get(0);
        TimelineEntry last = result.get(result.size() - 1);
        long totalMinutes =
                java.time.Duration.between(first.startTime(), last.endTime()).toMinutes();
        assertThat(totalMinutes).isEqualTo(121);
    }

    // -------------------------------------------------------------------------
    // Property 1: Monotone time ordering
    // -------------------------------------------------------------------------

    @Property(tries = 30)
    void property_monotoneTimeOrdering(@ForAll("validSinglePhaseConfigs") PhaseConfig phase) {
        List<TimelineEntry> result = service.calculate(START, List.of(phase), 0);
        if (result.size() < 2) {
            return;
        }
        for (int i = 0; i < result.size() - 1; i++) {
            assertThat(result.get(i).startTime())
                    .as("entry[%d].startTime <= entry[%d].startTime", i, i + 1)
                    .isBeforeOrEqualTo(result.get(i + 1).startTime());
            assertThat(result.get(i).endTime())
                    .as("entry[%d].endTime <= entry[%d].startTime", i, i + 1)
                    .isBeforeOrEqualTo(result.get(i + 1).startTime());
        }
    }

    // -------------------------------------------------------------------------
    // Property 2: Total duration = sum(phase durations) + sum(break durations)
    // -------------------------------------------------------------------------

    @Property(tries = 30)
    void property_totalDurationMatchesInputs(@ForAll("validSinglePhaseConfigs") PhaseConfig phase) {
        List<TimelineEntry> result = service.calculate(START, List.of(phase), 0);
        if (result.isEmpty()) {
            return;
        }

        // Compute expected total duration from inputs
        long expectedMinutes = (long) phase.lapCount() * phase.lapTimeMinutes();
        // Lap breaks: (lapCount - 1) lap breaks, unless replaced by intra-phase breaks
        // Count non-replaced lap-break slots
        int lapBreakCount = Math.max(0, phase.lapCount() - 1 - phase.phaseBreaks().size());
        expectedMinutes += (long) lapBreakCount * phase.lapBreakMinutes();
        // Intra-phase breaks
        for (PhaseBreakConfig pb : phase.phaseBreaks()) {
            expectedMinutes += pb.durationMinutes();
        }

        TimelineEntry first = result.get(0);
        TimelineEntry last = result.get(result.size() - 1);
        long actualMinutes =
                java.time.Duration.between(first.startTime(), last.endTime()).toMinutes();

        assertThat(actualMinutes)
                .as("total timeline duration should match sum of configured durations")
                .isEqualTo(expectedMinutes);
    }

    // -------------------------------------------------------------------------
    // Property 3: Break count matches configured breaks
    // -------------------------------------------------------------------------

    @Property(tries = 30)
    void property_intraPhaseBreakCountMatchesConfiguration(
            @ForAll("validSinglePhaseConfigs") PhaseConfig phase) {
        List<TimelineEntry> result = service.calculate(START, List.of(phase), 0);
        long actualIntraPhaseBreaks =
                result.stream()
                        .filter(e -> e.type() == TimelineEntryType.INTRA_PHASE_BREAK)
                        .count();
        // Only breaks whose afterLapNumber < lapCount are actually inserted
        long expectedIntraPhaseBreaks =
                phase.phaseBreaks().stream()
                        .filter(pb -> pb.afterLapNumber() < phase.lapCount())
                        .count();
        assertThat(actualIntraPhaseBreaks).isEqualTo(expectedIntraPhaseBreaks);
    }

    // -------------------------------------------------------------------------
    // Property 4: Result is never null
    // -------------------------------------------------------------------------

    @Property(tries = 30)
    void property_resultIsNeverNull(@ForAll("validSinglePhaseConfigs") PhaseConfig phase) {
        List<TimelineEntry> result = service.calculate(START, List.of(phase), 0);
        assertThat(result).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Arbitraries
    // -------------------------------------------------------------------------

    @Provide
    Arbitrary<PhaseConfig> validSinglePhaseConfigs() {
        Arbitrary<Integer> lapCounts = Arbitraries.integers().between(1, 5);
        Arbitrary<Integer> lapTimes = Arbitraries.integers().between(5, 30);
        Arbitrary<Integer> lapBreaks = Arbitraries.integers().between(0, 5);

        return lapCounts.flatMap(
                lapCount ->
                        lapTimes.flatMap(
                                lapTime ->
                                        lapBreaks.map(
                                                lapBreak ->
                                                        new PhaseConfig(
                                                                1,
                                                                lapCount,
                                                                lapTime,
                                                                lapBreak,
                                                                Collections.emptyList()))));
    }
}
