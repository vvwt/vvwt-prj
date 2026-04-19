package de.vvwt.tm.infrastructure.display;

import de.vvwt.tm.infrastructure.display.dto.DisplayGroupStandingsResponse;
import de.vvwt.tm.infrastructure.display.dto.DisplayMatchesResponse;
import de.vvwt.tm.infrastructure.display.dto.DisplayPhaseOverviewResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for display device tournament overview endpoints (E07S04).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/display/overview?token={token} — current phase overview (AC1)
 *   <li>GET /api/display/overview/matches?token={token}[&amp;lap={n}] — matches by lap (AC2)
 *   <li>GET /api/display/overview/groups?token={token} — group standings (AC3)
 * </ul>
 *
 * <h2>Authentication (AC4)</h2>
 *
 * <p>All endpoints require a valid display device token as a query parameter. Spring Security
 * permits the {@code /api/display/**} path without admin authentication (see {@link
 * de.vvwt.tm.auth.SecurityConfig}). Device-type validation and active-status checks are performed
 * at the service layer — invalid tokens return 401 via {@link
 * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler#handleUnauthorized}.
 *
 * <h2>Read-only (AC11)</h2>
 *
 * <p>Only GET mappings are declared. POST, PUT, and DELETE requests to any {@code /api/display/**}
 * path return HTTP 405 (Method Not Allowed) — the default Spring MVC behaviour when no handler is
 * registered for the method.
 *
 * <h2>No active phase (AC7)</h2>
 *
 * <p>When no active phase exists, the service throws {@link NoActivePhaseException}, which is
 * translated to HTTP 404 with body {@code {"status":"NO_ACTIVE_PHASE"}} by {@link
 * de.vvwt.tm.infrastructure.web.GlobalExceptionHandler#handleNoActivePhase}.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-5 — tenant scope is resolved from the device token via {@link DisplayOverviewService}
 *   <li>DEC-16 — all data served from local H2; no external calls
 *   <li>DEC-17 — tenant resolved eagerly; TenantContext set by DefaultTenantContextResolver
 * </ul>
 *
 * @see DisplayOverviewService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E07S04.story.md">Story
 *     E07S04</a>
 */
@RestController
@RequestMapping("/api/display")
public class DisplayOverviewController {

    private final DisplayOverviewService displayOverviewService;

    public DisplayOverviewController(DisplayOverviewService displayOverviewService) {
        this.displayOverviewService = displayOverviewService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/display/overview?token={token}
    // -------------------------------------------------------------------------

    /**
     * Returns the current active phase overview for the display device's tenant (AC1, AC6, AC7).
     *
     * <p>Returns HTTP 200 with the phase overview on success. Returns HTTP 401 if the token is
     * invalid or not for a DISPLAY device (AC4). Returns HTTP 404 with {@code
     * {"status":"NO_ACTIVE_PHASE"}} if no active phase exists (AC7).
     *
     * @param token the display device token (required query parameter)
     * @return 200 with {@link DisplayPhaseOverviewResponse}
     */
    @GetMapping("/overview")
    public ResponseEntity<DisplayPhaseOverviewResponse> getPhaseOverview(
            @RequestParam("token") String token) {
        DisplayPhaseOverviewResponse response = displayOverviewService.getPhaseOverview(token);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/display/overview/matches?token={token}[&lap={n}]
    // -------------------------------------------------------------------------

    /**
     * Returns all matches for the given lap (or current lap if {@code lap} is absent) (AC2).
     *
     * <p>Returns HTTP 200 with the matches list on success. Returns HTTP 401 if the token is
     * invalid or not for a DISPLAY device (AC4). Returns HTTP 404 with {@code
     * {"status":"NO_ACTIVE_PHASE"}} if no active phase exists (AC7).
     *
     * @param token the display device token (required)
     * @param lap the lap number to query (optional; defaults to current lap)
     * @return 200 with {@link DisplayMatchesResponse}
     */
    @GetMapping("/overview/matches")
    public ResponseEntity<DisplayMatchesResponse> getMatchesByLap(
            @RequestParam("token") String token,
            @RequestParam(value = "lap", required = false) Integer lap) {
        DisplayMatchesResponse response = displayOverviewService.getMatchesByLap(token, lap);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/display/overview/groups?token={token}
    // -------------------------------------------------------------------------

    /**
     * Returns group standings for the current active phase (AC3).
     *
     * <p>Returns HTTP 200 with group standings on success. Returns HTTP 401 if the token is invalid
     * or not for a DISPLAY device (AC4). Returns HTTP 404 with {@code {"status":"NO_ACTIVE_PHASE"}}
     * if no active phase exists (AC7).
     *
     * @param token the display device token (required)
     * @return 200 with {@link DisplayGroupStandingsResponse}
     */
    @GetMapping("/overview/groups")
    public ResponseEntity<DisplayGroupStandingsResponse> getGroupStandings(
            @RequestParam("token") String token) {
        DisplayGroupStandingsResponse response = displayOverviewService.getGroupStandings(token);
        return ResponseEntity.ok(response);
    }
}
