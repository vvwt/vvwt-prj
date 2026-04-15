import { svelte } from '@sveltejs/vite-plugin-svelte';
import { defineConfig } from 'vite';

/**
 * Vite configuration for the Tournament Manager Timer SPA.
 *
 * Story E11S03 — DEC-2: Vite + Svelte + TypeScript, no SvelteKit.
 *
 * base: '/timer/' ensures all generated asset paths (JS, CSS) are prefixed with /timer/,
 * so Spring Boot serves them correctly from classpath:/static/timer/.
 *
 * The maven-resources-plugin in pom.xml copies dist-timer/ → target/classes/static/timer/
 * after this build completes.
 *
 * DEC-15 / AC1: Vite produces no external CDN references; all assets are local.
 * The integration test TimerViewControllerIT verifies that the served HTML contains
 * no 'https://' in <script> or <link> tags.
 */
export default defineConfig({
  plugins: [svelte()],
  base: '/timer/',
  build: {
    outDir: 'dist-timer',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: [],
  },
});
