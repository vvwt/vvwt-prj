package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.PhaseBreakConfig;
import de.vvwt.tm.tournament.PhaseConfig;
import de.vvwt.tm.tournament.TimelineCalculationService;
import de.vvwt.tm.tournament.TimelineEntry;
import de.vvwt.tm.tournament.TimelineEntryType;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link TimelineCalculationService}.
 *
 * <p>Stateless, side-effect-free. Carries {@code @Service("tmTimelineCalculationService")} for
 * Spring auto-wiring (qualifier preserved for symmetry with E33S01-S05 and to permit future
 * explicit-qualifier use without amendment — AC-QUALIFIER-PRESERVED, E33S07).
 *
 * <p>Spring auto-wires this implementation wherever {@link TimelineCalculationService} is injected
 * by type. Direct instantiation ({@code new DefaultTimelineCalculationService()}) is valid for test
 * contexts that prefer constructor-call semantics (3 call sites: TimelineCalculationServiceTest,
 * LaufzettelAssemblerTest, ActivityScheduleAssemblerTest).
 *
 * <p>DEC-35 retrofit (E33S07): extracted from the former concrete {@code @Service} class at the
 * public root. Method bodies are byte-identical to the pre-refactor implementation.
 *
 * @see TimelineCalculationService
 */
// Qualifier avoids ConflictingBeanDefinitionException with legacy
// de.vvwt.tm.domain.timeline.TimelineCalculationService (DEC-21 reconstruction-in-place).
// Remove qualifier at E21S13 cutover once legacy class is deleted.
@Service("tmTimelineCalculationService")
public class DefaultTimelineCalculationService implements TimelineCalculationService {

    /** {@inheritDoc} */
    @Override
    public List<TimelineEntry> calculate(
            LocalTime startTime, List<PhaseConfig> phases, int sectionBreakMinutes) {
        Objects.requireNonNull(phases, "phases must not be null");

        // null start time → return empty list (draft preview can show structure without times)
        if (startTime == null) {
            return Collections.emptyList();
        }
        if (phases.isEmpty()) {
            return Collections.emptyList();
        }

        // AC-SERVICE-INCONSISTENT-INPUT: validate all phaseBreak references before computing
        for (PhaseConfig phase : phases) {
            for (PhaseBreakConfig pb : phase.phaseBreaks()) {
                if (pb.afterLapNumber() >= phase.lapCount()) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "PhaseBreakConfig.afterLapNumber (%d) must be < phase.lapCount"
                                            + " (%d) for phase %d",
                                    pb.afterLapNumber(), phase.lapCount(), phase.phaseNumber()));
                }
            }
        }

        List<TimelineEntry> timeline = new ArrayList<>();
        LocalTime cursor = startTime;

        for (int phaseIndex = 0; phaseIndex < phases.size(); phaseIndex++) {
            PhaseConfig phase = phases.get(phaseIndex);
            boolean isLastPhase = (phaseIndex == phases.size() - 1);

            cursor = appendPhase(timeline, phase, cursor);

            // Insert section break between phases (not after the last phase)
            if (!isLastPhase && sectionBreakMinutes > 0) {
                LocalTime sectionBreakEnd = cursor.plusMinutes(sectionBreakMinutes);
                timeline.add(
                        new TimelineEntry(
                                phase.phaseNumber(),
                                0,
                                TimelineEntryType.SECTION_BREAK,
                                cursor,
                                sectionBreakEnd,
                                null));
                cursor = sectionBreakEnd;
            }
        }

        return Collections.unmodifiableList(timeline);
    }

    /**
     * Appends all timeline entries for a single phase and advances the cursor.
     *
     * @param timeline mutable list to append entries to
     * @param phase phase configuration
     * @param cursor current wall-clock position at the start of the phase
     * @return updated cursor position after the phase ends
     */
    private LocalTime appendPhase(
            List<TimelineEntry> timeline, PhaseConfig phase, LocalTime cursor) {
        // zero-lap phase → single zero-duration marker entry
        if (phase.lapCount() == 0) {
            timeline.add(
                    new TimelineEntry(
                            phase.phaseNumber(),
                            0,
                            TimelineEntryType.MATCH_ROUND,
                            cursor,
                            cursor,
                            null));
            return cursor;
        }

        // Build a lookup from lap number → intra-phase break
        Map<Integer, PhaseBreakConfig> breakByLap =
                phase.phaseBreaks().isEmpty()
                        ? Collections.emptyMap()
                        : phase.phaseBreaks().stream()
                                .collect(
                                        Collectors.toMap(
                                                PhaseBreakConfig::afterLapNumber,
                                                pb -> pb,
                                                (a, b) -> a)); // keep first on duplicate key

        for (int lapNumber = 1; lapNumber <= phase.lapCount(); lapNumber++) {
            boolean isLastLap = (lapNumber == phase.lapCount());

            // Match round entry
            LocalTime lapEnd = cursor.plusMinutes(phase.lapTimeMinutes());
            timeline.add(
                    new TimelineEntry(
                            phase.phaseNumber(),
                            lapNumber,
                            TimelineEntryType.MATCH_ROUND,
                            cursor,
                            lapEnd,
                            null));
            cursor = lapEnd;

            if (!isLastLap) {
                // Check for intra-phase break at this lap boundary
                PhaseBreakConfig phaseBreak = breakByLap.get(lapNumber);
                if (phaseBreak != null) {
                    // INTRA_PHASE_BREAK replaces the lap break at this boundary
                    LocalTime breakEnd = cursor.plusMinutes(phaseBreak.durationMinutes());
                    timeline.add(
                            new TimelineEntry(
                                    phase.phaseNumber(),
                                    0,
                                    TimelineEntryType.INTRA_PHASE_BREAK,
                                    cursor,
                                    breakEnd,
                                    phaseBreak.label()));
                    cursor = breakEnd;
                } else if (phase.lapBreakMinutes() > 0) {
                    // Standard lap break between consecutive laps
                    LocalTime breakEnd = cursor.plusMinutes(phase.lapBreakMinutes());
                    timeline.add(
                            new TimelineEntry(
                                    phase.phaseNumber(),
                                    0,
                                    TimelineEntryType.LAP_BREAK,
                                    cursor,
                                    breakEnd,
                                    null));
                    cursor = breakEnd;
                }
                // No break appended after the last lap — loop ends
            }
        }

        return cursor;
    }
}
