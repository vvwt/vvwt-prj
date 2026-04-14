/**
 * Unit tests for deviceStore (E06S05, E07S03).
 *
 * Verifies:
 * - Store exports the expected API functions
 * - API functions make the correct HTTP calls (mocked fetch)
 * - AC9 error handling: 404 on PIN lookup throws with status 404
 * - de.json contains all device translation keys (AC11)
 * - E07S03: configureDisplayDevice, removeDevice, getDisplayLimit (AC3, AC4, AC5)
 * - E07S03: de.json contains all display device and filter translation keys (AC9)
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
  it('should export all required API functions (E06S05 + E07S03)', async () => {
    const module = await import('./deviceStore.ts');
    expect(typeof module.listDevices).toBe('function');
    expect(typeof module.findDeviceByPin).toBe('function');
    expect(typeof module.assignDevice).toBe('function');
    expect(typeof module.unassignDevice).toBe('function');
    expect(typeof module.clearAllDevices).toBe('function');
    // E07S03 additions:
    expect(typeof module.configureDisplayDevice).toBe('function');
    expect(typeof module.removeDevice).toBe('function');
    expect(typeof module.getDisplayLimit).toBe('function');
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

  it('configureDisplayDevice — PUT /api/devices/{id}/configure returns updated device (E07S03 AC3)', async () => {
    const updatedDevice = {
      id: 'uuid-display-1',
      deviceType: 'DISPLAY',
      pin: null,
      status: 'REGISTERED',
      assignedField: null,
      registeredAt: null,
      lastSeenAt: null,
      deviceName: 'Main Screen',
      configuration: '{"display_schema":"OVERVIEW"}',
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => updatedDevice,
    });

    const { configureDisplayDevice } = await import('./deviceStore.ts');
    const result = await configureDisplayDevice('uuid-display-1', 'Main Screen', '{"display_schema":"OVERVIEW"}');

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/uuid-display-1/configure'),
      expect.objectContaining({ method: 'PUT' })
    );
    expect(result.deviceName).toBe('Main Screen');
    expect(result.configuration).toBe('{"display_schema":"OVERVIEW"}');
  });

  it('removeDevice — DELETE /api/devices/{id} returns void (E07S03 AC4)', async () => {
    fetchSpy.mockResolvedValue({
      ok: true,
    });

    const { removeDevice } = await import('./deviceStore.ts');
    await expect(removeDevice('uuid-display-1')).resolves.toBeUndefined();

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/uuid-display-1'),
      expect.objectContaining({ method: 'DELETE' })
    );
  });

  it('removeDevice — 404 throws error with status 404 (E07S03 AC4/AC8)', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 404,
      json: async () => ({ message: 'Device not found' }),
    });

    const { removeDevice } = await import('./deviceStore.ts');
    await expect(removeDevice('unknown-id')).rejects.toMatchObject({ status: 404 });
  });

  it('getDisplayLimit — GET /api/devices/display-limit returns maxDisplayCount (E07S03 AC5)', async () => {
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => ({ maxDisplayCount: 4 }),
    });

    const { getDisplayLimit } = await import('./deviceStore.ts');
    const limit = await getDisplayLimit();

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/display-limit'),
      expect.objectContaining({ credentials: 'same-origin' })
    );
    expect(limit).toBe(4);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E07S03 i18n coverage — AC9
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E07S03 translation keys (AC9)', () => {
  it('should contain filter tab labels', () => {
    const devices = (deMessages as Record<string, Record<string, Record<string, string>>>).devices;
    expect(devices).toHaveProperty('filter');
    expect(devices.filter).toHaveProperty('all');
    expect(devices.filter).toHaveProperty('scoringTablets');
    expect(devices.filter).toHaveProperty('displayDevices');
  });

  it('should contain displayDevice sub-keys', () => {
    const devices = (deMessages as Record<string, Record<string, Record<string, string>>>).devices;
    expect(devices).toHaveProperty('displayDevice');
    const dd = devices.displayDevice;
    expect(dd).toHaveProperty('unnamed');
    expect(dd).toHaveProperty('configuredStatus');
    expect(dd).toHaveProperty('pendingStatus');
    expect(dd).toHaveProperty('configureButton');
    expect(dd).toHaveProperty('nameLabel');
    expect(dd).toHaveProperty('namePlaceholder');
    expect(dd).toHaveProperty('schemaLabel');
    expect(dd).toHaveProperty('schemaOption');
    expect(dd).toHaveProperty('saveButton');
    expect(dd).toHaveProperty('cancelButton');
    expect(dd).toHaveProperty('saveError');
    expect(dd).toHaveProperty('limitIndicator');
    expect(dd).toHaveProperty('limitReached');
  });

  it('should contain remove action labels', () => {
    const devices = (deMessages as Record<string, Record<string, string>>).devices;
    expect(devices).toHaveProperty('removeButton');
    expect(devices).toHaveProperty('removeConfirm');
    expect(devices).toHaveProperty('removeError');
  });

  it('should contain extended column headers (name, configStatus)', () => {
    const devices = (deMessages as Record<string, Record<string, Record<string, string>>>).devices;
    expect(devices.columns).toHaveProperty('name');
    expect(devices.columns).toHaveProperty('configStatus');
  });

  it('should contain DISPLAY deviceType label', () => {
    const devices = (deMessages as Record<string, Record<string, Record<string, string>>>).devices;
    expect(devices.deviceType).toHaveProperty('DISPLAY');
    expect(devices.deviceType).toHaveProperty('SCORING_TABLET');
  });
});
