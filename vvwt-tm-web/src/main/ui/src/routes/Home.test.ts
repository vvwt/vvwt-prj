// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — In-page h1 gone; title registered via pageHeader
// ─────────────────────────────────────────────────────────────────────────────

describe('Home.svelte — AC3: in-page h1 removed (E47S02)', () => {
  it('Home.svelte source does NOT contain an in-page <h1> for the page title', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Home.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration: no <h1> rendering home.heading inside <main>
    expect(source).not.toMatch(/<h1[^>]*>\s*\{\$_\('home\.heading'\)/);
  });

  it('Home.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Home.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('home.heading');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC7 — Action-area empty and tournament-name slot empty for Home
// ─────────────────────────────────────────────────────────────────────────────

describe('Home.svelte — AC7: empty action area and no tournament-name (E47S02)', () => {
  it('Home.svelte source registers actions: [] in pageHeader.set call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Home.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // No action buttons for home route
    expect(source).toContain('actions: []');
  });

  it('Home.svelte source registers tournamentId: null in pageHeader.set call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Home.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Home is top-level — no tournament context
    expect(source).toContain('tournamentId: null');
  });

  it('Home.svelte source registers backTo: null in pageHeader.set call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Home.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Home is top-level — no back-arrow
    expect(source).toContain('backTo: null');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC11 — Home title key is home.heading (not home.title — per Brief D-15)
// ─────────────────────────────────────────────────────────────────────────────

describe('Home.svelte — AC11: uses home.heading key (not home.title) per D-15 (E47S02)', () => {
  it('de.json has home.heading key with a non-empty string value', () => {
    const d = deMessages as unknown as Record<string, Record<string, string>>;
    expect(d.home).toHaveProperty('heading');
    expect(d.home.heading).toBeTypeOf('string');
    expect(d.home.heading.length).toBeGreaterThan(0);
  });

  it('de.json does NOT have a home.title key (D-15: no rename, existing key reused as-is)', () => {
    const d = deMessages as unknown as Record<string, Record<string, string>>;
    // Brief D-15 explicitly rejects renaming home.heading to home.title
    expect(d.home).not.toHaveProperty('title');
  });

  it('Home.svelte source uses home.heading as the title key (not home.title)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Home.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('home.heading');
    expect(source).not.toContain("'home.title'");
  });
});
