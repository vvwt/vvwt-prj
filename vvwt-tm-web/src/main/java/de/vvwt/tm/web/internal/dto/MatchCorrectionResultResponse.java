package de.vvwt.tm.web.internal.dto;

/**
 * HTTP response DTO for {@code POST /api/matches/{matchId}/correction} (E48S25,
 * AC-REST-CORRECTION-RESPONSE-DTO).
 *
 * <p>Web-tier-owned per DEC-40 Clause B condition (c) — this record aggregates data from multiple
 * domain types ({@link de.vvwt.tm.tournament.MatchState} + {@code auditOnly} flag) and is
 * serialized as a JSON HTTP response.
 *
 * @param newMatchState the derived match state name after the correction (e.g., {@code
 *     "FINISHED_WINNER1"}); for CANCELED audit-only corrections, this is {@code "CANCELED"}
 * @param auditOnly {@code true} if the correction was applied in audit-only mode (CANCELED match)
 * @see de.vvwt.tm.web.MatchCorrectionController
 * @see de.vvwt.tm.scoring.MatchCorrectionResult
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public record MatchCorrectionResultResponse(String newMatchState, boolean auditOnly) {}
