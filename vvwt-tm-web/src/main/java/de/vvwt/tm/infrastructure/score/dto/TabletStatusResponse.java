package de.vvwt.tm.infrastructure.score.dto;

/**
 * Response DTO for the tablet status API (E06S08).
 *
 * <p>Conveys the current idle/active state of the scoring tablet for a given field,
 * allowing the ES5 client state machine to select the appropriate display section.
 *
 * <h2>State values (AC1–AC6)</h2>
 * <ul>
 *   <li>{@link #STATE_TOURNAMENT_NOT_ACTIVE} — no tournament, or status=DRAFT/CANCELLED (AC6)</li>
 *   <li>{@link #STATE_TOURNAMENT_COMPLETE}   — tournament status=COMPLETED (AC5)</li>
 *   <li>{@link #STATE_PHASE_TRANSITION}      — active tournament, current phase COMPLETED, next PENDING (AC4)</li>
 *   <li>{@link #STATE_WAITING_FOR_LAP}       — active phase, all matches on this field in current lap are terminal (AC1)</li>
 *   <li>{@link #STATE_NO_MATCH_ON_FIELD}     — active phase, current lap has no match scheduled on this field (AC3)</li>
 *   <li>{@link #STATE_ACTIVE_MATCH}          — non-terminal match found; client should call /api/score/match (AC2)</li>
 * </ul>
 *
 * @param state       one of the STATE_* constants defined on this class
 * @param lapNumber   the current lap number within the active phase (0 if not applicable)
 * @param fieldNumber the court field number (always present, 1-based)
 *
 * @see de.vvwt.tm.infrastructure.score.TabletStatusService
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E06S08.story.md">Story E06S08</a>
 */
public record TabletStatusResponse(String state, int lapNumber, int fieldNumber) {

    /** AC6: No tournament exists or all tournaments are in DRAFT/CANCELLED status. */
    public static final String STATE_TOURNAMENT_NOT_ACTIVE = "TOURNAMENT_NOT_ACTIVE";

    /** AC5: Tournament lifecycle is COMPLETED — no further matches expected. */
    public static final String STATE_TOURNAMENT_COMPLETE   = "TOURNAMENT_COMPLETE";

    /**
     * AC4: Active tournament found, but no phase is currently ACTIVE — at least one phase is
     * COMPLETED and at least one is PENDING, indicating a gap between phases.
     */
    public static final String STATE_PHASE_TRANSITION      = "PHASE_TRANSITION";

    /**
     * AC1: Active phase found; all matches on this field in the current lap are in terminal state
     * (FINISHED, CANCELLED, etc.). The lap is over for this field; waiting for the next round.
     */
    public static final String STATE_WAITING_FOR_LAP       = "WAITING_FOR_LAP";

    /**
     * AC3: Active phase found; current lap is active but no match is scheduled on this field
     * (bye round for odd team counts, or this field is not used in this lap).
     */
    public static final String STATE_NO_MATCH_ON_FIELD     = "NO_MATCH_ON_FIELD";

    /**
     * AC2: A non-terminal match exists on this field in the current lap. The client should call
     * {@code GET /api/score/match} to retrieve match details for score entry.
     */
    public static final String STATE_ACTIVE_MATCH          = "ACTIVE_MATCH";
}
