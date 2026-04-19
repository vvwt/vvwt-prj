package de.vvwt.tm.domain.timer;

/**
 * Break type for timer schedule entries, distinguishing whether the break triggers pause music.
 *
 * <p>Maps from {@link de.vvwt.tm.domain.timeline.TimelineEntryType} as follows (AC3 — E11S02):
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.domain.timeline.TimelineEntryType#LAP_BREAK} → {@link #REGULAR} —
 *       inter-round break; triggers pause music (D-8)
 *   <li>{@link de.vvwt.tm.domain.timeline.TimelineEntryType#INTRA_PHASE_BREAK} → {@link
 *       #ADDITIONAL} — organiser-configured phase pause; does NOT trigger pause music
 *   <li>{@link de.vvwt.tm.domain.timeline.TimelineEntryType#SECTION_BREAK} → {@link #ADDITIONAL} —
 *       between-phase gap; does NOT trigger pause music
 * </ul>
 *
 * @see TimerDataService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story
 *     E11S02</a>
 */
public enum TimerBreakType {

    /** A regular inter-round break ({@code LAP_BREAK}) that triggers pause music (D-8). */
    REGULAR,

    /**
     * An additional break — either an intra-phase organiser break or a section break between
     * phases. Does NOT trigger pause music.
     */
    ADDITIONAL
}
