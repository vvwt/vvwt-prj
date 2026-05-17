// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.draft;

/**
 * Preview result for one section of a draft configuration.
 *
 * <p>Computed by {@link de.vvwt.tm.tournament.DraftService#preview} without creating any database
 * entities.
 *
 * <p>Inventory: E21S01 line 240. Named-interface sub-package placement by E33S04 (DEC-35 retrofit).
 * Legacy {@code de.vvwt.tm.domain.draft.DraftPreviewSection} remains active until E21S13.
 *
 * @see DraftPreviewResult
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
public final class DraftPreviewSection {

    /** 1-indexed phase number (= section's {@code sectionNumber}). */
    private final int phaseNumber;

    /** Number of groups in this phase. */
    private final int groupCount;

    /** Estimated teams per group (integer division of participating teams by groupCount). */
    private final int teamsPerGroup;

    /** Matches per group for round-robin: {@code teamsPerGroup × (teamsPerGroup - 1) / 2}. */
    private final int matchesPerGroup;

    /** Total laps (rounds): {@code teamsPerGroup - 1}. */
    private final int totalLaps;

    /** Total matches across all groups: {@code groupCount × matchesPerGroup}. */
    private final int totalMatches;

    /** Estimated section duration in minutes. */
    private final int estimatedTimeMinutes;

    /**
     * Constructs a preview section.
     *
     * @param phaseNumber phase sequence number
     * @param groupCount number of groups
     * @param teamsPerGroup teams per group
     * @param matchesPerGroup matches per group (round-robin)
     * @param totalLaps total rounds
     * @param totalMatches total matches across all groups
     * @param estimatedTimeMinutes estimated section duration
     */
    public DraftPreviewSection(
            int phaseNumber,
            int groupCount,
            int teamsPerGroup,
            int matchesPerGroup,
            int totalLaps,
            int totalMatches,
            int estimatedTimeMinutes) {
        this.phaseNumber = phaseNumber;
        this.groupCount = groupCount;
        this.teamsPerGroup = teamsPerGroup;
        this.matchesPerGroup = matchesPerGroup;
        this.totalLaps = totalLaps;
        this.totalMatches = totalMatches;
        this.estimatedTimeMinutes = estimatedTimeMinutes;
    }

    public int getPhaseNumber() {
        return phaseNumber;
    }

    public int getGroupCount() {
        return groupCount;
    }

    public int getTeamsPerGroup() {
        return teamsPerGroup;
    }

    public int getMatchesPerGroup() {
        return matchesPerGroup;
    }

    public int getTotalLaps() {
        return totalLaps;
    }

    public int getTotalMatches() {
        return totalMatches;
    }

    public int getEstimatedTimeMinutes() {
        return estimatedTimeMinutes;
    }
}
