// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { apiFetch } from '../lib/api.js';

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────

/** An intra-phase break within a draft section (E08S05 AC2). */
export interface DraftBreak {
    afterLapNumber: number;
    durationMinutes: number;
    label: string | null;
}

/** One section in the draft configuration. */
export interface DraftSection {
    sectionNumber: number;
    sortType: string;
    groupCount: number;
    gameMode: string;
    lapBreakTimeMinutes: number;
    sectionBreakTimeMinutes: number;
    lapTimeMinutes: number;
    setQuantity: number;
    breaks: DraftBreak[];  // E08S05 AC2 — may be empty
    /**
     * Team distribution algorithm for Phase-1 avatar assignment.
     * Optional (absent on legacy draft_json) — defaults to 'sequential' in addSection().
     * Valid values: 'sequential' (default) | 'round_robin' (legacy).
     * E51S15 — AC-TEST-UI-DROPDOWN-DEFAULT-SEQUENTIAL-RED.
     */
    distributionMode?: string;
}

/** The full draft configuration (ordered sections). */
export interface DraftConfig {
    sections: DraftSection[];
}

/** Structural preview for one draft section (E05S06 AC4). */
export interface DraftPreviewSection {
    phaseNumber: number;
    groupCount: number;
    teamsPerGroup: number;
    matchesPerGroup: number;
    totalLaps: number;
    totalMatches: number;
    estimatedTimeMinutes: number;
}

/** One entry in the timeline preview (E08S05 AC3). */
export interface DraftTimelineEntry {
    phaseNumber: number;
    lapNumber: number;
    type: 'MATCH_ROUND' | 'LAP_BREAK' | 'INTRA_PHASE_BREAK' | 'SECTION_BREAK';
    startTime: string;   // HH:mm:ss from LocalTime serialization
    endTime: string;
    label: string | null;
}

/** Draft preview response: structural sections + optional timeline (E08S05 AC3). */
export interface DraftPreview {
    sections: DraftPreviewSection[];
    timeline: DraftTimelineEntry[];  // empty when plannedStartTime is not set
}

/** Response from the apply endpoint: list of created phase IDs. */
export interface DraftApplyResponse {
    phaseIds: string[];
}

// ─────────────────────────────────────────────────────────────────
// API functions
// ─────────────────────────────────────────────────────────────────

/**
 * Fetches the current draft configuration for a tournament (E05S06 AC3).
 *
 * @param tournamentId the tournament UUID
 * @throws Error if the request fails
 */
export async function getDraft(tournamentId: string): Promise<DraftConfig> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/draft`);
    if (!res.ok) {
        throw new Error(`Failed to load draft: ${res.status}`);
    }
    return res.json();
}

/**
 * Saves the draft configuration for a tournament (E05S06 AC2).
 *
 * @param tournamentId the tournament UUID
 * @param config       the draft configuration to save
 * @throws Error with API error body if the request fails
 */
export async function saveDraft(tournamentId: string, config: DraftConfig): Promise<DraftConfig> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/draft`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error((err as { message?: string }).message ?? `Save failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}

/**
 * Calculates a preview of the draft without creating any entities (E05S06 AC4; E08S05 AC3;
 * E21S21 AC-IMPL-FE-DRAFTSTORE-BODY).
 *
 * @param tournamentId the tournament UUID
 * @param config       the current draft configuration to preview (sent as JSON body)
 * @throws Error if the request fails or the draft has no sections
 */
export async function previewDraft(tournamentId: string, config: DraftConfig): Promise<DraftPreview> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/draft/preview`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error((err as { message?: string }).message ?? `Preview failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}

/**
 * Applies the draft configuration: creates Phase entities and transitions the tournament
 * to PLANNED status (E05S06 AC5; E08S05 AC7 — persists PhaseBreak entities;
 * E21S21 AC-IMPL-FE-DRAFTSTORE-BODY).
 *
 * @param tournamentId the tournament UUID
 * @param config       the draft configuration to apply (sent as JSON body)
 * @throws Error if the request fails
 */
export async function applyDraft(tournamentId: string, config: DraftConfig): Promise<DraftApplyResponse> {
    const res = await apiFetch(`/api/tournaments/${tournamentId}/draft/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config),
    });
    if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error((err as { message?: string }).message ?? `Apply failed: ${res.status}`),
            { apiError: err, status: res.status }
        );
    }
    return res.json();
}
