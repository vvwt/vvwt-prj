package de.vvwt.tm.tournament.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Intra-phase break within a draft section.
 *
 * <p>Positioned after a specific lap number. When the draft is applied, each {@code DraftBreak}
 * becomes a {@link de.vvwt.tm.tournament.PhaseBreak} entity linked to the corresponding Phase.
 *
 * <p>Inventory: E21S01 line 237. Named-interface sub-package placement by E33S04 (DEC-35 retrofit).
 * Legacy {@code de.vvwt.tm.domain.draft.DraftBreak} remains active until E21S13.
 *
 * @see DraftSection
 * @see de.vvwt.tm.tournament.PhaseBreak
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public final class DraftBreak {

    /** 1-based lap number after which this break occurs. Must be ≥ 1 and < totalLaps. */
    private final int afterLapNumber;

    /** Duration of the break in minutes. Must be > 0. */
    private final int durationMinutes;

    /** Optional display label (e.g., "Mittagspause"). May be {@code null}. */
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
