/**
 * Tournament selection state and API functions for the Tournament Manager Admin SPA.
 *
 * Story E05S04 — AC9: selected tournament ID stored in SPA client-side state and
 * persisted across page navigations within the SPA via sessionStorage.
 *
 * The store exposes:
 *   - `selectedTournamentId` — reactive writable store (UUID string | null)
 *   - `selectTournament(id)` — sets the selection and persists to sessionStorage
 *   - `clearSelection()` — clears the selection
 *   - API functions: listTournaments, createTournament, updateTournament, deleteTournament,
 *     getTournamentRules
 *
 * All API functions use `apiFetch` from api.ts to include browser-cached basic-auth
 * credentials (E05S02 AC7).
 */

import { writable } from 'svelte/store';
import { apiFetch } from '../lib/api.js';

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────

/** A tournament as returned by GET /api/tournaments and GET /api/tournaments/{id}. */
export interface Tournament {
    id: string;
    description: string;
    appointment: string | null;   // ISO-8601 LocalDateTime or null
    status: 'DRAFT' | 'PLANNED' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED';
    matchFormat: string;
    fieldCount: number;
    teamCount: number;
    scoringRuleId: string;
    setValidationRuleId: string;
    matchGeneratorId: string;
    createdAt: string;
    plannedStartTime: string | null;  // HH:mm LocalTime or null (E08S05 AC1)
}

/** Available rule options returned by GET /api/scoring/rules. */
export interface TournamentRules {
    scoringRuleIds: string[];
    setValidationRuleIds: string[];
    matchGeneratorIds: string[];
    matchFormats: string[];
}

/** Request body for POST /api/tournaments. */
export interface TournamentCreateRequest {
    description: string;
    appointment: string | null;
    teamCount: number;
    fieldCount: number;
    matchFormat: string;
    scoringRuleId: string;
    setValidationRuleId: string;
    matchGeneratorId: string;
    plannedStartTime?: string | null;  // HH:mm or null (E48S14)
}

/** Request body for PUT /api/tournaments/{id}. Null means "do not change". */
export interface TournamentUpdateRequest {
    description?: string | null;
    appointment?: string | null;
    teamCount?: number | null;
    fieldCount?: number | null;
    matchFormat?: string | null;
    scoringRuleId?: string | null;
    setValidationRuleId?: string | null;
    matchGeneratorId?: string | null;
    plannedStartTime?: string | null;  // HH:mm or null to clear (E08S05 AC1)
}

// ─────────────────────────────────────────────────────────────────
// Selected tournament store (AC9)
// ─────────────────────────────────────────────────────────────────

/** Session storage key for the selected tournament ID. */
const SELECTED_TOURNAMENT_KEY = 'tm_selected_tournament_id';

/**
 * Reactive store holding the currently selected tournament UUID string.
 *
 * Initialized from sessionStorage so the selection survives page navigations
 * within the SPA (AC9). Reset when the browser tab is closed.
 */
export const selectedTournamentId = writable<string | null>(
    sessionStorage.getItem(SELECTED_TOURNAMENT_KEY)
);

/**
 * Selects a tournament as the "working" tournament.
 * Updates both the reactive store and sessionStorage (AC9).
 *
 * @param id the tournament UUID to select
 */
export function selectTournament(id: string): void {
    sessionStorage.setItem(SELECTED_TOURNAMENT_KEY, id);
    selectedTournamentId.set(id);
}

/**
 * Clears the working tournament selection.
 * Updates both the reactive store and sessionStorage.
 */
export function clearSelection(): void {
    sessionStorage.removeItem(SELECTED_TOURNAMENT_KEY);
    selectedTournamentId.set(null);
}

// ─────────────────────────────────────────────────────────────────
// API functions
// ─────────────────────────────────────────────────────────────────

/**
 * Fetches all tournaments for the current tenant (AC1).
 * Returns tournaments ordered by createdAt descending.
 *
 * @throws Error if the request fails
 */
export async function listTournaments(): Promise<Tournament[]> {
    const res = await apiFetch('/api/tournaments');
    if (!res.ok) {
        throw new Error(`Failed to list tournaments: ${res.status}`);
    }
    return res.json();
}

/**
 * Fetches a single tournament by UUID (AC2).
 *
 * @param id the tournament UUID
 * @throws Error if not found (404) or the request fails
 */
export async function getTournament(id: string): Promise<Tournament> {
    const res = await apiFetch(`/api/tournaments/${id}`);
    if (!res.ok) {
        throw new Error(`Failed to get tournament ${id}: ${res.status}`);
    }
    return res.json();
}

/**
 * Creates a new tournament (AC3).
 * Returns the created tournament (HTTP 201).
 *
 * @param data the creation request
 * @returns the created tournament
 * @throws Error with the API error body if the request fails
 */
export async function createTournament(data: TournamentCreateRequest): Promise<Tournament> {
    const res = await apiFetch('/api/tournaments', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `Create failed: ${res.status}`), { apiError: err, status: res.status });
    }
    return res.json();
}

/**
 * Updates an existing tournament (AC4).
 * Only DRAFT tournaments may be updated; HTTP 409 is returned otherwise.
 *
 * @param id   the tournament UUID
 * @param data partial update request — null fields are ignored
 * @returns the updated tournament
 * @throws Error with the API error body if the request fails
 */
export async function updateTournament(id: string, data: TournamentUpdateRequest): Promise<Tournament> {
    const res = await apiFetch(`/api/tournaments/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `Update failed: ${res.status}`), { apiError: err, status: res.status });
    }
    return res.json();
}

/**
 * Deletes a tournament (AC5, legacy path — DRAFT-only semantics, no phases).
 * Only DRAFT tournaments with no phases may be deleted.
 *
 * @param id the tournament UUID
 * @throws Error with the API error body if the request fails
 */
export async function deleteTournament(id: string): Promise<void> {
    const res = await apiFetch(`/api/tournaments/${id}`, { method: 'DELETE' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `Delete failed: ${res.status}`), { apiError: err, status: res.status });
    }
}

/**
 * Cascade-deletes a tournament and all its structural data (E48S13, AC-IMPL-CASCADE-DELETE-OP).
 *
 * Permitted for status ∈ {DRAFT, PLANNED, CANCELLED}. Rejects ACTIVE (→ typed 409
 * error.tournament.cascadeDelete.activeRejected) and COMPLETED (→ typed 409
 * error.tournament.cascadeDelete.completedRejected).
 *
 * @param id the tournament UUID
 * @throws Error with apiError and status if the request fails (e.g. 409 Conflict)
 */
export async function cascadeDeleteTournament(id: string): Promise<void> {
    const res = await apiFetch(`/api/tournaments/${id}`, { method: 'DELETE' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `Cascade-delete failed: ${res.status}`), { apiError: err, status: res.status });
    }
}

/**
 * Resets the Phasenplan of a PLANNED tournament back to DRAFT (E48S13, AC-IMPL-RESET-PLAN-OP).
 *
 * Only PLANNED tournaments may be reset. Returns the updated tournament with status DRAFT on
 * success. Rejects ACTIVE/CANCELLED/COMPLETED/DRAFT with a typed 409 error messageKey.
 *
 * @param id the tournament UUID
 * @returns the updated tournament with status DRAFT
 * @throws Error with apiError and status if the request fails (e.g. 409 Conflict)
 */
export async function resetPlan(id: string): Promise<Tournament> {
    const res = await apiFetch(`/api/tournaments/${id}/draft/reset-plan`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `resetPlan failed: ${res.status}`), { apiError: err, status: res.status });
    }
    return res.json();
}

/**
 * Fetches available rule and format options for the tournament form (AC8).
 *
 * @throws Error if the request fails
 */
export async function getTournamentRules(): Promise<TournamentRules> {
    const res = await apiFetch('/api/scoring/rules');
    if (!res.ok) {
        throw new Error(`Failed to load tournament rules: ${res.status}`);
    }
    return res.json();
}

// ─────────────────────────────────────────────────────────────────
// Lifecycle API functions (E48S03)
// ─────────────────────────────────────────────────────────────────

/**
 * Transitions a tournament from DRAFT to PLANNED (E48S03).
 *
 * @param id the tournament UUID
 * @returns the updated tournament with status PLANNED
 * @throws Error with apiError and status if the request fails (e.g. 409 Conflict)
 */
export async function markPlanned(id: string): Promise<Tournament> {
    const res = await apiFetch(`/api/tournaments/${id}/mark-planned`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `markPlanned failed: ${res.status}`), { apiError: err, status: res.status });
    }
    return res.json();
}

/**
 * Transitions a tournament from PLANNED to ACTIVE (E48S03).
 *
 * @param id the tournament UUID
 * @returns the updated tournament with status ACTIVE
 * @throws Error with apiError and status if the request fails (e.g. 409 Conflict)
 */
export async function activate(id: string): Promise<Tournament> {
    const res = await apiFetch(`/api/tournaments/${id}/activate`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `activate failed: ${res.status}`), { apiError: err, status: res.status });
    }
    return res.json();
}

/**
 * Transitions a tournament from ACTIVE to COMPLETED (E48S03).
 *
 * @param id the tournament UUID
 * @returns the updated tournament with status COMPLETED
 * @throws Error with apiError and status if the request fails (e.g. 409 Conflict)
 */
export async function complete(id: string): Promise<Tournament> {
    const res = await apiFetch(`/api/tournaments/${id}/complete`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `complete failed: ${res.status}`), { apiError: err, status: res.status });
    }
    return res.json();
}

/**
 * Transitions a tournament from PLANNED or ACTIVE to CANCELLED (E48S03).
 *
 * @param id the tournament UUID
 * @returns the updated tournament with status CANCELLED
 * @throws Error with apiError and status if the request fails (e.g. 409 Conflict)
 */
export async function cancelTournament(id: string): Promise<Tournament> {
    const res = await apiFetch(`/api/tournaments/${id}/cancel`, { method: 'POST' });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(new Error(err.message ?? `cancel failed: ${res.status}`), { apiError: err, status: res.status });
    }
    return res.json();
}
