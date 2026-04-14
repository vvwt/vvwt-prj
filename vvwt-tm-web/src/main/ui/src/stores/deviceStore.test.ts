/**
 * Unit tests for deviceStore (E06S05).
 *
 * Verifies:
 * - Store exports the expected API functions
 * - API functions make the correct HTTP calls (mocked fetch)
 * - AC9 error handling: 404 on PIN lookup throws with status 404
 * - de.json contains all device translation keys (AC11)
 */

import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// i18n coverage — AC11
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — device translations (AC11)', () => {
  it('should contain the devices namespace', () => {
    expect(deMessages).toHaveProperty('devices');
  });

  it('should contain all required device column headers', () => {
    const devices = (deMessages as Record<string, Record<string, Record<string, string>>>).devices;
    const cols = devices.columns;
    expect(cols).toHaveProperty('pin');
    expect(cols).toHaveProperty('deviceType');
    expect(cols).toHaveProperty('assignedField');
    expect(cols).toHaveProperty('status');
    expect(cols).toHaveProperty('lastSeen');
  });

  it('should contain all device status labels', () => {
    const devices = (deMessages as Record<string, Record<string, Record<string, string>>>).devices;
    const statuses = devices.status;
    expect(statuses).toHaveProperty('REGISTERED');
    expect(statuses).toHaveProperty('ASSIGNED');
    expect(statuses).toHaveProperty('DISCONNECTED');
  });

  it('should contain device action buttons and messages', () => {
    const devices = (deMessages as Record<string, Record<string, string>>).devices;
    expect(devices).toHaveProperty('title');
    expect(devices).toHaveProperty('empty');
    expect(devices).toHaveProperty('clearAllButton');
    expect(devices).toHaveProperty('clearAllConfirm');
    expect(devices).toHaveProperty('showQrButton');
    expect(devices).toHaveProperty('pinInputLabel');
    expect(devices).toHaveProperty('lookupButton');
    expect(devices).toHaveProperty('lookupError');
    expect(devices).toHaveProperty('assignButton');
    expect(devices).toHaveProperty('unassignButton');
    expect(devices).toHaveProperty('fieldConflictConfirm');
  });

  it('should contain devices nav label', () => {
    const nav = (deMessages as Record<string, Record<string, string>>).nav;
    expect(nav).toHaveProperty('devices');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// deviceStore — exported API functions
// ─────────────────────────────────────────────────────────────────────────────

describe('deviceStore — exported API functions', () => {
  it('should export all required API functions', async () => {
    const module = await import('./deviceStore.ts');
    expect(typeof module.listDevices).toBe('function');
    expect(typeof module.findDeviceByPin).toBe('function');
    expect(typeof module.assignDevice).toBe('function');
    expect(typeof module.unassignDevice).toBe('function');
    expect(typeof module.clearAllDevices).toBe('function');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// deviceStore — API integration (mocked fetch) — AC1, AC2, AC3, AC7, AC9
// ─────────────────────────────────────────────────────────────────────────────

describe('deviceStore — API calls (mocked fetch)', () => {
  let fetchSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    fetchSpy = vi.fn();
    global.fetch = fetchSpy;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('listDevices — GET /api/devices/list returns device array (AC1)', async () => {
    const mockDevices = [
      {
        id: 'uuid-1',
        deviceType: 'SCORING_TABLET',
        pin: '1234',
        status: 'REGISTERED',
        assignedField: null,
        registeredAt: null,
        lastSeenAt: null,
      },
    ];
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => mockDevices,
    });

    const { listDevices } = await import('./deviceStore.ts');
    const result = await listDevices();

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/list'),
      expect.objectContaining({ credentials: 'same-origin' })
    );
    expect(result).toEqual(mockDevices);
  });

  it('findDeviceByPin — GET /api/devices?pin=1234 returns device (AC2)', async () => {
    const mockDevice = {
      id: 'uuid-1',
      deviceType: 'SCORING_TABLET',
      pin: '1234',
      status: 'REGISTERED',
      assignedField: null,
      registeredAt: null,
      lastSeenAt: null,
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => mockDevice,
    });

    const { findDeviceByPin } = await import('./deviceStore.ts');
    const result = await findDeviceByPin('1234');

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices?pin=1234'),
      expect.objectContaining({ credentials: 'same-origin' })
    );
    expect(result).toEqual(mockDevice);
  });

  it('findDeviceByPin — 404 throws error with status 404 (AC9)', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 404,
    });

    const { findDeviceByPin } = await import('./deviceStore.ts');
    await expect(findDeviceByPin('9999')).rejects.toMatchObject({ status: 404 });
  });

  it('assignDevice — PUT /api/devices/{id}/assign (AC2)', async () => {
    const updatedDevice = {
      id: 'uuid-1',
      deviceType: 'SCORING_TABLET',
      pin: '1234',
      status: 'ASSIGNED',
      assignedField: 3,
      registeredAt: null,
      lastSeenAt: null,
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => updatedDevice,
    });

    const { assignDevice } = await import('./deviceStore.ts');
    const result = await assignDevice('uuid-1', 3);

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/uuid-1/assign'),
      expect.objectContaining({ method: 'PUT' })
    );
    expect(result.assignedField).toBe(3);
    expect(result.status).toBe('ASSIGNED');
  });

  it('assignDevice — 409 throws error with status 409 (AC4/AC9)', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ message: 'Field occupied' }),
    });

    const { assignDevice } = await import('./deviceStore.ts');
    await expect(assignDevice('uuid-1', 3)).rejects.toMatchObject({ status: 409 });
  });

  it('unassignDevice — PUT /api/devices/{id}/unassign (AC3)', async () => {
    const unassignedDevice = {
      id: 'uuid-1',
      deviceType: 'SCORING_TABLET',
      pin: '1234',
      status: 'REGISTERED',
      assignedField: null,
      registeredAt: null,
      lastSeenAt: null,
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => unassignedDevice,
    });

    const { unassignDevice } = await import('./deviceStore.ts');
    const result = await unassignDevice('uuid-1');

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/uuid-1/unassign'),
      expect.objectContaining({ method: 'PUT' })
    );
    expect(result.status).toBe('REGISTERED');
    expect(result.assignedField).toBeNull();
  });

  it('clearAllDevices — DELETE /api/devices returns void (AC7)', async () => {
    fetchSpy.mockResolvedValue({
      ok: true,
    });

    const { clearAllDevices } = await import('./deviceStore.ts');
    await expect(clearAllDevices()).resolves.toBeUndefined();

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices'),
      expect.objectContaining({ method: 'DELETE' })
    );
  });
});
