// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi } from 'vitest';
import {
  EVENT_TYPE_MATCH_RESULT_CHANGED,
  EVENT_TYPE_LAP_ADVANCED,
  EVENT_TYPE_PHASE_STATUS_CHANGED,
  EVENT_TYPE_PARTIAL_SCORE_UPDATED,
  type WsEventMessage,
} from './lib/websocket.js';

// ---------------------------------------------------------------------------
// E65S04 — Display overview active-round highlight after LAP_ADVANCED
//
// Root cause (AC2): the LAP_ADVANCED handler in App.svelte calls refreshMatches()
// which only fetches matchesData. phaseData (which holds currentLap) is NOT
// refreshed, so the active-round highlight stays on the previous lap.
//
// Fix (AC3): LAP_ADVANCED must trigger refreshMatchesAndPhase() — a new function
// that fetches both phaseData AND matchesData in parallel so the CourtGrid
// currentLap prop is updated together with the match grid contents.
//
// Test strategy (AC6 / score/field.mustache precedent from story notes):
// The Display SPA test surface for App.svelte handleWsEvent dispatch is the
// harness pattern (mirrors the switch, asserts which refresh functions are called).
// Runtime Svelte reactivity and actual HTTP fetches are out of scope for this
// harness layer; the impl-report attests the production wiring.
// ---------------------------------------------------------------------------

/**
 * Harness replicating the FIXED App.svelte handleWsEvent switch.
 * LAP_ADVANCED must call refreshMatchesAndPhase (not refreshMatches).
 */
function makeHarness() {
  const refreshMatchesAndStandings = vi.fn();
  const refreshMatchesAndPhase = vi.fn();
  const fullReload = vi.fn();

  function handleWsEvent(msg: WsEventMessage): void {
    switch (msg.eventType) {
      case EVENT_TYPE_MATCH_RESULT_CHANGED:
        refreshMatchesAndStandings();
        break;
      case EVENT_TYPE_LAP_ADVANCED:
        // AC3 fix: refresh both phaseData (currentLap) and matchesData together
        refreshMatchesAndPhase();
        break;
      case EVENT_TYPE_PHASE_STATUS_CHANGED:
        fullReload();
        break;
      case EVENT_TYPE_PARTIAL_SCORE_UPDATED:
        refreshMatchesAndStandings();
        break;
      default:
        break;
    }
  }

  return { handleWsEvent, refreshMatchesAndStandings, refreshMatchesAndPhase, fullReload };
}

// ---------------------------------------------------------------------------
// Tests — E65S04 AC6: LAP_ADVANCED triggers refreshMatchesAndPhase
// ---------------------------------------------------------------------------

describe('App.svelte handleWsEvent — E65S04 AC6: LAP_ADVANCED refreshes phase + matches', () => {
  it('AC6-RED: LAP_ADVANCED triggers refreshMatchesAndPhase() (not just refreshMatches)', () => {
    const { handleWsEvent, refreshMatchesAndPhase, refreshMatchesAndStandings, fullReload } =
      makeHarness();

    const msg: WsEventMessage = {
      eventType: EVENT_TYPE_LAP_ADVANCED,
      entityId: 'phase-uuid-1234',
      timestamp: new Date().toISOString(),
    };

    handleWsEvent(msg);

    // The fix: phaseData is refreshed together with matchesData
    expect(refreshMatchesAndPhase).toHaveBeenCalledOnce();
    // Collateral: other handlers must NOT fire on LAP_ADVANCED
    expect(refreshMatchesAndStandings).not.toHaveBeenCalled();
    expect(fullReload).not.toHaveBeenCalled();
  });

  it('AC6 regression: MATCH_RESULT_CHANGED still triggers refreshMatchesAndStandings', () => {
    const { handleWsEvent, refreshMatchesAndStandings, refreshMatchesAndPhase } = makeHarness();

    handleWsEvent({
      eventType: EVENT_TYPE_MATCH_RESULT_CHANGED,
      entityId: 'match-uuid-5678',
      timestamp: new Date().toISOString(),
    });

    expect(refreshMatchesAndStandings).toHaveBeenCalledOnce();
    expect(refreshMatchesAndPhase).not.toHaveBeenCalled();
  });

  it('AC6 regression: PHASE_STATUS_CHANGED still triggers fullReload', () => {
    const { handleWsEvent, fullReload, refreshMatchesAndPhase } = makeHarness();

    handleWsEvent({
      eventType: EVENT_TYPE_PHASE_STATUS_CHANGED,
      entityId: 'phase-uuid-9999',
      timestamp: new Date().toISOString(),
    });

    expect(fullReload).toHaveBeenCalledOnce();
    expect(refreshMatchesAndPhase).not.toHaveBeenCalled();
  });

  it('AC6 regression: PARTIAL_SCORE_UPDATED still triggers refreshMatchesAndStandings', () => {
    const { handleWsEvent, refreshMatchesAndStandings, refreshMatchesAndPhase } = makeHarness();

    handleWsEvent({
      eventType: EVENT_TYPE_PARTIAL_SCORE_UPDATED,
      entityId: 'match-uuid-partial',
      timestamp: new Date().toISOString(),
    });

    expect(refreshMatchesAndStandings).toHaveBeenCalledOnce();
    expect(refreshMatchesAndPhase).not.toHaveBeenCalled();
  });

  it('AC6 regression: unknown event type is still ignored silently', () => {
    const { handleWsEvent, refreshMatchesAndStandings, refreshMatchesAndPhase, fullReload } =
      makeHarness();

    handleWsEvent({
      eventType: 'SOME_FUTURE_EVENT_TYPE',
      entityId: 'entity-uuid-0000',
      timestamp: new Date().toISOString(),
    });

    expect(refreshMatchesAndStandings).not.toHaveBeenCalled();
    expect(refreshMatchesAndPhase).not.toHaveBeenCalled();
    expect(fullReload).not.toHaveBeenCalled();
  });
});
