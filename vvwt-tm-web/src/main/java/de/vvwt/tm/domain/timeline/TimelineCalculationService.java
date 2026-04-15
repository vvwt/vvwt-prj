package de.vvwt.tm.domain.timeline;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stateless, side-effect-free service that computes the full timeline of a tournament.
 *
 * <p>Given a tournament start time and an ordered list of phase configurations (lap counts,
 * durations, breaks), this service produces an ordered list of {@link TimelineEntry} objects
 * covering every match round, lap break, intra-phase break, and section break.
 *
 * <h2>Design constraints (E08S03)</h2>
 * <ul>
 *   <li>No database access — all input is provided by the caller (AC9).</li>
 *   <li>Pure function — identical inputs always yield identical output (AC10).</li>
 *   <li>Spring bean — declared as {@code @Service} for injection by consumers (AC1).</li>
 *   <li>Null start time returns an empty list (AC7 — draft preview can show structure without times).</li>
 * </ul>
 *
 * <h2>Timeline rules (AC3–AC6)</h2>
 * <ol>
 *   <li>Each match round occupies {@code lapTimeMinutes} starting at the current cursor.</li>
 *   <li>After each lap <em>except the last lap in a phase</em>, a {@link TimelineEntryType#LAP_BREAK}
 *       of {@code lapBreakMinutes} is appended — unless an intra-phase break replaces it at that
 *       lap boundary.</li>
 *   <li>An {@link TimelineEntryType#INTRA_PHASE_BREAK} replaces the trailing lap break at its
 *       {@code afterLapNumber} boundary.</li>
 *   <li>Between consecutive phases a {@link TimelineEntryType#SECTION_BREAK} of
 *       {@code sectionBreakMinutes} is appended.</li>
 *   <li>A phase with {@code lapCount == 0} produces a single zero-duration
 *       {@link TimelineEntryType#MATCH_ROUND} marker (AC7).</li>
 * </ol>
 *
 * @see PhaseConfig
 * @see PhaseBreakConfig
 * @see TimelineEntry
 * @see TimelineEntryType
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S03.story.md">Story E08S03</a>
 */
@Service
public class TimelineCalculationService {

    /**
     * Computes the full timeline for a tournament.
     *
     * @param startTime          wall-clock start time of the tournament; if {@code null} the
     *                           method returns an empty list (AC7)
     * @param phases             ordered list of phase configurations; must not be {@code null};
     *                           may be empty (returns empty list)
     * @param sectionBreakMinutes duration in minutes of the break inserted between consecutive
     *                            phases; 0 means phases are contiguous
     * @return immutable, ordered list of {@link TimelineEntry} objects; never {@code null}
     */
    public List<TimelineEntry> calculate(LocalTime startTime,
                                         List<PhaseConfig> phases,
                                         int sectionBreakMinutes) {
        // AC7: null start time → return empty list
        if (startTime == null) {
            return Collections.emptyList();
        }
        if (phases == null || phases.isEmpty()) {
            return Collections.emptyList();
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
                timeline.add(new TimelineEntry(
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
     * @param phase    phase configuration
     * @param cursor   current wall-clock position at the start of the phase
     * @return updated cursor position after the phase ends
     */
    private LocalTime appendPhase(List<TimelineEntry> timeline,
                                   PhaseConfig phase,
                                   LocalTime cursor) {
        // AC7: zero-lap phase → single zero-duration marker entry
        if (phase.lapCount() == 0) {
            timeline.add(new TimelineEntry(
                    phase.phaseNumber(),
                    0,
                    TimelineEntryType.MATCH_ROUND,
                    cursor,
                    cursor,
                    null));
            return cursor;
        }

        // Build a lookup from lap number → intra-phase break (AC4)
        Map<Integer, PhaseBreakConfig> breakByLap = phase.phaseBreaks() == null
                ? Collections.emptyMap()
                : phase.phaseBreaks().stream()
                        .collect(Collectors.toMap(PhaseBreakConfig::afterLapNumber,
                                                  b -> b,
                                                  (a, b) -> a));  // keep first on duplicate key

        for (int lapNumber = 1; lapNumber <= phase.lapCount(); lapNumber++) {
            boolean isLastLap = (lapNumber == phase.lapCount());

            // Match round entry
            LocalTime lapEnd = cursor.plusMinutes(phase.lapTimeMinutes());
            timeline.add(new TimelineEntry(
                    phase.phaseNumber(),
                    lapNumber,
                    TimelineEntryType.MATCH_ROUND,
                    cursor,
                    lapEnd,
                    null));
            cursor = lapEnd;

            if (!isLastLap) {
                // Check for intra-phase break at this lap boundary (AC4)
                PhaseBreakConfig phaseBreak = breakByLap.get(lapNumber);
                if (phaseBreak != null) {
                    // INTRA_PHASE_BREAK replaces the lap break at this boundary
                    LocalTime breakEnd = cursor.plusMinutes(phaseBreak.durationMinutes());
                    timeline.add(new TimelineEntry(
                            phase.phaseNumber(),
                            0,
                            TimelineEntryType.INTRA_PHASE_BREAK,
                            cursor,
                            breakEnd,
                            phaseBreak.label()));
                    cursor = breakEnd;
                } else if (phase.lapBreakMinutes() > 0) {
                    // Standard lap break between consecutive laps (AC3)
                    LocalTime breakEnd = cursor.plusMinutes(phase.lapBreakMinutes());
                    timeline.add(new TimelineEntry(
                            phase.phaseNumber(),
                            0,
                            TimelineEntryType.LAP_BREAK,
                            cursor,
                            breakEnd,
                            null));
                    cursor = breakEnd;
                }
                // AC6 compliance: no break appended after the last lap — loop ends here for isLastLap
            }
            // For the last lap, no trailing break is added (AC6)
        }

        return cursor;
    }
}
