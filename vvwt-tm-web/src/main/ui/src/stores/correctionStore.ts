/**
 * Correction API client for the Tournament Manager Admin SPA.
 *
 * Story E48S25 — Operator Match Score Correction + Nacherfassung.
 *
 * Provides types and API calls for:
 *   - Submitting a set-score correction (POST /api/matches/:matchId/correction)
 *
 * See MatchCorrectionController.java for the server-side contract.
 */
import { apiFetch } from '../lib/api.js';

// ── Types ─────────────────────────────────────────────────────────────────────

/** A single match summary from GET /api/phases/{phaseId}/matches (mirrors MatchSummaryResponse). */
export interface MatchSummary {
    matchId: string;
    state: string;
    lapNumber: number | null;
    fieldNumber: number | null;
}

/** A single set-score correction entry (mirrors SetScoreEntry HTTP DTO). */
export interface SetScoreEntry {
    setIndex: number;
    team1Points: number;
    team2Points: number;
}

/** Request payload for POST /api/matches/:matchId/correction (mirrors MatchCorrectionRequest). */
export interface MatchCorrectionRequest {
    tournamentId: string;
    phaseId: string;
    sets: SetScoreEntry[];
    reason?: string | null;
}

/** Response from POST /api/matches/:matchId/correction (mirrors MatchCorrectionResultResponse). */
export interface MatchCorrectionResult {
    newMatchState: string;
    auditOnly: boolean;
}

// ── API ───────────────────────────────────────────────────────────────────────

/**
 * Fetches the match summary list for a phase (for correction navigation).
 *
 * Maps to GET /api/phases/{phaseId}/matches (AC-FE-PHASELIST-CORRECTION-LINKS).
 *
 * Match states INPROGRESS and ONCHECK are excluded from correction eligibility
 * at the client (the backend also guards these states with HTTP 409).
 *
 * @param phaseId the UUID of the phase
 * @returns list of match summaries
 * @throws Error when the server returns a non-2xx status
 */
export async function listPhaseMatches(phaseId: string): Promise<MatchSummary[]> {
    const res = await apiFetch(`/api/phases/${phaseId}/matches`);
    if (!res.ok) {
        const err = await res.json().catch(() => ({})) as { message?: string };
        throw new Error(err.message ?? `HTTP ${res.status}`);
    }
    return res.json() as Promise<MatchSummary[]>;
}

/**
 * Submits a batch of set-score corrections for a match.
 *
 * Maps to POST /api/matches/{matchId}/correction (AC-REST-CORRECTION-ENDPOINT).
 *
 * @param matchId the UUID of the match to correct
 * @param request the correction payload
 * @returns the correction result (new match state + auditOnly flag)
 * @throws Error when the server returns a non-2xx status (with server message if available)
 */
export async function submitMatchCorrection(
    matchId: string,
    request: MatchCorrectionRequest
): Promise<MatchCorrectionResult> {
    const res = await apiFetch(`/api/matches/${matchId}/correction`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(request),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({})) as { message?: string; messageKey?: string };
        throw Object.assign(
            new Error(err.message ?? `HTTP ${res.status}`),
            { apiError: err }
        );
    }
    return res.json() as Promise<MatchCorrectionResult>;
}
