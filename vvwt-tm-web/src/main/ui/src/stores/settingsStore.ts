// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { apiFetch } from '../lib/api.js';

/**
 * Response from GET /api/settings.
 *
 * @see SettingsController (de.vvwt.tm.web)
 */
export interface SettingsResponse {
    /** Tenant display name — used to pre-fill the organizer field on the tournament create form (E68S01 AC1). */
    organizerDefault: string;
}

/**
 * Fetches tenant settings from GET /api/settings (E68S01).
 *
 * Used by TournamentForm.svelte to pre-fill the organizer field on the create form with the
 * organization's current display name.
 *
 * @throws Error if the request fails
 */
export async function getSettings(): Promise<SettingsResponse> {
    const res = await apiFetch('/api/settings');
    if (!res.ok) {
        throw new Error(`Failed to load settings: ${res.status}`);
    }
    return res.json();
}
