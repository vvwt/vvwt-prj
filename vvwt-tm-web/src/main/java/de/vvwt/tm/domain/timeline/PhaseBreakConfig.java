package de.vvwt.tm.domain.timeline;

/**
 * Input value object describing a single intra-phase break for timeline calculation.
 *
 * <p>Passed as part of {@link PhaseConfig#phaseBreaks()} to {@link
 * TimelineCalculationService#calculate}. The service does not read from the database — the caller
 * assembles these records from {@link de.vvwt.tm.domain.PhaseBreak} entities.
 *
 * @param afterLapNumber the 1-based lap number after which this break occurs; must be ≥ 1 and less
 *     than the total lap count of the enclosing phase
 * @param durationMinutes duration of the break in minutes; must be {@literal > 0}
 * @param label optional display label shown on the Laufzettel (e.g., "Mittagspause"); may be {@code
 *     null}
 * @see PhaseConfig
 * @see TimelineCalculationService
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S03.story.md">Story
 *     E08S03</a>
 */
public record PhaseBreakConfig(int afterLapNumber, int durationMinutes, String label) {}
