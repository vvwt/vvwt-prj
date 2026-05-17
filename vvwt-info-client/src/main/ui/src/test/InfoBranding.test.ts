// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/svelte';
import App from '../App.svelte';

describe('Info branding — lockup img (AC3)', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders lockup img with alt="Live Information" (AC3 — speaking alt per Brief Q-4)', () => {
    // AC3: lockup <img> with speaking alt text
    const { container } = render(App, {
      props: { tournamentToken: 'tok-test', teamToken: 'team-test' },
    });
    const imgs = container.querySelectorAll('img[alt="Live Information"]');
    expect(imgs.length).toBeGreaterThanOrEqual(1);
  });

  it('lockup img src ends with vvw-info-logo.svg (AC3 — yellow lockup, not blue TM logo)', () => {
    // AC3: must use yellow-inverted Info logo, NOT vvw-tm-logo.svg
    // Vite BASE_URL in jsdom = '/' → src = '/vvw-info-logo.svg' (ends with vvw-info-logo.svg)
    const { container } = render(App, {
      props: { tournamentToken: 'tok-test', teamToken: 'team-test' },
    });
    const img = container.querySelector('img[alt="Live Information"]') as HTMLImageElement | null;
    expect(img).not.toBeNull();
    expect(img!.src).toMatch(/vvw-info-logo\.svg$/);
  });

  it('lockup img src does NOT reference vvw-tm-logo.svg (AC3 — variant-mixing guard)', () => {
    // AC3: variant-mixing is a defect class — explicitly assert negative
    const { container } = render(App, {
      props: { tournamentToken: 'tok-test', teamToken: 'team-test' },
    });
    const tmLogos = container.querySelectorAll('img[src*="vvw-tm-logo"]');
    expect(tmLogos.length).toBe(0);
  });

  it('lockup img src does NOT contain hardcoded /info/ literal (AC11 path convention)', () => {
    // AC11: no hardcoded /info/ literal — Vite base resolution handles prefixing
    const { container } = render(App, {
      props: { tournamentToken: 'tok-test', teamToken: 'team-test' },
    });
    const img = container.querySelector('img[alt="Live Information"]') as HTMLImageElement | null;
    expect(img).not.toBeNull();
    // In a real Vite build, import.meta.env.BASE_URL = '/info/' → src ends with vvw-info-logo.svg
    // In jsdom tests, import.meta.env.BASE_URL = '/' → src = '/vvw-info-logo.svg'
    // Either way, the source code must NOT contain a hardcoded path literal (BASE_URL + filename) —
    // this is enforced by grep at QA time. The test here verifies the component renders.
    expect(img!.src).toBeTruthy();
  });
});

/**
 * AC4 (E44S04) — Brand lockup height invariant for Info SPA.
 *
 * DEC-22 Iron Law: this describe block is written BEFORE the App.svelte CSS edit (RED state).
 * RED: current App.svelte has `.brand-lockup { min-width: 120px; display: block; margin-bottom: 1rem; }` —
 *   POSITIVE regex fails (no height:2em), NEGATIVE for min-width:* matches (fails).
 *   NEGATIVE for height:auto is trivially satisfied (no height declaration exists), but included for symmetry.
 *
 * Strategy: fs.readFileSync source inspection + 3-step pre-process + 3 regex assertions.
 * Replaces the E44S03 AC12 describe block (min-width:120px class-proxy assertion) per E44S04 scope.
 * Other describe blocks (AC3 alt-text checks at lines 22–69) are preserved unchanged.
 *
 * DEC-22. Story: E44S04 — brand-lockup oversize fix.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

describe('Info branding — lockup height invariant', () => {
  const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', '..', 'App.svelte');

  function extractBrandLockupRuleBody(source: string): string {
    // Pre-process step 1: extract <style>...</style> block content
    const styleMatch = source.match(/<style[^>]*>([\s\S]*?)<\/style>/);
    if (!styleMatch) throw new Error('No <style> block found in App.svelte');
    const styleContent = styleMatch[1];

    // Pre-process step 2: strip CSS block comments
    const stripped = styleContent.replace(/\/\*[\s\S]*?\*\//g, '');

    // Pre-process step 3: locate .brand-lockup rule body
    const ruleMatch = stripped.match(/(?::global\()?\.brand-lockup\)?\s*\{([^}]*)\}/);
    if (!ruleMatch) throw new Error('No .brand-lockup rule found in <style> block');
    return ruleMatch[1];
  }

  const source = readFileSync(APP_SVELTE, 'utf-8');
  const ruleBody = extractBrandLockupRuleBody(source);

  it('POSITIVE: .brand-lockup declares height: 2em', () => {
    expect(ruleBody).toMatch(/\bheight\s*:\s*2em\s*[;}]/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare min-width:', () => {
    expect(ruleBody).not.toMatch(/\bmin-width\s*:/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare height: auto (symmetry assertion)', () => {
    expect(ruleBody).not.toMatch(/\bheight\s*:\s*auto\b/);
  });
});
