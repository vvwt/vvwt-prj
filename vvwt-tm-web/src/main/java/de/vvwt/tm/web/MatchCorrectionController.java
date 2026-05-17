// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.scoring.MatchCorrectionInput;
import de.vvwt.tm.scoring.MatchCorrectionResult;
import de.vvwt.tm.scoring.MatchCorrectionService;
import de.vvwt.tm.scoring.SetScoreCorrection;
import de.vvwt.tm.web.internal.dto.MatchCorrectionRequest;
import de.vvwt.tm.web.internal.dto.MatchCorrectionResultResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for operator match-score corrections and Nacherfassung (E48S25, DEC-40).
 *
 * <p>Primary-adapter-isolation: lives in {@code de.vvwt.tm.web} per DEC-40 Clause A. Delegates all
 * business logic to {@link MatchCorrectionService}.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/matches/{matchId}/correction — submit a batch correction for a match
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>All endpoints require authenticated Admin. Secured via the global {@code SecurityFilterChain}
 * (Spring Security HTTP Basic). Anonymous requests → 401. Non-admin requests → 403.
 *
 * <h2>Guard errors</h2>
 *
 * <ul>
 *   <li>Phase not ACTIVE → 409 ({@code error.correction.phase-not-active})
 *   <li>Match INPROGRESS or ONCHECK → 409 ({@code error.correction.match-live-scoring})
 *   <li>Standoff on non-tie format → 422 ({@code error.correction.standoff-format-mismatch})
 *   <li>Match not found (tenant-scoped) → 404
 * </ul>
 *
 * <h2>Success (HTTP 200)</h2>
 *
 * <p>Returns a {@link MatchCorrectionResultResponse} with the new match state and {@code auditOnly}
 * flag. For CANCELED matches, {@code auditOnly=true} and state remains {@code CANCELED}.
 *
 * @see MatchCorrectionService
 * @see de.vvwt.tm.web.GlobalExceptionHandler
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
@RestController("tmMatchCorrectionController")
@RequestMapping("/api/matches")
public class MatchCorrectionController {

    private final MatchCorrectionService matchCorrectionService;

    public MatchCorrectionController(
            @Qualifier("tmMatchCorrectionService") MatchCorrectionService matchCorrectionService) {
        this.matchCorrectionService = matchCorrectionService;
    }

    // -------------------------------------------------------------------------
    // POST /api/matches/{matchId}/correction
    // (AC-REST-CORRECTION-ENDPOINT, AC-SECURITY-CORRECTION-AUTH)
    // -------------------------------------------------------------------------

    /**
     * Submits a batch of set-score corrections (or Nacherfassung entries) for a match.
     *
     * <p>Eligible match states: {@code OPEN}, {@code ENABLED}, {@code FINISHED_WINNER1}, {@code
     * FINISHED_WINNER2}, {@code FINISHED_STANDOFF} (full cascade), {@code CANCELED} (audit-only).
     *
     * <p>Guard failures return HTTP 409 or 422 via {@link GlobalExceptionHandler}. Success returns
     * HTTP 200 with the new match state and {@code auditOnly} flag.
     *
     * @param matchId the UUID of the match to correct (from URL path)
     * @param request the correction request body
     * @return 200 OK with {@link MatchCorrectionResultResponse} on success; 409/422/404 on guard
     *     failure or match not found
     */
    @PostMapping("/{matchId}/correction")
    public ResponseEntity<MatchCorrectionResultResponse> correctMatch(
            @PathVariable UUID matchId, @RequestBody MatchCorrectionRequest request) {

        // Map HTTP DTO → domain input
        List<SetScoreCorrection> domainSets =
                request.sets().stream()
                        .map(
                                e ->
                                        new SetScoreCorrection(
                                                e.setIndex(), e.team1Points(), e.team2Points()))
                        .toList();

        MatchCorrectionInput input =
                new MatchCorrectionInput(
                        matchId,
                        request.tournamentId(),
                        request.phaseId(),
                        domainSets,
                        null, // actorId — resolved from Security context if needed; null = LAN mode
                        request.reason());

        MatchCorrectionResult result = matchCorrectionService.correctMatchSets(input);

        // Map domain result → HTTP response DTO
        MatchCorrectionResultResponse response =
                new MatchCorrectionResultResponse(
                        result.newMatchState().name(), result.auditOnly());

        return ResponseEntity.ok(response);
    }
}
