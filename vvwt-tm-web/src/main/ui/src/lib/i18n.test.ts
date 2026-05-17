// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
