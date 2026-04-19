package de.vvwt.tm.domain.referee;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable summary of a {@link RefereeAssigner#assignReferees(UUID)} execution.
 *
 * <p>Returned by the service after a successful (or partially successful) run. The human or calling
 * code can inspect counts and warnings to determine if manual intervention is needed.
 *
 * @see RefereeAssigner
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S10.story.md">Story
 *     E03S10 AC15</a>
 */
public final class RefereeAssignmentReport {

    /** Total number of matches in the phase (includes overrides and no-referee cases). */
    private final int totalMatches;

    /** Number of matches that were automatically assigned a referee team. */
    private final int assignedCount;

    /** Number of matches skipped because {@code Match.refereeDescription} was already set. */
    private final int overriddenCount;

    /**
     * Number of matches left without a referee team because no eligible non-playing team was
     * available. These matches require manual organiser intervention.
     */
    private final int noRefereeCount;

    /**
     * Per-team assignment count. Key: team UUID. Value: number of matches that team was assigned to
     * referee. Only teams that received at least one assignment appear in this map.
     */
    private final Map<UUID, Integer> perTeamAssignmentCount;

    /**
     * Human-readable warnings generated during the assignment run. Typically "Lap X: no eligible
     * referee for match Y".
     */
    private final List<String> warnings;

    private RefereeAssignmentReport(Builder builder) {
        this.totalMatches = builder.totalMatches;
        this.assignedCount = builder.assignedCount;
        this.overriddenCount = builder.overriddenCount;
        this.noRefereeCount = builder.noRefereeCount;
        this.perTeamAssignmentCount =
                Collections.unmodifiableMap(new LinkedHashMap<>(builder.perTeamAssignmentCount));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(builder.warnings));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Total matches in the phase. */
    public int getTotalMatches() {
        return totalMatches;
    }

    /** Matches auto-assigned a referee. */
    public int getAssignedCount() {
        return assignedCount;
    }

    /** Matches with existing {@code refereeDescription} override — left unchanged. */
    public int getOverriddenCount() {
        return overriddenCount;
    }

    /** Matches with no eligible referee — left with null {@code refereeTeamId}. */
    public int getNoRefereeCount() {
        return noRefereeCount;
    }

    /** Per-team referee assignment count (team ID → matches refereed). */
    public Map<UUID, Integer> getPerTeamAssignmentCount() {
        return perTeamAssignmentCount;
    }

    /** Human-readable warnings from the assignment run. */
    public List<String> getWarnings() {
        return warnings;
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /** Returns a new {@link Builder} for constructing a report. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for {@link RefereeAssignmentReport}. The Orchestrator (RefereeAssigner)
     * assembles the report incrementally during the algorithm.
     */
    public static final class Builder {

        private int totalMatches;
        private int assignedCount;
        private int overriddenCount;
        private int noRefereeCount;
        private final Map<UUID, Integer> perTeamAssignmentCount = new LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();

        private Builder() {}

        /** Sets the total match count. */
        public Builder totalMatches(int totalMatches) {
            this.totalMatches = totalMatches;
            return this;
        }

        /** Increments the auto-assigned count by 1. */
        public Builder incrementAssigned() {
            this.assignedCount++;
            return this;
        }

        /** Increments the manually-overridden count by the given amount. */
        public Builder addOverridden(int count) {
            this.overriddenCount += count;
            return this;
        }

        /** Increments the no-referee-available count by 1. */
        public Builder incrementNoReferee() {
            this.noRefereeCount++;
            return this;
        }

        /**
         * Records an assignment for the given team.
         *
         * @param teamId the team that was assigned
         */
        public Builder recordAssignment(UUID teamId) {
            perTeamAssignmentCount.merge(teamId, 1, Integer::sum);
            return this;
        }

        /**
         * Returns the current assignment count for a team (0 if not yet assigned).
         *
         * @param teamId the team to query
         * @return current count
         */
        public int getAssignmentCount(UUID teamId) {
            return perTeamAssignmentCount.getOrDefault(teamId, 0);
        }

        /** Adds a warning message. */
        public Builder addWarning(String warning) {
            this.warnings.add(warning);
            return this;
        }

        /** Builds the immutable report. */
        public RefereeAssignmentReport build() {
            return new RefereeAssignmentReport(this);
        }
    }

    @Override
    public String toString() {
        return "RefereeAssignmentReport{"
                + "totalMatches="
                + totalMatches
                + ", assignedCount="
                + assignedCount
                + ", overriddenCount="
                + overriddenCount
                + ", noRefereeCount="
                + noRefereeCount
                + ", warnings="
                + warnings.size()
                + '}';
    }
}
