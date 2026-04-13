/**
 * API client and types for the Device Management section of the admin SPA.
 *
 * Story E06S05 — AC1 (list), AC2 (PIN lookup), AC3 (unassign), AC5 (assign),
 * AC7 (clear all), AC9 (error handling).
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
 * A device as returned by the device management endpoints (E06S03, E06S05).
 *
 * Matches the shape of DeviceSummaryResponse on the backend.
 */
export interface Device {
    /** Device UUID — primary key on the server. */
    id: string;
    /** Device type enum name (e.g. "SCORING_TABLET"). */
    deviceType: string;
    /** Short numeric PIN shown on the tablet. */
    pin: string;
    /** Lifecycle status: "REGISTERED" | "ASSIGNED" | "DISCONNECTED". */
    status: 'REGISTERED' | 'ASSIGNED' | 'DISCONNECTED';
    /** Assigned court field number, or null if unassigned (AC2). */
    assignedField: number | null;
    /** ISO-8601 timestamp when the device first registered. */
    registeredAt: string | null;
    /** ISO-8601 timestamp of the device's last heartbeat, or null. */
    lastSeenAt: string | null;
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
 * Returns:
 *   200 OK   — device assigned
 *   409 Conflict — field already occupied by another device
 *   400 Bad Request — field number invalid
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
