// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { apiFetch } from '../lib/api.js';

// ─────────────────────────────────────────────────────────────────
// Types
// ─────────────────────────────────────────────────────────────────

/**
 * A device as returned by the device management endpoints (E06S03, E06S05, E07S02, E49S01).
 *
 * Matches the shape of DeviceSummaryResponse on the backend.
 *
 * E49S01 AC3: `pin` removed — PIN is a one-time out-of-band credential and must not appear
 * in summary/list responses.
 * E49S01 AC1: `deviceName` is non-optional — SCORING_TABLET receives a unique name at
 * registration; DISPLAY devices receive a name via configure.
 */
export interface Device {
    /** Device UUID — primary key on the server. */
    id: string;
    /** Device type enum name: "SCORING_TABLET" | "DISPLAY". */
    deviceType: string;
    /** Lifecycle status: "REGISTERED" | "ASSIGNED" | "DISCONNECTED". */
    status: 'REGISTERED' | 'ASSIGNED' | 'DISCONNECTED';
    /** Assigned court field number, or null if unassigned. */
    assignedField: number | null;
    /** ISO-8601 timestamp when the device first registered. */
    registeredAt: string | null;
    /** Human-readable device name (E49S01 AC1 for SCORING_TABLET; E07S02 AC4 for DISPLAY). */
    deviceName: string | null;
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
 * Assigns a device to a court field (E06S05-AC2, AC4, E49S01 AC5).
 *
 * Endpoint: PUT /api/devices/{id}/assign
 * Auth: admin required.
 *
 * For SCORING_TABLET: `pin` must be provided and match the device's stored PIN (E49S01 AC5).
 * For DISPLAY: `pin` must be null.
 *
 * @param deviceId the device UUID
 * @param fieldNumber the court field number to assign (>= 1)
 * @param pin the PIN for SCORING_TABLET verification (null for DISPLAY)
 * @returns the updated device
 * @throws Error with status 409 on field conflict
 * @throws Error with status 403 on PIN mismatch (E49S01 AC5)
 * @throws Error with status 423 if device is PIN-locked (E49S01 AC6)
 * @throws Error with status 422 if PIN missing for tablet or unexpected for display (E49S01 AC5)
 * @throws Error on other failures
 */
export async function assignDevice(deviceId: string, fieldNumber: number, pin: string | null): Promise<Device> {
    const res = await apiFetch(`/api/devices/${encodeURIComponent(deviceId)}/assign`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fieldNumber, pin }),
    });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.detail ?? body.message ?? `Assign failed: ${res.status}`),
            { status: res.status, apiError: body }
        );
    }
    return res.json();
}

/**
 * Resets the PIN fail-counter for a SCORING_TABLET device (E49S01 AC6).
 *
 * Endpoint: POST /api/devices/{id}/pin-lock/reset
 * Auth: admin required.
 *
 * @param deviceId the device UUID
 * @throws Error with status 404 if the device is not found
 * @throws Error on other failures
 */
export async function resetPinLock(deviceId: string): Promise<void> {
    const res = await apiFetch(`/api/devices/${encodeURIComponent(deviceId)}/pin-lock/reset`, {
        method: 'POST',
    });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.message ?? `Reset PIN lock failed: ${res.status}`),
            { status: res.status }
        );
    }
}

/**
 * Renames a SCORING_TABLET device (E49S01 AC11).
 *
 * Endpoint: PUT /api/devices/{id}/rename
 * Auth: admin required.
 *
 * @param deviceId the device UUID
 * @param newName the new device name
 * @returns the updated device
 * @throws Error with status 422 if device is DISPLAY
 * @throws Error with status 404 if the device is not found
 * @throws Error on other failures
 */
export async function renameDevice(deviceId: string, newName: string): Promise<Device> {
    const res = await apiFetch(`/api/devices/${encodeURIComponent(deviceId)}/rename`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ newName }),
    });
    if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw Object.assign(
            new Error(body.detail ?? body.message ?? `Rename failed: ${res.status}`),
            { status: res.status }
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
