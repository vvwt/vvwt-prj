/**
 * Unit tests for deviceStore (E06S05, E07S03, E49S01).
 *
 * Verifies:
 * - Store exports the expected API functions
 * - API functions make the correct HTTP calls (mocked fetch)
 * - de.json contains all device translation keys (AC11)
 * - E07S03: configureDisplayDevice, removeDevice, getDisplayLimit (AC3, AC4, AC5)
 * - E07S03: de.json contains all display device and filter translation keys (AC9)
 * - E49S01: assignDevice with pin, resetPinLock, renameDevice (AC5, AC6, AC11)
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
    // E49S01 AC3: pin column removed from device list
    expect(cols).toHaveProperty('deviceType');
    expect(cols).toHaveProperty('assignedField');
    expect(cols).toHaveProperty('status');
    expect(cols).toHaveProperty('registeredAt');
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
    // E49S01 AC8: pinInputLabel / lookupButton / lookupError removed (PIN is inline per-row)
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
  it('should export all required API functions (E06S05 + E07S03 + E49S01)', async () => {
    const module = await import('./deviceStore.ts');
    expect(typeof module.listDevices).toBe('function');
    // E49S01 AC8: findDeviceByPin removed (no PIN lookup endpoint)
    expect(typeof module.assignDevice).toBe('function');
    expect(typeof module.unassignDevice).toBe('function');
    expect(typeof module.clearAllDevices).toBe('function');
    // E07S03 additions:
    expect(typeof module.configureDisplayDevice).toBe('function');
    expect(typeof module.removeDevice).toBe('function');
    expect(typeof module.getDisplayLimit).toBe('function');
    // E49S01 additions:
    expect(typeof module.resetPinLock).toBe('function');
    expect(typeof module.renameDevice).toBe('function');
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
        // E49S01 AC3: no pin in summary
        status: 'REGISTERED',
        assignedField: null,
        registeredAt: null,
        deviceName: 'Tablet-A1B2',
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

  it('assignDevice — PUT /api/devices/{id}/assign with PIN (E49S01 AC5)', async () => {
    const updatedDevice = {
      id: 'uuid-1',
      deviceType: 'SCORING_TABLET',
      status: 'ASSIGNED',
      assignedField: 3,
      registeredAt: null,
      deviceName: 'Tablet-A1B2',
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => updatedDevice,
    });

    const { assignDevice } = await import('./deviceStore.ts');
    const result = await assignDevice('uuid-1', 3, '4567');

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
    await expect(assignDevice('uuid-1', 3, '4567')).rejects.toMatchObject({ status: 409 });
  });

  it('assignDevice — 403 throws error with status 403 on PIN mismatch (E49S01 AC5)', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({ type: 'urn:vvwt:tm:device:pin-mismatch', detail: 'PIN does not match' }),
    });

    const { assignDevice } = await import('./deviceStore.ts');
    await expect(assignDevice('uuid-1', 1, '9999')).rejects.toMatchObject({ status: 403 });
  });

  it('assignDevice — 423 throws error with status 423 when device is PIN-locked (E49S01 AC6)', async () => {
    fetchSpy.mockResolvedValue({
      ok: false,
      status: 423,
      json: async () => ({ type: 'urn:vvwt:tm:device:pin-locked', detail: 'Device is locked' }),
    });

    const { assignDevice } = await import('./deviceStore.ts');
    await expect(assignDevice('uuid-1', 1, '4567')).rejects.toMatchObject({ status: 423 });
  });

  it('resetPinLock — POST /api/devices/{id}/pin-lock/reset returns void (E49S01 AC6)', async () => {
    fetchSpy.mockResolvedValue({ ok: true });

    const { resetPinLock } = await import('./deviceStore.ts');
    await expect(resetPinLock('uuid-1')).resolves.toBeUndefined();

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/uuid-1/pin-lock/reset'),
      expect.objectContaining({ method: 'POST' })
    );
  });

  it('renameDevice — PUT /api/devices/{id}/rename returns updated device (E49S01 AC11)', async () => {
    const updatedDevice = {
      id: 'uuid-1',
      deviceType: 'SCORING_TABLET',
      status: 'REGISTERED',
      assignedField: null,
      registeredAt: null,
      deviceName: 'Tablet-NEW1',
    };
    fetchSpy.mockResolvedValue({
      ok: true,
      json: async () => updatedDevice,
    });

    const { renameDevice } = await import('./deviceStore.ts');
    const result = await renameDevice('uuid-1', 'Tablet-NEW1');

    expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining('/api/devices/uuid-1/rename'),
      expect.objectContaining({ method: 'PUT' })
    );
    expect(result.deviceName).toBe('Tablet-NEW1');
  });

  it('unassignDevice — PUT /api/devices/{id}/unassign (AC3)', async () => {
    const unassignedDevice = {
      id: 'uuid-1',
      deviceType: 'SCORING_TABLET',
      // E49S01 AC3: no pin in summary
      status: 'REGISTERED',
      assignedField: null,
      registeredAt: null,
      deviceName: 'Tablet-A1B2',
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
      // E49S01 AC3: no pin in summary
      status: 'REGISTERED',
      assignedField: null,
      registeredAt: null,
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

// ─────────────────────────────────────────────────────────────────────────────
// E49S01 i18n coverage
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E49S01 translation keys', () => {
  it('should contain scoringTablet sub-keys for inline PIN assignment and rename', () => {
    const devices = (deMessages as Record<string, Record<string, Record<string, string>>>).devices;
    expect(devices).toHaveProperty('scoringTablet');
    const st = devices.scoringTablet;
    expect(st).toHaveProperty('pinInputLabel');
    expect(st).toHaveProperty('pinMismatch');
    expect(st).toHaveProperty('pinLocked');
    expect(st).toHaveProperty('pinLockResetButton');
    expect(st).toHaveProperty('renameButton');
    expect(st).toHaveProperty('renameLabel');
  });
});
