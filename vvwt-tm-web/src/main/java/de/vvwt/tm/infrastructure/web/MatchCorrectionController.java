package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.MatchCorrectionService;
import de.vvwt.tm.infrastructure.web.dto.MatchDetailResponse;
import de.vvwt.tm.infrastructure.web.dto.SetCorrectionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for match result correction operations (E05S11).
 *
 * <h2>Endpoints (AC1–AC3)</h2>
 * <ul>
 *   <li>GET  /api/matches/{matchId}                  — full match detail (AC1)</li>
 *   <li>PUT  /api/matches/{matchId}/sets/{setIndex}  — correct existing set result (AC2)</li>
 *   <li>POST /api/matches/{matchId}/sets             — enter result for next set (AC3)</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <p>All exceptions are mapped to structured JSON responses by {@link GlobalExceptionHandler}:
 * <ul>
 *   <li>{@link java.util.NoSuchElementException}  → 404 (AC11: match not found / cross-tenant)</li>
 *   <li>{@link de.vvwt.tm.domain.ValidationException} → 400 (AC10: invalid scores)</li>
 *   <li>{@link IllegalArgumentException}          → 400</li>
 *   <li>{@link Exception}                         → 500</li>
 * </ul>
 *
 * <h2>Security (AC14)</h2>
 * <p>All {@code /api/**} endpoints require HTTP Basic authentication per
 * {@link de.vvwt.tm.auth.SecurityConfig}. Tenant scoping is enforced at the
 * {@link MatchCorrectionService} and repository layers (DEC-5, DEC-17).
 *
 * @see MatchCorrectionService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S11.story.md">Story E05S11</a>
 */
@RestController
public class MatchCorrectionController {

    private final MatchCorrectionService matchCorrectionService;

    public MatchCorrectionController(MatchCorrectionService matchCorrectionService) {
        this.matchCorrectionService = matchCorrectionService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/matches/{matchId}
    // -------------------------------------------------------------------------

    /**
     * Returns the full match detail including teams, referee, match format, state, and all
     * set results (AC1).
     *
     * @param matchId the match ID
     * @return 200 OK with match detail, or 404 if not found / belongs to a different tenant (AC11)
     */
    @GetMapping("/api/matches/{matchId}")
    public ResponseEntity<MatchDetailResponse> getMatchDetail(
            @PathVariable("matchId") UUID matchId) {

        MatchCorrectionService.MatchDetail detail = matchCorrectionService.getMatchDetail(matchId);
        return ResponseEntity.ok(MatchDetailResponse.from(detail));
    }

    // -------------------------------------------------------------------------
    // AC2 — PUT /api/matches/{matchId}/sets/{setIndex}
    // -------------------------------------------------------------------------

    /**
     * Corrects an existing set result and triggers the full 13-step cascade recompute (AC2).
     *
     * <p>The cascade updates the SetResult, writes an audit_log row (AC4), recomputes
     * MatchOutcome and match state (AC5), refreshes TeamAvatarRatings for both teams (AC5),
     * and emits a {@code MATCH_RESULT_CHANGED} WebSocket event (AC6).
     *
     * <p>Returns the updated match detail after the cascade completes.
     *
     * @param matchId    the match whose set to correct
     * @param setIndex   the 0-based index of the set to correct
     * @param request    the new scores
     * @param auth       Spring Security authentication; {@code null} in LAN mode
     * @return 200 OK with updated match detail, 400 on invalid scores (AC10), 404 if not found (AC11)
     */
    @PutMapping("/api/matches/{matchId}/sets/{setIndex}")
    public ResponseEntity<MatchDetailResponse> correctSet(
            @PathVariable("matchId") UUID matchId,
            @PathVariable("setIndex") int setIndex,
            @RequestBody SetCorrectionRequest request,
            Authentication auth) {

        String actorId = auth != null ? auth.getName() : null;
        MatchCorrectionService.MatchDetail detail =
                matchCorrectionService.correctSet(
                        matchId, setIndex, request.team1Points(), request.team2Points(), actorId);
        return ResponseEntity.ok(MatchDetailResponse.from(detail));
    }

    // -------------------------------------------------------------------------
    // AC3 — POST /api/matches/{matchId}/sets
    // -------------------------------------------------------------------------

    /**
     * Enters the result for the next unplayed set in the match (AC3).
     *
     * <p>The setIndex is auto-determined by the service as the count of existing sets.
     * The same cascade recompute runs as for AC2. The admin path shares the same
     * {@code CascadeRecomputeService} as the scoring tablet entry path — the difference
     * is {@code actorId} and the audit reason field.
     *
     * @param matchId the match for which to enter the next set
     * @param request the scores for the new set
     * @param auth    Spring Security authentication; {@code null} in LAN mode
     * @return 200 OK with updated match detail, 400 on invalid scores (AC10), 404 if not found (AC11)
     */
    @PostMapping("/api/matches/{matchId}/sets")
    public ResponseEntity<MatchDetailResponse> enterNewSet(
            @PathVariable("matchId") UUID matchId,
            @RequestBody SetCorrectionRequest request,
            Authentication auth) {

        String actorId = auth != null ? auth.getName() : null;
        MatchCorrectionService.MatchDetail detail =
                matchCorrectionService.enterNewSet(
                        matchId, request.team1Points(), request.team2Points(), actorId);
        return ResponseEntity.ok(MatchDetailResponse.from(detail));
    }
}
