package de.vvwt.tm.web.internal.dto;

/**
 * HTTP DTO element for a single set-score correction within a {@link MatchCorrectionRequest}
 * (E48S25, AC-REST-CORRECTION-REQUEST-DTO).
 *
 * @param setIndex 0-based index of the set within the match
 * @param team1Points corrected score for team 1 (&ge; 0)
 * @param team2Points corrected score for team 2 (&ge; 0)
 * @see MatchCorrectionRequest
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public record SetScoreEntry(int setIndex, int team1Points, int team2Points) {}
