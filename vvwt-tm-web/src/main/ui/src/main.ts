/**
 * Application bootstrap for Tournament Manager Admin SPA.
 *
 * Story E05S01 — AC1, AC5.
 *
 * Initializes i18n before mounting the Svelte app so that translation strings
 * are available immediately on first render (AC5, AC9).
 */

import { mount } from 'svelte';
import App from './App.svelte';
import { initI18n } from './lib/i18n.js';

// AC5, AC9: initialize i18n (locale detection + fallback) before mount
initI18n();

// Mount the root Svelte component
mount(App, { target: document.getElementById('app')! });
