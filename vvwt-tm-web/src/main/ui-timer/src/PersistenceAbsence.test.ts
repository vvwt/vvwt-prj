// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const COMPONENTS_DIR = resolve(new URL(import.meta.url).pathname, '..', 'components');
const LIB_DIR = resolve(new URL(import.meta.url).pathname, '..', 'lib');
const LOCALES_DIR = resolve(new URL(import.meta.url).pathname, '..', 'locales');

const sources: Record<string, string> = {
  'App.svelte': readFileSync(resolve(new URL(import.meta.url).pathname, '..', 'App.svelte'), 'utf-8'),
  'ScheduleRow.svelte': readFileSync(resolve(COMPONENTS_DIR, 'ScheduleRow.svelte'), 'utf-8'),
  'PhaseConfigRow.svelte': readFileSync(resolve(COMPONENTS_DIR, 'PhaseConfigRow.svelte'), 'utf-8'),
  'timelineRecompute.ts': readFileSync(resolve(LIB_DIR, 'timelineRecompute.ts'), 'utf-8'),
};

describe('E11S12 AC13 — Persistence-absence: no saveDraft or backend write in inline-edit path', () => {
  for (const [filename, source] of Object.entries(sources)) {
    it(`NEGATIVE: ${filename} does not call saveDraft`, () => {
      expect(source).not.toMatch(/saveDraft/);
    });

    it(`NEGATIVE: ${filename} does not call DraftService`, () => {
      // No reference to DraftService (Java class) — ephemeral FE-only logic.
      expect(source).not.toMatch(/DraftService/);
    });

    it(`NEGATIVE: ${filename} does not POST to draft or schedule write endpoints`, () => {
      // Inline-edit handlers must not POST to /api/draft or /api/schedule write endpoints.
      // Source-inspectable: no fetch/axios with POST to draft/schedule paths in inline-edit code.
      expect(source).not.toMatch(/fetch\s*\([^)]*POST[^)]*draft|POST[^)]*schedule/);
    });

    it(`NEGATIVE: ${filename} does not write to tournament.draftJson`, () => {
      expect(source).not.toMatch(/draftJson/);
    });
  }
});

describe('E11S12 AC13 — Ephemeral state: operator inline edits are client-side only', () => {
  it('POSITIVE: App.svelte ephemeralPhaseConfig is declared as local $state (not persisted)', () => {
    const appSource = sources['App.svelte'];
    // The ephemeral config must live in Svelte $state, not in a store or persisted location.
    expect(appSource).toMatch(/ephemeralPhaseConfig\s*=\s*\$state\s*\(.*new Map/s);
  });

  it('POSITIVE: App.svelte ephemeralBreakConfig is declared as local $state (not persisted)', () => {
    const appSource = sources['App.svelte'];
    expect(appSource).toMatch(/ephemeralBreakConfig\s*=\s*\$state\s*\(.*new Map/s);
  });

  it('POSITIVE: App.svelte resets ephemeral state on WS-driven reload (AC10 + AC7 combined)', () => {
    const appSource = sources['App.svelte'];
    // Both Maps are reset on loadTimerDataSilent.
    expect(appSource).toMatch(/ephemeralPhaseConfig\s*=\s*new Map/);
    expect(appSource).toMatch(/ephemeralBreakConfig\s*=\s*new Map/);
  });
});
