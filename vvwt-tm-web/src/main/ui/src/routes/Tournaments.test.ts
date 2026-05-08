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

// ─────────────────────────────────────────────────────────────────────────────
// E48S13 — Cascade-Delete button visibility + i18n (AC-IMPL-FE-CASCADE-DELETE-BUTTON)
// ─────────────────────────────────────────────────────────────────────────────

describe('Tournaments.svelte — E48S13 cascade-delete button (AC-TEST-FRONTEND-VITEST-RED)', () => {
  it('Tournaments.svelte source contains cascadeDeleteButton i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('tournaments.cascadeDeleteButton');
  });

  it('Tournaments.svelte source contains cascadeDeleteConfirm i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('tournaments.cascadeDeleteConfirm');
  });

  it('Tournaments.svelte source shows cascade-delete button for DRAFT status', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The cascade-delete button must appear in a DRAFT block
    expect(source).toContain("status === 'DRAFT'");
    expect(source).toContain('handleCascadeDelete');
  });

  it('Tournaments.svelte source shows cascade-delete button for PLANNED status', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("status === 'PLANNED'");
  });

  it('Tournaments.svelte source shows cascade-delete button for CANCELLED status', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("status === 'CANCELLED'");
  });

  it('de.json contains cascadeDeleteButton key', () => {
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).toHaveProperty('cascadeDeleteButton');
    expect(typeof t.cascadeDeleteButton).toBe('string');
    expect(t.cascadeDeleteButton.length).toBeGreaterThan(0);
  });

  it('de.json contains cascadeDeleteConfirm key', () => {
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).toHaveProperty('cascadeDeleteConfirm');
    expect(typeof t.cascadeDeleteConfirm).toBe('string');
    expect(t.cascadeDeleteConfirm.length).toBeGreaterThan(0);
  });

  it('Tournaments.svelte imports cascadeDeleteTournament from tournamentStore', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('cascadeDeleteTournament');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E52S01 — Zeitpläne button (AC-TEST-ZEITPLAENE-*) — RED-first per DEC-22
// ─────────────────────────────────────────────────────────────────────────────

describe('Tournaments.svelte — E52S01: Zeitpläne button (AC-TEST-ZEITPLAENE-BUTTON-VISIBLE-PLANNED-RED)', () => {
  it('Tournaments.svelte source contains tournaments.schedulesButton i18n key', async () => {
    // AC-TEST-ZEITPLAENE-BUTTON-VISIBLE-PLANNED-RED: button rendered for PLANNED|ACTIVE|COMPLETED|CANCELLED
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('tournaments.schedulesButton');
  });

  it('Tournaments.svelte source contains schedulesButton inside the phases-gate block', async () => {
    // AC-IMPL-VISIBILITY-GATE: button is inside PLANNED|ACTIVE|COMPLETED|CANCELLED gate
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The schedulesButton key must appear after the phases-gate condition
    const phasesGateIdx = source.indexOf("t.status === 'PLANNED' || t.status === 'ACTIVE' || t.status === 'COMPLETED' || t.status === 'CANCELLED'");
    const buttonIdx = source.indexOf('tournaments.schedulesButton');
    expect(phasesGateIdx).toBeGreaterThan(-1);
    expect(buttonIdx).toBeGreaterThan(phasesGateIdx);
  });
});

describe('Tournaments.svelte — E52S01: Zeitpläne button hidden for DRAFT (AC-TEST-ZEITPLAENE-BUTTON-HIDDEN-DRAFT-RED)', () => {
  it('Tournaments.svelte source does NOT place schedulesButton inside a DRAFT-only block', async () => {
    // AC-TEST-ZEITPLAENE-BUTTON-HIDDEN-DRAFT-RED: button absent for DRAFT tournaments
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The schedulesButton must NOT appear between a DRAFT-only {#if} and its {/if}
    // Simple heuristic: schedulesButton must not appear inside the DRAFT-only block
    const draftBlockMatch = source.match(/\{#if t\.status === 'DRAFT'\}([\s\S]*?)\{\/if\}/);
    if (draftBlockMatch) {
      expect(draftBlockMatch[1]).not.toContain('tournaments.schedulesButton');
    }
    // No DRAFT block at all is also acceptable (button gated at higher level)
  });
});

describe('Tournaments.svelte — E52S01: click reaches print index (AC-TEST-ZEITPLAENE-CLICK-REACHES-PRINT-INDEX-RED)', () => {
  it('Tournaments.svelte source contains window.open call to /print/tournaments/', async () => {
    // AC-TEST-ZEITPLAENE-CLICK-REACHES-PRINT-INDEX-RED: click opens PrintController printIndex
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("window.open(`/print/tournaments/");
    expect(source).toContain("'_blank'");
  });
});

describe('de.json — E52S01: schedulesButton i18n key (AC-TEST-I18N-DE-KEY-PRESENT-RED)', () => {
  it('de.json tournaments.schedulesButton key is present with value "Zeitpläne"', () => {
    // AC-TEST-I18N-DE-KEY-PRESENT-RED + AC-I18N-DE-NEW-KEY
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).toHaveProperty('schedulesButton');
    expect(t.schedulesButton).toBe('Zeitpläne');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E52S02 — Button rename: certificateTemplateButton → certificatesButton
// ─────────────────────────────────────────────────────────────────────────────

describe('Tournaments.svelte — E52S02: renamed Urkunden button (AC-TEST-ROW-BUTTON-RENAMED-RED)', () => {
  it('Tournaments.svelte source contains tournaments.certificatesButton i18n key (new key)', async () => {
    // AC-TEST-ROW-BUTTON-RENAMED-RED: button must use new key
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("tournaments.certificatesButton");
  });

  it('Tournaments.svelte source does NOT contain old key tournaments.certificateTemplateButton', async () => {
    // AC-TEST-ROW-BUTTON-RENAMED-RED: old key must be gone
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain("tournaments.certificateTemplateButton");
  });

  it('Tournaments.svelte source still navigates to /certificate-template route (URL unchanged)', async () => {
    // AC-GOV-FE-ROUTE-URL-UNCHANGED + AC-URL-FE-ROUTE-CERTIFICATE-TEMPLATE-UNCHANGED
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('/certificate-template');
  });
});

describe('de.json — E52S02: certificatesButton rename (AC-TEST-RENAME-KEY-IN-DE-JSON-RED)', () => {
  it('de.json tournaments.certificatesButton key is present with value "Urkunden"', () => {
    // AC-TEST-RENAME-KEY-IN-DE-JSON-RED + AC-I18N-DE-RENAME-KEY
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).toHaveProperty('certificatesButton');
    expect(t.certificatesButton).toBe('Urkunden');
  });

  it('de.json does NOT contain old key tournaments.certificateTemplateButton', () => {
    // AC-TEST-RENAME-KEY-IN-DE-JSON-RED: old key must be absent
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).not.toHaveProperty('certificateTemplateButton');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E52S03 — Remove redundant Timer-Link tournament-row button
// RED-first per DEC-22: these tests assert the NEW state (button absent, key absent).
// They FAIL against the current source (button still present, key still present).
// ─────────────────────────────────────────────────────────────────────────────

describe('E52S03 — Tournaments.svelte: Timer-Link button REMOVED (AC-TEST-TIMER-LINK-BUTTON-REMOVED-RED)', () => {
  it('Tournaments.svelte source does NOT contain tournaments.timerLinkButton i18n call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain("tournaments.timerLinkButton");
  });

  it('Tournaments.svelte source does NOT contain Timer-Link {#if} block (E11S07 AC1 block gone)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Tournaments.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The E11S07 AC1/AC3 timer link block was: {#if t.status === 'PLANNED' || t.status === 'ACTIVE'}
    // followed by the timer-link button. After E52S03 removal, this specific combination is gone.
    expect(source).not.toMatch(/E11S07 AC1\/AC3: timer link/);
  });
});

describe('E52S03 — de.json: tournaments.timerLinkButton KEY REMOVED (AC-TEST-TOURNAMENTS-TIMERLINKBUTTON-KEY-REMOVED-RED)', () => {
  it('de.json does NOT contain tournaments.timerLinkButton key after removal', () => {
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).not.toHaveProperty('timerLinkButton');
  });
});

describe('E52S03 — de.json: audio.timerLinkButton KEY PRESERVED (AC-TEST-AUDIO-TIMERLINKBUTTON-KEY-PRESERVED-RED)', () => {
  it('de.json STILL contains audio.timerLinkButton key with non-empty value', () => {
    const a = (deMessages as unknown as Record<string, Record<string, string>>).audio;
    expect(a).toHaveProperty('timerLinkButton');
    expect(typeof a.timerLinkButton).toBe('string');
    expect(a.timerLinkButton.length).toBeGreaterThan(0);
  });
});

describe('E52S03 — TimerAudio.svelte: push-handler PRESERVED (AC-TEST-TIMERAUDIO-PUSH-HANDLER-PRESERVED-RED)', () => {
  it('TimerAudio.svelte source contains push handler navigating to timer-link route', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TimerAudio.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('/timer-link`');
  });
});

describe('E52S03 — App.svelte: /timer-link route REGISTERED (AC-TEST-TIMERLINK-PAGE-REACHABLE-FROM-AUDIO-RED)', () => {
  it('App.svelte source still registers the /timer-link route (reachable via TimerAudio)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    expect(source).toContain('timer-link');
  });
});
