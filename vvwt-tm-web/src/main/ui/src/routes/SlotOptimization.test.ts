// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// AC1 — de.json has slotopt.title = "Slot-Optimierung"
// ─────────────────────────────────────────────────────────────────────────────

describe('i18n — AC1: slotopt.title key added to de.json (E47S02)', () => {
  it('de.json has a "slotopt" top-level namespace', () => {
    const d = deMessages as unknown as Record<string, unknown>;
    expect(d).toHaveProperty('slotopt');
  });

  it('de.json slotopt.title = "Slot-Optimierung"', () => {
    const d = deMessages as unknown as Record<string, Record<string, string>>;
    expect(d.slotopt).toHaveProperty('title');
    expect(d.slotopt.title).toBe('Slot-Optimierung');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC2 — Header shows "Slot-Optimierung"; legacy <h2>Slot Optimization</h2> gone
// ─────────────────────────────────────────────────────────────────────────────

describe('SlotOptimization.svelte — AC2: header title and legacy h2 removal (E47S02)', () => {
  it('SlotOptimization.svelte source registers slotopt.title key via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('slotopt.title');
  });

  it('SlotOptimization.svelte source does NOT contain hardcoded "Slot Optimization" in an h2 element', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The legacy <h2>Slot Optimization</h2> must be removed
    expect(source).not.toContain('<h2>Slot Optimization</h2>');
  });

  it('SlotOptimization.svelte source does NOT contain any <h2> for the page title', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration: no in-page <h2> for the page title (title in persistent header)
    // The template section should not contain a standalone page-title h2
    const templatePart = source.split('</script>').slice(1).join('</script>');
    expect(templatePart).not.toMatch(/<h2[^>]*>\s*Slot Optimization/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — In-page h2 gone (scoped to main/section container); pageHeader registered
// ─────────────────────────────────────────────────────────────────────────────

describe('SlotOptimization.svelte — AC3: in-page h2 removed (E47S02)', () => {
  it('SlotOptimization.svelte template does NOT contain "Slot Optimization" as visible text (i.e., in h2 element or unquoted)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The English literal is gone from the template — replaced by slotopt.title i18n key.
    // Only check the template section (after </script>); comments in <script> are ignored.
    const templatePart = source.split('</script>').slice(1).join('</script>');
    expect(templatePart).not.toContain('Slot Optimization');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC5 — Back-arrow on SlotOptimization: backTo = '/tournaments' (updated E48S15)
// ─────────────────────────────────────────────────────────────────────────────
// E48S15 P→Tournaments: slot-optimization now targets /tournaments (not /edit).
// Assertion updated to reflect the new convention (corrected-spec-test value per
// E48S15 story notes + DEC-22 §refactor-clause clarification).

describe('parentRouteMap — AC5: SlotOptimization back-arrow (E47S02 / E48S15)', () => {
  it('parentRouteMap resolves SlotOptimization to /tournaments (E48S15 P→Tournaments)', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:tournamentId/slot-optimization', 'abc-123');
    expect(result).toBe('/tournaments');
  });

  it('SlotOptimization.svelte source registers backTo via resolveParent', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
    expect(source).toContain('resolveParent');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC6 — SlotOptimization registers tournamentId for tournament-name display
// ─────────────────────────────────────────────────────────────────────────────

describe('SlotOptimization.svelte — AC6: tournamentId registered for tournament-name (E47S02)', () => {
  it('SlotOptimization.svelte source registers tournamentId in pageHeader.set call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // tournamentId from params passed into pageHeader so App.svelte can resolve tournament name
    expect(source).toContain('tournamentId');
    expect(source).toContain('pageHeader');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC7 — Action-area empty (cancel button stays in-page per story notes)
// ─────────────────────────────────────────────────────────────────────────────

describe('SlotOptimization.svelte — AC7: empty header action area (E47S02)', () => {
  it('SlotOptimization.svelte source registers actions: [] in pageHeader.set (cancel button stays in-page)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Cancel button is job-state-bound, stays in-page — no header actions
    expect(source).toContain('actions: []');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC10 — Inline cancel button NOT relocated; still present in route body
// ─────────────────────────────────────────────────────────────────────────────

describe('SlotOptimization.svelte — AC10: cancel button stays in-page (regression guard, E47S02)', () => {
  it('SlotOptimization.svelte source still contains btn--cancel button in the route body', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The inline cancel button is a job-state-bound control — must NOT be removed or relocated
    expect(source).toContain('btn--cancel');
  });

  it('SlotOptimization.svelte cancel button invokes cancelOptimization handler', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Verify the cancel function still exists — regression guard per AC10
    expect(source).toContain('cancelOptimization');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC14 — Graceful tournament-name absence (undefined store)
// ─────────────────────────────────────────────────────────────────────────────

describe('SlotOptimization — AC14: graceful undefined tournamentId handling (E47S02)', () => {
  it('App.svelte resolves undefined getTournament to empty string (no "undefined"/"null" in header)', async () => {
    // This is tested at the App.svelte level — the resolveTournamentName function
    // guards against undefined return by using t?.description ?? ''
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // App.svelte must use optional chaining or nullish coalescing to avoid 'undefined'/'null' literals
    expect(source).toContain("?? ''");
    expect(source).not.toContain('tournamentName = undefined');
  });

  it('de.json slotopt.title is defined so page title always renders even without tournament name', () => {
    const d = deMessages as unknown as Record<string, Record<string, string>>;
    // The page title is independent of the tournament name — it always renders from i18n
    expect(d.slotopt.title).toBe('Slot-Optimierung');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E51S07 — lastJobState display from phase.last_job_state (DEC-55 D-8)
// SlotOptimization.svelte must display the lastJobState value returned by the
// status API in a "Phase Job-Status" row.
// ─────────────────────────────────────────────────────────────────────────────

describe('SlotOptimization.svelte — E51S07: lastJobState display (DEC-55 D-8)', () => {
  it('SlotOptimization.svelte source declares a lastJobState state variable', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('lastJobState');
  });

  it('SlotOptimization.svelte StatusResponse interface includes lastJobState field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // StatusResponse must declare lastJobState
    const ifaceMatch = source.match(/interface StatusResponse \{([^}]+)\}/s);
    expect(ifaceMatch, 'StatusResponse interface not found').not.toBeNull();
    const ifaceBlock = ifaceMatch![1];
    expect(ifaceBlock).toContain('lastJobState');
  });

  it('SlotOptimization.svelte renders a job-state-row section when lastJobState is not null', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The template must have a conditional block for lastJobState !== null
    const templatePart = source.split('</script>').slice(1).join('</script>');
    expect(templatePart).toContain('lastJobState');
    // The row must only render when lastJobState is not null
    expect(templatePart).toMatch(/\{#if lastJobState !== null\}/);
  });

  it('SlotOptimization.svelte assigns body.lastJobState from the status API response', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './SlotOptimization.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // fetchStatus() must assign lastJobState from body.lastJobState
    expect(source).toContain('body.lastJobState');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC13 — No __header BEM blocks in any of the 4 S02 route files
// ─────────────────────────────────────────────────────────────────────────────

describe('E47S02 routes — AC13: no per-page __header BEM classes (E47S02)', () => {
  const routeFiles = [
    'Home.svelte',
    'TournamentForm.svelte',
    'SlotOptimization.svelte',
  ];

  for (const file of routeFiles) {
    it(`${file} does NOT contain any __header BEM block class`, async () => {
      const fs = await import('fs');
      const path = await import('path');
      const src = path.resolve(__dirname, `./${file}`);
      const source = fs.readFileSync(src, 'utf8');
      expect(source).not.toMatch(/\w+__header/);
    });
  }
});
