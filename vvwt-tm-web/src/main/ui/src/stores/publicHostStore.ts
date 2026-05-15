/**
 * Store for the server-detected LAN-reachable public host (E49S04).
 *
 * The admin SPA cannot determine the machine's LAN address from window.location.origin
 * when the operator opens the UI via loopback (http://127.0.0.1:8080). This store fetches
 * the server-detected (or explicitly configured) LAN host from GET /api/public-host so that
 * Devices.svelte and TimerLink.svelte can build QR-code URLs reachable from other LAN devices.
 *
 * Usage:
 *   import { getPublicOrigin } from '../stores/publicHostStore.js';
 *   const origin = await getPublicOrigin();   // e.g. 'http://192.168.1.42:8080'
 *
 * AC-TEST-REGISTRATION-URL-NOT-LOOPBACK-RED:
 *   The returned origin must use the server-detected host, NOT window.location.origin
 *   when that origin is loopback.
 *
 * AC-TEST-TIMER-URL-USES-LAN-HOST-RED:
 *   TimerLink.svelte uses the same origin from this store.
 *
 * AC-ERROR-NO-LAN-INTERFACE-FALLBACK:
 *   If the endpoint returns a loopback/localhost host (server found no LAN interface),
 *   the origin is constructed from that fallback value — the UI must not crash.
 *
 * DEC-15 / DEC-16: the server performs only local-interface enumeration; no external call.
 */

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
