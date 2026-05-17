// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  fetchPhaseOverview,
  fetchMatches,
  fetchGroupStandings,
  readDeviceToken,
  writeDeviceToken,
  clearDeviceToken,
  registerDisplayDevice,
  pollDeviceStatus,
  UnauthorizedError,
  NoActivePhaseError,
  DeviceLimitError,
  DeviceRemovedError,
  ApiError,
  DEVICE_TOKEN_STORAGE_KEY,
  DISPLAY_DEVICE_TYPE,
} from './displayApi.js';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function mockFetchResponse(status: number, body: unknown): void {
  const mockResponse = {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response;
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));
}

// ---------------------------------------------------------------------------
// Error classification tests (AC9)
// ---------------------------------------------------------------------------

describe('fetchPhaseOverview', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns parsed response on HTTP 200 (AC5)', async () => {
    const mockOverview = {
      phaseId: 'phase-1',
      phaseName: 'Round 1',
      phaseStatus: 'ACTIVE',
      lapCount: 3,
      currentLap: 1,
      fieldCount: 2,
      preparationPreview: false,
      groups: [],
    };
    mockFetchResponse(200, mockOverview);

    const result = await fetchPhaseOverview('test-token');

    expect(result).toEqual(mockOverview);
  });

  it('throws UnauthorizedError on HTTP 401 (AC9)', async () => {
    mockFetchResponse(401, { error: 'Unauthorized' });

    await expect(fetchPhaseOverview('bad-token')).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it('throws NoActivePhaseError on HTTP 404 (AC6)', async () => {
    mockFetchResponse(404, { status: 'NO_ACTIVE_PHASE' });

    await expect(fetchPhaseOverview('valid-token')).rejects.toBeInstanceOf(NoActivePhaseError);
  });

  it('throws ApiError on HTTP 500 (AC9)', async () => {
    mockFetchResponse(500, { error: 'Internal Server Error' });

    await expect(fetchPhaseOverview('valid-token')).rejects.toBeInstanceOf(ApiError);
  });

  it('ApiError on 500 carries the status code', async () => {
    mockFetchResponse(500, {});

    try {
      await fetchPhaseOverview('valid-token');
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(500);
    }
  });

  it('encodes token in URL query parameter (AC5)', async () => {
    mockFetchResponse(200, {
      phaseId: 'p',
      phaseName: 'P',
      phaseStatus: 'ACTIVE',
      lapCount: 1,
      currentLap: 1,
      fieldCount: 1,
      preparationPreview: false,
      groups: [],
    });

    await fetchPhaseOverview('tok en+special');

    const fetchMock = vi.mocked(fetch);
    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).toContain('token=tok%20en%2Bspecial');
  });
});

describe('fetchMatches', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('throws UnauthorizedError on HTTP 401 (AC9)', async () => {
    mockFetchResponse(401, {});

    await expect(fetchMatches('bad-token')).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it('throws NoActivePhaseError on HTTP 404 (AC6)', async () => {
    mockFetchResponse(404, {});

    await expect(fetchMatches('valid-token')).rejects.toBeInstanceOf(NoActivePhaseError);
  });

  it('omits lap param when not provided (AC5 — defaults to current lap)', async () => {
    mockFetchResponse(200, { phaseId: 'p', lap: 1, matches: [] });

    await fetchMatches('token');

    const fetchMock = vi.mocked(fetch);
    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).not.toContain('&lap=');
  });

  it('includes lap param when provided (AC5)', async () => {
    mockFetchResponse(200, { phaseId: 'p', lap: 2, matches: [] });

    await fetchMatches('token', 2);

    const fetchMock = vi.mocked(fetch);
    const calledUrl = fetchMock.mock.calls[0][0] as string;
    expect(calledUrl).toContain('&lap=2');
  });
});

describe('fetchGroupStandings', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('throws UnauthorizedError on HTTP 401 (AC9)', async () => {
    mockFetchResponse(401, {});

    await expect(fetchGroupStandings('bad-token')).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it('throws NoActivePhaseError on HTTP 404 (AC6)', async () => {
    mockFetchResponse(404, {});

    await expect(fetchGroupStandings('valid-token')).rejects.toBeInstanceOf(NoActivePhaseError);
  });
});

// ---------------------------------------------------------------------------
// Token reader tests (E07S05 AC5, E07S07 AC11)
// ---------------------------------------------------------------------------

describe('readDeviceToken', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns null when no token in localStorage (AC11)', () => {
    expect(readDeviceToken()).toBeNull();
  });

  it('returns stored token when present (AC5)', () => {
    localStorage.setItem(DEVICE_TOKEN_STORAGE_KEY, 'my-token-value');
    expect(readDeviceToken()).toBe('my-token-value');
  });

  it('uses the correct localStorage key (AC11)', () => {
    expect(DEVICE_TOKEN_STORAGE_KEY).toBe('vvwt_device_token');
  });
});

// ---------------------------------------------------------------------------
// E07S07 — writeDeviceToken and clearDeviceToken (AC11, AC7)
// ---------------------------------------------------------------------------

describe('writeDeviceToken', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('writes the token to localStorage with the correct key (AC11)', () => {
    writeDeviceToken('my-device-token');

    expect(localStorage.getItem(DEVICE_TOKEN_STORAGE_KEY)).toBe('my-device-token');
  });

  it('overwrites an existing token (AC11)', () => {
    localStorage.setItem(DEVICE_TOKEN_STORAGE_KEY, 'old-token');

    writeDeviceToken('new-token');

    expect(localStorage.getItem(DEVICE_TOKEN_STORAGE_KEY)).toBe('new-token');
  });
});

describe('clearDeviceToken', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('removes the token from localStorage (AC7)', () => {
    localStorage.setItem(DEVICE_TOKEN_STORAGE_KEY, 'token-to-clear');

    clearDeviceToken();

    expect(localStorage.getItem(DEVICE_TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('is idempotent — no error when no token exists (AC7)', () => {
    // Should not throw
    expect(() => clearDeviceToken()).not.toThrow();
  });
});

// ---------------------------------------------------------------------------
// E07S07 — registerDisplayDevice (AC1, AC6, AC9)
// ---------------------------------------------------------------------------

describe('registerDisplayDevice', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns deviceToken on HTTP 201 (AC1)', async () => {
    const mockResponse = {
      ok: true,
      status: 201,
      json: async () => ({ deviceToken: 'tok-abc123', pin: null }),
    } as unknown as Response;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await registerDisplayDevice();

    expect(result.deviceToken).toBe('tok-abc123');
    expect(result.pin).toBeNull();
  });

  it('sends POST /api/devices/register with deviceType DISPLAY (AC1)', async () => {
    const mockResponse = {
      ok: true,
      status: 201,
      json: async () => ({ deviceToken: 'tok-xyz', pin: null }),
    } as unknown as Response;
    const fetchSpy = vi.fn().mockResolvedValue(mockResponse);
    vi.stubGlobal('fetch', fetchSpy);

    await registerDisplayDevice();

    expect(fetchSpy).toHaveBeenCalledWith('/api/devices/register', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ deviceType: DISPLAY_DEVICE_TYPE }),
    }));
  });

  it('throws DeviceLimitError on HTTP 429 (AC6)', async () => {
    const mockResponse = {
      ok: false,
      status: 429,
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    await expect(registerDisplayDevice()).rejects.toBeInstanceOf(DeviceLimitError);
  });

  it('throws ApiError on HTTP 500 (AC9)', async () => {
    const mockResponse = {
      ok: false,
      status: 500,
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    await expect(registerDisplayDevice()).rejects.toBeInstanceOf(ApiError);
  });

  it('DISPLAY_DEVICE_TYPE constant is "DISPLAY" (AC1)', () => {
    expect(DISPLAY_DEVICE_TYPE).toBe('DISPLAY');
  });
});

// ---------------------------------------------------------------------------
// E07S07 — pollDeviceStatus (AC3, AC5, AC7, AC9)
// ---------------------------------------------------------------------------

describe('pollDeviceStatus', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('returns status result on HTTP 200 (AC3)', async () => {
    const statusBody = { status: 'REGISTERED', configuration: null, deviceName: null };
    const mockResponse = {
      ok: true,
      status: 200,
      json: async () => statusBody,
    } as unknown as Response;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await pollDeviceStatus('test-token');

    expect(result.status).toBe('REGISTERED');
    expect(result.configuration).toBeNull();
  });

  it('returns non-null configuration when device is configured (AC3, AC4)', async () => {
    const config = JSON.stringify({ display_schema: 'OVERVIEW' });
    const statusBody = { status: 'REGISTERED', configuration: config, deviceName: 'Screen A' };
    const mockResponse = {
      ok: true,
      status: 200,
      json: async () => statusBody,
    } as unknown as Response;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    const result = await pollDeviceStatus('test-token');

    expect(result.configuration).toBe(config);
    expect(result.deviceName).toBe('Screen A');
  });

  it('throws DeviceRemovedError on HTTP 404 (AC7)', async () => {
    const mockResponse = {
      ok: false,
      status: 404,
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    await expect(pollDeviceStatus('stale-token')).rejects.toBeInstanceOf(DeviceRemovedError);
  });

  it('throws ApiError on HTTP 500 (AC9)', async () => {
    const mockResponse = {
      ok: false,
      status: 500,
      json: async () => ({}),
    } as unknown as Response;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(mockResponse));

    await expect(pollDeviceStatus('test-token')).rejects.toBeInstanceOf(ApiError);
  });

  it('encodes token in URL query parameter (AC3)', async () => {
    const mockResponse = {
      ok: true,
      status: 200,
      json: async () => ({ status: 'REGISTERED', configuration: null, deviceName: null }),
    } as unknown as Response;
    const fetchSpy = vi.fn().mockResolvedValue(mockResponse);
    vi.stubGlobal('fetch', fetchSpy);

    await pollDeviceStatus('tok with spaces');

    const calledUrl = fetchSpy.mock.calls[0][0] as string;
    expect(calledUrl).toContain('token=tok%20with%20spaces');
  });
});
