package de.vvwt.tm.domain.draft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents one section (future Phase) within a tournament draft configuration.
 *
 * <p>A section carries the parameters for one phase that will be created when the draft is applied.
 * Validation constraints (minimum values) are enforced at the REST layer (Jakarta Validation)
 * and in {@link #validate()}, which is called by the {@code DraftService} before persisting.
 *
 * <p>Immutable — all fields are set at construction time. Jackson deserializes via
 * the {@link #JsonCreator}-annotated constructor (no no-arg constructor needed).
 *
 * @see DraftConfig
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06 AC1</a>
 */
public final class DraftSection {

    /**
     * 1-indexed ordering number of this section within the draft.
     * Determines the sequenceNumber of the resulting Phase.
     */
    private final int sectionNumber;

    /**
     * How teams are sorted when entering this phase.
     * Valid values: {@code team_number}, {@code placement_group}, {@code group_placement}.
     * For Phase 1 (section 1), {@code placement_group} and {@code group_placement} fall back
     * to {@code team_number} sorting (AC6).
     */
    private final String sortType;

    /**
     * Number of groups to split participating teams into for this phase.
     * Must be ≥ 1.
     */
    private final int groupCount;

    /**
     * Game mode for this phase. {@code roundrobin} is the only supported mode in V1.
     * Extensible for future modes.
     */
    private final String gameMode;

    /**
     * Pause duration between rounds within the section, in minutes. Must be ≥ 0.
     */
    private final int lapBreakTimeMinutes;

    /**
     * Pause duration after this section and before the next, in minutes. Must be ≥ 0.
     */
    private final int sectionBreakTimeMinutes;

    /**
     * Duration of each round (lap) in this section, in minutes. Must be > 0.
     */
    private final int lapTimeMinutes;

    /**
     * Number of sets per match. Must be ≥ 1. Must be compatible with the tournament's
     * {@link de.vvwt.tm.domain.MatchFormat} (AC11 — validated at apply time).
     */
    private final int setQuantity;

    /**
     * Jackson-compatible constructor. All fields are required.
     *
     * @param sectionNumber          ordering within the draft (≥ 1)
     * @param sortType               how teams enter this phase
     * @param groupCount             number of groups (≥ 1)
     * @param gameMode               game mode ({@code roundrobin} for V1)
     * @param lapBreakTimeMinutes    pause between laps (≥ 0)
     * @param sectionBreakTimeMinutes pause after section (≥ 0)
     * @param lapTimeMinutes         lap duration (> 0)
     * @param setQuantity            sets per match (≥ 1)
     */
    @JsonCreator
    public DraftSection(
            @JsonProperty("sectionNumber")          int sectionNumber,
            @JsonProperty("sortType")               String sortType,
            @JsonProperty("groupCount")             int groupCount,
            @JsonProperty("gameMode")               String gameMode,
            @JsonProperty("lapBreakTimeMinutes")    int lapBreakTimeMinutes,
            @JsonProperty("sectionBreakTimeMinutes") int sectionBreakTimeMinutes,
            @JsonProperty("lapTimeMinutes")         int lapTimeMinutes,
            @JsonProperty("setQuantity")            int setQuantity) {
        this.sectionNumber = sectionNumber;
        this.sortType = sortType;
        this.groupCount = groupCount;
        this.gameMode = gameMode;
        this.lapBreakTimeMinutes = lapBreakTimeMinutes;
        this.sectionBreakTimeMinutes = sectionBreakTimeMinutes;
        this.lapTimeMinutes = lapTimeMinutes;
        this.setQuantity = setQuantity;
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    /**
     * Validates the section's field constraints (AC1).
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
        if (!sortType.equals("team_number") && !sortType.equals("placement_group") && !sortType.equals("group_placement")) {
            throw new IllegalArgumentException(
                    "sortType must be one of: team_number, placement_group, group_placement. Got: " + sortType);
        }
        if (groupCount < 1) {
            throw new IllegalArgumentException("groupCount must be ≥ 1, got: " + groupCount);
        }
        if (gameMode == null || gameMode.isBlank()) {
            throw new IllegalArgumentException("gameMode must not be blank");
        }
        if (lapBreakTimeMinutes < 0) {
            throw new IllegalArgumentException("lapBreakTimeMinutes must be ≥ 0, got: " + lapBreakTimeMinutes);
        }
        if (sectionBreakTimeMinutes < 0) {
            throw new IllegalArgumentException("sectionBreakTimeMinutes must be ≥ 0, got: " + sectionBreakTimeMinutes);
        }
        if (lapTimeMinutes <= 0) {
            throw new IllegalArgumentException("lapTimeMinutes must be > 0, got: " + lapTimeMinutes);
        }
        if (setQuantity < 1) {
            throw new IllegalArgumentException("setQuantity must be ≥ 1, got: " + setQuantity);
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getSectionNumber() { return sectionNumber; }
    public String getSortType() { return sortType; }
    public int getGroupCount() { return groupCount; }
    public String getGameMode() { return gameMode; }
    public int getLapBreakTimeMinutes() { return lapBreakTimeMinutes; }
    public int getSectionBreakTimeMinutes() { return sectionBreakTimeMinutes; }
    public int getLapTimeMinutes() { return lapTimeMinutes; }
    public int getSetQuantity() { return setQuantity; }
}
