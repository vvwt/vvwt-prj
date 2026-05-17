// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { apiFetch } from '../lib/api.js';

interface PublicHostResponse {
    host: string;
    port: number;
    scheme: string;
}

let cachedOrigin: string | null = null;

/**
 * Returns the full origin string (scheme://host:port) from the backend's LAN-host detection.
 *
 * Cached after the first successful fetch. Falls back to window.location.origin if the
 * endpoint fails (AC-ERROR-NO-LAN-INTERFACE-FALLBACK — UI must never crash).
 *
 * @returns the public origin, e.g. 'http://192.168.1.42:8080'
 */
export async function getPublicOrigin(): Promise<string> {
    if (cachedOrigin !== null) {
        return cachedOrigin;
    }
    try {
        const resp = await apiFetch('/api/public-host');
        if (!resp.ok) {
            throw new Error(`HTTP ${resp.status}`);
        }
        const data: PublicHostResponse = await resp.json() as PublicHostResponse;
        cachedOrigin = `${data.scheme}://${data.host}:${data.port}`;
        return cachedOrigin;
    } catch {
        // Graceful fallback: use window.location.origin so the dialog still renders
        // (AC-ERROR-NO-LAN-INTERFACE-FALLBACK — never crash or show a blank dialog).
        return window.location.origin;
    }
}

/**
 * Clears the cached origin — for use in tests.
 */
export function clearPublicOriginCache(): void {
    cachedOrigin = null;
}
