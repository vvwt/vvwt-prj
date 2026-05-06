/**
 * i18n initialization for Tournament Manager Admin SPA.
 *
 * Story E05S01 — AC5, AC9.
 *
 * Uses svelte-i18n for translation. Locale detection order:
 *   1. browser navigator.language (AC9: browser Accept-Language detection)
 *   2. Falls back to 'de' if the browser locale has no translation file (AC9: German fallback)
 *
 * Adding a new language requires only:
 *   1. Add a new JSON file in src/locales/{locale}.json
 *   2. Register it in addMessages() below
 *   No Svelte component changes required (AC5).
 */

import { addMessages, getLocaleFromNavigator, init, locale } from 'svelte-i18n';
import de from '../locales/de.json';
import en from '../locales/en.json';

/** Default locale when no translation exists for the detected browser locale. */
const FALLBACK_LOCALE = 'de';

/** Register all available translation files. Add new locales here only. */
function registerTranslations(): void {
  addMessages('de', de);
  addMessages('en', en);
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
