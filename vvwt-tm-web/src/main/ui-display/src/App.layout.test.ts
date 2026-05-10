/**
 * Layout tests for Display SPA header band (E50S02) and Sidebar-Header reposition (E50S03).
 *
 * DEC-22 Iron Law: RED-first tests written BEFORE production code changes.
 *
 * E50S02 tests (retained, some updated for E50S03 supersession):
 *   - E50S03 supersedes E50S02's AC envelope: display-header is REMOVED from App.svelte.
 *   - E50S02 tests that asserted display-header IS present in App.svelte are updated here
 *     to assert display-header is NOT present (per AC-TEST-LAYOUT-NO-TOP-HEADER-RED).
 *   - E50S02 regression tests for ConnectionStatus 5 states, loading state, noPhase remain GREEN.
 *
 * E50S03 RED tests (AC-TEST-LAYOUT-*-RED):
 *   - Must FAIL against current source (display-header still in App.svelte, no sidebar-header).
 *   - Will pass after the fix (SidebarHeader.svelte introduced, OverviewLayout updated).
 *
 * E50S03 GREEN regression tests:
 *   - Must pass both before and after the fix.
 *
 * Strategy: source-inspection via readFileSync — the display SPA test stack is
 * Vitest + jsdom with vitest@^3.1.3 + jsdom@^29.0.2 (no Playwright).
 * Structural assertions use CSS class existence and style-rule content inspection.
 * No bounding-box geometry (jsdom does not implement it reliably).
 *
 * Story: E50S02 — contexts/artefacts/stories/E50S02.story.md
 * Story: E50S03 — contexts/artefacts/stories/E50S03.story.md
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
const OVERVIEW_LAYOUT_SVELTE = resolve(
  BASE,
  '..',
  'components',
  'OverviewLayout.svelte'
);
const SIDEBAR_HEADER_SVELTE = resolve(
  BASE,
  '..',
  'components',
  'SidebarHeader.svelte'
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
// E50S02 AC-TEST-LAYOUT-SINGLE-BAND-DOM-STRUCTURE-RED
// Updated for E50S03: display-header is REMOVED from App.svelte (superseded).
// The original E50S02 assertion "display-header IS present" is now superseded by
// E50S03 AC-TEST-LAYOUT-NO-TOP-HEADER-RED which asserts it is NOT present.
// ---------------------------------------------------------------------------
describe('E50S02->E50S03 AC-TEST-LAYOUT-SINGLE-BAND-DOM-STRUCTURE (supersession)', () => {
  /**
   * E50S03: The separate top-level <header class="display-header"> MUST NOT exist in App.svelte.
   * E50S03 supersedes E50S02's AC envelope — the header moves into SidebarHeader.svelte.
   */
  it('App.svelte template does NOT have a top-level display-header element (E50S03 supersession)', () => {
    const source = readSource(APP_SVELTE);
    expect(source).not.toMatch(/class="display-header"/);
  });

  /**
   * E50S02 regression: separate logo-only brand-header element must still not exist.
   */
  it('App.svelte template does NOT have a separate logo-only brand-header element', () => {
    const source = readSource(APP_SVELTE);
    expect(source).not.toMatch(/class="brand-header"/);
  });
});

// ---------------------------------------------------------------------------
// E50S02 AC-TEST-LAYOUT-LOGO-HEIGHT-CSS-RED (updated for E50S03)
// ---------------------------------------------------------------------------
describe('E50S02->E50S03 AC-TEST-LAYOUT-LOGO-HEIGHT-CSS (supersession)', () => {
  /**
   * E50S03: .display-header rule moves OUT of App.svelte into SidebarHeader.svelte.
   * App.svelte style block must NOT have a .display-header rule.
   */
  it('App.svelte style does NOT contain a .display-header rule (moved to SidebarHeader)', () => {
    const source = readSource(APP_SVELTE);
    const style = stripComments(extractStyleBlock(source));
    expect(style).not.toMatch(/\.display-header\s*\{/);
  });

  /**
   * App.svelte style must NOT have a .brand-header rule (confirmed absent from E50S02).
   */
  it('App.svelte style does NOT contain a .brand-header rule (logo-only header removed)', () => {
    const source = readSource(APP_SVELTE);
    const style = stripComments(extractStyleBlock(source));
    expect(style).not.toMatch(/\.brand-header\s*\{/);
  });
});

// ---------------------------------------------------------------------------
// E50S02 AC-TEST-LAYOUT-INDICATOR-IN-FLOW-RED (regression -- still GREEN)
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-INDICATOR-IN-FLOW-RED', () => {
  /**
   * ConnectionStatus.svelte MUST NOT declare position: fixed in its .connection-status rule.
   * Already GREEN since E50S02. Regression guard.
   */
  it('ConnectionStatus.svelte .connection-status rule does NOT declare position: fixed', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    const ruleMatch = style.match(/\.connection-status\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];

    expect(ruleBody).not.toMatch(/\bposition\s*:\s*fixed\b/);
  });

  it('ConnectionStatus.svelte .connection-status rule does NOT declare top: (removed with fixed)', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    const ruleMatch = style.match(/\.connection-status\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];

    expect(ruleBody).not.toMatch(/\btop\s*:/);
  });
});

// ---------------------------------------------------------------------------
// E50S02 AC-TEST-LAYOUT-NOPHASE-STATE-REGRESSION-GREEN (updated for E50S03)
// ---------------------------------------------------------------------------
describe('E50S02->E50S03 AC-TEST-LAYOUT-NOPHASE-STATE-REGRESSION-GREEN', () => {
  /**
   * E50S03 regression: logo/brand must be visible regardless of errorType.
   * In E50S03, the brand-lockup moves to SidebarHeader.svelte.
   * SidebarHeader.svelte must contain the brand-lockup img unconditionally.
   */
  it('SidebarHeader.svelte renders brand-lockup img unconditionally', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/class="brand-lockup"/);
  });

  it('SidebarHeader.svelte contains ConnectionStatusIndicator unconditionally', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/ConnectionStatus/);
  });
});

// ---------------------------------------------------------------------------
// E50S02 AC-TEST-LAYOUT-LOADING-STATE-REGRESSION-GREEN (updated for E50S03)
// ---------------------------------------------------------------------------
describe('E50S02->E50S03 AC-TEST-LAYOUT-LOADING-STATE-REGRESSION-GREEN', () => {
  /**
   * E50S03 regression: loading spinner is still inside the display-app content area.
   */
  it('loading spinner class (display-app__spinner) is present in App.svelte', () => {
    const source = readSource(APP_SVELTE);
    expect(source).toContain('class="display-app__spinner"');
  });

  it('loading spinner is inside the display-app content area (not in top area)', () => {
    const source = readSource(APP_SVELTE);
    const displayAppIdx = source.indexOf('class="display-app"');
    const spinnerIdx = source.indexOf('class="display-app__spinner"');

    expect(displayAppIdx).toBeGreaterThan(-1);
    expect(spinnerIdx).toBeGreaterThan(-1);
    expect(displayAppIdx).toBeLessThan(spinnerIdx);
  });
});

// ---------------------------------------------------------------------------
// E50S02 AC-TEST-LAYOUT-CONNECTION-STATES-VISUAL-PRESERVED-GREEN (unchanged)
// ---------------------------------------------------------------------------
describe('E50S02 AC-TEST-LAYOUT-CONNECTION-STATES-VISUAL-PRESERVED-GREEN', () => {
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

    expect(source).toContain('display.connection.connecting');
    expect(source).toContain('display.connection.connected');
    expect(source).toContain('display.connection.reconnecting');
    expect(source).toContain('display.connection.polling');
    expect(source).toContain('display.connection.disconnected');
  });

  it('ConnectionStatus.svelte status prop type is preserved', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);
    expect(source).toMatch(/status\s*:\s*ConnectionStatus/);
  });
});

// ---------------------------------------------------------------------------
// E50S02 AC-ERROR-HANDLING-INDICATOR-WHEN-LOADING (updated for E50S03)
// ---------------------------------------------------------------------------
describe('E50S02->E50S03 AC-ERROR-HANDLING-INDICATOR-WHEN-LOADING', () => {
  /**
   * E50S03: ConnectionStatusIndicator moves to SidebarHeader.svelte.
   * Per AC-TEST-LAYOUT-NOPHASE-LOADING-STATES-GREEN, SidebarHeader is rendered
   * consistently across loading states OR explicitly hidden per Delivery design
   * (deterministic behavior, no flicker). Delivery chose: SidebarHeader is passed as
   * a snippet to OverviewLayout — explicitly hidden during loading/error (deterministic,
   * no flicker since the element is structurally absent, not conditionally shown/hidden).
   *
   * The AC requires the indicator to render deterministically when shown. This is ensured
   * by SidebarHeader.svelte always rendering ConnectionStatusIndicator with the status prop.
   * App.svelte's connectionStatus state initializes to 'disconnected' (deterministic default).
   */
  it('SidebarHeader.svelte renders ConnectionStatusIndicator with status prop (deterministic init)', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/<ConnectionStatus/);
    expect(source).toMatch(/\{status\}/);
  });

  it('App.svelte connectionStatus state initializes to disconnected (deterministic loading default)', () => {
    const source = readSource(APP_SVELTE);
    expect(source).toMatch(/connectionStatus\s*=\s*\$state[^(]*\(\s*['"]disconnected['"]\s*\)/);
  });
});

// ===========================================================================
// E50S03 NEW TESTS -- RED-first (must fail before implementation)
// ===========================================================================

// ---------------------------------------------------------------------------
// E50S03 AC-TEST-LAYOUT-NO-TOP-HEADER-RED
// ---------------------------------------------------------------------------
describe('E50S03 AC-TEST-LAYOUT-NO-TOP-HEADER-RED', () => {
  /**
   * The rendered DOM MUST contain NO element with class `display-header` in App.svelte.
   *
   * RED: current App.svelte has <header class="display-header"> --> this test FAILS.
   * GREEN after fix: display-header is removed from App.svelte.
   */
  it('App.svelte template contains NO element with class display-header (top header removed)', () => {
    const source = readSource(APP_SVELTE);
    expect(source).not.toContain('class="display-header"');
  });

  it('App.svelte style does NOT define a .display-header CSS rule', () => {
    const source = readSource(APP_SVELTE);
    const style = stripComments(extractStyleBlock(source));
    expect(style).not.toMatch(/\.display-header\s*\{/);
  });
});

// ---------------------------------------------------------------------------
// E50S03 AC-TEST-LAYOUT-SIDEBAR-HEADER-RENDERED-RED
// ---------------------------------------------------------------------------
describe('E50S03 AC-TEST-LAYOUT-SIDEBAR-HEADER-RENDERED-RED', () => {
  /**
   * A new SidebarHeader.svelte component MUST exist using class `sidebar-header`.
   * OverviewLayout.svelte MUST render it at the top of the sidebar div.
   *
   * RED: SidebarHeader.svelte does not exist yet.
   * GREEN after fix: component created, OverviewLayout updated.
   */
  it('SidebarHeader.svelte exists and uses class sidebar-header', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/class="sidebar-header"/);
  });

  it('OverviewLayout.svelte renders sidebarHeader snippet inside overview-layout__sidebar', () => {
    const source = readSource(OVERVIEW_LAYOUT_SVELTE);
    expect(source).toMatch(/\{@render\s+sidebarHeader/);
  });

  it('OverviewLayout.svelte declares sidebarHeader Snippet prop', () => {
    const source = readSource(OVERVIEW_LAYOUT_SVELTE);
    expect(source).toMatch(/sidebarHeader/);
    expect(source).toMatch(/Snippet/);
  });

  it('In OverviewLayout.svelte the sidebarHeader render appears before GroupStandingsPanel in sidebar', () => {
    const source = readSource(OVERVIEW_LAYOUT_SVELTE);
    // Search from the class="overview-layout__sidebar" div tag in the template (not JSDoc comment)
    const sidebarDivIdx = source.indexOf('class="overview-layout__sidebar"');
    const sidebarHeaderRenderIdx = source.indexOf('@render sidebarHeader', sidebarDivIdx);
    const groupStandingsIdx = source.indexOf('<GroupStandingsPanel', sidebarDivIdx);

    expect(sidebarDivIdx).toBeGreaterThan(-1);
    expect(sidebarHeaderRenderIdx).toBeGreaterThan(-1);
    expect(groupStandingsIdx).toBeGreaterThan(-1);

    expect(sidebarHeaderRenderIdx).toBeLessThan(groupStandingsIdx);
  });
});

// ---------------------------------------------------------------------------
// E50S03 AC-TEST-LAYOUT-SIDEBAR-HEADER-CONTAINS-LOGO-AND-INDICATOR-RED
// ---------------------------------------------------------------------------
describe('E50S03 AC-TEST-LAYOUT-SIDEBAR-HEADER-CONTAINS-LOGO-AND-INDICATOR-RED', () => {
  /**
   * SidebarHeader.svelte MUST contain brand-lockup img + ConnectionStatusIndicator.
   *
   * RED: SidebarHeader.svelte does not exist yet.
   * GREEN after fix: component created with both elements.
   */
  it('SidebarHeader.svelte contains brand-lockup img element', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/class="brand-lockup"/);
  });

  it('SidebarHeader.svelte imports and renders ConnectionStatus component', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/ConnectionStatus/);
    expect(source).toMatch(/<ConnectionStatus/);
  });

  it('SidebarHeader.svelte accepts and passes status prop to ConnectionStatusIndicator', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/status/);
  });
});

// ---------------------------------------------------------------------------
// E50S03 AC-TEST-LAYOUT-SIDEBAR-HEADER-HEIGHT-MATCHES-COURT-GRID-HEADER-RED
// ---------------------------------------------------------------------------
describe('E50S03 AC-TEST-LAYOUT-SIDEBAR-HEADER-HEIGHT-MATCHES-COURT-GRID-HEADER-RED', () => {
  /**
   * Sidebar-Header CSS rule MUST set height equivalent to court-grid__headers band.
   * Equivalence via shared CSS custom property --field-header-height.
   *
   * RED: SidebarHeader.svelte does not exist; CSS var not declared.
   * GREEN after fix: variable declared and referenced by both components.
   */
  it('SidebarHeader.svelte .sidebar-header rule sets height (via CSS var or literal)', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    const style = stripComments(extractStyleBlock(source));
    const ruleMatch = style.match(/\.sidebar-header\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];
    expect(ruleBody).toMatch(/height\s*:/);
  });

  it('SidebarHeader and CourtGrid heights are equivalent (same CSS var or same literal)', () => {
    const sidebarSrc = readSource(SIDEBAR_HEADER_SVELTE);
    const courtGridSrc = readSource(
      resolve(BASE, '..', 'components', 'CourtGrid.svelte')
    );

    const sidebarStyle = stripComments(extractStyleBlock(sidebarSrc));
    const courtGridStyle = stripComments(extractStyleBlock(courtGridSrc));

    // Strategy A: both use var(--field-header-height)
    const sidebarUsesVar = /\.sidebar-header\s*\{[^}]*var\(--field-header-height\)/.test(sidebarStyle);
    const courtGridUsesVar = /\.court-grid__headers\s*\{[^}]*var\(--field-header-height\)/.test(courtGridStyle);

    if (sidebarUsesVar && courtGridUsesVar) {
      expect(sidebarUsesVar).toBe(true);
      expect(courtGridUsesVar).toBe(true);
      return;
    }

    // Strategy B: extract literal height values and compare
    const sidebarHeightMatch = sidebarStyle.match(/\.sidebar-header\s*\{[^}]*height\s*:\s*([^;}\s]+)/);
    const courtGridHeightMatch = courtGridStyle.match(/\.court-grid__headers\s*\{[^}]*height\s*:\s*([^;}\s]+)/);

    if (sidebarHeightMatch && courtGridHeightMatch) {
      expect(sidebarHeightMatch[1]).toBe(courtGridHeightMatch[1]);
      return;
    }

    throw new Error(
      'Height equivalence not established: neither shared CSS var(--field-header-height) nor matching literal values found in .sidebar-header and .court-grid__headers rules'
    );
  });
});

// ---------------------------------------------------------------------------
// E50S03 AC-TEST-LAYOUT-CONNECTION-STATES-VISUAL-PRESERVED-GREEN
// ---------------------------------------------------------------------------
describe('E50S03 AC-TEST-LAYOUT-CONNECTION-STATES-VISUAL-PRESERVED-GREEN', () => {
  /**
   * After relocation to SidebarHeader: 5 connection states preserved in ConnectionStatus.svelte.
   */
  it('ConnectionStatus.svelte still has all 5 connection states after SidebarHeader relocation', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);
    const states = ['connecting', 'connected', 'reconnecting', 'polling', 'disconnected'];
    for (const state of states) {
      expect(source).toContain(state);
    }
  });

  it('ConnectionStatus.svelte public status prop API preserved (E07S06 AC7 contract)', () => {
    const source = readSource(CONNECTION_STATUS_SVELTE);
    expect(source).toMatch(/status\s*:\s*ConnectionStatus/);
  });
});

// ---------------------------------------------------------------------------
// E50S03 AC-TEST-LAYOUT-NOPHASE-LOADING-STATES-GREEN
// ---------------------------------------------------------------------------
describe('E50S03 AC-TEST-LAYOUT-NOPHASE-LOADING-STATES-GREEN', () => {
  /**
   * SidebarHeader is rendered consistently across loading/error states.
   * Per story AC: "or explicitly hidden per Delivery design; deterministic behavior, no flicker."
   * Delivery chose: SidebarHeader is rendered inside OverviewLayout sidebar snippet (explicitly
   * hidden during loading/error — deterministic absence, no flicker). The sidebar-header
   * ALWAYS renders ConnectionStatusIndicator when the SidebarHeader IS rendered (no conditional
   * hiding of indicator within SidebarHeader).
   */
  it('SidebarHeader.svelte renders sidebar-header class (deterministic when rendered)', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    expect(source).toMatch(/sidebar-header/);
  });

  it('SidebarHeader.svelte always renders ConnectionStatusIndicator (no conditional within sidebar-header)', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    // No {#if ...} wrapping the ConnectionStatus component inside SidebarHeader
    const connectionStatusIdx = source.indexOf('<ConnectionStatus');
    const ifBeforeCS = source.lastIndexOf('{#if', connectionStatusIdx);
    const endIfBeforeCS = source.lastIndexOf('{/if}', connectionStatusIdx);
    // Either no {#if} before ConnectionStatus, or the last {/if} closes before the {#if}
    // i.e., no open {#if} block containing ConnectionStatus
    const isUnconditional = ifBeforeCS === -1 || (endIfBeforeCS > ifBeforeCS);
    expect(isUnconditional).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// E50S03 AC-GOVERNANCE-OVERVIEWLAYOUT-ADDITIVE-ONLY
// ---------------------------------------------------------------------------
describe('E50S03 AC-GOVERNANCE-OVERVIEWLAYOUT-ADDITIVE-ONLY', () => {
  /**
   * OverviewLayout.svelte must remain structurally intact.
   * Only additive changes: sidebarHeader Snippet prop + render call.
   */
  it('OverviewLayout.svelte still uses two-column CSS grid (4fr 28em)', () => {
    const source = readSource(OVERVIEW_LAYOUT_SVELTE);
    expect(source).toMatch(/4fr\s+28em/);
  });

  it('OverviewLayout.svelte still renders GroupStandingsPanel', () => {
    const source = readSource(OVERVIEW_LAYOUT_SVELTE);
    expect(source).toContain('GroupStandingsPanel');
  });

  it('OverviewLayout.svelte still renders CourtGrid', () => {
    const source = readSource(OVERVIEW_LAYOUT_SVELTE);
    expect(source).toContain('CourtGrid');
  });
});

// ---------------------------------------------------------------------------
// E50S03 AC-GOVERNANCE-LOGO-MIN-HEIGHT-FLOOR
// ---------------------------------------------------------------------------
describe('E50S03 AC-GOVERNANCE-LOGO-MIN-HEIGHT-FLOOR', () => {
  /**
   * Brand-lockup height CSS rule must be >= 1.8em (E44S02 AC13 floor).
   * The .brand-lockup rule now lives in SidebarHeader.svelte.
   */
  it('SidebarHeader.svelte .brand-lockup height is at least 1.8em', () => {
    const source = readSource(SIDEBAR_HEADER_SVELTE);
    const style = stripComments(extractStyleBlock(source));

    const ruleMatch = style.match(/\.brand-lockup\s*\{([^}]*)\}/);
    expect(ruleMatch).not.toBeNull();
    const ruleBody = ruleMatch![1];

    const heightMatch = ruleBody.match(/height\s*:\s*([\d.]+)em/);
    expect(heightMatch).not.toBeNull();

    const heightValue = parseFloat(heightMatch![1]);
    expect(heightValue).toBeGreaterThanOrEqual(1.8);
  });
});
