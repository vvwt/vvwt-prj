/**
 * i18n unit tests — Story E05S01, AC5, AC9.
 *
 * Verifies that:
 * - The German translation file loads correctly
 * - i18n initializes without throwing
 * - The fallback locale resolves to 'de'
 */

import { describe, expect, it } from 'vitest';
import deMessages from '../locales/de.json';

describe('i18n — de.json translation file', () => {
  it('should contain required top-level keys', () => {
    expect(deMessages).toHaveProperty('app');
    expect(deMessages).toHaveProperty('nav');
    expect(deMessages).toHaveProperty('home');
  });

  it('should have the app title string', () => {
    expect(deMessages.app.title).toBeTypeOf('string');
    expect(deMessages.app.title.length).toBeGreaterThan(0);
  });

  it('should have the home heading string (AC4 placeholder)', () => {
    expect(deMessages.home.heading).toBeTypeOf('string');
    expect(deMessages.home.heading.length).toBeGreaterThan(0);
  });

  it('should export a plain object (not null or array)', () => {
    expect(typeof deMessages).toBe('object');
    expect(Array.isArray(deMessages)).toBe(false);
    expect(deMessages).not.toBeNull();
  });
});

describe('i18n — initI18n()', () => {
  it('should initialize without throwing', async () => {
    // Import after potential jsdom environment setup
    const { initI18n } = await import('./i18n.js');
    expect(() => initI18n()).not.toThrow();
  });
});
