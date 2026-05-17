// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
