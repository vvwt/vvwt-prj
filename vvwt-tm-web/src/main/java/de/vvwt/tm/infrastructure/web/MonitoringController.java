package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.LiveMonitoringService;
import de.vvwt.tm.infrastructure.web.dto.CurrentLapSummaryResponse;
import de.vvwt.tm.infrastructure.web.dto.GroupTableEntryResponse;
import de.vvwt.tm.infrastructure.web.dto.LapMatchesResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * REST controller for live monitoring endpoints (E05S10).
 *
 * <h2>Endpoints (AC1–AC4)</h2>
 * <ul>
 *   <li>GET /api/phases/{phaseId}/groups/{groupNumber}/table — ranked group standings (AC1)</li>
 *   <li>GET /api/phases/{phaseId}/groups/tables             — all groups' standings (AC2)</li>
 *   <li>GET /api/phases/{phaseId}/laps/{lapNumber}/matches  — matches with set scores (AC3)</li>
 *   <li>GET /api/phases/{phaseId}/current-lap               — current lap summary (AC4)</li>
 * </ul>
 *
 * <h2>Security (AC13)</h2>
 * <p>All {@code /api/**} endpoints require HTTP Basic authentication per
 * {@link de.vvwt.tm.auth.SecurityConfig}. Tenant scoping is enforced at the
 * {@link LiveMonitoringService} and repository layers (DEC-5, DEC-17, AC14).
 *
 * <h2>Error handling</h2>
 * <p>All exceptions are mapped to structured JSON responses by {@link GlobalExceptionHandler}:
 * <ul>
 *   <li>{@link java.util.NoSuchElementException}      → 404</li>
 *   <li>{@link IllegalArgumentException}              → 400</li>
 *   <li>{@link Exception}                             → 500</li>
 * </ul>
 *
 * @see LiveMonitoringService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S10.story.md">Story E05S10</a>
 */
@RestController
public class MonitoringController {

    private final LiveMonitoringService liveMonitoringService;

    public MonitoringController(LiveMonitoringService liveMonitoringService) {
        this.liveMonitoringService = liveMonitoringService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/phases/{phaseId}/groups/{groupNumber}/table
    // -------------------------------------------------------------------------

    /**
     * Returns the ranked standings for a single group within the phase (AC1).
     *
     * <p>The list is sorted by D-33 ranking order: points DESC → setQuotient DESC →
     * ballQuotient DESC, with {@code withoutAssessment} rows always last (D-26).
     * {@code setQuotient} and {@code ballQuotient} are {@code null} when the team has
     * no losses (sentinel {@link Double#MAX_VALUE} → null for clean JSON, AC8).
     *
     * @param phaseId     the phase to query
     * @param groupNumber the group number (1-indexed)
     * @return 200 OK with list of ranked entries, or 404 if phase not found
     */
    @GetMapping("/api/phases/{phaseId}/groups/{groupNumber}/table")
    public ResponseEntity<List<GroupTableEntryResponse>> getGroupTable(
            @PathVariable("phaseId") UUID phaseId,
            @PathVariable("groupNumber") int groupNumber) {

        List<LiveMonitoringService.GroupTableEntry> entries =
                liveMonitoringService.getGroupTable(phaseId, groupNumber);
        return ResponseEntity.ok(GroupTableEntryResponse.fromList(entries));
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/phases/{phaseId}/groups
    // -------------------------------------------------------------------------

    /**
     * Returns all groups' ranked standings for the phase (AC2).
     *
     * <p>Also accessible at {@code /api/phases/{phaseId}/groups/tables} for URL clarity.
     * The canonical URL per the story spec is {@code /api/phases/{phaseId}/groups}.
     *
     * <p>The response is a map from group number (as string key, JSON-compatible) to
     * sorted list of entries. Groups are returned in ascending group number order.
     * Each group's entries are sorted by D-33 ranking order.
     *
     * @param phaseId the phase to query
     * @return 200 OK with map of groupNumber → sorted entries, or 404 if phase not found
     */
    @GetMapping({"/api/phases/{phaseId}/groups", "/api/phases/{phaseId}/groups/tables"})
    public ResponseEntity<Map<Integer, List<GroupTableEntryResponse>>> getAllGroupTables(
            @PathVariable("phaseId") UUID phaseId) {

        Map<Integer, List<LiveMonitoringService.GroupTableEntry>> domainMap =
                liveMonitoringService.getAllGroupTables(phaseId);

        // Convert to DTO map, preserving sort order (TreeMap sorts by key ascending)
        Map<Integer, List<GroupTableEntryResponse>> responseMap = new TreeMap<>();
        domainMap.forEach((gn, entries) ->
                responseMap.put(gn, GroupTableEntryResponse.fromList(entries)));

        return ResponseEntity.ok(responseMap);
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/phases/{phaseId}/laps/{lapNumber}/matches
    // -------------------------------------------------------------------------

    /**
     * Returns all matches (with set scores) for the given lap in the phase (AC3).
     *
     * <p>Matches are ordered by {@code fieldNumber}. Set results are ordered by
     * {@code setIndex} (0-based). Returns an empty matches list if no matches
     * exist for the specified lap.
     *
     * @param phaseId   the phase to query
     * @param lapNumber the lap number (1-indexed)
     * @return 200 OK with lap matches response, or 404 if phase not found
     */
    @GetMapping("/api/phases/{phaseId}/laps/{lapNumber}/matches")
    public ResponseEntity<LapMatchesResponse> getLapMatches(
            @PathVariable("phaseId") UUID phaseId,
            @PathVariable("lapNumber") int lapNumber) {

        List<LiveMonitoringService.LapMatchDetail> details =
                liveMonitoringService.getLapMatches(phaseId, lapNumber);
        return ResponseEntity.ok(LapMatchesResponse.from(phaseId, lapNumber, details));
    }

    // -------------------------------------------------------------------------
    // AC4 — GET /api/phases/{phaseId}/current-lap
    // -------------------------------------------------------------------------

    /**
     * Returns the current lap summary for the given phase (AC4).
     *
     * <p>The summary includes the current lap number, total lap count, match counts
     * by state for the current lap, and the phase status. The SPA uses this to
     * display a lap progress bar and match state breakdown.
     *
     * @param phaseId the phase to summarize
     * @return 200 OK with current lap summary, or 404 if phase not found
     */
    @GetMapping("/api/phases/{phaseId}/current-lap")
    public ResponseEntity<CurrentLapSummaryResponse> getCurrentLapSummary(
            @PathVariable("phaseId") UUID phaseId) {

        LiveMonitoringService.CurrentLapSummary summary =
                liveMonitoringService.getCurrentLapSummary(phaseId);
        return ResponseEntity.ok(CurrentLapSummaryResponse.from(summary));
    }
}
