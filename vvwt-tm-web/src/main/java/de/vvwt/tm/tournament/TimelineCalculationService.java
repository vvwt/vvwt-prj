package de.vvwt.tm.tournament;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Stateless, side-effect-free service that computes the full timeline of a tournament.
 *
 * <p><strong>D-2 placement rationale.</strong> Placed at {@code de.vvwt.tm.tournament.*} (public
 * boundary-API) per Session Brief D-2 (2026-04-20, human decision K2=A). Consumed cross-context by
 * {@code print} (Laufzettel timing) and {@code timer} (countdown schedule) via {@code
 * tournament::api}. Timeline is a tournament-owned concept — it describes the tournament's temporal
 * structure (phases, breaks, rounds); print and timer are downstream consumers.
 *
 * <p>Given a tournament start time and an ordered list of phase configurations (lap counts,
 * durations, breaks), this service produces an ordered list of {@link TimelineEntry} objects
 * covering every match round, lap break, intra-phase break, and section break.
 *
 * <p><strong>Spring-wiring decision (E21S11, AC-SPRING-WIRING-DECISION-DOC).</strong> Declared as
 * {@code @Service} (Spring-managed singleton). Rationale: the legacy class was Spring-managed and
 * downstream consumers ({@code LaufzettelAssembler}, {@code TimerDataService}) autowire it;
 * preserving {@code @Service} avoids constructor-call glue code in those consumers. The class
 * remains a pure function — {@code @Service} is a wiring hint, not a behavioural constraint.
 * Callers that prefer constructor-call semantics may do so: {@code new TimelineCalculationService()
 * .calculate(...)} is fully valid.
 *
 * <p><strong>Design constraints (DEC-22, DEC-30).</strong>
 *
 * <ul>
 *   <li>No database access — all input is provided by the caller.
 *   <li>Pure function — identical inputs always yield identical output.
 *   <li>Null start time returns an empty list (draft preview can show structure without times).
 * </ul>
 *
 * <p><strong>Timeline rules.</strong>
 *
 * <ol>
 *   <li>Each match round occupies {@code lapTimeMinutes} starting at the current cursor.
 *   <li>After each lap <em>except the last lap in a phase</em>, a {@link
 *       TimelineEntryType#LAP_BREAK} of {@code lapBreakMinutes} is appended — unless an intra-phase
 *       break replaces it at that lap boundary.
 *   <li>An {@link TimelineEntryType#INTRA_PHASE_BREAK} replaces the trailing lap break at its
 *       {@code afterLapNumber} boundary.
 *   <li>Between consecutive phases a {@link TimelineEntryType#SECTION_BREAK} of {@code
 *       sectionBreakMinutes} is appended.
 *   <li>A phase with {@code lapCount == 0} produces a single zero-duration {@link
 *       TimelineEntryType#MATCH_ROUND} marker.
 * </ol>
 *
 * <p>Inventory row 336 (E21S01): classified {@code uncertain}; resolved by D-2 as {@code
 * tournament} public boundary-API (E21S11). See DEC-21, DEC-22, DEC-30.
 *
 * @see PhaseConfig
 * @see PhaseBreakConfig
 * @see TimelineEntry
 * @see TimelineEntryType
 */
@Service
public class TimelineCalculationService {

    /**
     * Computes the full timeline for a tournament.
     *
     * @param startTime wall-clock start time of the tournament; if {@code null} the method returns
     *     an empty list
     * @param phases ordered list of phase configurations; must not be {@code null}; may be empty
     *     (returns empty list)
     * @param sectionBreakMinutes duration in minutes of the break inserted between consecutive
     *     phases; 0 means phases are contiguous
     * @return immutable, ordered list of {@link TimelineEntry} objects; never {@code null}
     * @throws NullPointerException if {@code phases} is {@code null}
     * @throws IllegalArgumentException if any {@link PhaseBreakConfig#afterLapNumber()} is ≥ the
     *     enclosing phase's {@link PhaseConfig#lapCount()}
     */
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
