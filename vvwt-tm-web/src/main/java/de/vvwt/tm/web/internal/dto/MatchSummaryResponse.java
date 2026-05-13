package de.vvwt.tm.web.internal.dto;

import java.util.UUID;

/**
 * HTTP response DTO representing a single match summary in a phase's match list.
 *
 * <p>Used by {@code GET /api/phases/{phaseId}/matches} (E48S25, AC-FE-PHASELIST-CORRECTION-LINKS).
 *
 * <p>Web-tier-owned per DEC-40 Clause B — this record projects match state for the Admin SPA
 * correction navigation (PhaseList shows "Korrigieren" links per match row).
 *
 * @param matchId the UUID of the match
 * @param state the match state name (e.g., {@code "FINISHED_WINNER1"}, {@code "OPEN"})
 * @param lapNumber the lap number the match belongs to (nullable)
 * @param fieldNumber the field number the match is assigned to (nullable)
 * @see de.vvwt.tm.web.MatchCorrectionController
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public record MatchSummaryResponse(
        UUID matchId, String state, Integer lapNumber, Integer fieldNumber) {}
