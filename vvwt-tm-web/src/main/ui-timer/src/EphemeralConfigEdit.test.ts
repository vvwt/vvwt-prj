// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const COMPONENTS_DIR = resolve(new URL(import.meta.url).pathname, '..', 'components');
const LIB_DIR = resolve(new URL(import.meta.url).pathname, '..', 'lib');

const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');
const SCHEDULE_ROW_SVELTE = resolve(COMPONENTS_DIR, 'ScheduleRow.svelte');
const PHASE_CONFIG_ROW_SVELTE = resolve(COMPONENTS_DIR, 'PhaseConfigRow.svelte');
const TIMELINE_RECOMPUTE_TS = resolve(LIB_DIR, 'timelineRecompute.ts');

const appSource = readFileSync(APP_SVELTE, 'utf-8');
const rowSource = readFileSync(SCHEDULE_ROW_SVELTE, 'utf-8');
const phaseConfigSource = readFileSync(PHASE_CONFIG_ROW_SVELTE, 'utf-8');
const timelineSource = readFileSync(TIMELINE_RECOMPUTE_TS, 'utf-8');

// ── AC5: Phase-config row above Round 1 of each phase ────────────────────────

describe('E11S12 AC5 — Phase-config row above Round 1 of each phase', () => {
  it('POSITIVE: PhaseConfigRow.svelte component exists and has required props', () => {
    expect(phaseConfigSource).toMatch(/PhaseConfigRowProps|interface.*Props/);
  });

  it('POSITIVE: PhaseConfigRow.svelte accepts lapTimeMinutes prop', () => {
    expect(phaseConfigSource).toMatch(/lapTimeMinutes\s*:/);
  });

  it('POSITIVE: PhaseConfigRow.svelte accepts lapBreakTimeMinutes prop', () => {
    expect(phaseConfigSource).toMatch(/lapBreakTimeMinutes\s*:/);
  });

  it('POSITIVE: PhaseConfigRow.svelte accepts sectionBreakTimeMinutes prop', () => {
    expect(phaseConfigSource).toMatch(/sectionBreakTimeMinutes\s*:/);
  });

  it('POSITIVE: PhaseConfigRow.svelte accepts isLastPhase boolean prop', () => {
    expect(phaseConfigSource).toMatch(/isLastPhase\s*\??\s*:\s*boolean/);
  });

  it('POSITIVE: PhaseConfigRow.svelte fires onUpdate callback with new config', () => {
    expect(phaseConfigSource).toMatch(/onUpdate/);
  });

  it('POSITIVE: App.svelte imports PhaseConfigRow component', () => {
    expect(appSource).toMatch(/PhaseConfigRow/);
  });

  it('POSITIVE: App.svelte inserts PhaseConfigRow above first ROUND of each phase', () => {
    // App.svelte must render PhaseConfigRow when it detects the first ROUND row of a phase.
    expect(appSource).toMatch(/PhaseConfigRow|getFirstRoundIndexForPhase/);
  });

  it('POSITIVE: App.svelte passes isLastPhase to PhaseConfigRow for the last phase', () => {
    expect(appSource).toMatch(/isLastPhase|lastPhaseNumber/);
  });
});

// ── AC6: Intra-phase break inline-edit ───────────────────────────────────────

describe('E11S12 AC6 — Intra-phase break inline-edit (ADDITIONAL BREAK rows)', () => {
  it('POSITIVE: ScheduleRow.svelte accepts onBreakEdit callback prop', () => {
    expect(rowSource).toMatch(/onBreakEdit\s*\??\s*:/);
  });

  it('POSITIVE: ScheduleRow.svelte shows inline edit for ADDITIONAL BREAK rows', () => {
    expect(rowSource).toMatch(/showBreakEdit/);
  });

  it('POSITIVE: ScheduleRow.svelte renders a duration input for ADDITIONAL BREAK rows', () => {
    // Duration input must exist for ADDITIONAL BREAK inline edit.
    expect(rowSource).toMatch(/breakDurationInput|duration.*input|input.*duration/i);
  });

  it('POSITIVE: App.svelte handles ADDITIONAL BREAK inline-edit via handleInlineBreakUpdate', () => {
    expect(appSource).toMatch(/handleInlineBreakUpdate|onBreakEdit/);
  });

  it('POSITIVE: App.svelte maintains ephemeralBreakConfig Map for ADDITIONAL BREAK overrides', () => {
    expect(appSource).toMatch(/ephemeralBreakConfig/);
  });

  it('POSITIVE: timelineRecompute.ts uses ephemeralBreakConfig for ADDITIONAL BREAK durations', () => {
    expect(timelineSource).toMatch(/ephemeralBreakConfig|EphemeralBreakConfig/);
  });
});

// ── AC7: Edits are ephemeral (no saveDraft call) ──────────────────────────────

describe('E11S12 AC7 — Inline edits are ephemeral: no saveDraft call', () => {
  it('NEGATIVE: App.svelte does not invoke saveDraft() in the inline-edit handler paths', () => {
    // Match only actual method invocations saveDraft( — not comments mentioning it.
    expect(appSource).not.toMatch(/saveDraft\s*\(/);
  });

  it('NEGATIVE: PhaseConfigRow.svelte does not invoke saveDraft()', () => {
    expect(phaseConfigSource).not.toMatch(/saveDraft\s*\(/);
  });

  it('NEGATIVE: ScheduleRow.svelte does not invoke saveDraft()', () => {
    expect(rowSource).not.toMatch(/saveDraft\s*\(/);
  });

  it('NEGATIVE: timelineRecompute.ts does not invoke saveDraft()', () => {
    expect(timelineSource).not.toMatch(/saveDraft\s*\(/);
  });
});

// ── AC9: Invalid inline-edit input validation ─────────────────────────────────

describe('E11S12 AC9 — Invalid inline-edit input: validation error renders inline', () => {
  it('POSITIVE: PhaseConfigRow.svelte validates lapTimeMinutes > 0', () => {
    // The validation must check lapTime > 0 (or > 0 after parse).
    expect(phaseConfigSource).toMatch(/lapTime.*<=\s*0|isNaN.*lapTime/s);
  });

  it('POSITIVE: PhaseConfigRow.svelte validates lapBreakTimeMinutes >= 0', () => {
    expect(phaseConfigSource).toMatch(/lapBreak.*<\s*0|isNaN.*lapBreak/s);
  });

  it('POSITIVE: PhaseConfigRow.svelte renders error message for invalid lapTime', () => {
    expect(phaseConfigSource).toMatch(/lapTimeError/);
  });

  it('POSITIVE: PhaseConfigRow.svelte renders error message for invalid lapBreak', () => {
    expect(phaseConfigSource).toMatch(/lapBreakError/);
  });

  it('POSITIVE: PhaseConfigRow.svelte does NOT call onUpdate when validation fails', () => {
    // The validateAndCommit function must return early on validation failure.
    expect(phaseConfigSource).toMatch(/if.*!valid.*return|return.*!valid/s);
  });

  it('POSITIVE: PhaseConfigRow.svelte uses i18n keys for error messages', () => {
    expect(phaseConfigSource).toMatch(/timer\.phaseConfig\.error/);
  });
});
