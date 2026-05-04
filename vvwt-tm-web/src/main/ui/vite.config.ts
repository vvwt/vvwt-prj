import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

/**
 * Vite configuration for Tournament Manager Admin SPA.
 *
 * Story E05S01 — DEC-2: Vite + Svelte + TypeScript, no SvelteKit.
 *
 * base: '/admin/' ensures all generated asset paths (JS, CSS) are prefixed with /admin/,
 * so Spring Boot serves them correctly from classpath:/static/admin/.
 */
export default defineConfig({
  plugins: [svelte()],
  base: '/admin/',
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
    outDir: 'dist',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: [],
  },
});
