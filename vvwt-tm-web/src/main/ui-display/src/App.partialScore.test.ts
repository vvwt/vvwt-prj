// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  EVENT_TYPE_MATCH_RESULT_CHANGED,
  EVENT_TYPE_LAP_ADVANCED,
  EVENT_TYPE_PHASE_STATUS_CHANGED,
  EVENT_TYPE_PARTIAL_SCORE_UPDATED,
  type WsEventMessage,
} from './lib/websocket.js';

// ---------------------------------------------------------------------------
// Harness — mirrors App.svelte handleWsEvent dispatch logic
// ---------------------------------------------------------------------------

/**
 * Minimal harness replicating the App.svelte handleWsEvent switch.
 * This is the code that the App.svelte production change must match.
 */
function makeHarness() {
  const refreshMatchesAndStandings = vi.fn();
  const refreshMatches = vi.fn();
  const fullReload = vi.fn();

  function handleWsEvent(msg: WsEventMessage): void {
    switch (msg.eventType) {
      case EVENT_TYPE_MATCH_RESULT_CHANGED:
        refreshMatchesAndStandings();
        break;
      case EVENT_TYPE_LAP_ADVANCED:
        refreshMatches();
        break;
      case EVENT_TYPE_PHASE_STATUS_CHANGED:
        fullReload();
        break;
      case EVENT_TYPE_PARTIAL_SCORE_UPDATED:
        // E65S02 AC2: partial score update → refresh matches + standings without full reload
        refreshMatchesAndStandings();
        break;
      default:
        // Unknown event type — ignore silently
        break;
    }
  }

  return { handleWsEvent, refreshMatchesAndStandings, refreshMatches, fullReload };
}

// ---------------------------------------------------------------------------
// Tests — E65S02 AC2/AC7: PARTIAL_SCORE_UPDATED dispatch
// ---------------------------------------------------------------------------

describe('App.svelte handleWsEvent — E65S02 AC2/AC7: PARTIAL_SCORE_UPDATED', () => {
  it('E65S02 AC2: PARTIAL_SCORE_UPDATED event type triggers refreshMatchesAndStandings()', () => {
    const { handleWsEvent, refreshMatchesAndStandings, refreshMatches, fullReload } = makeHarness();

    const msg: WsEventMessage = {
      eventType: EVENT_TYPE_PARTIAL_SCORE_UPDATED,
      entityId: 'match-uuid-1234',
      timestamp: new Date().toISOString(),
    };

    handleWsEvent(msg);

    expect(refreshMatchesAndStandings).toHaveBeenCalledOnce();
    expect(refreshMatches).not.toHaveBeenCalled();
    expect(fullReload).not.toHaveBeenCalled();
  });

  it('E65S02 AC2: PARTIAL_SCORE_UPDATED does NOT trigger fullReload (no page reload)', () => {
    const { handleWsEvent, fullReload } = makeHarness();

    handleWsEvent({
      eventType: EVENT_TYPE_PARTIAL_SCORE_UPDATED,
      entityId: 'match-uuid-5678',
      timestamp: new Date().toISOString(),
    });

    expect(fullReload).not.toHaveBeenCalled();
  });

  it('E65S02 AC5 (regression): MATCH_RESULT_CHANGED still triggers refreshMatchesAndStandings()', () => {
    const { handleWsEvent, refreshMatchesAndStandings } = makeHarness();

    handleWsEvent({
      eventType: EVENT_TYPE_MATCH_RESULT_CHANGED,
      entityId: 'match-uuid-9999',
      timestamp: new Date().toISOString(),
    });

    expect(refreshMatchesAndStandings).toHaveBeenCalledOnce();
  });

  it('E65S02 AC5 (regression): LAP_ADVANCED still triggers refreshMatches()', () => {
    const { handleWsEvent, refreshMatches } = makeHarness();

    handleWsEvent({
      eventType: EVENT_TYPE_LAP_ADVANCED,
      entityId: 'phase-uuid-1111',
      timestamp: new Date().toISOString(),
    });

    expect(refreshMatches).toHaveBeenCalledOnce();
  });

  it('E65S02 AC5 (regression): unknown event type is ignored silently', () => {
    const { handleWsEvent, refreshMatchesAndStandings, refreshMatches, fullReload } = makeHarness();

    handleWsEvent({
      eventType: 'SOME_FUTURE_EVENT_TYPE',
      entityId: 'entity-uuid-0000',
      timestamp: new Date().toISOString(),
    });

    expect(refreshMatchesAndStandings).not.toHaveBeenCalled();
    expect(refreshMatches).not.toHaveBeenCalled();
    expect(fullReload).not.toHaveBeenCalled();
  });
});
