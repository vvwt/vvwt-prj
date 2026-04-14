/**
 * i18n initialization for the Tournament Manager Display SPA.
 *
 * Story E07S05 — AC10: All visible strings are sourced from the translation layer.
 *
 * Mirrors the admin SPA pattern (E05S01) but uses the display-specific locales.
 * Locale detection order:
 *   1. browser navigator.language
 *   2. Falls back to 'de' if the browser locale has no translation file
 *
 * Adding a new language: add a JSON file in src/locales/{locale}.json and register
 * it in addMessages() below. No component changes required.
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
