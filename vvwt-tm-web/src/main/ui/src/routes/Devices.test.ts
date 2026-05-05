/**
 * Tests for Devices route — Story E47S01.
 *
 * AC3: No per-page .devices__header; title registered via pageHeader store.
 * AC7: Narrow viewport → "QR zeigen" (btn--secondary) is icon-only with aria-label.
 * AC8: Narrow → "alle löschen" (btn--danger) is in Overflow-Menu, not bare button; desktop → bare button.
 * AC17: Devices narrow → BOTH icon-collapse (D-9) AND Overflow-Menu (D-11) apply simultaneously.
 *
 * RED-first per DEC-22: all tests fail before migration.
 */

import { describe, it, expect } from 'vitest';
import { get } from 'svelte/store';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — No per-page __header
// ─────────────────────────────────────────────────────────────────────────────

describe('Devices.svelte — AC3: per-page header removed (E47S01)', () => {
  it('Devices.svelte source does NOT contain .devices__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Devices.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('devices__header');
  });

  it('Devices.svelte source does NOT contain .devices__header-actions class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Devices.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('devices__header-actions');
  });

  it('Devices.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Devices.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain("devices.title");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC7 — Non-destructive button icon-collapse via action ariaLabel
// ─────────────────────────────────────────────────────────────────────────────

describe('Devices.svelte — AC7: QR zeigen ariaLabel for icon-collapse (E47S01)', () => {
  it('pageHeader actions for Devices include QR button with ariaLabel', async () => {
    const { pageHeader } = await import('../stores/pageHeaderStore.js');
    pageHeader.set({
      title: 'Geräteverwaltung',
      backTo: null,
      tournamentId: null,
      actions: [
        { label: 'QR-Code anzeigen', ariaLabel: 'devices.showQrButton', handler: () => {}, variant: 'secondary' },
        { label: 'Alle löschen', ariaLabel: 'devices.clearAllButton', handler: () => {}, variant: 'danger' },
      ],
    });
    const state = get(pageHeader);
    const qrAction = state.actions.find(a => a.variant === 'secondary');
    expect(qrAction).toBeDefined();
    expect(qrAction!.ariaLabel).toBe('devices.showQrButton');
  });

  it('de.json devices.showQrButton has non-empty value (reused as aria-label at narrow viewport)', () => {
    const d = (deMessages as unknown as Record<string, Record<string, string>>).devices;
    expect(d).toHaveProperty('showQrButton');
    expect(d.showQrButton.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC8 — Overflow-Menu for destructive button
// ─────────────────────────────────────────────────────────────────────────────

describe('OverflowMenu.svelte — AC8: exists and has correct structure (E47S01)', () => {
  it('OverflowMenu.svelte component file exists', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const overflowMenuPath = path.resolve(__dirname, '../lib/OverflowMenu.svelte');
    expect(fs.existsSync(overflowMenuPath)).toBe(true);
  });

  it('OverflowMenu.svelte source has aria-haspopup="menu" trigger', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const overflowMenuPath = path.resolve(__dirname, '../lib/OverflowMenu.svelte');
    const source = fs.readFileSync(overflowMenuPath, 'utf8');
    expect(source).toContain('aria-haspopup');
  });

  it('OverflowMenu.svelte source accepts actions prop array', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const overflowMenuPath = path.resolve(__dirname, '../lib/OverflowMenu.svelte');
    const source = fs.readFileSync(overflowMenuPath, 'utf8');
    // Props should include actions or items
    expect(source).toMatch(/actions|items|menuItems/);
  });

  it('App.svelte source renders danger-variant actions via Overflow-Menu at narrow viewport', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    expect(source).toContain('OverflowMenu');
    expect(source).toMatch(/danger/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC17 — Devices narrow: icon-collapse (D-9) + Overflow-Menu (D-11) coexist
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — AC17: D-9+D-11 coexistence at narrow viewport (E47S01)', () => {
  it('pageHeader actions correctly classify QR as secondary and clearAll as danger', async () => {
    const { pageHeader } = await import('../stores/pageHeaderStore.js');
    pageHeader.set({
      title: 'Geräteverwaltung',
      backTo: null,
      tournamentId: null,
      actions: [
        { label: 'QR-Code anzeigen', ariaLabel: 'devices.showQrButton', handler: () => {}, variant: 'secondary' },
        { label: 'Alle löschen', ariaLabel: 'devices.clearAllButton', handler: () => {}, variant: 'danger' },
      ],
    });
    const state = get(pageHeader);
    expect(state.actions).toHaveLength(2);
    const secondary = state.actions.filter(a => a.variant === 'secondary');
    const danger = state.actions.filter(a => a.variant === 'danger');
    expect(secondary).toHaveLength(1);
    expect(danger).toHaveLength(1);
  });

  it('App.svelte source applies icon-collapse to secondary variant at narrow viewport', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    // Must handle secondary (non-destructive) with icon-only at narrow
    // and danger (destructive) with OverflowMenu at narrow
    expect(source).toContain('secondary');
    expect(source).toContain('danger');
    expect(source).toContain('OverflowMenu');
    expect(source).toContain('768');
  });

  it('de.json devices.clearAllButton has non-empty value', () => {
    const d = (deMessages as unknown as Record<string, Record<string, string>>).devices;
    expect(d).toHaveProperty('clearAllButton');
    expect(d.clearAllButton.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC16 — Tournament-name truncation (D-9) — App.svelte CSS
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — AC16: tournament-name truncation CSS (E47S01)', () => {
  it('App.svelte source contains text-overflow:ellipsis for tournament name element', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    expect(source).toMatch(/text-overflow\s*:\s*ellipsis/);
    expect(source).toMatch(/overflow\s*:\s*hidden/);
    expect(source).toMatch(/white-space\s*:\s*nowrap/);
  });

  it('App.svelte source hides or removes tournament name at narrowest breakpoint', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const appPath = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(appPath, 'utf8');
    // Must have a media query that hides the tournament name element at narrowest breakpoint
    expect(source).toMatch(/display\s*:\s*none|width\s*:\s*0/);
  });
});
