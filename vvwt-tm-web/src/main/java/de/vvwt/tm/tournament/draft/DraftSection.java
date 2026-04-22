package de.vvwt.tm.tournament.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One section (future Phase) within a tournament draft configuration.
 *
 * <p>Carries the parameters for one phase that will be created when the draft is applied. Immutable
 * — all fields set at construction. Jackson deserializes via {@link JsonCreator}-annotated
 * constructor.
 *
 * <p>Inventory: E21S01 line 241. Named-interface sub-package placement by E33S04 (DEC-35 retrofit).
 * Legacy {@code de.vvwt.tm.domain.draft.DraftSection} remains active until E21S13.
 *
 * @see DraftConfig
 * @see DraftBreak
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public final class DraftSection {

    /** 1-indexed ordering within the draft. Determines the Phase sequenceNumber. */
    private final int sectionNumber;

    /**
     * Team-sorting strategy entering this phase. Valid values: {@code team_number}, {@code
     * placement_group}, {@code group_placement}.
     */
    private final String sortType;

    /** Number of groups to split participating teams into. Must be ≥ 1. */
    private final int groupCount;

    /** Game mode for this phase. {@code roundrobin} is the only V1-supported mode. */
    private final String gameMode;

    /** Pause between rounds within the section, in minutes. Must be ≥ 0. */
    private final int lapBreakTimeMinutes;

    /** Pause after this section and before the next, in minutes. Must be ≥ 0. */
    private final int sectionBreakTimeMinutes;

    /** Duration of each round (lap) in minutes. Must be > 0. */
    private final int lapTimeMinutes;

    /** Sets per match. Must be ≥ 1. */
    private final int setQuantity;

    /** Optional intra-phase breaks. May be empty; never {@code null}. */
    private final List<DraftBreak> breaks;

    /**
     * Jackson-compatible constructor.
     *
     * @param sectionNumber ordering within the draft (≥ 1)
     * @param sortType team-entry sort strategy
     * @param groupCount number of groups (≥ 1)
     * @param gameMode game mode ({@code roundrobin} for V1)
     * @param lapBreakTimeMinutes pause between laps (≥ 0)
     * @param sectionBreakTimeMinutes pause after section (≥ 0)
     * @param lapTimeMinutes lap duration in minutes (> 0)
     * @param setQuantity sets per match (≥ 1)
     * @param breaks optional intra-phase breaks; {@code null} treated as empty
     */
    @JsonCreator
    public DraftSection(
            @JsonProperty("sectionNumber") int sectionNumber,
            @JsonProperty("sortType") String sortType,
            @JsonProperty("groupCount") int groupCount,
            @JsonProperty("gameMode") String gameMode,
            @JsonProperty("lapBreakTimeMinutes") int lapBreakTimeMinutes,
            @JsonProperty("sectionBreakTimeMinutes") int sectionBreakTimeMinutes,
            @JsonProperty("lapTimeMinutes") int lapTimeMinutes,
            @JsonProperty("setQuantity") int setQuantity,
            @JsonProperty("breaks") List<DraftBreak> breaks) {
        this.sectionNumber = sectionNumber;
        this.sortType = sortType;
        this.groupCount = groupCount;
        this.gameMode = gameMode;
        this.lapBreakTimeMinutes = lapBreakTimeMinutes;
        this.sectionBreakTimeMinutes = sectionBreakTimeMinutes;
        this.lapTimeMinutes = lapTimeMinutes;
        this.setQuantity = setQuantity;
        this.breaks = breaks == null ? List.of() : List.copyOf(breaks);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates base field constraints.
     *
     * @throws IllegalArgumentException if any field violates its constraint
     */
    public void validate() {
        if (sectionNumber < 1) {
            throw new IllegalArgumentException("sectionNumber must be ≥ 1, got: " + sectionNumber);
        }
        if (sortType == null || sortType.isBlank()) {
            throw new IllegalArgumentException("sortType must not be blank");
        }
        if (!sortType.equals("team_number")
                && !sortType.equals("placement_group")
                && !sortType.equals("group_placement")) {
            throw new IllegalArgumentException(
                    "sortType must be one of: team_number, placement_group, group_placement. Got: "
                            + sortType);
        }
        if (groupCount < 1) {
            throw new IllegalArgumentException("groupCount must be ≥ 1, got: " + groupCount);
        }
        if (gameMode == null || gameMode.isBlank()) {
            throw new IllegalArgumentException("gameMode must not be blank");
        }
        if (lapBreakTimeMinutes < 0) {
            throw new IllegalArgumentException(
                    "lapBreakTimeMinutes must be ≥ 0, got: " + lapBreakTimeMinutes);
        }
        if (sectionBreakTimeMinutes < 0) {
            throw new IllegalArgumentException(
                    "sectionBreakTimeMinutes must be ≥ 0, got: " + sectionBreakTimeMinutes);
        }
        if (lapTimeMinutes <= 0) {
            throw new IllegalArgumentException(
                    "lapTimeMinutes must be > 0, got: " + lapTimeMinutes);
        }
        if (setQuantity < 1) {
            throw new IllegalArgumentException("setQuantity must be ≥ 1, got: " + setQuantity);
        }
    }

    /**
     * Validates break semantics against the total lap count.
     *
     * @param totalLaps the total number of laps for this section
     * @throws IllegalArgumentException if any break violates the constraints
     */
    public void validateBreaks(int totalLaps) {
        Set<Integer> seenPositions = new HashSet<>();
        for (DraftBreak b : breaks) {
            if (b.getDurationMinutes() <= 0) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": break durationMinutes must be > 0, got: "
                                + b.getDurationMinutes());
            }
            if (b.getAfterLapNumber() < 1) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": break afterLapNumber must be ≥ 1, got: "
                                + b.getAfterLapNumber());
            }
            if (b.getAfterLapNumber() >= totalLaps) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": break afterLapNumber "
                                + b.getAfterLapNumber()
                                + " is out of range — must be < total laps ("
                                + totalLaps
                                + ")");
            }
            if (!seenPositions.add(b.getAfterLapNumber())) {
                throw new IllegalArgumentException(
                        "Section "
                                + sectionNumber
                                + ": duplicate break at afterLapNumber "
                                + b.getAfterLapNumber());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getSectionNumber() {
        return sectionNumber;
    }

    public String getSortType() {
        return sortType;
    }

    public int getGroupCount() {
        return groupCount;
    }

    public String getGameMode() {
        return gameMode;
    }

    public int getLapBreakTimeMinutes() {
        return lapBreakTimeMinutes;
    }

    public int getSectionBreakTimeMinutes() {
        return sectionBreakTimeMinutes;
    }

    public int getLapTimeMinutes() {
        return lapTimeMinutes;
    }

    public int getSetQuantity() {
        return setQuantity;
    }

    public List<DraftBreak> getBreaks() {
        return breaks;
    }
}
