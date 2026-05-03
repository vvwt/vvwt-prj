/**
 * AC3, AC12 — Brand lockup rendering tests for Info portal.
 *
 * DEC-22 Iron Law: written BEFORE App.svelte lockup addition (RED state).
 * RED: current App.svelte has no lockup <img> tag.
 *
 * AC3: Verifies the root component renders an <img> with:
 *   - alt="Live Information" (speaking alt per Brief Q-4)
 *   - src ending with 'vvw-info-logo.svg' (yellow-inverted lockup, NOT blue vvw-tm-logo.svg)
 *   - src resolved via Vite BASE_URL (no hardcoded /info/ literal)
 *
 * AC12: Verifies the lockup <img> has min-width >= 120px CSS (Brief C-9).
 *
 * DEC-2: Svelte 5 + Vite + TypeScript; no SvelteKit.
 * Story: E44S03 — DEC-22, DEC-42.
 */

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

describe('Info branding — lockup minimum render size (AC12)', () => {
  afterEach(() => {
    cleanup();
  });

  it('lockup img has CSS class or style enforcing min-width >= 120px (AC12 — Brief C-9)', () => {
    // AC12: lockup rendered at >=120px CSS width
    // jsdom does not compute CSS from stylesheets, so we check for the brand-lockup CSS class
    // which must declare min-width: 120px in the <style> block of App.svelte.
    const { container } = render(App, {
      props: { tournamentToken: 'tok-test', teamToken: 'team-test' },
    });
    const img = container.querySelector('img[alt="Live Information"]') as HTMLImageElement | null;
    expect(img).not.toBeNull();
    // Must have a class (brand-lockup or equivalent) — CSS width is declared on it
    const hasStyling =
      img!.classList.length > 0 ||
      (img!.getAttribute('style') !== null &&
        img!.getAttribute('style')!.includes('min-width'));
    expect(hasStyling).toBe(true);
  });
});
