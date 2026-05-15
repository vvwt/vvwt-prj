package de.vvwt.tm.display;

import java.util.UUID;

/**
 * Bounded-context-owned query-shape record for the display phase overview response (E25S01).
 *
 * <p>Resides at {@code de.vvwt.tm.display.*} (root, public) per DEC-40 §2026-04-27 Clarification
 * (Pattern A — bounded-context-owned query-shape records). Placing this type in {@code
 * web.internal.dto.*} would create a {@code display→web} dependency, causing a {@code display↔web}
 * Modulith cycle rejected by {@code ApplicationModules.verify()} (escalation commit {@code 23d947b}
 * for historical context).
 *
 * <p>9 fields preserved verbatim per audit (i) inventory + Brief v2.2 C-8 wire-shape + C-3
 * signature-preservation. {@code tenantId} is mandatory per Brief v2.2 O-9 (Svelte SPA builds the
 * STOMP topic {@code /topic/display/{tenantId}/events} from this field).
 *
 * <p>Nested record {@code GroupSummary} is co-located in this compilation unit per Java records
 * canon. JSON wire shape (Jackson default field-name serialization) is preserved per Brief v2.2 C-8
 * + D-9.
 *
 * @param phaseId UUID of the active (or pending-with-preview) phase
 * @param tenantId UUID of the owning tenant (mandatory per O-9 STOMP-topic derivation)
 * @param phaseName human-readable phase description
 * @param phaseStatus lifecycle status string ({@code "ACTIVE"} or {@code "PENDING"})
 * @param lapCount total number of laps in this phase
 * @param currentLap 1-based running-lap index (DEC-65): 0 = sentinel "no lap running" (PENDING,
 *     PREPARED, ASSIGNED, ACTIVE post-last-lap, COMPLETED); ACTIVE mid-phase lap K → K ∈ [1,
 *     lapCount]
 * @param fieldCount number of playing fields for this tournament
 * @param preparationPreview {@code true} when phase is PENDING but slot-optimization has run
 * @param groups ordered list of group summaries
 * @see DisplayOverviewService
 * @see E25S01
 */
public record DisplayPhaseOverviewResponse(
        UUID phaseId,
        UUID tenantId,
        String phaseName,
        String phaseStatus,
        int lapCount,
        int currentLap,
        int fieldCount,
        boolean preparationPreview,
        java.util.List<GroupSummary> groups) {

    /**
     * Summary of a single group within the phase overview.
     *
     * @param groupNumber 1-based group number
     * @param teamCount number of teams (avatars) assigned to this group
     */
    public record GroupSummary(int groupNumber, int teamCount) {}
}
