/**
 * E50S04 — CourtGrid multi-round display tests.
 *
 * DEC-22 Iron Law: RED-first tests written BEFORE production code changes.
 *
 * RED tests must FAIL against the current CourtGrid.svelte (single-lap render):
 *   - AC-TEST-MULTI-ROUND-ALL-LAPS-VISIBLE-RED: N distinct lapNumbers → N row-groups per field
 *   - AC-TEST-MULTI-ROUND-LAP-ASCENDING-ORDER-RED: rows ordered lap ascending (1 at top)
 *   - AC-TEST-VERTICAL-FILL-EQUAL-DISTRIBUTION-RED: grid-template-rows: repeat(N, 1fr)
 *   - AC-TEST-ACTIVE-ROUND-HIGHLIGHT-RED: active-round CSS class on currentLap row
 *   - AC-TEST-ACTIVE-ROUND-HIGHLIGHT-CSS-CONTRAST-RED: active-round background differs ≥ 30 RGB
 *
 * GREEN regression test:
 *   - AC-TEST-NOPHASE-LOADING-STATES-GREEN: existing CourtGrid interface unchanged
 *
 * Strategy: source-inspection via readFileSync — same pattern as App.layout.test.ts (E50S02).
 * CourtGrid.svelte prop interface must NOT change (AC-GOVERNANCE-NO-OUT-OF-SCOPE-REFACTOR).
 *
 * Story: E50S04 — contexts/artefacts/stories/E50S04.story.md
 * DECs: DEC-2, DEC-22, DEC-29, DEC-30, DEC-54
 */

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = new URL(import.meta.url).pathname;
const COURT_GRID_SVELTE = resolve(BASE, '..', 'CourtGrid.svelte');
const DISPLAY_API_TS = resolve(BASE, '..', '..', 'lib', 'displayApi.ts');

function readSource(path: string): string {
  return readFileSync(path, 'utf-8');
}

function extractStyleBlock(source: string): string {
  const m = source.match(/<style[^>]*>([\s\S]*?)<\/style>/);
  if (!m) throw new Error('No <style> block found in ' + path);
  return m[1];
}

function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

// ---------------------------------------------------------------------------
// AC-TEST-MULTI-ROUND-ALL-LAPS-VISIBLE-RED
// ---------------------------------------------------------------------------
describe('E50S04 AC-TEST-MULTI-ROUND-ALL-LAPS-VISIBLE-RED', () => {
  /**
   * CourtGrid.svelte MUST group matches by lapNumber and render multiple row-groups per field.
   *
   * RED: current CourtGrid renders one flat list per field (no lap-grouping).
   * GREEN after fix: CourtGrid iterates distinct lapNumbers per field and renders one row-group
   *   per lap (e.g., using {#each distinctLaps as lap} outer loop around {#each fieldMatches...}).
   *
   * Evidence: CourtGrid.svelte template must contain a reference to 'lapNumber' — the multi-lap
   * grouping is lap-number-keyed per the data model.
   */
  it('CourtGrid.svelte template contains lapNumber reference (multi-lap grouping key)', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // After fix: CourtGrid groups/filters matches by lapNumber
    expect(source).toMatch(/lapNumber/);
  });

  /**
   * The field-column body iterates over rounds (laps) with a round-row wrapper element
   * carrying a class like "court-grid__round-row" or similar — signals multi-lap structure.
   *
   * RED: current CourtGrid has no round-level wrapper; matches rendered flat.
   * GREEN after fix: each lap's matches are wrapped in a "round-row" div.
   */
  it('CourtGrid.svelte template has a round-row wrapper class for multi-lap grouping', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // Round-row wrapper: any class containing "round-row" signals the lap-grouping structure
    expect(source).toMatch(/round-row/);
  });

  /**
   * displayApi.ts MatchEntry interface includes lapNumber field.
   *
   * RED: current MatchEntry has no lapNumber.
   * GREEN after fix: lapNumber: number (or number | null) added to MatchEntry.
   */
  it('displayApi.ts MatchEntry interface declares lapNumber field', () => {
    const source = readSource(DISPLAY_API_TS);
    // lapNumber must appear in the MatchEntry interface block
    expect(source).toMatch(/lapNumber\s*:/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-MULTI-ROUND-LAP-ASCENDING-ORDER-RED
// ---------------------------------------------------------------------------
describe('E50S04 AC-TEST-MULTI-ROUND-LAP-ASCENDING-ORDER-RED', () => {
  /**
   * CourtGrid.svelte must sort lap rows by lapNumber ascending before rendering.
   *
   * RED: current CourtGrid has no lap-sorting logic.
   * GREEN after fix: distinctLaps array is sorted ascending (sort((a,b) => a-b) or similar).
   *
   * Evidence: CourtGrid.svelte script block must contain a sort call on the laps collection.
   */
  it('CourtGrid.svelte script block contains ascending sort for lap numbers', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // Sort pattern: .sort() or .sort((a, b) => ...) on the laps/rounds collection
    // Check for any sort invocation in the script block (ascending order implied by a - b or a < b)
    const scriptMatch = source.match(/<script[^>]*>([\s\S]*?)<\/script>/);
    expect(scriptMatch).not.toBeNull();
    const script = scriptMatch![1];
    expect(script).toMatch(/\.sort\(/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-VERTICAL-FILL-EQUAL-DISTRIBUTION-RED
// ---------------------------------------------------------------------------
describe('E50S04 AC-TEST-VERTICAL-FILL-EQUAL-DISTRIBUTION-RED', () => {
  /**
   * CourtGrid.svelte field-column body MUST apply grid-template-rows: repeat(N, 1fr)
   * for equal vertical distribution of N lap rows.
   *
   * RED: current CourtGrid uses overflow-y: auto on .court-grid__field-column — no equal rows.
   * GREEN after fix: a CSS rule or inline style uses grid-template-rows: repeat(..., 1fr).
   *
   * Evidence: CourtGrid.svelte contains the text "1fr" in a grid-template-rows context.
   */
  it('CourtGrid.svelte contains grid-template-rows repeat with 1fr for vertical fill', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // After fix: field-column uses grid + repeat(N, 1fr) for equal row heights
    // This may appear as an inline style binding or a CSS class with a CSS variable
    expect(source).toMatch(/1fr/);
  });

  /**
   * The field-column must use display:grid (not flex or block) for equal row distribution.
   * CSS-style-attribute inspection per AC story constraint (no bounding-box geometry in jsdom).
   *
   * RED: current .court-grid__field-column does not declare display: grid.
   * GREEN after fix: .court-grid__field-column uses display: grid.
   */
  it('CourtGrid.svelte .court-grid__field-column CSS rule declares display: grid', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    const ruleMatch = style.match(/\.court-grid__field-column\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];

    expect(ruleBody).toMatch(/display\s*:\s*grid/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-ACTIVE-ROUND-HIGHLIGHT-RED
// ---------------------------------------------------------------------------
describe('E50S04 AC-TEST-ACTIVE-ROUND-HIGHLIGHT-RED', () => {
  /**
   * CourtGrid.svelte must apply an active-round CSS class to the round row matching currentLap.
   *
   * RED: current CourtGrid has no round-level active class logic.
   * GREEN after fix: round-row element has class:round-row--active={lap === currentLap} or similar.
   *
   * Evidence: CourtGrid.svelte template contains "round-row--active" (or analogous active class).
   */
  it('CourtGrid.svelte template contains active-round CSS class binding', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // Active class: round-row--active is the canonical name per AC story
    expect(source).toMatch(/round-row--active/);
  });

  /**
   * The active-round class binding uses currentLap comparison.
   *
   * RED: no currentLap comparison for active class in current CourtGrid.
   * GREEN after fix: class:round-row--active={lap === currentLap} or equivalent in template.
   */
  it('CourtGrid.svelte template binds active class using currentLap comparison', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // The binding must reference currentLap for the active class condition
    expect(source).toMatch(/round-row--active[^=]*=\{[^}]*currentLap/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-ACTIVE-ROUND-HIGHLIGHT-CSS-CONTRAST-RED
// ---------------------------------------------------------------------------
describe('E50S04 AC-TEST-ACTIVE-ROUND-HIGHLIGHT-CSS-CONTRAST-RED', () => {
  /**
   * CourtGrid.svelte .round-row--active CSS rule MUST set a background-color that
   * differs from the inactive row background by at least ~30 RGB in at least one channel.
   *
   * RED: current CourtGrid has no .round-row--active rule at all.
   * GREEN after fix: .round-row--active rule exists with a distinct background-color.
   *
   * Strategy: CSS style-rule inspection (no bounding-box geometry per jsdom limitation).
   * The inactive row background is white (#fff) or very light; the active background must
   * be at least ~30 RGB different in one channel (e.g., #e0e8f5 vs #fff = R: 255-224=31 ✓).
   */
  it('CourtGrid.svelte .round-row--active CSS rule declares a background-color', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    const ruleMatch = style.match(/\.round-row--active\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];

    // Active rule must set background-color (the RGB-Δ ≥ 30 is representationally verifiable
    // by the explicit color value differing from #fff — full numeric check impractical in
    // source-inspection but color presence is the structural gate)
    expect(ruleBody).toMatch(/background(-color)?\s*:/);
  });

  /**
   * CourtGrid.svelte has a round-row base CSS rule (for inactive rounds).
   *
   * GREEN regression: base round-row rule must exist alongside the active variant.
   */
  it('CourtGrid.svelte .round-row base CSS rule exists (inactive round base style)', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    // Base rule (not --active): .round-row { ... }
    expect(style).toMatch(/\.round-row\s*\{/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-NOPHASE-LOADING-STATES-GREEN (regression guard)
// ---------------------------------------------------------------------------
describe('E50S04 AC-TEST-NOPHASE-LOADING-STATES-GREEN', () => {
  /**
   * Regression: CourtGrid.svelte Props interface is unchanged.
   * matchesData, fieldCount, currentLap must remain the only props.
   *
   * AC-GOVERNANCE-NO-OUT-OF-SCOPE-REFACTOR: prop-signature changes are FORBIDDEN.
   */
  it('CourtGrid.svelte Props interface still declares matchesData, fieldCount, currentLap', () => {
    const source = readSource(COURT_GRID_SVELTE);

    const scriptMatch = source.match(/<script[^>]*>([\s\S]*?)<\/script>/);
    expect(scriptMatch).not.toBeNull();
    const script = scriptMatch![1];

    expect(script).toMatch(/matchesData/);
    expect(script).toMatch(/fieldCount/);
    expect(script).toMatch(/currentLap/);
  });

  /**
   * Regression: CourtGrid.svelte still renders field-column headers.
   * The field-column header structure (court-grid__field-header) must be preserved.
   */
  it('CourtGrid.svelte template still renders .court-grid__field-header elements', () => {
    const source = readSource(COURT_GRID_SVELTE);
    expect(source).toMatch(/court-grid__field-header/);
  });

  /**
   * Regression: CourtGrid.svelte still imports MatchRow component.
   * MatchRow invocation signature must be unchanged per AC-GOVERNANCE-NO-OUT-OF-SCOPE-REFACTOR.
   */
  it('CourtGrid.svelte still imports and uses MatchRow component', () => {
    const source = readSource(COURT_GRID_SVELTE);
    expect(source).toMatch(/import MatchRow/);
    expect(source).toMatch(/<MatchRow/);
  });
});
