package de.vvwt.tm.web.internal.dto;

import java.util.List;
import java.util.UUID;

/**
 * HTTP request DTO for {@code POST /api/matches/{matchId}/correction} (E48S25,
 * AC-REST-CORRECTION-REQUEST-DTO).
 *
 * <p>Web-tier-owned HTTP contract per DEC-40 Clause B (HTTP-contract-owned). The scoring module
 * owns the domain contract ({@link de.vvwt.tm.scoring.MatchCorrectionInput}); this DTO carries the
 * HTTP wire shape.
 *
 * @param tournamentId UUID of the parent tournament (required for DEC-37 lock-first contract)
 * @param phaseId UUID of the parent phase (required for phase guard check)
 * @param sets list of set-score corrections (required; must not be null or empty)
 * @param reason optional operator-provided reason for the correction
 * @see de.vvwt.tm.web.MatchCorrectionController
 * @see de.vvwt.tm.scoring.MatchCorrectionInput
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public record MatchCorrectionRequest(
        UUID tournamentId, UUID phaseId, List<SetScoreEntry> sets, String reason) {}
