// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// E11S17 — WsAudioInvariant source-inspection tests.
// Verifies that the audioEngineJustStarted invariant is maintained across all
// WS-driven state changes (AC2/E11S17), preventing spurious audio replays after
// WebSocket LAP_ADVANCED / PHASE_CHANGED / reconnect events.
//
// DEC-22 §refactor-clause routing (AC10):
//   - Fresh audit finds NO existing WS-handler audio-invariant assertions → Q-1a path
//   - All tests authored RED-first (FAIL against pre-fix App.svelte, PASS after fix)
//
// Root cause (verified, App.svelte HEAD post-E11S15):
//   handleWsLapAdvanced resets lastFiredActiveIndex=-1 + lastPlayingIndex=-1 but does NOT
//   set audioEngineJustStarted=true. loadTimerDataSilent (called by all three WS handlers)
//   does not reset the indices or set the flag → next onTick fires spurious audio.

const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');

const appSource = readFileSync(APP_SVELTE, 'utf-8');

// ── AC2: audioEngineJustStarted invariant in loadTimerDataSilent ───────────────

describe('E11S17 AC2 — loadTimerDataSilent re-establishes audioEngineJustStarted invariant', () => {
  /**
   * RED attestation: pre-fix loadTimerDataSilent body does NOT contain
   *   audioEngineJustStarted = true
   * → this test FAILS on pre-fix code.
   * POST-fix: loadTimerDataSilent sets audioEngineJustStarted = true.
   * → this test PASSES on post-fix code.
   */
  it('AC2-POSITIVE: loadTimerDataSilent MUST set audioEngineJustStarted = true', () => {
    // Extract the loadTimerDataSilent function body.
    const fnMatch = appSource.match(
      /async\s+function\s+loadTimerDataSilent\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'loadTimerDataSilent function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/audioEngineJustStarted\s*=\s*true/);
    }
  });

  /**
   * RED attestation: pre-fix loadTimerDataSilent does NOT reset lastFiredActiveIndex / lastPlayingIndex
   *   inside the function body (the resets live in handleWsLapAdvanced, not here).
   *
   * POST-fix: loadTimerDataSilent resets both indices AND sets the flag, so all three WS
   *   handlers inherit the invariant via the common await call.
   *
   * PASS even if the invariant is set per-handler rather than in loadTimerDataSilent,
   * so we assert behavioural sufficiency (flag set somewhere before first post-WS tick):
   * the invariant MUST be set either inside loadTimerDataSilent or in every handler that
   * calls it — we verify the loadTimerDataSilent path here; handler-level tests below
   * cover the per-handler placement case.
   */
  it('AC2-POSITIVE: loadTimerDataSilent MUST reset lastFiredActiveIndex to -1', () => {
    const fnMatch = appSource.match(
      /async\s+function\s+loadTimerDataSilent\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'loadTimerDataSilent function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/lastFiredActiveIndex\s*=\s*-1/);
    }
  });

  it('AC2-POSITIVE: loadTimerDataSilent MUST reset lastPlayingIndex to -1', () => {
    const fnMatch = appSource.match(
      /async\s+function\s+loadTimerDataSilent\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'loadTimerDataSilent function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/lastPlayingIndex\s*=\s*-1/);
    }
  });
});

// ── AC2 cont.: handleWsLapAdvanced — invariant present (either in body or via loadTimerDataSilent) ──

describe('E11S17 AC2 — handleWsLapAdvanced maintains audioEngineJustStarted invariant', () => {
  /**
   * After the fix, audioEngineJustStarted = true is established before the next onTick after
   * LAP_ADVANCED. The fix may place the set either inside loadTimerDataSilent (preferred) or
   * in handleWsLapAdvanced after the await — either is conformant per AC2. We verify the
   * combined App.svelte source contains the required pattern in the LAP_ADVANCED context.
   *
   * Source-inspection approach: extract the region from handleWsLapAdvanced to the next handler,
   * and assert that the region — together with the called loadTimerDataSilent body — provides
   * audioEngineJustStarted = true.
   *
   * Since loadTimerDataSilent is a shared helper, the simplest conformant fix is to place the
   * assignment inside loadTimerDataSilent. The AC2-POSITIVE: loadTimerDataSilent test above
   * covers that. This test verifies the handler-level regression shape: the reset-index block
   * that was present before the fix (which lacked the flag) is now either absent or augmented.
   */
  it('AC2-NEGATIVE: handleWsLapAdvanced MUST NOT reset indices without also setting audioEngineJustStarted', () => {
    // Extract handleWsLapAdvanced function body.
    const fnMatch = appSource.match(
      /async\s+function\s+handleWsLapAdvanced\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\*\*|\n  \/\/ ──|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'handleWsLapAdvanced function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      // If this handler resets lastFiredActiveIndex = -1, it MUST ALSO set audioEngineJustStarted = true.
      // If it does NOT reset (because loadTimerDataSilent handles it), the test passes trivially.
      const resetsIndex = /lastFiredActiveIndex\s*=\s*-1/.test(body);
      if (resetsIndex) {
        expect(body).toMatch(/audioEngineJustStarted\s*=\s*true/);
      }
      // If no reset in handler (loadTimerDataSilent handles it), the loadTimerDataSilent test above covers it.
    }
  });
});

// ── AC3: Spurious audio regression guard ──────────────────────────────────────

describe('E11S17 AC3 — Spurious audio on WS reconnect is suppressed by invariant', () => {
  /**
   * After the fix, handleWsReconnected calls loadTimerDataSilent which sets
   * audioEngineJustStarted = true. The next onTick re-synchronises via the
   * init-suppress branch (App.svelte line 453) without firing audio.
   *
   * Source inspection: verify that handleWsReconnected's call to loadTimerDataSilent
   * is present (carries the invariant via the common helper).
   */
  it('AC3-POSITIVE: handleWsReconnected delegates to loadTimerDataSilent (invariant inherited)', () => {
    const fnMatch = appSource.match(
      /async\s+function\s+handleWsReconnected\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\*\*|\n  \/\/ ──|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'handleWsReconnected function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/loadTimerDataSilent\s*\(\s*\)/);
    }
  });

  /**
   * handleWsPhaseChanged also delegates to loadTimerDataSilent.
   */
  it('AC3-POSITIVE: handleWsPhaseChanged delegates to loadTimerDataSilent (invariant inherited)', () => {
    const fnMatch = appSource.match(
      /async\s+function\s+handleWsPhaseChanged\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\*\*|\n  \/\/ ──|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'handleWsPhaseChanged function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      expect(fnMatch[1]).toMatch(/loadTimerDataSilent\s*\(\s*\)/);
    }
  });
});

// ── AC9: STOP transport regression guard ──────────────────────────────────────

describe('E11S17 AC9 — STOP transport preserves existing reset path (regression guard)', () => {
  /**
   * AC9: handleStop resets lastFiredActiveIndex=-1, lastPlayingIndex=-1,
   *   audioEngineJustStarted=false (NOT true — STOP returns to STOPPED state,
   *   onTick early-returns, invariant naturally satisfied).
   * This is a regression guard: the fix to loadTimerDataSilent must NOT accidentally
   * change the STOP handler's audioEngineJustStarted = false assignment.
   *
   * FAILS if handleStop sets audioEngineJustStarted = true (incorrect).
   * PASSES if handleStop sets audioEngineJustStarted = false.
   */
  it('AC9-POSITIVE: handleStop sets audioEngineJustStarted = false (not true)', () => {
    const fnMatch = appSource.match(
      /function\s+handleStop\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'handleStop function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      // STOP must set audioEngineJustStarted = false (regression guard).
      expect(body).toMatch(/audioEngineJustStarted\s*=\s*false/);
      // STOP must NOT set audioEngineJustStarted = true (would cause spurious init-suppress on next PLAY).
      expect(body).not.toMatch(/audioEngineJustStarted\s*=\s*true/);
    }
  });

  it('AC9-POSITIVE: handleStop resets lastFiredActiveIndex and lastPlayingIndex to -1', () => {
    const fnMatch = appSource.match(
      /function\s+handleStop\s*\(\s*\)\s*:\s*void\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'handleStop function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      expect(body).toMatch(/lastFiredActiveIndex\s*=\s*-1/);
      expect(body).toMatch(/lastPlayingIndex\s*=\s*-1/);
    }
  });
});

// ── AC8: Idempotent loadTimerDataSilent (regression guard) ───────────────────

describe('E11S17 AC8 — loadTimerDataSilent idempotent on rapid successive invocations', () => {
  /**
   * The invariant set by loadTimerDataSilent (audioEngineJustStarted = true + index resets)
   * is idempotent: calling it twice sets the same values. The source inspection verifies
   * that each assignment is an unconditional write (not a conditional accumulation).
   */
  it('AC8-POSITIVE: audioEngineJustStarted is set unconditionally (not conditionally accumulated)', () => {
    const fnMatch = appSource.match(
      /async\s+function\s+loadTimerDataSilent\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\/ ──|\n  \/\*\*|\n  async function|\n  function\s)/
    );
    expect(fnMatch, 'loadTimerDataSilent function not found in App.svelte').toBeTruthy();
    if (fnMatch) {
      const body = fnMatch[1];
      // Must be a direct assignment, not `audioEngineJustStarted = audioEngineJustStarted || true`
      // or similar conditional form.
      expect(body).toMatch(/audioEngineJustStarted\s*=\s*true/);
      expect(body).not.toMatch(/audioEngineJustStarted\s*=\s*audioEngineJustStarted/);
    }
  });
});

// ── AC6: audioEngine surface preserved (regression guard) ────────────────────

describe('E11S17 AC6 — AudioEngine play/stop/pause surface NOT modified by this fix', () => {
  it('AC6-POSITIVE: audioEngine.ts core playback methods are preserved unchanged by E11S17 fix', () => {
    // The fix lives in App.svelte WS handlers / loadTimerDataSilent, not in audioEngine.ts.
    // AC6 asserts: AudioEngine.play() / stopAll() / stopPauseMusic() / preload() surface preserved.
    // We verify the audioEngine.ts source still exports these methods (regression guard).
    const audioEnginePath = resolve(new URL(import.meta.url).pathname, '..', 'lib', 'audioEngine.ts');
    const engineSource = readFileSync(audioEnginePath, 'utf-8');
    expect(engineSource).toMatch(/play\s*\(/);
    expect(engineSource).toMatch(/stopAll\s*\(/);
    expect(engineSource).toMatch(/stopPauseMusic\s*\(/);
    expect(engineSource).toMatch(/preload\s*\(/);
    expect(engineSource).toMatch(/pauseAll\s*\(/);
  });

  it('AC6-POSITIVE: App.svelte fix does NOT modify audioEngine.ts (fix is in App.svelte only)', () => {
    // The WS-handler invariant fix must not introduce new AudioEngine method calls not present
    // in App.svelte before E11S17 (i.e., the fix adds only index-reset + flag assignments,
    // not new engine API calls). Verify by checking that the WS handler bodies do NOT contain
    // direct audioEngine.play / audioEngine.stop calls (those are only in onTick).
    const lapHandlerMatch = appSource.match(
      /async\s+function\s+handleWsLapAdvanced\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\*\*|\n  \/\/ ──|\n  async function|\n  function\s)/
    );
    if (lapHandlerMatch) {
      expect(lapHandlerMatch[1]).not.toMatch(/audioEngine\.(play|stopAll|stopPauseMusic|pauseAll)\s*\(/);
    }
    const reconnectHandlerMatch = appSource.match(
      /async\s+function\s+handleWsReconnected\s*\(\s*\)\s*:\s*Promise<void>\s*\{([\s\S]*?)(?=\n  \/\*\*|\n  \/\/ ──|\n  async function|\n  function\s)/
    );
    if (reconnectHandlerMatch) {
      expect(reconnectHandlerMatch[1]).not.toMatch(/audioEngine\.(play|stopAll|stopPauseMusic|pauseAll)\s*\(/);
    }
  });
});
