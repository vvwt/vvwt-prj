// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');

function readAppSvelte(): string {
  return readFileSync(APP_SVELTE, 'utf-8');
}

// ── AC13: Audio-fire init-suppression ──────────────────────────────────────────

describe('E11S10 AC13 — Audio-fire init-suppression (source-inspection, RED-first)', () => {
  const source = readAppSvelte();

  it('AC13-NEGATIVE: App.svelte MUST NOT use -2 sentinel for lastFiredActiveIndex init-suppression', () => {
    // The bug: `lastFiredActiveIndex = -2` was used to suppress first-tick audio.
    // After the fix, this sentinel is replaced by an explicit `audioEngineJustStarted` flag.
    // This test FAILS on the pre-fix code (which contains -2) and PASSES after the fix.
    expect(source).not.toMatch(/lastFiredActiveIndex\s*=\s*\$state<number>\(-2\)/);
  });

  it('AC13-POSITIVE: App.svelte MUST use an explicit audioEngineJustStarted flag for init-suppression', () => {
    // After the fix, the init-suppression is done via a boolean flag.
    // This test FAILS on the pre-fix code (no such flag) and PASSES after the fix.
    expect(source).toMatch(/audioEngineJustStarted/);
  });

  it('AC13-POSITIVE: onTick MUST check audioEngineJustStarted before firing audio', () => {
    // After the fix, onTick suppresses audio on the first tick after engine start.
    // The flag must be referenced inside the onTick function body.
    // This test FAILS on the pre-fix code and PASSES after the fix.
    const onTickMatch = source.match(/function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function|\n  \/\/\s*──)/);
    expect(onTickMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (onTickMatch) {
      expect(onTickMatch[1]).toMatch(/audioEngineJustStarted/);
    }
  });
});

// ── AC13/E11S13: Audio activate-fire playingIndex correction ──────────────────

describe('E11S13 AC13 — Audio activate-fire uses playingIndex (REFACTOR Phase-3, source-inspection)', () => {
  const source = readAppSvelte();

  /**
   * RED-on-old attestation (AC13/E11S13):
   *   Pre-fix code (E11S12 era, App.svelte:448): `effectiveSchedule()[activeEventIndex]`
   *   → test AC13-ACTIVATE-INDEX-POSITIVE fails (asserts playingIndex usage — absent)
   *   → test AC13-ACTIVATE-WRONG-NEGATIVE passes (but we need the inverse on post-fix)
   *
   *   Post-fix code (E11S13): `effectiveSchedule()[playingIndex]`
   *   → test AC13-ACTIVATE-INDEX-POSITIVE passes
   *   → test AC13-ACTIVATE-INDEX-NEGATIVE passes (activeEventIndex not used for activate lookup)
   */

  it('AC13-ACTIVATE-INDEX-POSITIVE: onTick activate-fire looks up entry via playingIndex (AC1/E11S13)', () => {
    // After the fix, the activate-fire block uses effectiveSchedule()[playingIndex].
    // This test FAILs on pre-fix code (activate used activeEventIndex, not playingIndex).
    // This test PASSes on post-fix code.
    const onTickMatch = source.match(/function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function|\n  \/\/\s*──)/);
    expect(onTickMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (onTickMatch) {
      const onTickBody = onTickMatch[1];
      // The activate-fire entry lookup must use playingIndex
      expect(onTickBody).toMatch(/effectiveSchedule\s*\(\s*\)\s*\[\s*playingIndex\s*\]/);
    }
  });

  it('AC13-ACTIVATE-INDEX-NEGATIVE: onTick activate-fire MUST NOT look up entry via activeEventIndex (AC1/E11S13)', () => {
    // After the fix, the activate lookup no longer uses activeEventIndex as the entry key.
    // The gate condition (activeEventIndex !== lastFiredActiveIndex) still uses activeEventIndex —
    // only the entry LOOKUP must not. We verify the lookup pattern is absent.
    // This test FAILs on pre-fix code (which has effectiveSchedule()[activeEventIndex] for activate).
    // This test PASSes on post-fix code.
    const onTickMatch = source.match(/function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function|\n  \/\/\s*──)/);
    expect(onTickMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (onTickMatch) {
      const onTickBody = onTickMatch[1];
      // The activate-fire lookup pattern `effectiveSchedule()[activeEventIndex]` must be absent
      // in the section after the deactivate block (i.e., for the activate fire).
      // We look for the comment "Fire activate event" block to scope the check.
      const activateBlockMatch = onTickBody.match(/Fire activate event[\s\S]*?lastFiredActiveIndex\s*=/);
      expect(activateBlockMatch, 'Activate-fire block not found in onTick').toBeTruthy();
      if (activateBlockMatch) {
        expect(activateBlockMatch[0]).not.toMatch(/effectiveSchedule\s*\(\s*\)\s*\[\s*activeEventIndex\s*\]/);
      }
    }
  });

  it('AC11-DEACTIVATE-PRESERVED: onTick deactivate path still uses lastPlayingIndex (AC2/E11S13)', () => {
    // The deactivate path is unchanged: it must still reference lastPlayingIndex for the entry lookup.
    // This test verifies the deactivate path was NOT inadvertently changed.
    const onTickMatch = source.match(/function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function|\n  \/\/\s*──)/);
    expect(onTickMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (onTickMatch) {
      const onTickBody = onTickMatch[1];
      // The deactivate block must reference effectiveSchedule()[lastPlayingIndex]
      expect(onTickBody).toMatch(/effectiveSchedule\s*\(\s*\)\s*\[\s*lastPlayingIndex\s*\]/);
    }
  });
});

// ── AC15: Auto-PLAYING after Dialog confirm + countdown-tick coverage ──────────

describe('E11S10 AC15 — Auto-PLAYING after Dialog confirm (source-inspection, RED-first)', () => {
  const source = readAppSvelte();

  it('AC15-POSITIVE: handleClockSyncConfirm MUST set transportState to PLAYING', () => {
    // After the fix, confirming the ClockSyncDialog auto-starts the engine.
    // The handleClockSyncConfirm function body must contain transportState = 'PLAYING'.
    // This test FAILS on the pre-fix code and PASSES after the fix.
    const confirmFnMatch = source.match(
      /async\s+function\s+handleClockSyncConfirm[\s\S]*?(?=\n  \/\/ ──|\n  async function|\n  function)/
    );
    expect(confirmFnMatch, 'handleClockSyncConfirm function not found in App.svelte').toBeTruthy();
    if (confirmFnMatch) {
      expect(confirmFnMatch[0]).toMatch(/transportState\s*=\s*['"]PLAYING['"]/);
    }
  });

  it('AC15-POSITIVE: handleClockSyncConfirm MUST call startTick after data loads', () => {
    // The tick interval must be engaged after confirm (countdown ticks from confirm onward).
    // This test FAILS on the pre-fix code and PASSES after the fix.
    const confirmFnMatch = source.match(
      /async\s+function\s+handleClockSyncConfirm[\s\S]*?(?=\n  \/\/ ──|\n  async function|\n  function)/
    );
    expect(confirmFnMatch, 'handleClockSyncConfirm function not found in App.svelte').toBeTruthy();
    if (confirmFnMatch) {
      expect(confirmFnMatch[0]).toMatch(/startTick\s*\(\s*\)/);
    }
  });

  it('AC15-POSITIVE: App.svelte must declare audioEngineJustStarted and set it true on engine start', () => {
    // The flag must be set to true when the engine transitions to PLAYING.
    expect(source).toMatch(/audioEngineJustStarted\s*=\s*(true|\$state<boolean>\(false\))/);
  });
});
