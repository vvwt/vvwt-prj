package de.vvwt.tm.tournament.internal.referee;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable summary of a {@link RefereeAssigner#assignReferees(java.util.UUID)} execution (E21S08
 * reconstruction).
 *
 * <p>Reconstruction-in-place counterpart of {@code
 * de.vvwt.tm.domain.referee.RefereeAssignmentReport} (inventory row 277). Lives at {@code
 * de.vvwt.tm.tournament.internal.referee} per AC-PACKAGE-D8.
 *
 * <p>Returned by the service after a successful (or partially successful) run. Callers inspect
 * counts and warnings to determine if manual intervention is needed.
 *
 * <p>Legacy {@code de.vvwt.tm.domain.referee.RefereeAssignmentReport} remains untouched until
 * E21S13 atomic cutover per DEC-32.
 *
 * @see RefereeAssigner
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (reconstruction-in-place)</a>
 * @see <a href="E21S08">E21S08 — inventory row 277</a>
 */
public final class RefereeAssignmentReport {

    private final int totalMatches;
    private final int assignedCount;
    private final int overriddenCount;
    private final int noRefereeCount;
    private final Map<UUID, Integer> perTeamAssignmentCount;
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

    /** Total matches in the phase. */
    public int getTotalMatches() {
        return totalMatches;
    }

    /** Matches auto-assigned a referee. */
    public int getAssignedCount() {
        return assignedCount;
    }

    /** Matches with existing {@code refereeDescription} — left unchanged. */
    public int getOverriddenCount() {
        return overriddenCount;
    }

    /** Matches left without a referee because no eligible non-playing team was available. */
    public int getNoRefereeCount() {
        return noRefereeCount;
    }

    /** Per-team referee assignment count (team UUID → number of matches refereed). */
    public Map<UUID, Integer> getPerTeamAssignmentCount() {
        return perTeamAssignmentCount;
    }

    /** Human-readable warnings generated during the assignment run. */
    public List<String> getWarnings() {
        return warnings;
    }

    /** Returns a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
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

    /**
     * Mutable builder for {@link RefereeAssignmentReport}. Assembled incrementally by {@link
     * RefereeAssigner} during the assignment algorithm.
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

        /** Increments the no-referee count by 1. */
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
}
