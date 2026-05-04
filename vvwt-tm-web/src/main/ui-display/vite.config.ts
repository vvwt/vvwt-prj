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
    environment: 'jsdom',
    globals: true,
    setupFiles: [],
  },
});
