import { svelte } from '@sveltejs/vite-plugin-svelte';
import { svelteTesting } from '@testing-library/svelte/vite';
import { defineConfig } from 'vite';
import path from 'path';

/**
 * Vite configuration for the Public Participant Info Service SPA.
 *
 * Story E38S01 — DEC-2: Vite + Svelte 5 + TypeScript; SvelteKit explicitly absent.
 * Story E38S08 — AC8: output wired into vvwt-info-server/src/main/resources/static/info/
 *                AC7: Svelte 5 + TypeScript 5 + Vite 5+; no SvelteKit.
 *
 * base: '/info/' ensures all generated asset paths are prefixed for Spring Boot static serving.
 * outDir: points directly to vvwt-info-server static resources (AC8 — no copy step needed).
 */
export default defineConfig({
  plugins: [svelte(), svelteTesting()],
  base: '/info/',
  build: {
    // AC8: bundle output goes directly into vvwt-info-server static resources
    // Path: from src/main/ui/ go up 4 levels to vvwt-prj root, then into vvwt-info-server
    outDir: path.resolve(
      import.meta.dirname,
      '../../../../vvwt-info-server/src/main/resources/static/info',
    ),
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['@testing-library/svelte/vitest'],
  },
});
