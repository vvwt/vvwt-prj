package de.vvwt.tm.web;

import de.vvwt.tm.display.DisplayGroupStandingsResponse;
import de.vvwt.tm.display.DisplayMatchesResponse;
import de.vvwt.tm.display.DisplayOverviewService;
import de.vvwt.tm.display.DisplayPhaseOverviewResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for display device tournament overview endpoints — E25S02 Q-1a TDD reconstruction.
 *
 * <h2>Endpoints (AC-RED-FIRST-DISPLAY-OVERVIEW-CONTROLLER, AC-URL-PATHS-PRESERVED)</h2>
 *
 * <ul>
 *   <li>GET /api/display/overview?token={token} — current phase overview (AC1)
 *   <li>GET /api/display/overview/matches?token={token}[&amp;lap={n}] — matches by lap (AC2)
 *   <li>GET /api/display/overview/groups?token={token} — group standings (AC3)
 * </ul>
 *
 * <p>URL paths preserved verbatim per AC-URL-PATHS-PRESERVED (C-8 + D-9). JSON wire shapes
 * preserved verbatim per AC-JSON-WIRE-PRESERVED. The 3 response record types are imported from
 * {@code de.vvwt.tm.display.*} per DEC-40 §2026-04-27 Clarification Pattern A — bounded-context-owned
 * query-shape records. No web-tier DTO wrapping (AC-RECORDS-AUTHORED-BY-S01).
 *
 * <h2>Authentication (AC-AUTHENTICATION-FLOW-PRESERVED)</h2>
 *
 * <p>All endpoints require a valid display device token as a query parameter. Spring Security
 * permits the {@code /api/display/**} path without admin authentication (see {@link
 * de.vvwt.tm.auth.internal.SecurityConfig}). Device-type validation and active-status checks are
 * performed at the service layer via {@link DisplayOverviewService} — invalid tokens return 401 via
 * {@link GlobalExceptionHandler#handleUnauthorized}.
 *
 * <h2>No active phase (AC7)</h2>
 *
 * <p>When no active phase exists, the service throws {@link de.vvwt.tm.display.NoActivePhaseException},
 * which is translated to HTTP 404 with body {@code {"status":"NO_ACTIVE_PHASE"}} by
 * {@link GlobalExceptionHandler#handleNoActivePhase}. The handler now imports
 * {@code de.vvwt.tm.display.NoActivePhaseException} per AC-GLOBAL-EXCEPTION-HANDLER-IMPORT-UPDATE
 * (updated by E25S01, verified here).
 *
 * <h2>Read-only (AC11)</h2>
 *
 * <p>Only GET mappings are declared. POST, PUT, and DELETE requests to any {@code /api/display/**}
 * path return HTTP 405 (Method Not Allowed) — the default Spring MVC behaviour when no handler is
 * registered for the method.
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-40 Clause A — controller resides in {@code de.vvwt.tm.web}, NOT in any bounded-context
 *       module; relocated from {@code de.vvwt.tm.infrastructure.display.*} per E25 display track
 *   <li>DEC-40 §2026-04-27 Clarification Pattern A — response records at {@code de.vvwt.tm.display.*}
 *       consumed cross-module via {@code web→display} allowedDependency; no {@code web.internal.dto.*}
 *       created for display records (AC-RECORDS-AUTHORED-BY-S01)
 *   <li>DEC-22 Iron Law Q-1a — authored RED-first: {@link DisplayOverviewControllerIT} written before
 *       this class; RED commit 42c263d; GREEN commit = this class
 *   <li>DEC-5 — tenant scope resolved from device token via {@link DisplayOverviewService}
 *   <li>DEC-16 — all data served from local H2; no external calls
 * </ul>
 *
 * @see DisplayOverviewService
 * @see DisplayViewController
 * @since E25S02
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
     * invalid or not for a DISPLAY device (AC4, AC-AUTHENTICATION-FLOW-PRESERVED). Returns HTTP 404
     * with {@code {"status":"NO_ACTIVE_PHASE"}} if no active phase exists (AC7).
     *
     * <p>Response type {@link DisplayPhaseOverviewResponse} is a bounded-context-owned record at
     * {@code de.vvwt.tm.display.*} per DEC-40 §2026-04-27 Clarification Pattern A. Includes
     * {@code tenantId} field critical for WebSocket STOMP-topic construction per O-9.
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
     * @param lap   the lap number to query (optional; defaults to current lap when null)
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
