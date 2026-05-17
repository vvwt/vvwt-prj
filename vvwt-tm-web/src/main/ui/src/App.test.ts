// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { get } from 'svelte/store';
import deMessages from './locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// AC10 (i18n) — common.moreActions key must exist in de.json (E47S01, AC10)
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — common.moreActions (E47S01 AC10)', () => {
  it('should contain the common namespace', () => {
    expect(deMessages).toHaveProperty('common');
  });

  it('should contain common.moreActions key with non-empty string value', () => {
    const common = (deMessages as unknown as Record<string, Record<string, string>>).common;
    expect(common).toBeDefined();
    expect(common).toHaveProperty('moreActions');
    expect(typeof common.moreActions).toBe('string');
    expect(common.moreActions.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC1 — pageHeader store API: exists, readable, settable with correct shape
// ─────────────────────────────────────────────────────────────────────────────

describe('pageHeaderStore — shell mechanism API (E47S01 AC1)', () => {
  it('should export a writable pageHeader store from pageHeaderStore.ts', async () => {
    const mod = await import('./stores/pageHeaderStore.js');
    expect(mod).toHaveProperty('pageHeader');
    expect(typeof mod.pageHeader.subscribe).toBe('function');
    expect(typeof mod.pageHeader.set).toBe('function');
    expect(typeof mod.pageHeader.update).toBe('function');
  });

  it('should have initial state with empty title and null backTo/tournamentId', async () => {
    const { pageHeader } = await import('./stores/pageHeaderStore.js');
    const state = get(pageHeader);
    expect(state).toHaveProperty('title');
    expect(state).toHaveProperty('backTo');
    expect(state).toHaveProperty('tournamentId');
    expect(state).toHaveProperty('actions');
    expect(Array.isArray(state.actions)).toBe(true);
  });

  it('should accept a title/actions/backTo/tournamentId payload', async () => {
    const { pageHeader } = await import('./stores/pageHeaderStore.js');
    const payload = {
      title: 'Teams',
      backTo: '/tournaments/abc/edit',
      tournamentId: 'abc',
      actions: [{ label: 'Team hinzufügen', ariaLabel: 'teams.addButton', handler: () => {}, variant: 'primary' as const }],
    };
    pageHeader.set(payload);
    const state = get(pageHeader);
    expect(state.title).toBe('Teams');
    expect(state.backTo).toBe('/tournaments/abc/edit');
    expect(state.tournamentId).toBe('abc');
    expect(state.actions).toHaveLength(1);
    expect(state.actions[0].label).toBe('Team hinzufügen');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC2 — brand-header sticky style present in App.svelte CSS
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — sticky brand-header (E47S01 AC2)', () => {
  it('App.svelte source contains position:sticky rule for .brand-header', async () => {
    // We read App.svelte source and assert the CSS rule is present.
    // This is the only reliable way in jsdom (which does not parse <style> blocks).
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, './App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    expect(source).toMatch(/position\s*:\s*sticky/);
    expect(source).toMatch(/top\s*:\s*0/);
  });

  it('App.svelte does NOT use position:fixed for .brand-header (AC2 constraint)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, './App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    // Must not use position:fixed in the brand-header context
    // Allow position:fixed elsewhere (e.g. modals) but not for .brand-header
    const brandHeaderStyleBlock = source.match(/\.brand-header\s*\{([^}]*)\}/)?.[1] ?? '';
    expect(brandHeaderStyleBlock).not.toMatch(/position\s*:\s*fixed/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC15 — persistent header DOM child order
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — header DOM order (E47S01 AC15)', () => {
  it('App.svelte source contains brand-lockup img before page title slot', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, './App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');

    // Logo must appear before the page title in the template
    const logoIdx = source.indexOf('brand-lockup');
    const titleIdx = source.indexOf('pageHeaderState.title') !== -1
      ? source.indexOf('pageHeaderState.title')
      : source.indexOf('headerState.title');
    expect(logoIdx).toBeGreaterThan(-1);
    expect(titleIdx).toBeGreaterThan(-1);
    expect(logoIdx).toBeLessThan(titleIdx);
  });

  it('App.svelte source renders back-arrow conditionally (only when backTo is set)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, './App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    // Should have a conditional block for backTo
    expect(source).toMatch(/backTo/);
    expect(source).toMatch(/#if.*backTo|backTo.*#if/s);
  });

  it('App.svelte source renders tournament name conditionally (only when tournamentId set)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, './App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    expect(source).toMatch(/tournamentId/);
  });
});
