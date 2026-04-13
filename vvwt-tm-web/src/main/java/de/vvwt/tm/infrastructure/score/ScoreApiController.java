package de.vvwt.tm.infrastructure.score;

import de.vvwt.tm.infrastructure.score.dto.MatchScoreResponse;
import de.vvwt.tm.infrastructure.score.dto.PartialScoreRequest;
import de.vvwt.tm.infrastructure.score.dto.SetSubmitRequest;
import de.vvwt.tm.infrastructure.score.dto.TabletStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * REST controller for the scoring tablet API endpoints (E06S06).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/score/match?field={n}&token={t}}  — resolve active match for a field (AC1, AC4)</li>
 *   <li>{@code GET  /api/score/status?field={n}&token={t}} — tablet idle/active state (E06S08 AC1–AC6)</li>
 *   <li>{@code POST /api/score/partial}                    — partial (live) score update (AC5)</li>
 *   <li>{@code POST /api/score/submit}                     — final set result submission (AC7)</li>
 * </ul>
 *
 * <h2>Security</h2>
 * <p>All endpoints are accessible without admin session authentication (DEC-12, E06S02 AC9).
 * Device identity is validated by {@link ScoreEntryService} via the {@code deviceToken} parameter
 * (AC8). Invalid/missing tokens → 401 (AC8). Valid token but wrong field → 403 (AC12).
 *
 * <h2>Error handling</h2>
 * <p>Exceptions thrown by {@link ScoreEntryService} are handled globally by
 * {@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler}:
 * <ul>
 *   <li>{@link de.vvwt.tm.domain.UnauthorizedException} → HTTP 401</li>
 *   <li>{@link de.vvwt.tm.domain.ForbiddenException}    → HTTP 403</li>
 *   <li>{@link de.vvwt.tm.domain.ValidationException}   → HTTP 400 (AC6)</li>
 * </ul>
 *
 * @see ScoreEntryService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S06.story.md">Story E06S06</a>
 */
@RestController
@RequestMapping("/api/score")
public class ScoreApiController {

    private final ScoreEntryService   scoreEntryService;
    private final TabletStatusService tabletStatusService;

    public ScoreApiController(ScoreEntryService scoreEntryService,
                              TabletStatusService tabletStatusService) {
        this.scoreEntryService   = scoreEntryService;
        this.tabletStatusService = tabletStatusService;
    }

    // -------------------------------------------------------------------------
    // AC1, AC4, AC9: Match resolution
    // -------------------------------------------------------------------------

    /**
     * Returns the active match for the given field and device token (AC1, AC4, AC9).
     *
     * <p>The device token is validated and field ownership is asserted (AC8, AC12).
     * If an active non-terminal match exists for the field's current lap, its display data
     * is returned. If no match is scheduled ({@code AC9}), HTTP 204 No Content is returned
     * so the tablet can render the "no match" state.
     *
     * @param field       the court field number (1-based)
     * @param deviceToken the tablet's opaque device token
     * @return 200 with {@link MatchScoreResponse}, or 204 if no active match (AC9)
     */
    @GetMapping("/match")
    public ResponseEntity<MatchScoreResponse> getMatch(
            @RequestParam("field") int field,
            @RequestParam("token") String deviceToken) {

        Optional<MatchScoreResponse> matchOpt = scoreEntryService.getMatchForField(field, deviceToken);
        return matchOpt
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // -------------------------------------------------------------------------
    // E06S08 AC1–AC6, AC9: Tablet idle/active state
    // -------------------------------------------------------------------------

    /**
     * Returns the current tablet state for the given field and device token (E06S08 AC1–AC6, AC9).
     *
     * <p>The device token is validated (AC9). An invalid or unassigned token results in HTTP 401,
     * which the client interprets as a redirect to {@code /score/register}.
     *
     * <p>Returns HTTP 200 with a {@link TabletStatusResponse} whose {@code state} field
     * tells the ES5 client state machine which idle screen (or active match) to show.
     *
     * @param field       the court field number (1-based)
     * @param deviceToken the tablet's opaque device token
     * @return 200 with {@link TabletStatusResponse}; 401 if token invalid/unassigned
     */
    @GetMapping("/status")
    public ResponseEntity<TabletStatusResponse> getTabletStatus(
            @RequestParam("field") int field,
            @RequestParam("token") String deviceToken) {

        TabletStatusResponse status = tabletStatusService.getTabletStatus(field, deviceToken);
        return ResponseEntity.ok(status);
    }

    // -------------------------------------------------------------------------
    // AC5: Partial (live) score update
    // -------------------------------------------------------------------------

    /**
     * Accepts a partial (live) score update and broadcasts it via WebSocket (AC5).
     *
     * <p>The score is NOT persisted. The update is forwarded to connected WebSocket subscribers
     * on {@code /topic/score/field/{fieldNumber}} for real-time display.
     *
     * @param request the partial score request body
     * @return 204 No Content on success
     */
    @PostMapping("/partial")
    public ResponseEntity<Void> partialScore(@Valid @RequestBody PartialScoreRequest request) {
        scoreEntryService.handlePartialScore(request);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // AC7, AC8: Final set result submission
    // -------------------------------------------------------------------------

    /**
     * Submits a final set result and triggers cascade recompute (AC7, AC8).
     *
     * <p>The device token is validated (AC8). The set score is validated by the
     * {@link de.vvwt.tm.domain.rules.SetValidationRule} before persistence (AC6). On success,
     * the cascade service records the set result, updates match state, and broadcasts a
     * WebSocket notification. Returns 204 No Content on success.
     *
     * @param request the set submit request body
     * @return 204 No Content on success
     */
    @PostMapping("/submit")
    public ResponseEntity<Void> submitSet(@Valid @RequestBody SetSubmitRequest request) {
        scoreEntryService.submitSetResult(request);
        return ResponseEntity.noContent().build();
    }
}
