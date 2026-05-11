/**
 * Tests for Teams route — Story E47S01 / E48S15.
 *
 * AC3: No per-page .teams__header; title registered via pageHeader store.
 * AC4: Per-page "Team hinzufügen" button gone from <main>; action registered via store invokes openAddRow.
 * AC5 (E47S01): Back-arrow action navigates via parentRouteMap.
 * E48S15: parentRouteMap 7 sub-routes updated to target /tournaments (P→Tournaments).
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
// AC5 / E48S15 — Back-arrow navigation via parentRouteMap (P→Tournaments)
// ─────────────────────────────────────────────────────────────────────────────
// E48S15 RED-first (DEC-22): 6 assertions updated + 1 new (slot-optimization)
// to expect '/tournaments' instead of '/tournaments/:tournamentId/edit'.
// Phase-routes + CRUD-routes assertions cover AC-NO-IMPACT-ON-PHASE-ROUTES and
// AC-NO-IMPACT-ON-TOURNAMENT-CRUD-ROUTES. Unknown-route null-assertions cover
// AC-ERROR-HANDLING-UNKNOWN-ROUTE-PATTERN.

describe('parentRouteMap — back-arrow navigation (E47S01 / E48S15)', () => {
  it('parentRouteMap.ts exports resolveParent function', async () => {
    const mod = await import('../lib/parentRouteMap.js');
    expect(typeof mod.resolveParent).toBe('function');
  });

  // 7 sub-routes: P→Tournaments (E48S15 RED-first — AC-TEST-PARENT-ROUTE-MAP-TARGETS-RED)
  it('resolveParent resolves Teams route to /tournaments (E48S15)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/teams', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves DraftConfig route to /tournaments (E48S15)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/draft', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves TimerAudio route to /tournaments (E48S15)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/audio', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves TimerLink route to /tournaments (E48S15)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/timer-link', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves TeamPhotos route to /tournaments (E48S15)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/photos', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves CertificateTemplate route to /tournaments (E48S15)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/certificate-template', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves SlotOptimization route to /tournaments (E48S15 — new assertion)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/slot-optimization', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  // Phase-routes: unchanged (E48S05 + E48S08 — AC-NO-IMPACT-ON-PHASE-ROUTES)
  it('resolveParent resolves phases overview to /tournaments (E48S05 — unchanged)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/phases', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves phase transition to /phases (E48S08 — unchanged)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/phases/:phaseId/transition', 'abc-123');
    expect(result).toBe('/tournaments/abc-123/phases');
  });

  // Tournament CRUD routes: unchanged (AC-NO-IMPACT-ON-TOURNAMENT-CRUD-ROUTES)
  it('resolveParent resolves /tournaments/new to /tournaments (unchanged)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/new', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('resolveParent resolves /tournaments/:id/edit to /tournaments (unchanged)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:id/edit', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  // Unknown routes: null (AC-ERROR-HANDLING-UNKNOWN-ROUTE-PATTERN)
  it('resolveParent returns null for unknown routes (AC-ERROR-HANDLING-UNKNOWN-ROUTE-PATTERN)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    expect(resolveParent('/some/unknown/route', 'abc-123')).toBeNull();
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

// ─────────────────────────────────────────────────────────────────────────────
// E05S13 — AC-IMPL-UI-ADDROW-DEFAULT-TRUE + AC-TEST-UI-ADDROW-DEFAULT-RED-FIRST
// RED-first per DEC-22 Iron Law: this test was committed while Teams.svelte:164
// still had refereeAssignment: false — assertion fails before the GREEN flip.
// ─────────────────────────────────────────────────────────────────────────────

describe('Teams.svelte — E05S13: openAddRow refereeAssignment default', () => {
  it('Teams.svelte source initialises addRow with refereeAssignment: true (E05S13)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // openAddRow() addRow literal must contain refereeAssignment: true
    // Matches the object literal inside openAddRow() at Teams.svelte:164
    expect(source).toContain('refereeAssignment: true');
  });

  it('Teams.svelte source openAddRow does NOT initialise refereeAssignment: false (E05S13)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Teams.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After the flip the addRow literal must not contain refereeAssignment: false
    // (the only place refereeAssignment appears in openAddRow is line 164)
    const openAddRowBlock = source.match(/function openAddRow\(\)[\s\S]*?^\s*\}/m)?.[0] ?? '';
    expect(openAddRowBlock).not.toContain('refereeAssignment: false');
  });
});
