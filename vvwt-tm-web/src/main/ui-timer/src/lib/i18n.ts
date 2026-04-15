/**
 * i18n initialization for the Tournament Manager Timer SPA.
 *
 * Story E11S03 — DEC-2: Vite + Svelte + TypeScript, no SvelteKit.
 *
 * Mirrors the admin and display SPA patterns (E05S01, E07S05).
 * Locale detection order:
 *   1. browser navigator.language
 *   2. Falls back to 'de' if the browser locale has no translation file
 */

import { addMessages, getLocaleFromNavigator, init, locale } from 'svelte-i18n';
import de from '../locales/de.json';

/** Default locale when no translation exists for the detected browser locale. */
const FALLBACK_LOCALE = 'de';

/** Register all available translation files. Add new locales here only. */
function registerTranslations(): void {
  addMessages('de', de);
}

/**
 * Initialize the i18n system.
 * Must be called once before mounting the Svelte app.
 */
export function initI18n(): void {
  registerTranslations();

  const detectedLocale = getLocaleFromNavigator() ?? FALLBACK_LOCALE;

  init({
    fallbackLocale: FALLBACK_LOCALE,
    initialLocale: detectedLocale,
  });
}

/** Export the reactive locale store for use in locale-switcher components. */
export { locale };
