// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { apiFetch } from '../lib/api.js';

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────

/** A team as returned by the REST API (AC1 — E05S05). */
export interface Team {
    id: string;
    tournamentId: string;
    teamNumber: number;
    description: string;
    participate: boolean;
    refereeAssignment: boolean;
    withoutAssessment: boolean;
    createdAt: string;
    /** Whether a team photo has been uploaded for this team (E12S02 AC4, E12S03 AC1). */
    hasPhoto: boolean;
}

/** Request body for POST /api/tournaments/{id}/teams (AC2). */
export interface TeamCreateRequest {
    description: string;
    teamNumber?: number | null;
    participate?: boolean;
    refereeAssignment?: boolean;
    withoutAssessment?: boolean;
}

/** Request body for PUT /api/tournaments/{id}/teams/{teamId} (AC3). */
export interface TeamUpdateRequest {
    description?: string | null;
    teamNumber?: number | null;
    participate: boolean;
    refereeAssignment: boolean;
    withoutAssessment: boolean;
}

/** Request body for bulk create (AC5). */
export interface TeamBulkCreateRequest {
    teams: TeamCreateRequest[];
}

/** Per-item result from bulk create (AC5). */
export interface BulkItemResult {
    team: Team | null;
    errorMessage: string | null;
    success: boolean;
}

/** Response from bulk create (AC5). */
export interface TeamBulkCreateResponse {
    results: BulkItemResult[];
}

// ─────────────────────────────────────────────────────────────────
// API functions
// ─────────────────────────────────────────────────────────────────

/**
 * Fetches all teams for the given tournament, ordered by team_number ascending (AC1).
 *
 * @param tournamentId the tournament UUID
 * @throws Error if the request fails or tournament not found
 */
export async function listTeams(tournamentId: string): Promise<Team[]> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/teams`);
    if (!res.ok) {
        throw new Error(`Failed to list teams: ${res.status}`);
    }
    return res.json();
}

/**
 * Creates a new team in the given tournament (AC2).
 *
 * @param tournamentId the tournament UUID
 * @param data         the creation request
 * @returns the created team (HTTP 201)
 * @throws Error with API error body if the request fails
 */
export async function createTeam(tournamentId: string, data: TeamCreateRequest): Promise<Team> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/teams`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(err.message ?? `Create team failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}

/**
 * Updates an existing team (AC3). Only DRAFT tournaments; HTTP 409 otherwise.
 *
 * @param tournamentId the tournament UUID
 * @param teamId       the team UUID
 * @param data         the update request
 * @returns the updated team
 * @throws Error with API error body if the request fails
 */
export async function updateTeam(
    tournamentId: string,
    teamId: string,
    data: TeamUpdateRequest
): Promise<Team> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/teams/${teamId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(err.message ?? `Update team failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}

/**
 * Deletes a team (AC4). Only DRAFT tournaments with no avatar references.
 *
 * @param tournamentId the tournament UUID
 * @param teamId       the team UUID
 * @throws Error with API error body if the request fails
 */
export async function deleteTeam(tournamentId: string, teamId: string): Promise<void> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/teams/${teamId}`, {
        method: 'DELETE',
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(err.message ?? `Delete team failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
}

/**
 * Bulk creates multiple teams in a single request (AC5).
 *
 * @param tournamentId the tournament UUID
 * @param data         the bulk creation request
 * @returns per-item results
 * @throws Error if the request fails entirely (network error / 404)
 */
export async function bulkCreateTeams(
    tournamentId: string,
    data: TeamBulkCreateRequest
): Promise<TeamBulkCreateResponse> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/teams/bulk`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(err.message ?? `Bulk create failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}
