/**
 * Entry point for the Tournament Manager Timer SPA.
 *
 * Story E11S03 — AC1, DEC-2: Vite + Svelte + TypeScript, no SvelteKit.
 *
 * Initializes i18n before mounting the Svelte app so that translation strings
 * are available immediately on first render.
 */

import { mount } from 'svelte';
import App from './App.svelte';
import { initI18n } from './lib/i18n.js';

// Initialize i18n (locale detection + German fallback) before mount
initI18n();

// Mount the timer SPA root component
mount(App, { target: document.getElementById('app')! });
