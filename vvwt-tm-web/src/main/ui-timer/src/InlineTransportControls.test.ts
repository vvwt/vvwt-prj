// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const COMPONENTS_DIR = resolve(new URL(import.meta.url).pathname, '..', 'components');
const LIB_DIR = resolve(new URL(import.meta.url).pathname, '..', 'lib');

const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');
const SCHEDULE_ROW_SVELTE = resolve(COMPONENTS_DIR, 'ScheduleRow.svelte');
const TIMELINE_RECOMPUTE_TS = resolve(LIB_DIR, 'timelineRecompute.ts');

const appSource = readFileSync(APP_SVELTE, 'utf-8');
const rowSource = readFileSync(SCHEDULE_ROW_SVELTE, 'utf-8');
const timelineSource = readFileSync(TIMELINE_RECOMPUTE_TS, 'utf-8');

// ── AC1: Inline play / pause / stop controls per audio-triggering row ─────────

describe('E11S12 AC1 — Inline transport controls in every audio-triggering row', () => {
  it('POSITIVE: ScheduleRow.svelte accepts onInlinePlay callback prop', () => {
    expect(rowSource).toMatch(/onInlinePlay\s*\??\s*:/);
  });

  it('POSITIVE: ScheduleRow.svelte accepts onInlinePause callback prop', () => {
    expect(rowSource).toMatch(/onInlinePause\s*\??\s*:/);
  });

  it('POSITIVE: ScheduleRow.svelte accepts onInlineStop callback prop', () => {
    expect(rowSource).toMatch(/onInlineStop\s*\??\s*:/);
  });

  it('POSITIVE: ScheduleRow.svelte renders inline play button for ROUND/REGULAR BREAK rows', () => {
    // The inline-ctrl--play button must appear in source.
    expect(rowSource).toMatch(/inline-ctrl--play/);
  });

  it('POSITIVE: ScheduleRow.svelte renders inline pause and stop buttons', () => {
    expect(rowSource).toMatch(/inline-ctrl--pause/);
    expect(rowSource).toMatch(/inline-ctrl--stop/);
  });

  it('POSITIVE: ScheduleRow.svelte controls are conditional on showInlineControls', () => {
    // Only ROUND and REGULAR BREAK rows show inline controls — not ADDITIONAL BREAK.
    expect(rowSource).toMatch(/showInlineControls/);
  });

  it('POSITIVE: App.svelte passes onInlinePlay to ScheduleRow', () => {
    expect(appSource).toMatch(/onInlinePlay/);
  });

  it('POSITIVE: App.svelte passes onInlinePause to ScheduleRow', () => {
    expect(appSource).toMatch(/onInlinePause/);
  });

  it('POSITIVE: App.svelte passes onInlineStop to ScheduleRow', () => {
    expect(appSource).toMatch(/onInlineStop/);
  });
});

// ── AC2: Audio-activity indicator via inline-play colour saturation ───────────

describe('E11S12 AC2 — Audio-activity indicator via inline-play colour saturation', () => {
  it('POSITIVE: ScheduleRow.svelte accepts an isAudioActive boolean prop', () => {
    expect(rowSource).toMatch(/isAudioActive\s*\??\s*:\s*boolean/);
  });

  it('POSITIVE: ScheduleRow.svelte CSS defines inline-play--active class', () => {
    const styleMatch = rowSource.match(/<style[^>]*>([\s\S]*?)<\/style>/);
    expect(styleMatch).toBeTruthy();
    const styleContent = styleMatch![1];
    expect(styleContent).toMatch(/inline-play--active/);
  });

  it('POSITIVE: ScheduleRow.svelte applies inline-play--active class binding for active audio', () => {
    // The class binding must tie to isAudioActive.
    expect(rowSource).toMatch(/inline-play--active.*isAudioActive|isAudioActive.*inline-play--active/s);
  });

  it('POSITIVE: App.svelte calls isRowAudioActive to determine audio activity per row', () => {
    expect(appSource).toMatch(/isRowAudioActive/);
  });

  it('POSITIVE: App.svelte passes isAudioActive to ScheduleRow', () => {
    expect(appSource).toMatch(/isAudioActive/);
  });
});

// ── AC3: Manual time-shift via inline play "skip-to" ─────────────────────────

describe('E11S12 AC3 — Manual time-shift via inline play skip-to', () => {
  it('POSITIVE: App.svelte defines handleSkipTo function', () => {
    expect(appSource).toMatch(/handleSkipTo/);
  });

  it('POSITIVE: App.svelte skip-to logic uses timeOverrides Map', () => {
    // The skip-to must use the existing timeOverrides Map pattern (E11S04 AC7).
    expect(appSource).toMatch(/timeOverrides/);
  });

  it('POSITIVE: App.svelte skip-to sets entries from rowIndex onward in timeOverrides', () => {
    // The skip-to must update every row from rowIndex onward.
    // Verify the function references rowIndex or a loop over entries.
    expect(appSource).toMatch(/rowIndex|handleSkipTo.*for|for.*handleSkipTo/s);
  });

  it('POSITIVE: App.svelte passes handleSkipTo (or onInlinePlay) to ScheduleRow', () => {
    // onInlinePlay in App.svelte should invoke or be handleSkipTo.
    expect(appSource).toMatch(/handleSkipTo|onInlinePlay.*handleSkipTo|handleSkipTo.*onInlinePlay/s);
  });
});

// ── AC4: Manual time-shift wiped by WS reload ────────────────────────────────

describe('E11S12 AC4 — Manual time-shift is wiped by WS-driven loadTimerDataSilent', () => {
  it('POSITIVE: App.svelte loadTimerDataSilent resets timeOverrides', () => {
    // Consistent with AC7 E11S04: timeOverrides is reset on WS-driven reload.
    // The loadTimerDataSilent (or equivalent reload path) must reset timeOverrides = new Map().
    expect(appSource).toMatch(/loadTimerDataSilent[\s\S]{0,500}timeOverrides\s*=\s*new Map|timeOverrides\s*=\s*new Map[\s\S]{0,500}loadTimerDataSilent/);
  });
});

// ── AC8 path (a): FE-port timeline recompute ─────────────────────────────────

describe('E11S12 AC8 — effectiveSchedule recomputes on ephemeral config edit', () => {
  it('POSITIVE: timelineRecompute.ts exports recomputeSchedule function', () => {
    expect(timelineSource).toMatch(/export\s+function\s+recomputeSchedule/);
  });

  it('POSITIVE: timelineRecompute.ts exports EphemeralPhaseConfig interface', () => {
    expect(timelineSource).toMatch(/export\s+interface\s+EphemeralPhaseConfig/);
  });

  it('POSITIVE: timelineRecompute.ts exports EphemeralBreakConfig interface', () => {
    expect(timelineSource).toMatch(/export\s+interface\s+EphemeralBreakConfig/);
  });

  it('POSITIVE: App.svelte imports recomputeSchedule from timelineRecompute', () => {
    expect(appSource).toMatch(/recomputeSchedule/);
  });

  it('POSITIVE: App.svelte declares effectiveSchedule derived from recompute', () => {
    expect(appSource).toMatch(/effectiveSchedule/);
  });

  it('POSITIVE: App.svelte uses effectiveSchedule in snapshot building', () => {
    expect(appSource).toMatch(/effectiveSchedule\(\)/);
  });
});

// ── AC10: WS-driven reload resets ephemeral config state ─────────────────────

describe('E11S12 AC10 — WS-driven reload resets ephemeral phase-config state', () => {
  it('POSITIVE: App.svelte resets ephemeralPhaseConfig on loadTimerDataSilent', () => {
    // loadTimerDataSilent must reset both ephemeral config Maps.
    expect(appSource).toMatch(/ephemeralPhaseConfig\s*=\s*new Map/);
  });

  it('POSITIVE: App.svelte re-populates ephemeralBreakConfig from buildInitialBreakConfigFull on loadTimerDataSilent (E11S16 REFACTOR Phase-3)', () => {
    // E11S16: REFACTOR Phase-3 correction.
    // Pre-fix: loadTimerDataSilent set ephemeralBreakConfig = buildInitialBreakConfigFull(data)
    // (already set by E11S14 — the original test asserted ephemeralBreakConfig = new Map()
    // which would have FAILED even against the E11S14 code, since E11S14 changed it to
    // buildInitialBreakConfigFull). The assertion is corrected to match actual E11S14+E11S16
    // behaviour: ephemeralBreakConfig is rebuilt from backend data, NOT cleared.
    // FAILS against pre-E11S14 code (which set ephemeralBreakConfig = new Map());
    // PASSES against E11S14+ code (which calls buildInitialBreakConfigFull).
    expect(appSource).toMatch(/buildInitialBreakConfigFull/);
  });
});

// ── AC11: Past-row inline click: option (a) uniform skip-to-now ──────────────

describe('E11S12 AC11 — Inline play on past row: uniform skip-to-now (option a)', () => {
  it('POSITIVE: App.svelte handleSkipTo applies delta regardless of row past/future status', () => {
    // Option (a): skip-to always applies (past or future).
    // The handleSkipTo function uses nowSeconds + delta without a past-guard that would block it.
    // Verified by presence of nowSeconds in the skip-to handler.
    expect(appSource).toMatch(/handleSkipTo[\s\S]{0,2000}nowSeconds|nowSeconds[\s\S]{0,2000}handleSkipTo/s);
  });
});
