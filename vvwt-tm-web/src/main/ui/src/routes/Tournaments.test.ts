/**
 * Tests for Tournaments route — Story E47S01.
 *
 * AC3: Route registers title via pageHeader store; per-page .tournaments__header is removed.
 * AC4: Per-page "Neues Turnier" button gone from route body; header action handler invokes push('/tournaments/new').
 * AC7: Narrow viewport → "Neues Turnier" button is icon-only (no visible text, has aria-label).
 * AC10 (i18n): tournaments.title key present in de.json (already tested in TimerLink.test.ts — not re-tested here).
 *
 * RED-first per DEC-22: all these tests fail against the current implementation because
 *   - .tournaments__header still exists in the template
 *   - The "Neues Turnier" button is in <main>, not registered via pageHeader
 *   - No pageHeader store exists yet
 */

import { describe, it, expect } from 'vitest';
import { get } from 'svelte/store';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — No per-page __header; page title registered via store
// ─────────────────────────────────────────────────────────────────────────────

describe('Tournaments.svelte — AC3: per-page header removed (E47S01)', () => {
  it('Tournaments.svelte source does NOT contain .tournaments__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration, the per-page header div is gone
    expect(source).not.toContain('tournaments__header');
  });

  it('Tournaments.svelte source does NOT contain per-page h1 for title', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // h1 with title inside main is gone; title is registered via store
    // The source must register the title string via pageHeader
    expect(source).toContain("tournaments.title");
    // But it should NOT render an h1 inside main containing the title
    expect(source).not.toMatch(/<h1[^>]*>\s*\{[^}]*tournaments\.title/);
  });

  it('Tournaments.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC4 — Per-page action button gone from route body; action registered via store
// ─────────────────────────────────────────────────────────────────────────────

describe('Tournaments.svelte — AC4: per-page create button removed (E47S01)', () => {
  it('Tournaments.svelte source does NOT render inline "Neues Turnier" button inside main', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The main template must not contain a button with the createButton key
    // inside a direct __header div (the old pattern)
    expect(source).not.toMatch(/class="btn btn--primary"[^>]*>\s*\{[^}]*tournaments\.createButton/);
  });

  it('Tournaments.svelte source registers createButton action via pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The handler push('/tournaments/new') must be present and wired via pageHeader actions
    expect(source).toContain("push('/tournaments/new')");
    expect(source).toContain('pageHeader');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC7 — Responsive collapse: icon-only at narrow viewport via App.svelte
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte + pageHeaderStore — AC7: icon-collapse rule (E47S01)', () => {
  it('pageHeader actions include ariaLabel field for icon-collapse accessibility', async () => {
    const { pageHeader } = await import('../stores/pageHeaderStore.js');
    // Set a non-destructive action and verify ariaLabel is part of the interface
    pageHeader.set({
      title: 'Turniere',
      backTo: null,
      tournamentId: null,
      actions: [{
        label: 'Neues Turnier',
        ariaLabel: 'tournaments.createButton',
        handler: () => {},
        variant: 'primary',
      }],
    });
    const state = get(pageHeader);
    expect(state.actions[0]).toHaveProperty('ariaLabel');
    expect(state.actions[0].ariaLabel).toBe('tournaments.createButton');
  });

  it('App.svelte source contains responsive collapse media query (≤768px)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    // Must have responsive collapse for narrow viewport
    expect(source).toMatch(/768px/);
  });

  it('de.json tournaments.createButton key has non-empty value (re-used as aria-label)', () => {
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).toHaveProperty('createButton');
    expect(typeof t.createButton).toBe('string');
    expect(t.createButton.length).toBeGreaterThan(0);
  });
});
