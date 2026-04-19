package de.vvwt.tm.domain.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;

/**
 * Represents one section (future Phase) within a tournament draft configuration.
 *
 * <p>A section carries the parameters for one phase that will be created when the draft is applied.
 * Validation constraints (minimum values) are enforced at the REST layer (Jakarta Validation) and
 * in {@link #validate()} / {@link #validateBreaks(int)}, which are called by the {@code
 * DraftService} before persisting.
 *
 * <p>E08S05: the {@code breaks} field is added to support intra-phase breaks (AC2). Each break
 * occupies a position after a given lap number. On apply, breaks become {@link
 * de.vvwt.tm.domain.PhaseBreak} entities (AC7).
 *
 * <p>Immutable — all fields are set at construction time. Jackson deserializes via the {@link
 * #JsonCreator}-annotated constructor (no no-arg constructor needed).
 *
 * @see DraftConfig
 * @see DraftBreak
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story
 *     E05S06 AC1</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S05.story.md">Story
 *     E08S05 AC2</a>
 */
public final class DraftSection {

    /**
     * 1-indexed ordering number of this section within the draft. Determines the sequenceNumber of
     * the resulting Phase.
     */
    private final int sectionNumber;

    /**
     * How teams are sorted when entering this phase. Valid values: {@code team_number}, {@code
     * placement_group}, {@code group_placement}. For Phase 1 (section 1), {@code placement_group}
     * and {@code group_placement} fall back to {@code team_number} sorting (AC6).
     */
    private final String sortType;

    /** Number of groups to split participating teams into for this phase. Must be ≥ 1. */
    private final int groupCount;

    /**
     * Game mode for this phase. {@code roundrobin} is the only supported mode in V1. Extensible for
     * future modes.
     */
    private final String gameMode;

    /** Pause duration between rounds within the section, in minutes. Must be ≥ 0. */
    private final int lapBreakTimeMinutes;

    /** Pause duration after this section and before the next, in minutes. Must be ≥ 0. */
    private final int sectionBreakTimeMinutes;

    /** Duration of each round (lap) in this section, in minutes. Must be > 0. */
    private final int lapTimeMinutes;

    /**
     * Number of sets per match. Must be ≥ 1. Must be compatible with the tournament's {@link
     * de.vvwt.tm.domain.MatchFormat} (AC11 — validated at apply time).
     */
    private final int setQuantity;

    /**
     * Optional list of intra-phase breaks for this section (AC2 — E08S05). Each break occurs after
     * a specific lap number. May be empty; never {@code null}. Semantic validation (valid position,
     * no duplicates) is performed by {@link #validateBreaks(int)} in the service layer before
     * persisting.
     */
    private final List<DraftBreak> breaks;

    /**
     * Jackson-compatible constructor. All fields are required.
     *
     * @param sectionNumber ordering within the draft (≥ 1)
     * @param sortType how teams enter this phase
     * @param groupCount number of groups (≥ 1)
     * @param gameMode game mode ({@code roundrobin} for V1)
     * @param lapBreakTimeMinutes pause between laps (≥ 0)
     * @param sectionBreakTimeMinutes pause after section (≥ 0)
     * @param lapTimeMinutes lap duration (> 0)
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
     * Validates the section's base field constraints (AC1).
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
     * Validates break semantics against the total lap count for this section (E08S05 AC8, AC9).
     *
     * <p>Rules:
     *
     * <ul>
     *   <li>Each break's {@code afterLapNumber} must be ≥ 1 and strictly less than {@code
     *       totalLaps}
     *   <li>No two breaks may share the same {@code afterLapNumber} (AC9)
     *   <li>Each break's {@code durationMinutes} must be {@literal > 0} (AC5 — validated at REST
     *       layer too)
     * </ul>
     *
     * @param totalLaps the total number of laps in this section (computed from team/group count)
     * @throws IllegalArgumentException if any break violates the constraints
     */
    public void validateBreaks(int totalLaps) {
        Set<Integer> seenPositions = new java.util.HashSet<>();
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
                                + b.getAfterLapNumber()
                                + " (AC9 — breaks at the same lap position are not allowed)");
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
