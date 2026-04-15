/**
 * Activity type store and API functions for the Tournament Manager Admin SPA.
 *
 * Story E08S06 — AC1 (CRUD), AC2 (assignment preview), AC8 (i18n).
 *
 * All API functions use `apiFetch` from api.ts to include browser-cached basic-auth
 * credentials (same pattern as tournamentStore.ts — E05S02 AC7).
 */

import { apiFetch } from '../lib/api.js';

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────

/** An activity type as returned by GET/POST/PUT /api/tournaments/{id}/activity-types. */
export interface ActivityType {
    id: string;
    tournamentId: string;
    name: string;
    assignmentRule: string;
    capacityPerRound: number | null;
    sortOrder: number;
}

/** Request body for POST /api/tournaments/{id}/activity-types. */
export interface ActivityTypeCreateRequest {
    name: string;
    assignmentRule: string;
    capacityPerRound: number | null;
    sortOrder: number;
}

/** Request body for PUT /api/tournaments/{id}/activity-types/{activityId}. */
export interface ActivityTypeUpdateRequest {
    name: string;
    assignmentRule: string;
    capacityPerRound: number | null;
    sortOrder: number;
}

/** A team reference in the assignment preview response. */
export interface TeamRef {
    teamId: string;
    teamNumber: number;
    teamName: string;
}

/** Assignments for a single lap. */
export interface LapEntry {
    lapNumber: number;
    teams: TeamRef[];
}

/** Assignments for a single activity type. */
export interface ActivityTypeAssignment {
    activityTypeName: string;
    entries: LapEntry[];
}

/** Teams that could not be assigned for a given activity type. */
export interface UnassignedEntry {
    activityTypeName: string;
    teams: TeamRef[];
}

/**
 * Response from GET /api/tournaments/{id}/activity-assignments.
 * Mirrors ActivityAssignmentPreviewResponse on the backend.
 */
export interface ActivityAssignmentPreview {
    phaseId: string | null;
    assignments: ActivityTypeAssignment[];
    unassigned: UnassignedEntry[];
}

// ─────────────────────────────────────────────────────────────────
// API functions — Activity Type CRUD (AC1)
// ─────────────────────────────────────────────────────────────────

/**
 * Fetches all activity types for the given tournament, ordered by sortOrder.
 *
 * @param tournamentId the tournament UUID
 * @throws Error if the request fails
 */
export async function listActivityTypes(tournamentId: string): Promise<ActivityType[]> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/activity-types`);
    if (!res.ok) {
        throw new Error(`Failed to list activity types: ${res.status}`);
    }
    return res.json();
}

/**
 * Creates a new activity type (AC1 — POST).
 *
 * @param tournamentId the tournament UUID
 * @param data         the creation request
 * @returns the created activity type
 * @throws Error with the API error body if the request fails (including 409 for duplicate name)
 */
export async function createActivityType(
    tournamentId: string,
    data: ActivityTypeCreateRequest
): Promise<ActivityType> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/activity-types`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(err.message ?? `Create failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}

/**
 * Updates an activity type (AC1 — PUT).
 *
 * @param tournamentId the tournament UUID
 * @param activityId   the activity type UUID
 * @param data         the update request
 * @returns the updated activity type
 * @throws Error with the API error body if the request fails
 */
export async function updateActivityType(
    tournamentId: string,
    activityId: string,
    data: ActivityTypeUpdateRequest
): Promise<ActivityType> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/activity-types/${activityId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(err.message ?? `Update failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}

/**
 * Deletes an activity type (AC1 — DELETE).
 *
 * @param tournamentId the tournament UUID
 * @param activityId   the activity type UUID
 * @throws Error with the API error body if the request fails
 */
export async function deleteActivityType(
    tournamentId: string,
    activityId: string
): Promise<void> {
    const res = await apiFetch(
        `/api/tournaments/${tournamentId}/activity-types/${activityId}`,
        { method: 'DELETE' }
    );
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(err.message ?? `Delete failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
}

// ─────────────────────────────────────────────────────────────────
// API functions — Assignment Preview (AC2)
// ─────────────────────────────────────────────────────────────────

/**
 * Fetches the activity assignment preview for the tournament's current phase (AC2).
 *
 * Returns an empty preview (not an error) when:
 * - No activity types are configured (AC6)
 * - No phase exists or no matches are slotted (AC6 — phase not prepared)
 *
 * @param tournamentId the tournament UUID
 * @param phaseId      optional phase UUID to override auto-resolution
 * @throws Error if the request fails (e.g., 404 tournament not found)
 */
export async function getActivityAssignmentPreview(
    tournamentId: string,
    phaseId?: string
): Promise<ActivityAssignmentPreview> {
    const url = phaseId
        ? `/api/tournaments/${tournamentId}/activity-assignments?phaseId=${phaseId}`
        : `/api/tournaments/${tournamentId}/activity-assignments`;
    const res = await apiFetch(url);
    if (!res.ok) {
        throw new Error(`Failed to load activity assignments: ${res.status}`);
    }
    return res.json();
}
