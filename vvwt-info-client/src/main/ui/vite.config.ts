import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

/**
 * Vite configuration for the Public Participant Info Service SPA.
 *
 * Story E38S01 — DEC-2: Vite + Svelte 5 + TypeScript; SvelteKit explicitly absent.
 * AC11: npm install && npm run build produces a working static-asset bundle to dist/.
 *
 * base: '/info/' ensures all generated asset paths are prefixed for Spring Boot static serving.
 * Bundle relocation into vvwt-info-server/src/main/resources/static/ is E38S08 scope.
 */
export default defineConfig({
  plugins: [svelte()],
  base: '/info/',
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
