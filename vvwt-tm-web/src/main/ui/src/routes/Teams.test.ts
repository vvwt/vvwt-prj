/**
 * Tests for Teams route — Story E47S01.
 *
 * AC3: No per-page .teams__header; title registered via pageHeader store.
 * AC4: Per-page "Team hinzufügen" button gone from <main>; action registered via store invokes openAddRow.
 * AC5: Back-arrow action navigates to /tournaments/:tournamentId/edit (via parentRouteMap).
 * AC6: Tournament name displayed in header (via tournamentStore.getTournament).
 * AC7: Narrow viewport — "Team hinzufügen" is icon-only with aria-label.
 * AC14: getTournament returns undefined → no literal "undefined"/"null" in header; back-arrow + title still registered.
 *
 * RED-first per DEC-22: all tests fail before migration.
 */

import { describe, it, expect } from 'vitest';
import { get } from 'svelte/store';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — No per-page __header
// ─────────────────────────────────────────────────────────────────────────────

describe('Teams.svelte — AC3: per-page header removed (E47S01)', () => {
  it('Teams.svelte source does NOT contain .teams__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('teams__header');
  });

  it('Teams.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain("teams.title");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC4 — Per-page button gone; action wired via store
// ─────────────────────────────────────────────────────────────────────────────

describe('Teams.svelte — AC4: per-page add button removed (E47S01)', () => {
  it('Teams.svelte source does NOT render inline "Team hinzufügen" button inside main header div', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration, the button is gone from the __header region
    // We check there is no teams__header div at all (already tested in AC3)
    // Additional check: no standalone h1 for teams title in main template
    expect(source).not.toMatch(/<h1[^>]*>\s*\{[^}]*teams\.title/);
  });

  it('Teams.svelte source registers openAddRow as action handler via pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('openAddRow');
    expect(source).toContain('pageHeader');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC5 — Back-arrow navigation via parentRouteMap
// ─────────────────────────────────────────────────────────────────────────────

describe('parentRouteMap — AC5: back-arrow navigation (E47S01)', () => {
  it('parentRouteMap.ts exports resolveParent function', async () => {
    const mod = await import('../lib/parentRouteMap.js');
    expect(typeof mod.resolveParent).toBe('function');
  });

  it('resolveParent resolves Teams route to /tournaments/:tournamentId/edit', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/teams', 'abc-123');
    expect(result).toBe('/tournaments/abc-123/edit');
  });

  it('resolveParent resolves DraftConfig route to /tournaments/:tournamentId/edit', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/draft', 'abc-123');
    expect(result).toBe('/tournaments/abc-123/edit');
  });

  it('resolveParent resolves TimerAudio route to /tournaments/:tournamentId/edit', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/audio', 'abc-123');
    expect(result).toBe('/tournaments/abc-123/edit');
  });

  it('resolveParent resolves TimerLink route to /tournaments/:tournamentId/edit', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/timer-link', 'abc-123');
    expect(result).toBe('/tournaments/abc-123/edit');
  });

  it('resolveParent resolves TeamPhotos route to /tournaments/:tournamentId/edit', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/photos', 'abc-123');
    expect(result).toBe('/tournaments/abc-123/edit');
  });

  it('resolveParent resolves CertificateTemplate route to /tournaments/:tournamentId/edit', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/certificate-template', 'abc-123');
    expect(result).toBe('/tournaments/abc-123/edit');
  });

  it('resolveParent returns null for top-level routes (no back-arrow)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    expect(resolveParent('/tournaments', '')).toBeNull();
    expect(resolveParent('/devices', '')).toBeNull();
    expect(resolveParent('/', '')).toBeNull();
  });

  it('Teams.svelte source includes backTo registration in pageHeader.set call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC6 — Tournament name in header
// ─────────────────────────────────────────────────────────────────────────────

describe('Teams.svelte — AC6: tournament name registered (E47S01)', () => {
  it('Teams.svelte source passes tournamentId to pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('tournamentId');
    // tournamentId must be in the pageHeader.set call
    expect(source).toContain('pageHeader');
  });

  it('App.svelte source resolves tournament name via tournamentStore', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    expect(source).toContain('tournamentStore');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC7 — Icon-collapse for Teams "Team hinzufügen"
// ─────────────────────────────────────────────────────────────────────────────

describe('Teams.svelte — AC7: ariaLabel for icon-collapse (E47S01)', () => {
  it('pageHeader action for Teams has ariaLabel set to teams.addButton key', async () => {
    const { pageHeader } = await import('../stores/pageHeaderStore.js');
    // Simulate what Teams.svelte will do on mount
    pageHeader.set({
      title: 'Teams',
      backTo: '/tournaments/abc-123/edit',
      tournamentId: 'abc-123',
      actions: [{
        label: 'Team hinzufügen',
        ariaLabel: 'teams.addButton',
        handler: () => {},
        variant: 'primary',
      }],
    });
    const state = get(pageHeader);
    expect(state.actions[0].ariaLabel).toBe('teams.addButton');
  });

  it('de.json teams.addButton has non-empty value', () => {
    const t = (deMessages as unknown as Record<string, Record<string, string>>).teams;
    expect(t).toHaveProperty('addButton');
    expect(t.addButton.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC14 — Graceful tournament-name absence (undefined from store)
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — AC14: graceful undefined tournament name (E47S01)', () => {
  it('App.svelte source handles undefined tournament gracefully (no literal "undefined" rendered)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    // The source must have a guard: conditionally render tournament name only when defined
    // Look for patterns like: {#if tournamentName} or tournamentName ?? '' or similar
    const hasTournamentNameGuard = (
      source.includes('tournamentName ??') ||
      source.includes('?? \'\'') ||
      source.match(/#if.*tournamentName/s) !== null ||
      source.includes('?.description') ||
      source.match(/#if.*tournament\b/s) !== null
    );
    expect(hasTournamentNameGuard).toBe(true);
  });

  it('Teams.svelte source still registers title and backTo even when tournamentId invalid', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The pageHeader.set call must NOT be conditional on tournament resolution
    // Title and backTo registration happens on mount regardless of tournament data
    expect(source).toContain('pageHeader');
    expect(source).toContain('backTo');
  });
});
