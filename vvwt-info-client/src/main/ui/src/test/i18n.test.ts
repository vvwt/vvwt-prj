// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import de from '../lib/i18n/de.js';

describe('de.ts string table', () => {
  it('exports a non-empty Record<string, string>', () => {
    expect(typeof de).toBe('object');
    expect(de).not.toBeNull();
    expect(Object.keys(de).length).toBeGreaterThan(0);
  });

  it('contains required supersede UX key (AC5)', () => {
    expect(de['tournament.ended']).toBeDefined();
    expect(de['tournament.ended']).toContain('beendet');
  });

  it('contains required 410 Gone UX key (AC6)', () => {
    expect(de['link.expired']).toBeDefined();
    expect(de['link.expired']).toContain('Veranstalter');
  });

  it('contains network-lost key (AC14)', () => {
    expect(de['connection.lost']).toBeDefined();
    expect(de['connection.lost']).toContain('Verbindung');
  });

  it('contains stale-data key (AC15)', () => {
    expect(de['data.stale']).toBeDefined();
    expect(de['data.stale']).toContain('veraltet');
  });

  it('contains reconnect indicator key (AC14)', () => {
    expect(de['connection.reconnecting']).toBeDefined();
  });

  it('all values are non-empty strings', () => {
    for (const [key, value] of Object.entries(de)) {
      expect(typeof value, `key '${key}' must be string`).toBe('string');
      expect(value.length, `key '${key}' must be non-empty`).toBeGreaterThan(0);
    }
  });
});
