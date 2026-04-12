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
