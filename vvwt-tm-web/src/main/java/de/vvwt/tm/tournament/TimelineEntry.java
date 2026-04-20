package de.vvwt.tm.tournament;

import java.time.LocalTime;
import java.util.Objects;

/**
 * A single entry in the computed tournament timeline.
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.*} (public boundary-API) per Session Brief D-2
 * (2026-04-20, human decision K2=A). Consumed cross-context by {@code print} (Laufzettel timing)
 * and {@code timer} (countdown schedule) via {@code tournament::api}.
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
 *   <li>{@link #type} — classifies the entry; drives display logic in consumers; never {@code null}
 *   <li>{@link #startTime} — wall-clock start; derived from the tournament start time; never {@code
 *       null}
 *   <li>{@link #endTime} — wall-clock end; {@code startTime + duration}; never {@code null}
 *   <li>{@link #label} — non-null only for {@link TimelineEntryType#INTRA_PHASE_BREAK} entries that
 *       carry an organiser-entered label
 * </ul>
 *
 * <p>Given identical inputs the list is always identical (deterministic output).
 *
 * <p>Inventory row 337 (E21S01): classified {@code uncertain}; resolved by D-2 as {@code
 * tournament} public boundary-API (E21S11). See DEC-21, DEC-22.
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
 */
public record TimelineEntry(
        int phaseNumber,
        int lapNumber,
        TimelineEntryType type,
        LocalTime startTime,
        LocalTime endTime,
        String label) {

    /**
     * Compact constructor validating non-null preconditions for mandatory fields.
     *
     * @throws NullPointerException if {@code type}, {@code startTime}, or {@code endTime} is {@code
     *     null}
     */
    public TimelineEntry {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(endTime, "endTime must not be null");
    }
}
