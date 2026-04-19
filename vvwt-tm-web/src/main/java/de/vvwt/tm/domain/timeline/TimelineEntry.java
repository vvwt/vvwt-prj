package de.vvwt.tm.domain.timeline;

import java.time.LocalTime;

/**
 * A single entry in the computed tournament timeline.
 *
 * <p>The {@link TimelineCalculationService} returns an ordered, immutable list of these records.
 * Each entry represents either a match round or one of the break types defined by {@link
 * TimelineEntryType}.
 *
 * <p>Field semantics:
 *
 * <ul>
 *   <li>{@link #phaseNumber} — 1-based phase index within the tournament
 *   <li>{@link #lapNumber} — 1-based lap index within the phase; 0 for break entries and for
 *       zero-lap phase markers
 *   <li>{@link #type} — classifies the entry; drives display logic in consumers
 *   <li>{@link #startTime} — wall-clock start; derived from the tournament start time
 *   <li>{@link #endTime} — wall-clock end; {@code startTime + duration}
 *   <li>{@link #label} — non-null only for {@link TimelineEntryType#INTRA_PHASE_BREAK} entries that
 *       carry an organiser-entered label
 * </ul>
 *
 * <p>Given identical inputs the list is always identical (AC10 — deterministic output).
 *
 * @param phaseNumber 1-based phase number
 * @param lapNumber 1-based lap number; 0 for break entries and zero-lap phase markers
 * @param type entry type — never {@code null}
 * @param startTime wall-clock start time — never {@code null}
 * @param endTime wall-clock end time — never {@code null}; equals {@code startTime} for
 *     zero-duration entries (zero-lap phase marker)
 * @param label optional display label; non-null only for INTRA_PHASE_BREAK entries that have an
 *     organiser label
 * @see TimelineEntryType
 * @see TimelineCalculationService
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S03.story.md">Story
 *     E08S03</a>
 */
public record TimelineEntry(
        int phaseNumber,
        int lapNumber,
        TimelineEntryType type,
        LocalTime startTime,
        LocalTime endTime,
        String label) {}
