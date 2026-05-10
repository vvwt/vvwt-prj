/**
 * Tests for TournamentForm route — Story E47S02 and E48S14.
 *
 * E47S02 tests:
 * AC3: In-page <h1> gone from route body; title registered via pageHeader store.
 * AC4: main h1 GONE (RED: exists pre-migration) AND Save/Cancel still in form__actions (TRUE pre+post).
 * AC5: Back-arrow registered; backTo = '/tournaments' for both /new and /:id/edit.
 * AC6 complement: TournamentForm-edit does NOT show tournament name in header.
 * AC7: Action-area empty; tournament-name slot empty for both TournamentForm-new and TournamentForm-edit.
 * AC8: No per-page back-button (pop() call bound to a back-button) in main body.
 * AC9: No in-template <h1> rendering the page title in main container.
 *
 * E48S14 tests:
 * AC-TEST-FRONTEND-CREATE-PAYLOAD-RED: TournamentForm.svelte CREATE payload includes plannedStartTime.
 * RED-first: before fix, the CREATE object literal at lines 126-135 omits plannedStartTime.
 * After fix: plannedStartTime line mirrors the UPDATE payload at line 123.
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

// ─────────────────────────────────────────────────────────────────────────────
// E51S07: optimize checkbox presence and payload inclusion
// DEC-55 D-5: TournamentForm must render a slot-optimization checkbox (default checked)
// and include the `optimize` field in both CREATE and UPDATE payloads.
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — E51S07: optimize checkbox present with default checked (DEC-55 D-5)', () => {
  it('TournamentForm.svelte source contains an input[type=checkbox] with id="optimize"', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('id="optimize"');
    expect(source).toContain('type="checkbox"');
  });

  it('TournamentForm.svelte source binds checkbox to an optimize state variable defaulting to true', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // $state(true) default for the checkbox (DEC-55 D-5)
    expect(source).toMatch(/let optimize.*=.*\$state\s*\(\s*true\s*\)/);
    // bind:checked wires the checkbox to the state variable
    expect(source).toContain('bind:checked={optimize}');
  });

  it('TournamentForm.svelte source uses slotopt.checkbox.label i18n key as checkbox label', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('slotopt.checkbox.label');
  });

  it('de.json slotopt.checkbox.label is a non-empty string', async () => {
    const d = (await import('../locales/de.json')).default as unknown as Record<string, Record<string, Record<string, string>>>;
    expect(d.slotopt.checkbox).toHaveProperty('label');
    expect(d.slotopt.checkbox.label.length).toBeGreaterThan(0);
  });
});

describe('TournamentForm.svelte — E51S07: optimize field in CREATE payload (DEC-55 D-5)', () => {
  it('TournamentForm.svelte CREATE payload object literal includes optimize field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    const createBranchMatch = source.match(/const req: TournamentCreateRequest = \{([^}]+)\}/s);
    expect(createBranchMatch).not.toBeNull();
    const createBlock = createBranchMatch![1];
    expect(createBlock).toContain('optimize');
  });

  it('TournamentCreateRequest TS interface in tournamentStore.ts includes optional optimize field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const storeSrc = path.resolve(__dirname, '../stores/tournamentStore.ts');
    const source = fs.readFileSync(storeSrc, 'utf8');
    const ifaceMatch = source.match(/export interface TournamentCreateRequest \{([^}]+)\}/s);
    expect(ifaceMatch).not.toBeNull();
    const ifaceBlock = ifaceMatch![1];
    expect(ifaceBlock).toContain('optimize');
  });
});

describe('TournamentForm.svelte — E51S07: optimize field in UPDATE payload (DEC-55 D-5)', () => {
  it('TournamentForm.svelte UPDATE (editId branch) call includes optimize field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The edit branch calls updateTournament(editId, { ... optimize, ... })
    // We extract the updateTournament call block
    const updateMatch = source.match(/await updateTournament\(editId,\s*\{([^}]+)\}/s);
    expect(updateMatch, 'updateTournament call not found in source').not.toBeNull();
    const updateBlock = updateMatch![1];
    expect(updateBlock).toContain('optimize');
  });

  it('TournamentUpdateRequest TS interface in tournamentStore.ts includes optional optimize field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const storeSrc = path.resolve(__dirname, '../stores/tournamentStore.ts');
    const source = fs.readFileSync(storeSrc, 'utf8');
    const ifaceMatch = source.match(/export interface TournamentUpdateRequest \{([^}]+)\}/s);
    expect(ifaceMatch).not.toBeNull();
    const ifaceBlock = ifaceMatch![1];
    expect(ifaceBlock).toContain('optimize');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-TEST-FRONTEND-CREATE-PAYLOAD-RED (E48S14)
// RED-first: TournamentForm.svelte CREATE payload (lines 126-135) omits plannedStartTime
// before the fix. After fix: mirrors the UPDATE payload which already includes it at line 123.
// DEC-22 Iron Law: this test is RED before the payload-line addition.
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — AC-TEST-FRONTEND-CREATE-PAYLOAD-RED: CREATE payload includes plannedStartTime (E48S14)', () => {
  it('TournamentForm.svelte CREATE payload object literal includes plannedStartTime field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The CREATE payload is the const req: TournamentCreateRequest = { ... } block.
    // Before fix: does NOT contain plannedStartTime in the CREATE branch.
    // After fix: mirrors line 123 (UPDATE branch) with plannedStartTime conditional-trim.
    // Mechanical check: the string 'plannedStartTime' must appear AFTER the 'else {' that starts
    // the CREATE branch — specifically in the req object literal at 'const req: TournamentCreateRequest'.
    const createBranchMatch = source.match(/const req: TournamentCreateRequest = \{([^}]+)\}/s);
    expect(createBranchMatch).not.toBeNull();
    const createBlock = createBranchMatch![1];
    expect(createBlock).toContain('plannedStartTime');
  });

  it('TournamentCreateRequest TS interface in tournamentStore.ts includes optional plannedStartTime field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const storeSrc = path.resolve(__dirname, '../stores/tournamentStore.ts');
    const source = fs.readFileSync(storeSrc, 'utf8');
    // Extract TournamentCreateRequest interface block
    const ifaceMatch = source.match(/export interface TournamentCreateRequest \{([^}]+)\}/s);
    expect(ifaceMatch).not.toBeNull();
    const ifaceBlock = ifaceMatch![1];
    // Before fix: interface does NOT declare plannedStartTime.
    // After fix: declares 'plannedStartTime?: string | null'.
    expect(ifaceBlock).toContain('plannedStartTime');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E53S05: seedMannschaftsfoto Vorbelegung checkbox
// AC3: admin-UI form has seedMannschaftsfoto checkbox pre-selected (default true)
// AC4: opt-out path — form transmits seedMannschaftsfoto in CREATE payload
// AC12: i18n-resolved label key (mannschaftsfoto.checkbox.label)
// DEC-22 Iron Law: these tests are RED before the production changes are applied.
// ─────────────────────────────────────────────────────────────────────────────

describe('TournamentForm.svelte — E53S05 AC3: seedMannschaftsfoto checkbox present with default checked', () => {
  it('TournamentForm.svelte source contains id="seedMannschaftsfoto" checkbox input', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED before E53S05: checkbox not present → fails
    // GREEN after E53S05: checkbox added with id="seedMannschaftsfoto"
    expect(source).toContain('id="seedMannschaftsfoto"');
    expect(source).toContain('type="checkbox"');
  });

  it('TournamentForm.svelte source binds checkbox to seedMannschaftsfoto state variable defaulting to true', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // $state(true) default for the checkbox (analogous to optimize checkbox, DEC-55 D-5 pattern)
    expect(source).toMatch(/let seedMannschaftsfoto.*=.*\$state\s*\(\s*true\s*\)/);
    // bind:checked wires the checkbox to the state variable
    expect(source).toContain('bind:checked={seedMannschaftsfoto}');
  });

  it('TournamentForm.svelte source uses mannschaftsfoto.checkbox.label i18n key as checkbox label', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC12: i18n-resolved label, not hard-coded German string
    expect(source).toContain('mannschaftsfoto.checkbox.label');
  });

  it('de.json mannschaftsfoto.checkbox.label is a non-empty string (AC12)', async () => {
    const d = (await import('../locales/de.json')).default as unknown as Record<string, Record<string, Record<string, string>>>;
    expect(d.mannschaftsfoto).toBeDefined();
    expect(d.mannschaftsfoto.checkbox).toHaveProperty('label');
    expect(d.mannschaftsfoto.checkbox.label.length).toBeGreaterThan(0);
  });
});

describe('TournamentForm.svelte — E53S05 AC4: seedMannschaftsfoto field in CREATE payload', () => {
  it('TournamentForm.svelte CREATE payload object literal includes seedMannschaftsfoto field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TournamentForm.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The CREATE payload is the const req: TournamentCreateRequest = { ... } block.
    // RED before E53S05: payload does not include seedMannschaftsfoto.
    // GREEN after E53S05: payload includes seedMannschaftsfoto.
    const createBranchMatch = source.match(/const req: TournamentCreateRequest = \{([^}]+)\}/s);
    expect(createBranchMatch).not.toBeNull();
    const createBlock = createBranchMatch![1];
    expect(createBlock).toContain('seedMannschaftsfoto');
  });

  it('TournamentCreateRequest TS interface in tournamentStore.ts includes optional seedMannschaftsfoto field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const storeSrc = path.resolve(__dirname, '../stores/tournamentStore.ts');
    const source = fs.readFileSync(storeSrc, 'utf8');
    const ifaceMatch = source.match(/export interface TournamentCreateRequest \{([^}]+)\}/s);
    expect(ifaceMatch).not.toBeNull();
    const ifaceBlock = ifaceMatch![1];
    // RED before E53S05: interface does not declare seedMannschaftsfoto.
    // GREEN after E53S05: declares seedMannschaftsfoto?: boolean | null.
    expect(ifaceBlock).toContain('seedMannschaftsfoto');
  });
});
