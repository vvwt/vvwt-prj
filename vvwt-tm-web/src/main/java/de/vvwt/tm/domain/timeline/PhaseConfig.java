package de.vvwt.tm.domain.timeline;

import java.util.List;

/**
 * Input value object describing one phase's configuration for timeline calculation.
 *
 * <p>Passed in an ordered list to {@link TimelineCalculationService#calculate}.
 * The service is stateless and has no database access — the caller constructs these records
 * from {@link de.vvwt.tm.domain.Phase} and {@link de.vvwt.tm.domain.PhaseBreak} entities.
 *
 * <p>Phase order within the list determines the output timeline order: phase at index 0 is
 * the first phase, phase at index 1 follows after a section break, and so on.
 *
 * @param phaseNumber      1-based sequential number for this phase, used to populate
 *                         {@link TimelineEntry#phaseNumber()} on all generated entries
 * @param lapCount         number of laps (match rounds) in this phase; a value of 0 produces
 *                         a single marker entry with {@link TimelineEntryType#MATCH_ROUND}
 *                         and zero duration (AC7)
 * @param lapTimeMinutes   duration of each match round in minutes; must be {@literal > 0}
 *                         when {@code lapCount > 0}
 * @param lapBreakMinutes  duration of the short break between consecutive laps in minutes;
 *                         0 means no lap break is inserted
 * @param phaseBreaks      ordered list of intra-phase breaks; may be empty but never {@code null}
 *
 * @see PhaseBreakConfig
 * @see TimelineCalculationService
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S03.story.md">Story E08S03</a>
 */
public record PhaseConfig(
        int phaseNumber,
        int lapCount,
        int lapTimeMinutes,
        int lapBreakMinutes,
        List<PhaseBreakConfig> phaseBreaks
) {
}
