/**
 * API client and types for the Device Management section of the admin SPA.
 *
 * Story E06S05 — AC1 (list), AC2 (PIN lookup), AC3 (unassign), AC5 (assign),
 * AC7 (clear all), AC9 (error handling).
 * Story E07S03 — AC3 (configure display device), AC4 (remove device), AC5 (display limit).
 *
 * All API functions use `apiFetch` from api.ts to include browser-cached basic-auth
 * credentials (E05S02 AC7). Auth is enforced server-side for all endpoints except
 * /api/devices/register and /api/devices/status (E06S03 design).
 */

import { apiFetch } from '../lib/api.js';

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────

/**
 * A device as returned by the device management endpoints (E06S03, E06S05, E07S02).
 *
 * Matches the shape of DeviceSummaryResponse on the backend.
 */
export interface Device {
    /** Device UUID — primary key on the server. */
    id: string;
    /** Device type enum name: "SCORING_TABLET" | "DISPLAY". */
    deviceType: string;
    /** Short numeric PIN shown on the tablet (null for DISPLAY devices). */
    pin: string | null;
    /** Lifecycle status: "REGISTERED" | "ASSIGNED" | "DISCONNECTED". */
    status: 'REGISTERED' | 'ASSIGNED' | 'DISCONNECTED';
    /** Assigned court field number, or null if unassigned. */
    assignedField: number | null;
    /** ISO-8601 timestamp when the device first registered. */
    registeredAt: string | null;
    /** ISO-8601 timestamp of the device's last heartbeat, or null. */
    lastSeenAt: string | null;
    /** Human-readable device name for display devices (E07S02 AC4). Null for scoring tablets. */
    deviceName?: string | null;
    /** JSON configuration string for display devices (E07S02 AC4). Null for scoring tablets. */
    configuration?: string | null;
}

// ─────────────────────────────────────────────────────────────────
// API functions
// ─────────────────────────────────────────────────────────────────

/**
 * Fetches all devices for the current tenant (E06S05-AC1).
 *
 * Endpoint: GET /api/devices/list
 * Auth: admin required.
 *
 * @returns list of all registered devices; never null
 * @throws Error if the request fails
 */
export async function listDevices(): Promise<Device[]> {
    const res = await apiFetch('/api/devices/list');
    if (!res.ok) {
        throw new Error(`Failed to list devices: ${res.status}`);
    }
    return res.json();
}

/**
 * Looks up a device by the PIN entered in the admin UI (E06S05-AC2).
 *
 * Endpoint: GET /api/devices?pin={pin}
 * Auth: admin required.
 *
 * @param pin the numeric PIN displayed on the tablet
 * @returns the matching device
 * @throws Error with status 404 if no device matches the PIN
 * @throws Error on other failures
 */
export async function findDeviceByPin(pin: string): Promise<Device> {
    const res = await apiFetch(`/api/devices?pin=${encodeURIComponent(pin)}`);
    if (res.status === 404) {
        const err = Object.assign(
            new Error(`No device found for PIN: ${pin}`),
            { status: 404 }
        );
        throw err;
    }
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.message ?? `PIN lookup failed: ${res.status}`),
            { status: res.status }
        );
    }
    return res.json();
}

/**
 * Assigns a device to a court field (E06S05-AC2, AC4).
 *
 * Endpoint: PUT /api/devices/{id}/assign
 * Auth: admin required.
 *
 * @param deviceId the device UUID
 * @param fieldNumber the court field number to assign (>= 1)
 * @returns the updated device
 * @throws Error with status 409 on field conflict (caller shows dialog — AC4)
 * @throws Error on other failures
 */
export async function assignDevice(deviceId: string, fieldNumber: number): Promise<Device> {
    const res = await apiFetch(`/api/devices/${encodeURIComponent(deviceId)}/assign`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fieldNumber }),
    });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.message ?? `Assign failed: ${res.status}`),
            { status: res.status, apiError: body }
        );
    }
    return res.json();
}

/**
 * Unassigns a device from its court field (E06S05-AC3).
 *
 * Endpoint: PUT /api/devices/{id}/unassign
 * Auth: admin required.
 * Idempotent: unassigning an already-unassigned device succeeds without error.
 *
 * @param deviceId the device UUID
 * @returns the updated device (status reverts to REGISTERED)
 * @throws Error if the request fails
 */
export async function unassignDevice(deviceId: string): Promise<Device> {
    const res = await apiFetch(`/api/devices/${encodeURIComponent(deviceId)}/unassign`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
    });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.message ?? `Unassign failed: ${res.status}`),
            { status: res.status }
        );
    }
    return res.json();
}

/**
 * Removes all registered devices for the current tenant (E06S05-AC7).
 *
 * Endpoint: DELETE /api/devices
 * Auth: admin required.
 * Idempotent: if no devices exist, completes without error.
 *
 * @throws Error if the request fails
 */
export async function clearAllDevices(): Promise<void> {
    const res = await apiFetch('/api/devices', { method: 'DELETE' });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.message ?? `Clear all failed: ${res.status}`),
            { status: res.status }
        );
    }
}

/**
 * Configures a DISPLAY device: sets its name and display schema (E07S03 AC3).
 *
 * Endpoint: PUT /api/devices/{id}/configure
 * Auth: admin required.
 *
 * @param deviceId the device UUID
 * @param deviceName the human-readable name for the display device
 * @param configuration the JSON configuration string (e.g. {"display_schema":"OVERVIEW"})
 * @returns the updated device
 * @throws Error if the request fails (400 if not a DISPLAY device, 404 if not found)
 */
export async function configureDisplayDevice(
    deviceId: string,
    deviceName: string,
    configuration: string
): Promise<Device> {
    const res = await apiFetch(`/api/devices/${encodeURIComponent(deviceId)}/configure`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ deviceName, configuration }),
    });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.message ?? `Configure failed: ${res.status}`),
            { status: res.status }
        );
    }
    return res.json();
}

/**
 * Removes a device (scoring tablet or display device) by ID (E07S03 AC4).
 *
 * Endpoint: DELETE /api/devices/{id}
 * Auth: admin required.
 *
 * @param deviceId the device UUID to remove
 * @throws Error with status 404 if the device is not found
 * @throws Error on other failures
 */
export async function removeDevice(deviceId: string): Promise<void> {
    const res = await apiFetch(`/api/devices/${encodeURIComponent(deviceId)}`, {
        method: 'DELETE',
    });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.message ?? `Remove device failed: ${res.status}`),
            { status: res.status }
        );
    }
}

/**
 * Fetches the configured maximum number of DISPLAY devices (E07S03 AC5).
 *
 * Endpoint: GET /api/devices/display-limit
 * Auth: admin required.
 *
 * @returns the max display device count
 * @throws Error if the request fails
 */
export async function getDisplayLimit(): Promise<number> {
    const res = await apiFetch('/api/devices/display-limit');
    if (!res.ok) {
        throw new Error(`Failed to get display limit: ${res.status}`);
    }
    const body = await res.json() as { maxDisplayCount: number };
    return body.maxDisplayCount;
}
