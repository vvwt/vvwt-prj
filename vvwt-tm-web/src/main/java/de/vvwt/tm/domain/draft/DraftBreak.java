package de.vvwt.tm.domain.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents an intra-phase break within a draft section.
 *
 * <p>A break is positioned after a specific lap number within the section. When the draft is
 * applied, each {@code DraftBreak} becomes a {@link de.vvwt.tm.domain.PhaseBreak} entity linked to
 * the corresponding Phase (E08S05 AC7).
 *
 * <p>Validation constraints:
 *
 * <ul>
 *   <li>{@code afterLapNumber} must be ≥ 1 and strictly less than the section's total laps
 *   <li>{@code durationMinutes} must be {@literal > 0}
 *   <li>{@code label} is optional; may be {@code null} or blank
 * </ul>
 *
 * <p>Constraint enforcement: REST-layer validation via Jakarta Validation on {@link
 * de.vvwt.tm.infrastructure.web.dto.DraftBreakRequest}; semantic constraints (valid lap number, no
 * duplicate positions) are validated by {@link DraftSection#validateBreaks(int)} called from the
 * service layer.
 *
 * @see DraftSection
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story
 *     E08S05 AC2, AC7, AC8, AC9</a>
 */
public final class DraftBreak {

    /**
     * The 1-based lap number after which this break occurs. Must be ≥ 1 and strictly less than the
     * section's total lap count.
     */
    private final int afterLapNumber;

    /** Duration of the break in minutes. Must be {@literal > 0}. */
    private final int durationMinutes;

    /**
     * Optional display label shown on the Laufzettel (e.g., "Mittagspause"). May be {@code null} or
     * blank.
     */
    private final String label;

    /**
     * Jackson-compatible constructor.
     *
     * @param afterLapNumber the 1-based lap number after which this break occurs (≥ 1)
     * @param durationMinutes the break duration in minutes ({@literal > 0})
     * @param label optional display label; may be {@code null}
     */
    @JsonCreator
    public DraftBreak(
            @JsonProperty("afterLapNumber") int afterLapNumber,
            @JsonProperty("durationMinutes") int durationMinutes,
            @JsonProperty("label") String label) {
        this.afterLapNumber = afterLapNumber;
        this.durationMinutes = durationMinutes;
        this.label = label;
    }

    public int getAfterLapNumber() {
        return afterLapNumber;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getLabel() {
        return label;
    }
}
