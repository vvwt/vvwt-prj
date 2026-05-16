// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import deMessages from '../locales/de.json';
// qrcode has no @types package; the same pattern is used in Devices.svelte (E06S05).
// eslint-disable-next-line @typescript-eslint/ban-ts-comment
// @ts-ignore
import * as QRCodeLib from 'qrcode';
// AC7 (E11S08): import the production URL builder from its dedicated module.
// The function was extracted from the inline $derived rune in TimerLink.svelte (AC5)
// into lib/timerLinkUrl.ts so it is importable as a plain ESM module in Vitest.
// This import used the legacy .svelte module path in the RED commit; corrected here to
// the canonical lib module path after the AC5 extraction landed.
import { buildTimerUrl } from '../lib/timerLinkUrl.js';

// ─────────────────────────────────────────────────────────────────────────────
// i18n coverage — all timerLink keys must be present in de.json (AC4, AC6)
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — timerLink translations (E11S07)', () => {
  it('should contain the timerLink namespace', () => {
    expect(deMessages).toHaveProperty('timerLink');
  });

  it('should contain the required timerLink keys', () => {
    const tl = (deMessages as unknown as Record<string, Record<string, string>>).timerLink;
    expect(tl).toHaveProperty('title');
    expect(tl).toHaveProperty('backButton');
    expect(tl).toHaveProperty('urlLabel');
    expect(tl).toHaveProperty('copyButton');
    expect(tl).toHaveProperty('copiedButton');
    expect(tl).toHaveProperty('openButton');
    expect(tl).toHaveProperty('noScheduleNote');  // AC6
    expect(tl).toHaveProperty('qrLabel');
    expect(tl).toHaveProperty('qrError');
  });

  it('should contain the timerLink error namespace', () => {
    const tl = (deMessages as unknown as Record<string, Record<string, Record<string, string>>>).timerLink;
    expect(tl).toHaveProperty('error');
    expect(tl.error).toHaveProperty('noTournament');
  });

  it('noScheduleNote should be a non-empty string (AC6)', () => {
    const tl = (deMessages as unknown as Record<string, Record<string, string>>).timerLink;
    expect(typeof tl.noScheduleNote).toBe('string');
    expect(tl.noScheduleNote.length).toBeGreaterThan(0);
  });
});

describe('de.json — audio.timerLinkButton (E11S07 AC3)', () => {
  it('should contain the timerLinkButton key in the audio namespace', () => {
    const a = (deMessages as unknown as Record<string, Record<string, string>>).audio;
    expect(a).toHaveProperty('timerLinkButton');
    expect(typeof a.timerLinkButton).toBe('string');
    expect(a.timerLinkButton.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Timer URL construction — AC1, AC5
// ─────────────────────────────────────────────────────────────────────────────

describe('Timer URL derivation (AC1, AC5 — E11S08 AC7: uses production buildTimerUrl)', () => {
  /**
   * The timer URL is constructed as (canonical Wave-2 path per E11S08 AC5):
   *   `${window.location.origin}/timer/tournaments/${tournamentId}`
   *
   * AC5: for the same tournamentId and origin, the URL is always the same —
   * it does not depend on any runtime state that would change between loads
   * (no random tokens, no session IDs, no timestamps).
   *
   * AC7 (E11S08): the local `buildTimerUrl` helper (lines 96-99 in the original) is
   * replaced by the production-imported function from TimerLink.svelte. The import above
   * was RED before AC5 extraction; now GREEN with canonical /timer/tournaments/ path.
   */

  const SAMPLE_UUID = '550e8400-e29b-41d4-a716-446655440000';
  const SAMPLE_ORIGIN = 'http://localhost:8080';

  // buildTimerUrl is now the production import from TimerLink.svelte (AC7)

  it('should include the tournamentId in the path (AC1)', () => {
    const url = buildTimerUrl(SAMPLE_ORIGIN, SAMPLE_UUID);
    expect(url).toContain(SAMPLE_UUID);
  });

  it('should use /timer/tournaments/ as the path prefix (AC1 — canonical Wave-2)', () => {
    const url = buildTimerUrl(SAMPLE_ORIGIN, SAMPLE_UUID);
    expect(url).toContain('/timer/tournaments/');
  });

  it('should be deterministic for the same inputs (AC5)', () => {
    const url1 = buildTimerUrl(SAMPLE_ORIGIN, SAMPLE_UUID);
    const url2 = buildTimerUrl(SAMPLE_ORIGIN, SAMPLE_UUID);
    expect(url1).toBe(url2);
  });

  it('should return empty string when tournamentId is empty (edge case — AC6)', () => {
    const url = buildTimerUrl(SAMPLE_ORIGIN, '');
    expect(url).toBe('');
  });

  it('should preserve port in origin (AC5 — dev vs prod parity)', () => {
    const url = buildTimerUrl('http://localhost:8080', SAMPLE_UUID);
    expect(url).toMatch(/^http:\/\/localhost:8080\/timer\/tournaments\//);
  });

  it('should work with https origin (production scenario)', () => {
    const url = buildTimerUrl('https://tm.example.org', SAMPLE_UUID);
    expect(url).toMatch(/^https:\/\/tm\.example\.org\/timer\/tournaments\//);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// QR code generation — AC2
// ─────────────────────────────────────────────────────────────────────────────

describe('QR code SVG generation (AC2)', () => {
  /**
   * Tests the buildQrSvg logic extracted from the component.
   * `qrcode` library is available in node (not canvas-dependent for the `create` API).
   * AC2: QR code is rendered as an SVG large enough to scan at ~1 meter.
   *   Cell size 5px × ~40 modules typical + 2×16px padding = ~232px minimum.
   */

  function buildQrSvg(text: string): string {
    try {
      const qr = (QRCodeLib as unknown as {
        create: (text: string, opts: { errorCorrectionLevel: string }) => {
          modules: { data: Uint8ClampedArray | boolean[]; size: number };
        };
      }).create(text, { errorCorrectionLevel: 'M' });

      const size = qr.modules.size;
      const data = qr.modules.data;
      const cellSize = 5;
      const padding = 16;
      const totalSize = size * cellSize + padding * 2;

      let rects = '';
      for (let row = 0; row < size; row++) {
        for (let col = 0; col < size; col++) {
          if (data[row * size + col]) {
            const x = padding + col * cellSize;
            const y = padding + row * cellSize;
            rects += `<rect x="${x}" y="${y}" width="${cellSize}" height="${cellSize}" fill="black"/>`;
          }
        }
      }

      return (
        `<svg xmlns="http://www.w3.org/2000/svg" width="${totalSize}" height="${totalSize}" ` +
        `viewBox="0 0 ${totalSize} ${totalSize}">` +
        `<rect width="${totalSize}" height="${totalSize}" fill="white"/>` +
        rects +
        `</svg>`
      );
    } catch {
      return '<svg xmlns="http://www.w3.org/2000/svg" width="260" height="60"></svg>';
    }
  }

  it('should return a string starting with <svg for a valid URL', () => {
    const svg = buildQrSvg('http://localhost:8080/timer/test-id');
    expect(svg).toMatch(/^<svg/);
  });

  it('should contain rect elements for QR modules (AC2 — visual content)', () => {
    const svg = buildQrSvg('http://localhost:8080/timer/test-id');
    expect(svg).toContain('<rect');
  });

  it('should produce SVG with width ≥ 150px (AC2 — scannable at ~1m)', () => {
    // 150px is a conservative lower bound for scannability at 1 meter.
    // Real output with cell size 5px is ~197–250px depending on URL length — well above this threshold.
    const svg = buildQrSvg('http://localhost:8080/timer/550e8400-e29b-41d4-a716-446655440000');
    const widthMatch = svg.match(/width="(\d+)"/);
    expect(widthMatch).not.toBeNull();
    const width = parseInt(widthMatch![1], 10);
    expect(width).toBeGreaterThanOrEqual(150);
  });

  it('should produce square SVG (width equals height)', () => {
    const svg = buildQrSvg('http://localhost:8080/timer/test-id');
    const widthMatch = svg.match(/width="(\d+)"/);
    const heightMatch = svg.match(/height="(\d+)"/);
    expect(widthMatch).not.toBeNull();
    expect(heightMatch).not.toBeNull();
    expect(widthMatch![1]).toBe(heightMatch![1]);
  });

  it('should return a valid SVG even for empty input (error fallback)', () => {
    // empty string: QRCodeLib.create will throw
    const svg = buildQrSvg('');
    expect(svg).toMatch(/^<svg/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E47S01: Shell migration tests for TimerLink route
// ─────────────────────────────────────────────────────────────────────────────

describe('TimerLink.svelte — AC3: per-page header removed (E47S01)', () => {
  it('TimerLink.svelte source does NOT contain .timer-link__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerLink.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('timer-link__header');
  });

  it('TimerLink.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerLink.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('timerLink.title');
  });
});

describe('TimerLink.svelte — AC5: backTo registered (E47S01)', () => {
  it('TimerLink.svelte source registers backTo in pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerLink.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
  });
});

describe('TimerLink.svelte — AC6: tournamentId registered (E47S01)', () => {
  it('TimerLink.svelte source passes tournamentId to pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerLink.svelte');
    const source = fs.readFileSync(src, 'utf8');
    const pageHeaderCall = source.match(/pageHeader\.set\(\{([^}]*)\}/s)?.[1] ?? '';
    expect(pageHeaderCall).toContain('tournamentId');
  });
});

describe('TimerLink.svelte — AC12: pop() back-button removed (E47S01)', () => {
  it('TimerLink.svelte source has NO button template with timerLink.backButton', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerLink.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toMatch(/<button[^>]*>\s*\{[^}]*timerLink\.backButton/);
  });
});
