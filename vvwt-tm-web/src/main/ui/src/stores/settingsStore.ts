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

    /**
     * Width component of the global crop aspect ratio (E71S01 AC3).
     * Together with cropAspectRatioHeight defines the W:H ratio locked in the crop step.
     * Default: 11 (ratio 11:5). Server-configured, ENV-overridable.
     */
    cropAspectRatioWidth: number;

    /**
     * Height component of the global crop aspect ratio (E71S01 AC3).
     * Together with cropAspectRatioWidth defines the W:H ratio locked in the crop step.
     * Default: 5 (ratio 11:5). Server-configured, ENV-overridable.
     */
    cropAspectRatioHeight: number;

    /**
     * Maximum long-edge pixel length after downscale (E71S01 AC3).
     * Images already smaller are not upscaled.
     * Default: 2200. Server-configured, ENV-overridable.
     */
    cropMaxLongEdge: number;
}

/**
 * Fetches tenant settings from GET /api/settings (E68S01, E71S01).
 *
 * Used by TournamentForm.svelte to pre-fill the organizer field on the create form, and by
 * TeamPhotos.svelte to load the crop aspect ratio and max long edge for the photo crop step.
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
