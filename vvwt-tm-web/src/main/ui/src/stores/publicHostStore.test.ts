// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearPublicOriginCache, getPublicOrigin } from './publicHostStore.js';

// ─────────────────────────────────────────────────────────────────────────────
// AC-TEST-REGISTRATION-URL-NOT-LOOPBACK-RED
// getPublicOrigin() must return the server-provided LAN origin, NOT window.location.origin.
// ─────────────────────────────────────────────────────────────────────────────

describe('getPublicOrigin — E49S04 LAN host detection', () => {
    beforeEach(() => {
        clearPublicOriginCache();
        vi.restoreAllMocks();
    });

    afterEach(() => {
        clearPublicOriginCache();
        vi.restoreAllMocks();
    });

    it('returns LAN origin from backend when server detects a site-local address', async () => {
        // Arrange: backend returns a site-local address
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ host: '192.168.1.42', port: 8080, scheme: 'http' }),
        });
        vi.stubGlobal('fetch', mockFetch);

        // Act
        const origin = await getPublicOrigin();

        // Assert: must return the LAN origin (not loopback)
        expect(origin).toBe('http://192.168.1.42:8080');
        expect(origin).not.toContain('127.0.0.1');
        expect(origin).not.toContain('localhost');
    });

    it('does NOT use window.location.origin when backend provides a LAN address', async () => {
        // Arrange: backend returns a site-local address; window.location.origin is loopback
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ host: '10.0.1.55', port: 8080, scheme: 'http' }),
        }));

        // jsdom sets window.location.origin to 'http://localhost' — the test machine's loopback
        const origin = await getPublicOrigin();
        expect(origin).toBe('http://10.0.1.55:8080');
    });

    it('builds origin as scheme://host:port (AC-REGISTRATION-URL-LAN-REACHABLE format)', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ host: '192.168.100.5', port: 9090, scheme: 'http' }),
        }));

        const origin = await getPublicOrigin();
        expect(origin).toBe('http://192.168.100.5:9090');
    });

    it('caches the result after the first fetch (no double requests)', async () => {
        const mockFetch = vi.fn().mockResolvedValue({
            ok: true,
            json: async () => ({ host: '192.168.1.1', port: 8080, scheme: 'http' }),
        });
        vi.stubGlobal('fetch', mockFetch);

        await getPublicOrigin();
        await getPublicOrigin();

        expect(mockFetch).toHaveBeenCalledTimes(1);
    });

    it('falls back to window.location.origin when backend is unreachable (AC-ERROR-NO-LAN-INTERFACE-FALLBACK)', async () => {
        vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network error')));

        // Should not throw; must return a non-empty fallback
        const origin = await getPublicOrigin();
        expect(origin).not.toBeNull();
        expect(origin).not.toBe('');
    });

    it('falls back when backend returns non-OK status', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
            ok: false,
            status: 503,
        }));

        const origin = await getPublicOrigin();
        expect(origin).not.toBeNull();
        expect(origin).not.toBe('');
    });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-TEST-TIMER-URL-USES-LAN-HOST-RED
// The same LAN origin is used for timer URLs — verify at the store level.
// The Devices.svelte and TimerLink.svelte integration is tested via source inspection
// below (same pattern as existing TimerLink.test.ts / Devices.svelte pattern).
// ─────────────────────────────────────────────────────────────────────────────

describe('Devices.svelte — uses publicHostStore for QR URL (AC-TEST-REGISTRATION-URL-NOT-LOOPBACK-RED)', () => {
    it('Devices.svelte imports getPublicOrigin from publicHostStore', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const src = path.resolve(__dirname, '../routes/Devices.svelte');
        const source = fs.readFileSync(src, 'utf8');
        expect(source).toContain('publicHostStore');
        expect(source).toContain('getPublicOrigin');
    });

    it('Devices.svelte does NOT build registrationUrl from window.location.origin alone (E49S04 fix)', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const src = path.resolve(__dirname, '../routes/Devices.svelte');
        const source = fs.readFileSync(src, 'utf8');
        // The old pattern was: const registrationUrl = window.location.origin + '/score/register'
        // After fix: must use the server-provided origin (getPublicOrigin result)
        expect(source).not.toContain("window.location.origin + '/score/register'");
    });

    it('Devices.svelte QR dialog does NOT render window.location.origin directly for the URL text', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const src = path.resolve(__dirname, '../routes/Devices.svelte');
        const source = fs.readFileSync(src, 'utf8');
        // The old template had: {window.location.origin + '/score/register'}
        // After fix: must use the publicOrigin variable (not window.location.origin in template)
        expect(source).not.toContain("{window.location.origin + '/score/register'}");
    });
});

describe('TimerLink.svelte — uses publicHostStore LAN host (AC-TEST-TIMER-URL-USES-LAN-HOST-RED)', () => {
    it('TimerLink.svelte imports getPublicOrigin from publicHostStore', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const src = path.resolve(__dirname, '../routes/TimerLink.svelte');
        const source = fs.readFileSync(src, 'utf8');
        expect(source).toContain('publicHostStore');
        expect(source).toContain('getPublicOrigin');
    });

    it('TimerLink.svelte does NOT use window.location.origin directly as the timer URL base', async () => {
        const fs = await import('fs');
        const path = await import('path');
        const src = path.resolve(__dirname, '../routes/TimerLink.svelte');
        const source = fs.readFileSync(src, 'utf8');
        // The old pattern was: buildTimerUrl(window.location.origin, tournamentId)
        // After fix: the origin must come from the publicHostStore, not directly from window.location.origin
        expect(source).not.toContain('buildTimerUrl(window.location.origin,');
    });
});
