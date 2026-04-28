package de.vvwt.tm.timer;

/**
 * Break type for timer schedule entries, distinguishing whether the break triggers pause music.
 *
 * <p>Maps from {@link de.vvwt.tm.tournament.TimelineEntryType} as follows (AC3 — E11S02 / E26S01):
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.tournament.TimelineEntryType#LAP_BREAK} → {@link #REGULAR} — inter-round
 *       break; triggers pause music
 *   <li>{@link de.vvwt.tm.tournament.TimelineEntryType#INTRA_PHASE_BREAK} → {@link #ADDITIONAL} —
 *       organiser-configured phase pause; does NOT trigger pause music
 *   <li>{@link de.vvwt.tm.tournament.TimelineEntryType#SECTION_BREAK} → {@link #ADDITIONAL} —
 *       between-phase gap; does NOT trigger pause music
 * </ul>
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.TimerBreakType} per DEC-21 module layout.
 * Reconstruction of legacy {@code de.vvwt.tm.domain.timer.TimerBreakType} via D-7 Option γ
 * (E26S01). Two public values preserved verbatim per C-3 signature-preservation.
 *
 * @see TimerDataService
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public enum TimerBreakType {

    /** A regular inter-round break ({@code LAP_BREAK}) that triggers pause music. */
    REGULAR,

    /**
     * An additional break — either an intra-phase organiser break or a section break between
     * phases. Does NOT trigger pause music.
     */
    ADDITIONAL
}
