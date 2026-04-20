package de.vvwt.tm.tournament;

/**
 * Classifies each entry in a computed tournament timeline.
 *
 * <p>Placed at {@code de.vvwt.tm.tournament.*} (public boundary-API) per Session Brief D-2
 * (2026-04-20, human decision K2=A). Consumed cross-context by {@code print} (Laufzettel timing)
 * and {@code timer} (countdown schedule) via {@code tournament::api}.
 *
 * <p>Used by {@link TimelineEntry} to distinguish match rounds from the various break types.
 * Consumers filter by type to produce their respective views.
 *
 * <p>Values:
 *
 * <ul>
 *   <li>{@link #MATCH_ROUND} — an actual competition round; laps have assigned matches
 *   <li>{@link #LAP_BREAK} — the short gap between consecutive laps within a phase
 *   <li>{@link #INTRA_PHASE_BREAK} — an organiser-configured pause within a phase (e.g. lunch)
 *   <li>{@link #SECTION_BREAK} — the transition gap between two consecutive phases
 * </ul>
 *
 * <p>Inventory rows 334–338 (E21S01): all five timeline classes were {@code uncertain}; resolved by
 * D-2 as {@code tournament} public boundary-API (E21S11). See DEC-21.
 *
 * @see TimelineEntry
 * @see TimelineCalculationService
 */
public enum TimelineEntryType {

    /** A competition round in which teams play their assigned matches. */
    MATCH_ROUND,

    /**
     * A short break between two consecutive laps within a phase. Omitted from Laufzettel display
     * (implicit gap) but included in the timeline for completeness.
     */
    LAP_BREAK,

    /**
     * An organiser-configured pause that interrupts a phase at a specific lap boundary. Carries an
     * optional display label (e.g., "Mittagspause") in the enclosing {@link TimelineEntry}.
     */
    INTRA_PHASE_BREAK,

    /**
     * The gap between the end of one phase and the start of the next phase. Duration is set by the
     * {@code sectionBreakMinutes} parameter in {@link TimelineCalculationService#calculate}.
     */
    SECTION_BREAK
}
