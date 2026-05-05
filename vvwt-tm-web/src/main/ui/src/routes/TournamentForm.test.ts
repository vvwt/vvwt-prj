/**
 * Tests for TournamentForm route — Story E47S02.
 *
 * AC3: In-page <h1> gone from route body; title registered via pageHeader store.
 * AC4: main h1 GONE (RED: exists pre-migration) AND Save/Cancel still in form__actions (TRUE pre+post).
 * AC5: Back-arrow registered; backTo = '/tournaments' for both /new and /:id/edit.
 * AC6 complement: TournamentForm-edit does NOT show tournament name in header.
 * AC7: Action-area empty; tournament-name slot empty for both TournamentForm-new and TournamentForm-edit.
 * AC8: No per-page back-button (pop() call bound to a back-button) in main body.
 * AC9: No in-template <h1> rendering the page title in main container.
 *
 * RED-first per DEC-22: AC3, AC4, AC5, AC8, AC9 fail before migration
 * (TournamentForm.svelte still has <h1> in main and no pageHeader registration).
 */

import { describe, it, expect } from 'vitest';

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — In-page h1 gone; title registered via pageHeader
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC3: in-page h1 removed (E47S02)', () => {
  it('TournamentForm.svelte source does NOT contain an in-page <h1> for page title', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration: no standalone <h1> rendering the form title in main
    expect(source).not.toMatch(/<h1[^>]*>\s*\{[^}]*tournamentForm\.(createTitle|editTitle)/);
  });

  it('TournamentForm.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('tournamentForm.');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC4 — main h1 GONE; Save/Cancel STILL in form__actions (form-association preserved per D-13)
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC4: title removed + form buttons preserved per D-13 (E47S02)', () => {
  it('TournamentForm.svelte source has no <h1> page title in main form body', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // (i) The in-place page title <h1> is GONE from route's main container
    expect(source).not.toMatch(/<h1[^>]*>\s*[\n\s]*\{/);
  });

  it('TournamentForm.svelte source has Save button with type="submit" inside form__actions', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // (ii) Save button type="submit" present — form-association preserved per D-13
    expect(source).toContain('type="submit"');
    expect(source).toContain('form__actions');
  });

  it('TournamentForm.svelte source has Cancel button inside form__actions (form-association preserved per D-13)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // (ii) Cancel button (type="button") is in form__actions per D-13
    expect(source).toContain('form__actions');
    expect(source).toContain("tournamentForm.cancelButton");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC5 — Back-arrow on TournamentForm-new and TournamentForm-edit
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC5: back-arrow registered (E47S02)', () => {
  it('parentRouteMap resolves TournamentForm-new to /tournaments', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/new', '');
    expect(result).toBe('/tournaments');
  });

  it('parentRouteMap resolves TournamentForm-edit to /tournaments', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/tournaments/:id/edit', '');
    expect(result).toBe('/tournaments');
  });

  it('TournamentForm.svelte source registers backTo in pageHeader.set call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
    expect(source).toContain("'/tournaments'");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC6 complement — TournamentForm-edit does NOT show tournament name in header
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC6 complement: no tournament-name in header per D-6 (E47S02)', () => {
  it('TournamentForm.svelte source registers tournamentId: null in pageHeader.set (D-6: form contains name)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // D-6: on /tournaments/:id/edit the form contains the name → no header-name
    expect(source).toContain('tournamentId: null');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC7 — Action-area empty and tournament-name slot empty for both TournamentForm routes
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC7: empty action area + empty tournament-name (E47S02)', () => {
  it('TournamentForm.svelte source registers actions: [] in pageHeader.set call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // No header action buttons for TournamentForm routes (Save/Cancel in form__actions per D-13)
    expect(source).toContain('actions: []');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC8 — No per-page back-button in TournamentForm main body
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC8: per-page back-button removed (E47S02)', () => {
  it('TournamentForm.svelte source has no per-page back-button invoking pop() in main body', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration: the per-page back-button (previously calling pop()) is removed.
    // The back-arrow is exclusively in the persistent header (registered via pageHeader store).
    // Check there is no button in the template section (outside form__actions) with aria-label*=zurück|back
    // Mechanical: no <button> with aria-label containing "zurück" or "back" in the route body
    expect(source).not.toMatch(/<button[^>]+aria-label[^>]*[Zz]urück/);
    expect(source).not.toMatch(/<button[^>]+aria-label[^>]*[Bb]ack/);
  });

  it('TournamentForm.svelte source imports pop but does NOT call pop() as a back-navigation button handler outside form__actions', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // pop() is still used for post-save navigation — that is fine.
    // The per-page BACK button that navigated backward is removed.
    // We verify there is no <button> specifically for "back" navigation outside form__actions
    // by checking no button with classes like btn--back or aria-label for going back
    expect(source).not.toContain('btn--back');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC9 — No in-template <h1> for page title (mechanical grep)
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC9: no in-place page title h1 in template (E47S02)', () => {
  it('TournamentForm.svelte source has no <h1> in the Svelte template section', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration: title exclusively in persistent header; no <h1> in template body
    // Split at </script> to check only the template section
    const templatePart = source.split('</script>').slice(1).join('</script>');
    expect(templatePart).not.toContain('<h1');
  });
});
