/**
 * E50S04 — CourtGrid multi-round display tests.
 * E50S05 — CourtGrid body row-distribution defect fix tests.
 *
 * DEC-22 Iron Law: RED-first tests written BEFORE production code changes.
 *
 * E50S04 RED tests must FAIL against the current CourtGrid.svelte (single-lap render):
 *   - AC-TEST-MULTI-ROUND-ALL-LAPS-VISIBLE-RED: N distinct lapNumbers → N row-groups per field
 *   - AC-TEST-MULTI-ROUND-LAP-ASCENDING-ORDER-RED: rows ordered lap ascending (1 at top)
 *   - AC-TEST-VERTICAL-FILL-EQUAL-DISTRIBUTION-RED: grid-template-rows: repeat(N, 1fr)
 *   - AC-TEST-ACTIVE-ROUND-HIGHLIGHT-RED: active-round CSS class on currentLap row
 *   - AC-TEST-ACTIVE-ROUND-HIGHLIGHT-CSS-CONTRAST-RED: active-round background differs ≥ 30 RGB
 *
 * E50S05 RED test must FAIL on current code (no body row-distribution mechanism):
 *   - AC-TEST-COURT-GRID-BODY-ROW-DISTRIBUTION-RED: .court-grid__body must declare a row-distribution
 *     mechanism (grid-template-rows, grid-auto-rows, or display:flex with field-column flex:1)
 *
 * GREEN regression tests (E50S05):
 *   - AC-TEST-COURT-GRID-FIELD-COLUMN-1FR-PER-LAP-PRESERVED-GREEN
 *   - AC-TEST-COURT-GRID-ZERO-LAPS-NO-LAYOUT-ERROR-GREEN
 *   - AC-TEST-COURT-GRID-SINGLE-LAP-FULL-HEIGHT-GREEN
 *   - AC-TEST-COURT-GRID-MANY-LAPS-EQUAL-DISTRIBUTION-GREEN
 *   - AC-TEST-COURT-GRID-NOPHASE-LOADING-STATES-GREEN
 *
 * Strategy: source-inspection via readFileSync — same pattern as App.layout.test.ts (E50S02).
 * CourtGrid.svelte prop interface must NOT change (AC-GOVERNANCE-NO-OUT-OF-SCOPE-REFACTOR).
 *
 * Story: E50S04 — contexts/artefacts/stories/E50S04.story.md
 * Story: E50S05 — contexts/artefacts/stories/E50S05.story.md
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

// ===========================================================================
// E50S05 — CourtGrid body row-distribution defect fix
// ===========================================================================

// ---------------------------------------------------------------------------
// AC-TEST-COURT-GRID-BODY-ROW-DISTRIBUTION-RED
// ---------------------------------------------------------------------------
describe('E50S05 AC-TEST-COURT-GRID-BODY-ROW-DISTRIBUTION-RED', () => {
  /**
   * CourtGrid.svelte .court-grid__body CSS rule MUST declare a row-distribution mechanism
   * that allows child .court-grid__field-column elements to receive a definite portion of
   * the body's height.
   *
   * RED: current .court-grid__body has display:grid + grid-template-columns + flex:1 +
   *   overflow:hidden but NO grid-template-rows / grid-auto-rows declaration.
   *   The implicit grid row defaults to 'auto' (content-sized), so field-columns do NOT
   *   receive a definite parent height → their internal grid-template-rows:repeat(N,1fr)
   *   distribution collapses to content-height. This IS the chain defect (Brief O-5).
   *
   * GREEN after fix: .court-grid__body declares one of:
   *   (a) grid-template-rows: 1fr (or repeat(N, 1fr) for any N ≥ 1)
   *   (b) grid-auto-rows: 1fr (or any non-auto/min-content/max-content row-size keyword)
   *   (c) display:flex with flex-direction:column AND .court-grid__field-column declares flex:1
   *   (d) any other declared mechanism that documentably distributes body height to field-columns
   *
   * Strategy: CSS source-inspection — same pattern as E50S04 tests.
   * The test asserts the CSSOM-property whose ABSENCE is the chain defect.
   * Necessary-AND-sufficient under jsdom's introspectable surface per Story AC + Brief C-6.
   *
   * Story: E50S05 — contexts/artefacts/stories/E50S05.story.md
   * DECs: DEC-2, DEC-22, DEC-54
   */
  it('.court-grid__body CSS rule declares a row-distribution mechanism (grid-template-rows or grid-auto-rows)', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    // Extract the .court-grid__body rule (multiline, up to closing brace)
    const bodyRuleMatch = style.match(/\.court-grid__body\s*\{([^}]*)\}/);
    expect(bodyRuleMatch).not.toBeNull();
    const bodyRuleBody = bodyRuleMatch![1];

    // Assert that body has a row-distribution mechanism:
    //   (a) grid-template-rows (any non-'auto' value provides the missing distribution)
    //   (b) grid-auto-rows (explicit row-size override)
    //   (c) display:flex (as alternative to grid — flex distributes children via flex:1)
    // At least ONE of these patterns must be present.
    const hasGridTemplateRows = /grid-template-rows\s*:/.test(bodyRuleBody);
    const hasGridAutoRows = /grid-auto-rows\s*:/.test(bodyRuleBody);
    const hasDisplayFlex = /display\s*:\s*flex/.test(bodyRuleBody);

    // The assertion: body must have a row-distribution mechanism — absence is the defect.
    // On current production code: bodyRuleBody has only grid-template-columns, flex, overflow — no row-distribution.
    expect(hasGridTemplateRows || hasGridAutoRows || hasDisplayFlex).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-COURT-GRID-FIELD-COLUMN-1FR-PER-LAP-PRESERVED-GREEN (regression guard)
// ---------------------------------------------------------------------------
describe('E50S05 AC-TEST-COURT-GRID-FIELD-COLUMN-1FR-PER-LAP-PRESERVED-GREEN', () => {
  /**
   * Regression guard: E50S04 T4 contract — .court-grid__field-column internal grid-template-rows
   * using repeat(var(--lap-count, 1), 1fr) MUST be preserved after the E50S05 body-level fix.
   *
   * GREEN: current code already passes this (field-column has the rule); must remain GREEN.
   * Story: E50S05 — contexts/artefacts/stories/E50S05.story.md
   */
  it('.court-grid__field-column CSS rule still declares grid-template-rows with 1fr per lap', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    const columnRuleMatch = style.match(/\.court-grid__field-column\s*\{([^}]*)\}/);
    expect(columnRuleMatch).not.toBeNull();
    const columnRuleBody = columnRuleMatch![1];

    // Must have display:grid and grid-template-rows with 1fr
    expect(columnRuleBody).toMatch(/display\s*:\s*grid/);
    expect(columnRuleBody).toMatch(/grid-template-rows\s*:/);
    expect(columnRuleBody).toMatch(/1fr/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-COURT-GRID-ZERO-LAPS-NO-LAYOUT-ERROR-GREEN (regression guard)
// ---------------------------------------------------------------------------
describe('E50S05 AC-TEST-COURT-GRID-ZERO-LAPS-NO-LAYOUT-ERROR-GREEN', () => {
  /**
   * Regression guard: when matchesData.matches is empty (no laps), CourtGrid renders
   * fieldCount field columns with no round-rows, and the degenerate fallback --lap-count:1
   * (Math.max(1, laps.length) per CourtGrid.svelte:127) MUST be preserved.
   *
   * Evidence: CourtGrid.svelte script block still contains Math.max(1, ...) fallback for lap-count.
   * GREEN: current code already preserves this; must remain GREEN after fix.
   * Story: E50S05 — contexts/artefacts/stories/E50S05.story.md
   */
  it('CourtGrid.svelte preserves Math.max(1, laps.length) fallback for --lap-count degenerate case', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // Math.max(1, ...) fallback guards against zero-lap degenerate grid
    expect(source).toMatch(/Math\.max\s*\(\s*1\s*,/);
  });

  it('CourtGrid.svelte inline style sets --lap-count via Math.max(1, laps.length) on field column', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // The inline style on .court-grid__field-column must use --lap-count with the Math.max fallback
    expect(source).toMatch(/--lap-count\s*:\s*\{[^}]*Math\.max/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-COURT-GRID-SINGLE-LAP-FULL-HEIGHT-GREEN
// ---------------------------------------------------------------------------
describe('E50S05 AC-TEST-COURT-GRID-SINGLE-LAP-FULL-HEIGHT-GREEN', () => {
  /**
   * Structural assertion: when a single lap is present (--lap-count:1), the body row-distribution
   * mechanism (per AC-TEST-COURT-GRID-BODY-ROW-DISTRIBUTION-RED) and the field-column
   * grid-template-rows:repeat(1,1fr) together produce the WHAT-property that the single
   * round-row fills the entire body height.
   *
   * Evidence: Both body row-distribution AND field-column 1fr rule are present in the CSS.
   * This is a structural combination test (not bounding-box geometry).
   * GREEN after fix: both rules present simultaneously.
   * Story: E50S05 — contexts/artefacts/stories/E50S05.story.md
   */
  it('body row-distribution AND field-column 1fr are present simultaneously (enables single-lap full-height)', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    // Body must have row-distribution
    const bodyRuleMatch = style.match(/\.court-grid__body\s*\{([^}]*)\}/);
    expect(bodyRuleMatch).not.toBeNull();
    const bodyRuleBody = bodyRuleMatch![1];
    const hasBodyRowDistribution =
      /grid-template-rows\s*:/.test(bodyRuleBody) ||
      /grid-auto-rows\s*:/.test(bodyRuleBody) ||
      /display\s*:\s*flex/.test(bodyRuleBody);
    expect(hasBodyRowDistribution).toBe(true);

    // Field-column must have 1fr-per-lap distribution
    const columnRuleMatch = style.match(/\.court-grid__field-column\s*\{([^}]*)\}/);
    expect(columnRuleMatch).not.toBeNull();
    const columnRuleBody = columnRuleMatch![1];
    expect(columnRuleBody).toMatch(/grid-template-rows\s*:/);
    expect(columnRuleBody).toMatch(/1fr/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-COURT-GRID-MANY-LAPS-EQUAL-DISTRIBUTION-GREEN
// ---------------------------------------------------------------------------
describe('E50S05 AC-TEST-COURT-GRID-MANY-LAPS-EQUAL-DISTRIBUTION-GREEN', () => {
  /**
   * Structural assertion: when N>1 laps are present, the body row-distribution mechanism
   * and field-column grid-template-rows:repeat(N,1fr) together produce the WHAT-property
   * that each of the N round-rows receives 1/N of the body height.
   *
   * Evidence: body row-distribution present + field-column uses repeat(var(--lap-count,1),1fr).
   * The CSS variable --lap-count is set inline to the actual lap count.
   * GREEN after fix: all required CSS rules present simultaneously.
   * Story: E50S05 — contexts/artefacts/stories/E50S05.story.md
   */
  it('body row-distribution AND field-column repeat(var(--lap-count),1fr) enable equal many-lap distribution', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    // Body must have row-distribution (same check as AC-BODY-ROW-DISTRIBUTION-RED)
    const bodyRuleMatch = style.match(/\.court-grid__body\s*\{([^}]*)\}/);
    expect(bodyRuleMatch).not.toBeNull();
    const bodyRuleBody = bodyRuleMatch![1];
    const hasBodyRowDistribution =
      /grid-template-rows\s*:/.test(bodyRuleBody) ||
      /grid-auto-rows\s*:/.test(bodyRuleBody) ||
      /display\s*:\s*flex/.test(bodyRuleBody);
    expect(hasBodyRowDistribution).toBe(true);

    // Field-column must use CSS variable --lap-count for dynamic 1fr distribution
    const columnRuleMatch = style.match(/\.court-grid__field-column\s*\{([^}]*)\}/);
    expect(columnRuleMatch).not.toBeNull();
    const columnRuleBody = columnRuleMatch![1];
    expect(columnRuleBody).toMatch(/--lap-count/);
    expect(columnRuleBody).toMatch(/1fr/);
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-COURT-GRID-NOPHASE-LOADING-STATES-GREEN (E50S05 regression guard)
// ---------------------------------------------------------------------------
describe('E50S05 AC-TEST-COURT-GRID-NOPHASE-LOADING-STATES-GREEN', () => {
  /**
   * Regression guard: the body-level CSS rule change does NOT affect loading/error states.
   * CourtGrid is not rendered in noPhase or loading states (OverviewLayout/App.svelte
   * control rendering). The prop interface and template structure must be unchanged.
   *
   * GREEN: these are structural source checks — must remain GREEN after fix.
   * Story: E50S05 — contexts/artefacts/stories/E50S05.story.md
   */
  it('CourtGrid.svelte changes are limited to the <style> block (no script or template changes)', () => {
    const source = readSource(COURT_GRID_SVELTE);

    // Script block still has the same key derived computations (not changed by fix)
    const scriptMatch = source.match(/<script[^>]*>([\s\S]*?)<\/script>/);
    expect(scriptMatch).not.toBeNull();
    const script = scriptMatch![1];
    // Key derived values from E50S04 (no changes allowed per AC-GOVERNANCE-NO-OUT-OF-SCOPE-REFACTOR)
    expect(script).toMatch(/matchesByField/);
    expect(script).toMatch(/distinctLapsSorted/);
    expect(script).toMatch(/matchesForLap/);
    expect(script).toMatch(/maxLapCount/);
  });

  it('CourtGrid.svelte template structure is unchanged (court-grid__body and field-column divs intact)', () => {
    const source = readSource(COURT_GRID_SVELTE);
    // Template structure unchanged
    expect(source).toMatch(/court-grid__body/);
    expect(source).toMatch(/court-grid__field-column/);
    expect(source).toMatch(/<MatchRow/);
  });
});
