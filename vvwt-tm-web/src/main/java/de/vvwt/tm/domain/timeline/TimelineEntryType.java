package de.vvwt.tm.domain.timeline;

/**
 * Classifies each entry in a computed tournament timeline.
 *
 * <p>Used by {@link TimelineEntry} to distinguish match rounds from the various break types.
 * Consumers (Laufzettel renderer, Mannschaftsfoto-Übersicht) filter by type to produce their
 * respective views.
 *
 * <p>Values:
 * <ul>
 *   <li>{@link #MATCH_ROUND}      — an actual competition round; laps have assigned matches</li>
 *   <li>{@link #LAP_BREAK}        — the short gap between consecutive laps within a phase</li>
 *   <li>{@link #INTRA_PHASE_BREAK}— an organiser-configured pause within a phase (e.g. lunch)</li>
 *   <li>{@link #SECTION_BREAK}    — the transition gap between two consecutive phases</li>
 * </ul>
 *
 * @see TimelineEntry
 * @see TimelineCalculationService
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S03.story.md">Story E08S03</a>
 */
public enum TimelineEntryType {

    /** A competition round in which teams play their assigned matches. */
    MATCH_ROUND,

    /**
     * A short break between two consecutive laps within a phase.
     * Omitted from Laufzettel display (implicit gap) but included in the timeline for completeness.
     */
    LAP_BREAK,

    /**
     * An organiser-configured pause that interrupts a phase at a specific lap boundary.
     * Carries an optional display label (e.g., "Mittagspause") in the enclosing {@link TimelineEntry}.
     */
    INTRA_PHASE_BREAK,

    /**
     * The gap between the end of one phase and the start of the next phase.
     * Duration is set by the {@code sectionBreakMinutes} parameter in
     * {@link TimelineCalculationService#calculate}.
     */
    SECTION_BREAK
}
