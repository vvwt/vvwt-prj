import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

/**
 * Vite configuration for the Tournament Manager Display SPA (Gesamtübersicht).
 *
 * Story E07S05 — DEC-2: Vite + Svelte + TypeScript, no SvelteKit.
 *
 * base: '/display/' ensures all generated asset paths (JS, CSS) are prefixed with /display/,
 * so Spring Boot serves them correctly from classpath:/static/display/.
 *
 * The maven-resources-plugin in pom.xml copies dist-display/ → target/classes/static/display/
 * after this build completes.
 *
 * DEC-16 / AC7: Vite produces no external CDN references; all assets are local.
 * The integration test DisplayViewControllerIT verifies that the served HTML contains
 * no 'https://' in <script> or <link> tags.
 *
 * E50S06 — test.projects: two vitest projects run via `npm test` → `vitest run`:
 *   1. "jsdom": all existing source-inspection tests (App.layout, CourtGrid, etc.)
 *   2. "browser": real-browser-layout-engine tests (OverviewLayout.layout-browser.test.ts)
 *      Uses Playwright/Chromium (system Chromium at /usr/bin/chromium).
 *      DEC-54: bound to Maven `test` phase via npm-test-display execution in pom.xml.
 */
export default defineConfig({
  plugins: [svelte()],
  base: '/display/',
  // E18S03: sockjs-client@1.6.1 references the Node.js built-in `global` identifier at
  // module-evaluation time. Vite ≥ 5 does not auto-shim `global`, so the built bundle
  // contains unguarded `global.*` references (global.WebSocket, global.XMLHttpRequest, …)
  // that throw `ReferenceError: global is not defined` in browsers. The `define` entry
  // below substitutes every bare `global` reference in the bundle with `globalThis` at
  // build time. Removing this entry will re-break all three SPAs. See DEC-2, DEC-16.
  define: {
    global: 'globalThis',
  },
  build: {
    outDir: 'dist-display',
    emptyOutDir: true,
  },
  test: {
    /**
     * E50S06: vitest workspace configuration via test.projects (canonical vitest 3.x approach).
     *
     * Project 1 "jsdom": all existing source-inspection tests.
     *   - No changes to test environment for existing tests.
     *   - Excludes *.layout-browser.test.ts (real-browser tests go to project 2).
     *
     * Project 2 "browser": OverviewLayout.layout-browser.test.ts only.
     *   - Runs in Playwright/Chromium to compute actual CSS layout geometry.
     *   - Uses system Chromium at /usr/bin/chromium via PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH
     *     env var (with /usr/bin/chromium as the default).
     *   - PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 prevents playwright from downloading Chromium
     *     during npm install (relies on system Chromium 148, confirmed headless-capable).
     *   - No pom.xml changes needed: both projects run via the existing `npm test` command,
     *     which is bound to the Maven `test` phase by npm-test-display (AC-GOVERNANCE-NO-BACKEND-CHANGES,
     *     AC-GOVERNANCE-LAYOUT-TEST-IN-MVN-VERIFY).
     */
    projects: [
      {
        /**
         * Project 1: jsdom environment — all existing source-inspection tests.
         * Covers: App.layout.test.ts, CourtGrid.test.ts, displayApi.test.ts,
         *         App.noPhasePolling.test.ts, AppBranding.test.ts, websocket.test.ts.
         */
        test: {
          name: 'jsdom',
          environment: 'jsdom',
          globals: true,
          setupFiles: [],
          include: ['src/**/*.test.ts'],
          exclude: ['src/**/*.layout-browser.test.ts'],
        },
      },
      {
        /**
         * Project 2: Playwright browser environment — real-layout-engine tests.
         * Covers: OverviewLayout.layout-browser.test.ts (E50S06 regression test).
         *
         * executablePath reads PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH env var with
         * /usr/bin/chromium as the fallback (system Chromium 148).
         * Launch args --no-sandbox and --disable-setuid-sandbox are required for
         * running Chromium as root or in restricted environments.
         */
        plugins: [svelte()],
        test: {
          name: 'browser',
          include: ['src/**/*.layout-browser.test.ts'],
          browser: {
            enabled: true,
            provider: 'playwright',
            headless: true,
            instances: [
              {
                browser: 'chromium',
                launch: {
                  executablePath:
                    process.env['PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH'] ?? '/usr/bin/chromium',
                  args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage'],
                },
              },
            ],
          },
        },
      },
    ],
  },
});
