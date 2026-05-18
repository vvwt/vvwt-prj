// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { apiFetch } from '../lib/api.js';

// ── Types ─────────────────────────────────────────────────────────────────────

/** Per-set score from GET /api/phases/{phaseId}/matches (mirrors MatchSummaryResponse.SetScoreDto). */
export interface SetScore {
    setIndex: number;
    team1Points: number;
    team2Points: number;
}

/** A single match summary from GET /api/phases/{phaseId}/matches (mirrors MatchSummaryResponse). */
export interface MatchSummary {
    matchId: string;
    state: string;
    lapNumber: number | null;
    fieldNumber: number | null;
    team1Name: string;
    team2Name: string;
    /** Human-readable team number for team 1. Null when no team is assigned to this slot (E66S05). */
    team1Number: number | null;
    /** Human-readable team number for team 2. Null when no team is assigned to this slot (E66S05). */
    team2Number: number | null;
    setScores: SetScore[];
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
 * Maps to GET /api/phases/{phaseId}/matches.
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
