package de.vvwt.tm.domain.draft;

/**
 * Preview result for one section of a draft configuration (AC4 — E05S06).
 *
 * <p>Calculated by {@link de.vvwt.tm.domain.DraftService#previewDraft} without creating
 * any database entities. Allows the organizer to review what the draft will produce
 * before committing via apply.
 *
 * @see DraftSection
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S06.story.md">Story E05S06 AC4</a>
 */
public final class DraftPreviewSection {

    /** 1-indexed phase number (equals the section's {@code sectionNumber}). */
    private final int phaseNumber;

    /** Number of groups in this phase. */
    private final int groupCount;

    /**
     * Estimated teams per group (based on participating team count ÷ groupCount).
     * Uses integer division; last group may receive fewer teams if not evenly divisible.
     */
    private final int teamsPerGroup;

    /**
     * Matches per group for round-robin game mode.
     * Formula: {@code teamsPerGroup × (teamsPerGroup - 1) / 2}.
     */
    private final int matchesPerGroup;

    /**
     * Total laps (rounds) in this section for round-robin.
     * Formula: {@code teamsPerGroup - 1}.
     */
    private final int totalLaps;

    /** Total matches across all groups: {@code groupCount × matchesPerGroup}. */
    private final int totalMatches;

    /**
     * Estimated duration of the section in minutes.
     * Formula: {@code totalLaps × lapTimeMinutes + max(0, totalLaps - 1) × lapBreakTimeMinutes + sectionBreakTimeMinutes}.
     */
    private final int estimatedTimeMinutes;

    /**
     * Constructs a preview section.
     *
     * @param phaseNumber         phase sequence number (= sectionNumber)
     * @param groupCount          number of groups
     * @param teamsPerGroup       estimated teams per group
     * @param matchesPerGroup     matches per group (round-robin)
     * @param totalLaps           total rounds
     * @param totalMatches        total matches across all groups
     * @param estimatedTimeMinutes estimated section duration
     */
    public DraftPreviewSection(int phaseNumber, int groupCount, int teamsPerGroup,
                                int matchesPerGroup, int totalLaps, int totalMatches,
                                int estimatedTimeMinutes) {
        this.phaseNumber = phaseNumber;
        this.groupCount = groupCount;
        this.teamsPerGroup = teamsPerGroup;
        this.matchesPerGroup = matchesPerGroup;
        this.totalLaps = totalLaps;
        this.totalMatches = totalMatches;
        this.estimatedTimeMinutes = estimatedTimeMinutes;
    }

    public int getPhaseNumber() { return phaseNumber; }
    public int getGroupCount() { return groupCount; }
    public int getTeamsPerGroup() { return teamsPerGroup; }
    public int getMatchesPerGroup() { return matchesPerGroup; }
    public int getTotalLaps() { return totalLaps; }
    public int getTotalMatches() { return totalMatches; }
    public int getEstimatedTimeMinutes() { return estimatedTimeMinutes; }
}
