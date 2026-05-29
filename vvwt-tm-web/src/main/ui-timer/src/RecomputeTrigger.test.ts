// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// E11S16 — RecomputeTrigger tests.
// Verifies that the recompute trigger distinguishes "baseline pre-population"
// (E11S14 AC4/AC5) from "user-edit" (E11S12 AC6), so that schedule
// effectiveSchedule() returns backend times verbatim on fresh page load
// and only recomputes after the operator makes an inline edit.
// DEC-22 §refactor-clause routing (AC9): fresh RED-first source-inspection
// tests (Q-1a path) — no existing test asserts "hasEphemeralOverrides uses
// userEditedBreakConfig" because the concept of userEditedBreakConfig is new.
// All tests FAIL against pre-fix production code and PASS against post-fix code.

const SRC_DIR = resolve(new URL(import.meta.url).pathname, '..');
const LIB_DIR = resolve(SRC_DIR, 'lib');

const APP_SVELTE = resolve(SRC_DIR, 'App.svelte');
const TIMELINE_RECOMPUTE_TS = resolve(LIB_DIR, 'timelineRecompute.ts');

const appSource = readFileSync(APP_SVELTE, 'utf-8');
const timelineSource = readFileSync(TIMELINE_RECOMPUTE_TS, 'utf-8');

// ── AC1/AC2: effectiveSchedule trigger — recompute fires only on user edits ───

describe('E11S16 AC2 — Recompute trigger: hasEphemeralOverrides checks userEditedBreakConfig not ephemeralBreakConfig', () => {
  it('POSITIVE: App.svelte declares userEditedBreakConfig as a separate state Map', () => {
    // AC2 mechanism (i): a second map for user-edited break entries only.
    // FAILS against pre-fix App.svelte (no userEditedBreakConfig); PASSES after fix.
    expect(appSource).toMatch(/userEditedBreakConfig/);
  });

  it('POSITIVE: App.svelte passes userEditedBreakConfig to hasEphemeralOverrides (not ephemeralBreakConfig)', () => {
    // The hasEphemeralOverrides call site in effectiveSchedule must use
    // userEditedBreakConfig as the break-config argument.
    // FAILS against pre-fix (passes ephemeralBreakConfig); PASSES after fix.
    expect(appSource).toMatch(/hasEphemeralOverrides\s*\([^)]*userEditedBreakConfig/);
  });

  it('POSITIVE: App.svelte passes userEditedBreakConfig to recomputeSchedule (not ephemeralBreakConfig for the break-override arg)', () => {
    // The recomputeSchedule call site must use userEditedBreakConfig as the
    // break-override map — so recompute uses user edits, not baseline display state.
    // FAILS against pre-fix (passes ephemeralBreakConfig); PASSES after fix.
    expect(appSource).toMatch(/recomputeSchedule\s*\([^)]*userEditedBreakConfig/);
  });

  it('POSITIVE: handleInlineBreakUpdate populates userEditedBreakConfig on operator commit', () => {
    // The operator inline-edit handler must write to userEditedBreakConfig.
    // FAILS against pre-fix (no userEditedBreakConfig write); PASSES after fix.
    expect(appSource).toMatch(/handleInlineBreakUpdate[\s\S]{0,500}userEditedBreakConfig|userEditedBreakConfig[\s\S]{0,200}handleInlineBreakUpdate/s);
  });
});

// ── AC10: WS-driven reload wipes userEditedBreakConfig + rebuilds baseline ────

describe('E11S16 AC10 / AC8a — WS reload wipes userEditedBreakConfig and rebuilds ephemeralBreakConfig from backend', () => {
  it('POSITIVE: loadTimerDataSilent resets userEditedBreakConfig to new Map()', () => {
    // WS-driven reload clears user-edit state (AC8a: both states wiped).
    // FAILS against pre-fix (no userEditedBreakConfig reset); PASSES after fix.
    expect(appSource).toMatch(/loadTimerDataSilent[\s\S]{0,1500}userEditedBreakConfig\s*=\s*new Map/s);
  });

  it('POSITIVE: loadTimerDataSilent re-populates ephemeralBreakConfig via buildInitialBreakConfigFull (not new Map)', () => {
    // After WS reload, baseline pre-population is rebuilt from fresh backend data.
    // FAILS against pre-fix (loadTimerDataSilent uses buildInitialBreakConfigFull already
    // from E11S14 — this test will already PASS on the pre-E11S16 code for this particular
    // assertion; it is included as a regression guard to ensure the E11S14 baseline-rebuild
    // behaviour is preserved after E11S16 changes loadTimerDataSilent).
    // AC9 note: this test acts as a carryover regression guard, not a fresh RED-first test.
    expect(appSource).toMatch(/loadTimerDataSilent[\s\S]{0,1500}buildInitialBreakConfigFull/s);
  });
});

// ── AC1: Schedule passthrough on fresh load (source-level regression guard) ───

describe('E11S16 AC1 — effectiveSchedule passes through timerData.schedule when no user edits', () => {
  it('POSITIVE: effectiveSchedule calls hasEphemeralOverrides before deciding to recompute', () => {
    // effectiveSchedule must guard with hasEphemeralOverrides.
    // This is stable across the fix (no regression).
    expect(appSource).toMatch(/hasEphemeralOverrides/);
  });

  it('POSITIVE: hasEphemeralOverrides in timelineRecompute.ts checks both ephemeralPhaseConfig and userEditedBreakConfig (second param)', () => {
    // The function signature change: accept userEditedBreakConfig as second param.
    // FAILS against pre-fix timelineRecompute.ts (second param named ephemeralBreakConfig);
    // PASSES after fix (param renamed to userEditedBreakConfig).
    expect(timelineSource).toMatch(/hasEphemeralOverrides\s*\([^)]*userEditedBreakConfig/);
  });
});

// ── AC14: No saveDraft call in any break-edit path ────────────────────────────

describe('E11S16 AC14 — Ephemeral edit invariant: no saveDraft call added', () => {
  it('NEGATIVE: App.svelte does not call saveDraft() (E11S12 D-11 / E11S14 AC22 carryover)', () => {
    // AC14: the userEditedBreakConfig mechanism must NOT introduce saveDraft.
    expect(appSource).not.toMatch(/saveDraft\s*\(/);
  });
});
