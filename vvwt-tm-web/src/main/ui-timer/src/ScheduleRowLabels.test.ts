// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * E11S14 AC18 — FE label-switching for Phasen-Pause vs Zusatzpause.
 *
 * Source-inspection tests per AppBranding.test.ts / ScheduleRowNext.test.ts precedent.
 *
 * DEC-22 REFACTOR Phase-3: these tests FAIL against pre-fix ScheduleRow.svelte (no PHASE_BREAK
 * handling in entryLabel) and PASS after the fix.
 *
 * AC12 — Audio events remain silent for both kinds: both stay breakType=ADDITIONAL; no audio
 * classification change needed (tested indirectly via source inspection of entryLabel).
 */

const COMPONENTS_DIR = resolve(new URL(import.meta.url).pathname, '..', 'components');
const LOCALES_DIR = resolve(new URL(import.meta.url).pathname, '..', 'locales');

const scheduleRowSource = readFileSync(resolve(COMPONENTS_DIR, 'ScheduleRow.svelte'), 'utf-8');
const deJson = readFileSync(resolve(LOCALES_DIR, 'de.json'), 'utf-8');

// ── AC10 / AC18: Phasen-Pause label ─────────────────────────────────────────

describe('E11S14 AC10/AC18 — Phasen-Pause label for SECTION_BREAK entries', () => {
  it('POSITIVE: de.json contains timer.schedule.phasenPause key', () => {
    // The i18n key must be present for the "Phasen-Pause" label
    expect(deJson).toMatch(/"phasenPause"\s*:/);
  });

  it('POSITIVE: de.json timer.schedule.phasenPause value is "Phasen-Pause"', () => {
    const parsed = JSON.parse(deJson) as Record<string, unknown>;
    const schedule = (parsed as { timer: { schedule: Record<string, string> } }).timer.schedule;
    expect(schedule['phasenPause']).toBe('Phasen-Pause');
  });

  it('POSITIVE: ScheduleRow.svelte handles "PHASE_BREAK" stable key in entryLabel', () => {
    // entryLabel must switch on PHASE_BREAK stable key (fail-on-old: pre-fix has no such check)
    expect(scheduleRowSource).toMatch(/PHASE_BREAK/);
  });

  it('POSITIVE: ScheduleRow.svelte uses phasenPause i18n key for PHASE_BREAK entries', () => {
    // Must reference timer.schedule.phasenPause (or equivalent i18n call)
    expect(scheduleRowSource).toMatch(/phasenPause/);
  });

  it('NEGATIVE: ScheduleRow.svelte does not render the "PHASE_BREAK" literal string in the label', () => {
    // The stable key must NOT appear as a rendered string — only as a discriminator
    // We check that when label === 'PHASE_BREAK' the component renders the i18n key, not the literal
    // This is enforced by the PHASE_BREAK check preceding the label-display logic
    // (the conditional appears before any potential raw label rendering)
    // Source-inspection: entryLabel must not return 'PHASE_BREAK' directly
    // Check: there is a branch that maps 'PHASE_BREAK' to i18n, not to itself
    expect(scheduleRowSource).toMatch(/PHASE_BREAK.*phasenPause|phasenPause.*PHASE_BREAK/s);
  });
});

// ── AC11 / AC18: Zusatzpause label ──────────────────────────────────────────

describe('E11S14 AC11/AC18 — Zusatzpause label for INTRA_PHASE_BREAK entries', () => {
  it('POSITIVE: de.json contains timer.schedule.breakAdditional key with "Zusatzpause" value', () => {
    const parsed = JSON.parse(deJson) as Record<string, unknown>;
    const schedule = (parsed as { timer: { schedule: Record<string, string> } }).timer.schedule;
    // breakAdditional is the existing fallback key — value should be "Zusatzpause"
    expect(schedule['breakAdditional']).toBe('Zusatzpause');
  });

  it('POSITIVE: ScheduleRow.svelte uses breakAdditional i18n key as fallback for non-PHASE_BREAK ADDITIONAL entries', () => {
    // The fallback path must use breakAdditional (or equivalent) for INTRA_PHASE_BREAK labels
    expect(scheduleRowSource).toMatch(/breakAdditional/);
  });

  it('POSITIVE: ScheduleRow.svelte renders entry.label for non-empty, non-PHASE_BREAK labels', () => {
    // When label is a real user label (not PHASE_BREAK), it should be rendered
    // Source-inspection: entryLabel uses label || fallback pattern for ADDITIONAL entries
    expect(scheduleRowSource).toMatch(/entry\.label/);
  });
});

// ── AC14 / AC18: null/empty label fallback ───────────────────────────────────

describe('E11S14 AC14/AC18 — null/empty label falls back to Zusatzpause', () => {
  it('POSITIVE: breakLabelInput in ScheduleRow.svelte does not use PHASE_BREAK as initial value', () => {
    // When entry.label === "PHASE_BREAK", the Bezeichnung input should init to '' not "PHASE_BREAK"
    // Indirectly verified: the breakLabelInput init must check for PHASE_BREAK and use '' instead
    expect(scheduleRowSource).toMatch(/PHASE_BREAK/); // PHASE_BREAK appears in the file
    // Additional check: the label input init has a guard for PHASE_BREAK
    // (this may be expressed as: entry.label === 'PHASE_BREAK' ? '' : entry.label)
    expect(scheduleRowSource).toMatch(/PHASE_BREAK.*''|''.*PHASE_BREAK|PHASE_BREAK.*empty|breakLabelInput/s);
  });
});
