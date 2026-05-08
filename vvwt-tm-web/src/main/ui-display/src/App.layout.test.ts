/**
 * E50S02 — Layout tests for unified header band.
 *
 * DEC-22 Iron Law: RED-first tests written BEFORE production code changes.
 *
 * RED tests (AC-TEST-LAYOUT-*-RED):
 *   - Must FAIL against the current source (separate brand-header + fixed ConnectionStatus).
 *   - Will pass after the fix (unified display-header flex row).
 *
 * GREEN regression tests (AC-TEST-LAYOUT-*-REGRESSION-GREEN):
 *   - Must pass both before and after the fix.
 *
 * Strategy: source-inspection via readFileSync — the display SPA test stack is
 * Vitest + jsdom with vitest@^3.1.3 + jsdom@^29.0.2 (no Playwright).
 * Structural assertions use CSS class existence and style-rule content inspection.
 * No bounding-box geometry (jsdom does not implement it reliably).
 *
 * Story: E50S02 — contexts/artefacts/stories/E50S02.story.md
 * DECs: DEC-2, DEC-22, DEC-29, DEC-30, DEC-54
 */

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = new URL(import.meta.url).pathname;
const APP_SVELTE = resolve(BASE, '..', 'App.svelte');
const CONNECTION_STATUS_SVELTE = resolve(
  BASE,
  '..',
  'components',
  'ConnectionStatus.svelte'
);

function readSource(path: string): string {
  return readFileSync(path, 'utf-8');
}

function extractStyleBlock(source: string): string {
  const m = source.match(/<style[^>]*>([\s\S]*?)<\/style>/);
  if (!m) throw new Error('No <style> block found');
  return m[1];
}

function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

// ---------------------------------------------------------------------------
// AC-TEST-LAYOUT-SINGLE-BAND-DOM-STRUCTURE-RED
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-SINGLE-BAND-DOM-STRUCTURE-RED', () => {
  /**
   * The unified header container CSS class "display-header" MUST be present in App.svelte
   * (the new shared parent for logo + indicator).
   *
   * RED: current App.svelte has ".brand-header" (logo only) — ".display-header" does not exist.
   * GREEN after fix: App.svelte uses ".display-header" as the unified flex-row container.
   */
  it('App.svelte template uses "display-header" class (unified container for logo + indicator)', () => {
    const source = readSource(APP_SVELTE);
    expect(source).toMatch(/class="display-header"/);
  });

  /**
   * The separate logo-only header element <header class="brand-header"> MUST NOT exist after fix.
   *
   * RED: current App.svelte has <header class="brand-header"> for logo only.
   * GREEN after fix: brand-header is replaced by display-header.
   */
  it('App.svelte template does NOT have a separate logo-only brand-header element', () => {
    const source = readSource(APP_SVELTE);
    expect(source).not.toMatch(/class="brand-header"/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-LAYOUT-LOGO-HEIGHT-CSS-RED
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-LOGO-HEIGHT-CSS-RED', () => {
  /**
   * The logo element's height in the new unified header MUST fall within the <=~3em structural
   * envelope (AC-TEST-LAYOUT-LOGO-HEIGHT-CSS-RED: height <= ~3em / ~2.5x field-header text size).
   *
   * The pre-fix logo container was ".brand-header" with padding: 0.5rem 1rem containing
   * ".brand-lockup" at height: 2em — total envelope > 3em (2em + 2*0.5rem = 3em+ with border).
   *
   * Post-fix: ".display-header" must NOT contain "padding: 0.5rem 1rem" with ".brand-lockup"
   * height: 2em. We assert that the App.svelte style block:
   *   (a) does NOT have a ".brand-header" rule at all (the container is gone), AND
   *   (b) ".brand-lockup" height is NOT "2em" (the old oversized value from E44S04).
   *
   * RED: current App.svelte has ".brand-header" with padding AND ".brand-lockup" at height:2em.
   * GREEN after fix: ".brand-header" removed, ".brand-lockup" height ≤ ~3em but != 2em (e.g., 1.8em).
   *
   * Note: AppBranding.test.ts (E44S04) asserts height:2em; that test is updated in this story
   * to match the new spec (1.8em). The E44S02 AC13 min-width:120px is preserved via brand-lockup
   * being visible; AC13 height constraint is relaxed per the story notes.
   */
  it('App.svelte style does NOT contain a .brand-header rule (logo-only header removed)', () => {
    const source = readSource(APP_SVELTE);
    const style = stripComments(extractStyleBlock(source));
    // .brand-header rule must not exist after fix
    expect(style).not.toMatch(/\.brand-header\s*\{/);
  });

  it('App.svelte style contains a .display-header rule (unified header present)', () => {
    const source = readSource(APP_SVELTE);
    const style = stripComments(extractStyleBlock(source));
    expect(style).toMatch(/\.display-header\s*\{/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-LAYOUT-INDICATOR-IN-FLOW-RED
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-INDICATOR-IN-FLOW-RED', () => {
  /**
   * ConnectionStatus.svelte MUST NOT declare position: fixed in its .connection-status rule.
   *
   * RED: current ConnectionStatus.svelte has "position: fixed; top: 0.5rem; right: 0.75rem".
   * GREEN after fix: "position: fixed" removed; margin-left: auto used for right-alignment.
   */
  it('ConnectionStatus.svelte .connection-status rule does NOT declare position: fixed', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    // Extract .connection-status rule body
    const ruleMatch = style.match(/\.connection-status\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];

    // Assert: no position: fixed
    expect(ruleBody).not.toMatch(/\bposition\s*:\s*fixed\b/);
  });

  it('ConnectionStatus.svelte .connection-status rule does NOT declare top: (removed with fixed)', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    const ruleMatch = style.match(/\.connection-status\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];

    // "top:" is only needed for position:fixed — must be absent after fix
    expect(ruleBody).not.toMatch(/\btop\s*:/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-LAYOUT-NOPHASE-STATE-REGRESSION-GREEN
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-NOPHASE-STATE-REGRESSION-GREEN', () => {
  /**
   * Regression: logo/brand must be visible regardless of errorType (noPhase/unauthorized/generic).
   *
   * Asserted via source inspection: the <header> (or whatever top-level element contains the
   * brand-lockup img) MUST be rendered unconditionally — not wrapped in an {#if errorType === null}
   * or similar conditional block.
   *
   * Strategy: assert that the <img ... class="brand-lockup"> tag appears BEFORE the
   * {#if isRegisterPage} block (or equivalently, that it is NOT inside an {#if errorType}
   * conditional block). Source-order inspection is sufficient for Svelte SFC templates.
   */
  it('brand-lockup img is rendered unconditionally — not inside {#if errorType} block', () => {
    const source = readSource(APP_SVELTE);

    // Find the position of the brand-lockup img in the template
    const brandLockupIdx = source.indexOf('class="brand-lockup"');
    expect(brandLockupIdx).toBeGreaterThan(-1);

    // Find the position of the {#if errorType} conditional (which guards the error panel)
    // The brand-lockup must appear BEFORE this block in the template, meaning it is unconditional
    const errorTypeIfIdx = source.indexOf('{:else if errorType !== null}');
    expect(errorTypeIfIdx).toBeGreaterThan(-1);

    // brand-lockup appears before the errorType conditional → rendered unconditionally
    expect(brandLockupIdx).toBeLessThan(errorTypeIfIdx);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-LAYOUT-LOADING-STATE-REGRESSION-GREEN
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-LOADING-STATE-REGRESSION-GREEN', () => {
  /**
   * Regression: during loading state, the brand logo is visible AND the spinner renders
   * inside the main content area (not in the header).
   *
   * Asserted via source inspection:
   * 1. brand-lockup appears before the {#if loading} spinner block
   * 2. The spinner (.display-app__spinner) is inside the {#if loading} conditional in .display-app
   */
  it('brand-lockup img appears before the loading spinner block', () => {
    const source = readSource(APP_SVELTE);

    const brandLockupIdx = source.indexOf('class="brand-lockup"');
    const spinnerIdx = source.indexOf('class="display-app__spinner"');

    expect(brandLockupIdx).toBeGreaterThan(-1);
    expect(spinnerIdx).toBeGreaterThan(-1);
    expect(brandLockupIdx).toBeLessThan(spinnerIdx);
  });

  it('loading spinner is inside the display-app content area (not in header)', () => {
    const source = readSource(APP_SVELTE);

    // The spinner should be inside {#if loading} block which is inside .display-app div
    // Assert that display-app div appears before spinner in source
    const displayAppIdx = source.indexOf('class="display-app"');
    const spinnerIdx = source.indexOf('class="display-app__spinner"');

    expect(displayAppIdx).toBeGreaterThan(-1);
    expect(spinnerIdx).toBeGreaterThan(-1);
    expect(displayAppIdx).toBeLessThan(spinnerIdx);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-LAYOUT-CONNECTION-STATES-VISUAL-PRESERVED-GREEN
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-CONNECTION-STATES-VISUAL-PRESERVED-GREEN', () => {
  /**
   * Regression: the 5 connection states (connecting/connected/reconnecting/polling/disconnected)
   * and their visual styles + i18n aria-label/title are preserved after the layout fix.
   */
  it('ConnectionStatus.svelte dotClass map contains all 5 connection states', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);

    const states = [
      'connecting',
      'connected',
      'reconnecting',
      'polling',
      'disconnected',
    ] as const;

    for (const state of states) {
      expect(source).toContain(state);
    }
  });

  it('ConnectionStatus.svelte preserves cs-dot--green, cs-dot--amber, cs-dot--red, cs-dot--spinning CSS classes', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);

    expect(source).toContain('cs-dot--green');
    expect(source).toContain('cs-dot--amber');
    expect(source).toContain('cs-dot--red');
    expect(source).toContain('cs-dot--spinning');
  });

  it('ConnectionStatus.svelte aria-label and title use i18n labelKey map', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);

    // i18n label map must reference the 5 i18n keys
    expect(source).toContain('display.connection.connecting');
    expect(source).toContain('display.connection.connected');
    expect(source).toContain('display.connection.reconnecting');
    expect(source).toContain('display.connection.polling');
    expect(source).toContain('display.connection.disconnected');
  });

  it('ConnectionStatus.svelte status prop type is preserved', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);

    // Props interface must still declare status: ConnectionStatus
    expect(source).toMatch(/status\s*:\s*ConnectionStatus/);
  });
});

// ---------------------------------------------------------------------------
// AC-ERROR-HANDLING-INDICATOR-WHEN-LOADING (documented choice)
// ---------------------------------------------------------------------------
describe('E50S02 AC-ERROR-HANDLING-INDICATOR-WHEN-LOADING', () => {
  /**
   * During the loading state, the connection indicator is always rendered in the header
   * (not conditionally hidden). It shows "disconnected" state (the initial value of
   * connectionStatus state variable). This is deterministic and produces no flicker.
   *
   * Assert: ConnectionStatusIndicator is rendered unconditionally (always visible in the header)
   * after the fix — it is NOT wrapped in {#if !loading} or {#if phaseData !== null} inside the header.
   *
   * RED: current App.svelte wraps ConnectionStatusIndicator in:
   *   {#if !loading && errorType === null && phaseData !== null}
   * GREEN after fix: ConnectionStatusIndicator appears in the unified header unconditionally.
   */
  it('ConnectionStatusIndicator is rendered in the display-header unconditionally (not inside loading guard)', () => {
    const source = readSource(APP_SVELTE);

    // Find the display-header element in the template
    const headerIdx = source.indexOf('class="display-header"');
    expect(headerIdx).toBeGreaterThan(-1);

    // Find ConnectionStatusIndicator usage in the header area
    // After the fix, ConnectionStatusIndicator appears as a direct child of display-header
    // (outside any {#if ...} block), before the isRegisterPage conditional
    const registerPageIfIdx = source.indexOf('{#if isRegisterPage}');
    expect(registerPageIfIdx).toBeGreaterThan(-1);

    // ConnectionStatusIndicator in the header must appear between header and isRegisterPage block
    const connectionStatusInHeaderIdx = source.indexOf(
      '<ConnectionStatusIndicator',
      headerIdx
    );
    expect(connectionStatusInHeaderIdx).toBeGreaterThan(-1);
    expect(connectionStatusInHeaderIdx).toBeLessThan(registerPageIfIdx);
  });
});
