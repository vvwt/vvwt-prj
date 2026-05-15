/**
 * Vitest workspace configuration — Display SPA (E50S06).
 *
 * Two projects:
 *   1. "jsdom" — all existing source-inspection tests (App.layout, CourtGrid, displayApi, etc.)
 *      Runs with jsdom environment (no layout engine — same as before E50S06).
 *   2. "browser" — real-browser-layout-engine tests (OverviewLayout.layout.test.ts only).
 *      Runs with Playwright/Chromium to compute actual CSS layout geometry.
 *      Uses system Chromium at /usr/bin/chromium to avoid browser binary downloads.
 *
 * DEC-22 Iron Law: AC-TEST-VERTICAL-FILL-REAL-ENGINE-RED requires a test environment
 * that computes layout geometry — jsdom does not; this browser project does.
 *
 * DEC-54: both projects run via `npm test` → `vitest run`, which is bound to the Maven
 * `test` phase by the `npm-test-display` execution in pom.xml.
 * No pom.xml changes needed (AC-GOVERNANCE-NO-BACKEND-CHANGES satisfied).
 *
 * Story: E50S06 — contexts/artefacts/stories/E50S06.story.md
 */

import { defineWorkspace } from 'vitest/config';

export default defineWorkspace([
  {
    /**
     * Project 1: jsdom environment — all existing source-inspection tests.
     * Covers: App.layout.test.ts, CourtGrid.test.ts, displayApi.test.ts,
     *         App.noPhasePolling.test.ts, AppBranding.test.ts, websocket.test.ts.
     */
    extends: './vite.config.ts',
    test: {
      name: 'jsdom',
      environment: 'jsdom',
      include: [
        'src/**/*.test.ts',
      ],
      exclude: [
        'src/**/*.layout-browser.test.ts',
      ],
    },
  },
  {
    /**
     * Project 2: Playwright browser environment — real-layout-engine tests.
     * Covers: OverviewLayout.layout-browser.test.ts (E50S06 regression test).
     *
     * Uses system Chromium at /usr/bin/chromium (confirmed Chromium 148, headless-capable).
     * PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 prevents npm install from downloading Chromium.
     *
     * The executablePath is read from PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH env var
     * (set in package.json test:layout script and inherited by Maven) with
     * /usr/bin/chromium as the fallback.
     */
    extends: './vite.config.ts',
    test: {
      name: 'browser',
      include: [
        'src/**/*.layout-browser.test.ts',
      ],
      browser: {
        enabled: true,
        provider: 'playwright',
        headless: true,
        instances: [
          {
            browser: 'chromium',
            launch: {
              executablePath: process.env['PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH'] ?? '/usr/bin/chromium',
              args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
            },
          },
        ],
      },
    },
  },
]);
