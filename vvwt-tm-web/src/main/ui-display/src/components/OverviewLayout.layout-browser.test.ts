// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, beforeEach, beforeAll } from 'vitest';
import { commands, page } from '@vitest/browser/context';

/**
 * Reads the <style> block from a Svelte component file via @vitest/browser commands.
 * The readFile command runs on the vitest server (Node.js), not in the browser.
 * This gives the test access to the live OverviewLayout.svelte CSS, not a copy.
 *
 * Path construction: use URL manipulation (browser-compatible, no node:path import).
 */
async function readOverviewLayoutCSS(): Promise<string> {
  // Construct path: from this file's URL, go up one level to get the component dir
  const thisFileUrl = new URL(import.meta.url);
  // thisFileUrl.pathname: .../src/components/OverviewLayout.layout-browser.test.ts
  // We want:             .../src/components/OverviewLayout.svelte
  const svelteFilePath = thisFileUrl.pathname.replace(
    /OverviewLayout\.layout-browser\.test\.ts$/,
    'OverviewLayout.svelte'
  );
  const source = await commands.readFile(svelteFilePath, 'utf-8');
  const styleMatch = source.match(/<style[^>]*>([\s\S]*?)<\/style>/);
  if (!styleMatch) throw new Error('No <style> block found in OverviewLayout.svelte');
  return styleMatch[1];
}

let overviewLayoutCSS: string;

/** Viewport height used for layout assertions (matches page.viewport() call) */
const VIEWPORT_HEIGHT = 896;
const VIEWPORT_WIDTH = 1280;

beforeAll(async () => {
  overviewLayoutCSS = await readOverviewLayoutCSS();
  // Set the iframe viewport to a known size so height assertions are deterministic.
  // vitest browser runs tests in an iframe with a small default height; without this,
  // window.innerHeight returns the outer browser window height but 100vh is the iframe
  // height, causing a mismatch in layout assertions.
  await page.viewport(VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
});

/**
 * Creates a fixture DOM element with the real OverviewLayout CSS injected.
 * On RED commit: no grid-row on __main/__sidebar → auto-placement in row 1 → height = 0.
 * On GREEN commit: grid-row: 2 on both → placed in 1fr row → height ≈ viewport height.
 */
function buildOverviewLayoutFixture(options: { includeBanner: boolean }): HTMLElement {
  const container = document.createElement('div');
  container.style.position = 'fixed';
  container.style.top = '0';
  container.style.left = '0';
  container.style.width = '100vw';
  container.style.height = '100vh';
  container.style.overflow = 'hidden';

  // Inject the ACTUAL CSS from OverviewLayout.svelte (read from disk via commands.readFile)
  const style = document.createElement('style');
  style.textContent = overviewLayoutCSS;
  container.appendChild(style);

  const layout = document.createElement('div');
  layout.className = 'overview-layout';
  layout.style.height = '100%';

  if (options.includeBanner) {
    const banner = document.createElement('div');
    banner.className = 'overview-layout__preview-banner';
    banner.textContent = 'VORSCHAU';
    layout.appendChild(banner);
  }

  const main = document.createElement('div');
  main.className = 'overview-layout__main';
  layout.appendChild(main);

  const sidebar = document.createElement('div');
  sidebar.className = 'overview-layout__sidebar';
  layout.appendChild(sidebar);

  container.appendChild(layout);
  return container;
}

describe('E50S06 AC-TEST-VERTICAL-FILL-REAL-ENGINE-RED', () => {
  /**
   * The failing scenario: ACTIVE phase, no preview banner.
   *
   * RED on current HEAD: .overview-layout__main has no grid-row assignment,
   * so CSS auto-placement puts it in row 1 (auto = 0 height since content is empty).
   * The 1fr row stays empty. getBoundingClientRect().height = 0.
   *
   * GREEN after fix: explicit grid-row: 2 places __main in the 1fr row.
   * getBoundingClientRect().height ≈ viewport height.
   *
   * DEC-22 RED-first: this test was committed before the production fix was applied.
   * The RED commit (chore(E50S06): RED) precedes the GREEN commit (feat(E50S06): fix)
   * in git log — demonstrably verifiable via `git log --oneline`.
   */
  let fixture: HTMLElement;

  beforeEach(() => {
    fixture = buildOverviewLayoutFixture({ includeBanner: false });
    document.body.appendChild(fixture);
    document.body.style.margin = '0';
    document.body.style.padding = '0';
    document.documentElement.style.height = '100%';
    document.body.style.height = '100%';
  });

  it('banner-absent (ACTIVE): .overview-layout__main fills ≥ 90% of the viewport height', () => {
    const main = fixture.querySelector('.overview-layout__main') as HTMLElement;
    expect(main).not.toBeNull();

    const mainRect = main.getBoundingClientRect();

    // DEFECT fingerprint: without grid-row:2, mainRect.height = 0 (auto row, no content)
    // PASS condition: mainRect.height must be ≥ 90% of the viewport height (fills the 1fr row)
    // viewport height is set to VIEWPORT_HEIGHT (896) via page.viewport() in beforeAll.
    expect(mainRect.height).toBeGreaterThanOrEqual(VIEWPORT_HEIGHT * 0.9);
  });

  it('banner-absent (ACTIVE): .overview-layout__sidebar fills ≥ 90% of the viewport height', () => {
    const sidebar = fixture.querySelector('.overview-layout__sidebar') as HTMLElement;
    expect(sidebar).not.toBeNull();

    const sidebarRect = sidebar.getBoundingClientRect();

    // Same defect: sidebar also auto-places in row 1, collapses to 0 height
    expect(sidebarRect.height).toBeGreaterThanOrEqual(VIEWPORT_HEIGHT * 0.9);
  });

  it('banner-absent: both __main and __sidebar have the same top position (same grid row)', () => {
    const main = fixture.querySelector('.overview-layout__main') as HTMLElement;
    const sidebar = fixture.querySelector('.overview-layout__sidebar') as HTMLElement;

    const mainRect = main.getBoundingClientRect();
    const sidebarRect = sidebar.getBoundingClientRect();

    // Both are in the same row (row 2 / 1fr after fix).
    // Row alignment is confirmed when heights are also correct (complementary assertion).
    expect(Math.abs(mainRect.top - sidebarRect.top)).toBeLessThan(2); // same row ± 1px
  });
});

describe('E50S06 AC-TEST-VERTICAL-FILL-BANNER-PRESENT-GREEN', () => {
  /**
   * Non-recurring guard: with the preview banner present, __main/__sidebar must
   * still fill the available space (the 1fr row below the banner).
   *
   * Per AC text: "its test mechanism is a Delivery HOW decision and need not use the
   * real-layout-engine harness (a structural assertion is acceptable here)".
   * This test uses the same real-browser approach as the banner-absent test for
   * consistency and to provide a more thorough check.
   *
   * GREEN: passes after the fix. The explicit grid-row: 2 makes both banner-absent
   * and banner-present cases consistent.
   *
   * Note: Even on current HEAD (RED), the banner-present case MAY pass because
   * the banner element occupies the auto row, and auto-placement may assign __main
   * to row 2 implicitly. This test is a GUARD — it must remain GREEN after the fix
   * to ensure the fix doesn't break the banner-present rendering.
   */
  let fixture: HTMLElement;

  beforeEach(() => {
    fixture = buildOverviewLayoutFixture({ includeBanner: true });
    document.body.appendChild(fixture);
    document.body.style.margin = '0';
    document.body.style.padding = '0';
    document.documentElement.style.height = '100%';
    document.body.style.height = '100%';
  });

  it('banner-present (PENDING/PREPARATION): all three elements are rendered', () => {
    const banner = fixture.querySelector('.overview-layout__preview-banner');
    const main = fixture.querySelector('.overview-layout__main');
    const sidebar = fixture.querySelector('.overview-layout__sidebar');

    expect(banner).not.toBeNull();
    expect(main).not.toBeNull();
    expect(sidebar).not.toBeNull();
  });

  it('banner-present: .overview-layout__main fills a substantial portion below the banner', () => {
    const main = fixture.querySelector('.overview-layout__main') as HTMLElement;
    const banner = fixture.querySelector('.overview-layout__preview-banner') as HTMLElement;

    const bannerRect = banner.getBoundingClientRect();
    const mainRect = main.getBoundingClientRect();

    // __main should fill the space below the banner (the 1fr row).
    // VIEWPORT_HEIGHT set via page.viewport() in beforeAll.
    const availableHeight = VIEWPORT_HEIGHT - bannerRect.height;
    expect(mainRect.height).toBeGreaterThanOrEqual(availableHeight * 0.9);
  });
});
