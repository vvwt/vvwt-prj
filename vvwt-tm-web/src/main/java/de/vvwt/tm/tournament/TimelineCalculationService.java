package de.vvwt.tm.tournament;

import java.time.LocalTime;
import java.util.List;

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
 * <p><strong>DEC-35 retrofit (E33S07).</strong> Converted from a concrete {@code @Service} class to
 * an interface per DEC-35 § Public package clause 1. The implementation resides at {@code
 * de.vvwt.tm.tournament.internal.DefaultTimelineCalculationService}. Constructor-call semantics
 * (previously documented as valid in the legacy Javadoc) are no longer supported on this type — use
 * {@code DefaultTimelineCalculationService} for direct instantiation in tests.
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
 * tournament} public boundary-API (E21S11). See DEC-21, DEC-22, DEC-30, DEC-35.
 *
 * @see PhaseConfig
 * @see PhaseBreakConfig
 * @see TimelineEntry
 * @see TimelineEntryType
 */
public interface TimelineCalculationService {

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
    List<TimelineEntry> calculate(
            LocalTime startTime, List<PhaseConfig> phases, int sectionBreakMinutes);
}
