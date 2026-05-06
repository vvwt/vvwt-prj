/**
 * Phase overview API functions for the Tournament Manager Admin SPA (E48S05 + E48S06).
 *
 * Provides types and API calls for reading phases and for phase lifecycle mutations (E48S06).
 */
import { apiFetch } from '../lib/api.js';

// ── Types ─────────────────────────────────────────────────────────────────────

/** Match counts grouped by state name. */
export interface MatchCountsByState {
    OPEN: number;
    ENABLED: number;
    INPROGRESS: number;
    ONCHECK: number;
    FINISHED_WINNER1: number;
    FINISHED_WINNER2: number;
    FINISHED_STANDOFF: number;
    CANCELED: number;
    [key: string]: number;
}

/** Phase overview DTO from GET /api/tournaments/:tournamentId/phases. */
export interface PhaseOverview {
    id: string;
    sequenceNumber: number;
    description: string;
    status: 'PENDING' | 'ACTIVE' | 'COMPLETED';
    gameMode: string | null;
    currentLapNumber: number;
    matchCountsByState: MatchCountsByState;
}

// ── API ───────────────────────────────────────────────────────────────────────

/**
 * Fetches the phase overview list for a tournament.
 *
 * @param tournamentId the tournament UUID
 * @returns ordered list of phase overviews (by sequenceNumber)
 * @throws Error when the server returns a non-2xx status
 */
export async function listPhases(tournamentId: string): Promise<PhaseOverview[]> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/phases`);
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error((err as { message?: string }).message ?? `HTTP ${res.status}`);
    }
    return res.json() as Promise<PhaseOverview[]>;
}

// ── Lifecycle mutations (E48S06) ──────────────────────────────────────────────

/**
 * Starts a phase: PENDING → ACTIVE.
 *
 * @param phaseId the phase UUID
 * @throws Error with operator-actionable message on HTTP 409 (invalid transition)
 */
export async function startPhase(phaseId: string): Promise<void> {
    const res = await apiFetch(`/api/phases/${phaseId}/start`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error((err as { message?: string }).message ?? `HTTP ${res.status}`);
    }
}

/**
 * Completes a phase: ACTIVE → COMPLETED (only when all matches are finished).
 *
 * @param phaseId the phase UUID
 * @throws Error with operator-actionable message on HTTP 409 (unfinished matches or invalid state)
 */
export async function completePhase(phaseId: string): Promise<void> {
    const res = await apiFetch(`/api/phases/${phaseId}/complete`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error((err as { message?: string }).message ?? `HTTP ${res.status}`);
    }
}

/**
 * Force-completes a phase (Notabschluss): ACTIVE → COMPLETED + voids unfinished matches.
 *
 * @param phaseId the phase UUID
 * @throws Error with operator-actionable message on HTTP 409 (invalid transition)
 */
export async function forceCompletePhase(phaseId: string): Promise<void> {
    const res = await apiFetch(`/api/phases/${phaseId}/force-complete`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error((err as { message?: string }).message ?? `HTTP ${res.status}`);
    }
}
