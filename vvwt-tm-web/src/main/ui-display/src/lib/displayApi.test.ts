/**
 * Unit tests for displayApi.ts (E07S05, AC9).
 *
 * Tests focus on error classification: the displayFetch helper must throw the
 * correct typed error based on HTTP status code:
 *   - 401 → UnauthorizedError
 *   - 404 → NoActivePhaseError
 *   - 500 → ApiError with status 500
 *   - 200 → returns parsed JSON body
 *
 * Also tests the localStorage token reader (readDeviceToken).
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  fetchPhaseOverview,
  fetchMatches,
  fetchGroupStandings,
  readDeviceToken,
  UnauthorizedError,
  NoActivePhaseError,
  ApiError,
  DEVICE_TOKEN_STORAGE_KEY,
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
// Token reader tests (AC5, AC11)
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
