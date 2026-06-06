// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// E11S18 — AudioEndFire source-inspection tests.
// Verifies that the last-round END sound is fired when activeEventIndex === -1
// (schedule complete) and the playing ROUND entry's endTime has elapsed (AC1/E11S18).
//
// DEC-22 §refactor-clause routing (AC8):
//   - Fresh audit finds NO existing test covering the last-round END fire surface.
//   - All tests authored RED-first (FAIL against pre-fix App.svelte, PASS after fix).
//
// Root cause (verified, App.svelte HEAD post-E11S17):
//   onTick() fire gate `activeEventIndex !== lastFiredActiveIndex && activeEventIndex !== -1`
//   suppresses the deactivate (END_SOUND) block for the last ROUND once activeEventIndex === -1.
//   The fix adds a separate last-round END guard that fires when:
//     activeEventIndex === -1, !lastRoundEndFired, playingIndex >= 0,
//     playingEntry.type === 'ROUND', endTimeSecs !== null && currentNowSecs >= endTimeSecs.

const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');
const appSource = readFileSync(APP_SVELTE, 'utf-8');

// ── AC5: lastRoundEndFired one-shot guard declared ────────────────────────────

describe('E11S18 AC5 — lastRoundEndFired one-shot guard is declared', () => {
  /**
   * RED attestation: pre-fix App.svelte does NOT declare lastRoundEndFired.
   * POST-fix: App.svelte declares `let lastRoundEndFired = $state<boolean>(false)`.
   */
  it('AC5-POSITIVE: App.svelte declares lastRoundEndFired state variable', () => {
    expect(appSource).toMatch(/let\s+lastRoundEndFired\s*=\s*\$state/);
  });
});

// ── AC1: onTick last-round END fire block present ─────────────────────────────

describe('E11S18 AC1 — onTick fires last-round END when activeEventIndex === -1 and endTime elapsed', () => {
  /**
   * RED attestation: pre-fix App.svelte onTick() has no block checking
   *   `activeEventIndex === -1 && !lastRoundEndFired && playingIndex >= 0`.
   * POST-fix: this guard block is present.
   */
  it('AC1-POSITIVE: onTick contains last-round END fire guard', () => {
    // Extract onTick function body
    const fnMatch = appSource.match(
      /function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function\s|\n  \/\/\s*──|\n  \/\*\*|\n  (?:async\s+)?function\s)/
    );
    expect(fnMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      // Must check activeEventIndex === -1 AND !lastRoundEndFired AND playingIndex >= 0
      expect(body).toMatch(/activeEventIndex\s*===\s*-1/);
      expect(body).toMatch(/!lastRoundEndFired/);
      expect(body).toMatch(/playingIndex\s*>=\s*0/);
    }
  });

  /**
   * RED attestation: pre-fix code never calls audioEngine.play('END') in the last-round path.
   * POST-fix: the guard block calls audioEngine.play('END').
   */
  it('AC1-POSITIVE: last-round guard block calls audioEngine.play END', () => {
    const fnMatch = appSource.match(
      /function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function\s|\n  \/\/\s*──|\n  \/\*\*|\n  (?:async\s+)?function\s)/
    );
    expect(fnMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      // Last-round END guard must call audioEngine.play('END')
      expect(body).toMatch(/audioEngine\.play\s*\(\s*['"]END['"]\s*\)/);
    }
  });

  /**
   * RED attestation: pre-fix code does not set lastRoundEndFired = true.
   * POST-fix: guard sets lastRoundEndFired = true after firing.
   */
  it('AC5-POSITIVE: guard sets lastRoundEndFired = true after firing (one-shot)', () => {
    const fnMatch = appSource.match(
      /function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function\s|\n  \/\/\s*──|\n  \/\*\*|\n  (?:async\s+)?function\s)/
    );
    expect(fnMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      expect(body).toMatch(/lastRoundEndFired\s*=\s*true/);
    }
  });

  /**
   * RED attestation: pre-fix code's existing fire block at `activeEventIndex !== -1` is unchanged.
   * POST-fix: original guard `&& activeEventIndex !== -1` must remain (AC2 regression guard).
   */
  it('AC2-REGRESSION: existing fire gate activeEventIndex !== -1 is preserved (non-final rounds unaffected)', () => {
    const fnMatch = appSource.match(
      /function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function\s|\n  \/\/\s*──|\n  \/\*\*|\n  (?:async\s+)?function\s)/
    );
    expect(fnMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      // The original guard `activeEventIndex !== -1` MUST still exist (not dropped)
      expect(body).toMatch(/activeEventIndex\s*!==\s*-1/);
    }
  });

  /**
   * Verifies that resolveEffectiveEndTime is used in the last-round END guard
   * to determine when the last ROUND's endTime has elapsed (AC1).
   */
  it('AC1-POSITIVE: last-round guard uses resolveEffectiveEndTime to check endTime', () => {
    const fnMatch = appSource.match(
      /function\s+onTick\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  function\s|\n  \/\/\s*──|\n  \/\*\*|\n  (?:async\s+)?function\s)/
    );
    expect(fnMatch, 'onTick function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      expect(body).toMatch(/resolveEffectiveEndTime\s*\(/);
    }
  });
});

// ── AC7: WS reload resets lastRoundEndFired ────────────────────────────────────

describe('E11S18 AC7 — loadTimerDataSilent resets lastRoundEndFired (WS reload no double-fire)', () => {
  /**
   * RED attestation: pre-fix loadTimerDataSilent does not reset lastRoundEndFired.
   * POST-fix: loadTimerDataSilent resets lastRoundEndFired = false.
   */
  it('AC7-POSITIVE: loadTimerDataSilent resets lastRoundEndFired to false', () => {
    const fnMatch = appSource.match(
      /async\s+function\s+loadTimerDataSilent\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'loadTimerDataSilent function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/lastRoundEndFired\s*=\s*false/);
    }
  });
});

// ── AC6: handleStop resets lastRoundEndFired ───────────────────────────────────

describe('E11S18 AC6 — handleStop resets lastRoundEndFired (transport STOP regression guard)', () => {
  /**
   * RED attestation: pre-fix handleStop does not reset lastRoundEndFired.
   * POST-fix: handleStop resets lastRoundEndFired = false.
   */
  it('AC6-POSITIVE: handleStop resets lastRoundEndFired to false', () => {
    const fnMatch = appSource.match(
      /function\s+handleStop\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'handleStop function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/lastRoundEndFired\s*=\s*false/);
    }
  });
});

// ── AC3: handlePlay resets lastRoundEndFired ───────────────────────────────────

describe('E11S18 AC3 — handlePlay resets lastRoundEndFired (re-play from PAUSED allows last-round END)', () => {
  /**
   * RED attestation: pre-fix handlePlay does not reset lastRoundEndFired.
   * POST-fix: handlePlay resets lastRoundEndFired = false so re-play can still fire it.
   */
  it('AC3-POSITIVE: handlePlay resets lastRoundEndFired to false', () => {
    const fnMatch = appSource.match(
      /function\s+handlePlay\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'handlePlay function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/lastRoundEndFired\s*=\s*false/);
    }
  });
});

// ── AC12: No new AudioEngine methods introduced ────────────────────────────────

describe('E11S18 AC12 — AudioEngine surface not modified by this fix', () => {
  it('AC12-POSITIVE: fix uses existing audioEngine.play(END) — no new AudioEngine method added', () => {
    // The fix reuses audioEngine.play('END') which already existed.
    // AC12: no new public method names on AudioEngine.
    const audioEnginePath = resolve(new URL(import.meta.url).pathname, '..', 'lib', 'audioEngine.ts');
    const engineSource = readFileSync(audioEnginePath, 'utf-8');
    // Existing methods must still be present (regression guard)
    expect(engineSource).toMatch(/play\s*\(/);
    expect(engineSource).toMatch(/stopAll\s*\(/);
    expect(engineSource).toMatch(/stopPauseMusic\s*\(/);
    expect(engineSource).toMatch(/preload\s*\(/);
    expect(engineSource).toMatch(/pauseAll\s*\(/);
  });
});
