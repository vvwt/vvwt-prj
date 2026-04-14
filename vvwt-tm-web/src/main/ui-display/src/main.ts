/**
 * Entry point for the Tournament Manager Display SPA (Gesamtübersicht).
 *
 * Story E07S05 — AC10 (i18n), DEC-2 (Svelte + Vite + TypeScript, no SvelteKit).
 *
 * Initializes i18n before mounting the Svelte app so that translation strings
 * are available immediately on first render (AC10).
 */

import { mount } from 'svelte';
import App from './App.svelte';
import { initI18n } from './lib/i18n.js';

// AC10: initialize i18n (locale detection + German fallback) before mount
initI18n();

// Mount the display SPA root component
mount(App, { target: document.getElementById('app')! });
