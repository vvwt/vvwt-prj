// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ---------------------------------------------------------------------------
// Mocks — must be declared before importing the module under test
// ---------------------------------------------------------------------------

// Mock @stomp/stompjs Client
const mockActivate = vi.fn();
const mockDeactivate = vi.fn();
let capturedClientOptions: Record<string, unknown> = {};

vi.mock('@stomp/stompjs', () => {
  return {
    Client: vi.fn().mockImplementation((options: Record<string, unknown>) => {
      capturedClientOptions = options;
      return {
        activate: mockActivate,
        deactivate: mockDeactivate,
      };
    }),
  };
});

// Mock sockjs-client
vi.mock('sockjs-client', () => {
  return {
    default: vi.fn().mockReturnValue({}),
  };
});

// ---------------------------------------------------------------------------
// Module under test — imported AFTER mocks are set up
// ---------------------------------------------------------------------------

import {
  connect,
  disconnect,
  isPolling,
  MAX_RECONNECT_ATTEMPTS,
  type ConnectionStatus,
  type WsEventMessage,
  EVENT_TYPE_MATCH_RESULT_CHANGED,
  EVENT_TYPE_LAP_ADVANCED,
  EVENT_TYPE_PHASE_STATUS_CHANGED,
  EVENT_TYPE_PARTIAL_SCORE_UPDATED,
} from './websocket.js';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeCallbacks() {
  const statusHistory: ConnectionStatus[] = [];
  let fallbackCalled = false;
  const receivedEvents: WsEventMessage[] = [];

  return {
    onEvent: vi.fn((msg: WsEventMessage) => receivedEvents.push(msg)),
    onStatusChange: vi.fn((s: ConnectionStatus) => statusHistory.push(s)),
    onFallback: vi.fn(() => { fallbackCalled = true; }),
    statusHistory,
    receivedEvents,
    get fallbackCalled() { return fallbackCalled; },
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('websocket.ts — reconnect and fallback orchestration (AC7, AC9)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    mockActivate.mockClear();
    mockDeactivate.mockClear();
    capturedClientOptions = {};
    disconnect(); // reset module state before each test
  });

  afterEach(() => {
    vi.useRealTimers();
    disconnect();
  });

  // -------------------------------------------------------------------------
  // connect() basic behaviour
  // -------------------------------------------------------------------------

  it('calls onStatusChange("connecting") immediately on connect() (AC7)', () => {
    const cb = makeCallbacks();

    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    expect(cb.statusHistory[0]).toBe('connecting');
  });

  it('calls stompClient.activate() on connect()', () => {
    const cb = makeCallbacks();

    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    expect(mockActivate).toHaveBeenCalledOnce();
  });

  // -------------------------------------------------------------------------
  // Reconnect backoff (AC7)
  // -------------------------------------------------------------------------

  it('schedules reconnect with initial 1s delay after first disconnect (AC7)', () => {
    const cb = makeCallbacks();
    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    // Simulate first WebSocket close
    const onClose = capturedClientOptions['onWebSocketClose'] as () => void;
    onClose?.();

    expect(cb.statusHistory).toContain('reconnecting');

    // Advance by 999ms — reconnect should NOT have fired yet
    vi.advanceTimersByTime(999);
    // activate called only once (initial connect)
    expect(mockActivate).toHaveBeenCalledTimes(1);

    // Advance by 1ms more — reconnect should fire (1000ms = initial delay)
    vi.advanceTimersByTime(1);
    expect(mockActivate).toHaveBeenCalledTimes(2);
  });

  it('doubles the delay on each consecutive failure (AC7)', () => {
    const cb = makeCallbacks();
    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    const onClose = capturedClientOptions['onWebSocketClose'] as () => void;

    // Failure 1 → delay 1s
    onClose?.();
    vi.advanceTimersByTime(1_000);
    expect(mockActivate).toHaveBeenCalledTimes(2);

    // Failure 2 → delay 2s
    const onClose2 = capturedClientOptions['onWebSocketClose'] as () => void;
    onClose2?.();
    vi.advanceTimersByTime(1_999); // not yet
    expect(mockActivate).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(1);    // exactly 2s elapsed
    expect(mockActivate).toHaveBeenCalledTimes(3);
  });

  // -------------------------------------------------------------------------
  // Fallback trigger after MAX_RECONNECT_ATTEMPTS (AC9)
  // -------------------------------------------------------------------------

  it(`calls onFallback() after ${MAX_RECONNECT_ATTEMPTS} consecutive failures (AC9)`, () => {
    const cb = makeCallbacks();
    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    // Simulate MAX_RECONNECT_ATTEMPTS failures
    let delayMs = 1_000;
    for (let i = 0; i < MAX_RECONNECT_ATTEMPTS; i++) {
      const onClose = capturedClientOptions['onWebSocketClose'] as () => void;
      onClose?.();
      if (i < MAX_RECONNECT_ATTEMPTS - 1) {
        // advance timer to trigger the reconnect
        vi.advanceTimersByTime(delayMs);
        delayMs = Math.min(delayMs * 2, 30_000);
      }
    }

    // After MAX_RECONNECT_ATTEMPTS failures, fallback is triggered
    expect(cb.onFallback).toHaveBeenCalledOnce();
    expect(cb.statusHistory).toContain('polling');
    expect(isPolling()).toBe(true);
  });

  it('does not schedule further reconnects once in fallback mode (AC9)', () => {
    const cb = makeCallbacks();
    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    // Exhaust reconnect attempts
    let delayMs = 1_000;
    for (let i = 0; i < MAX_RECONNECT_ATTEMPTS; i++) {
      const onClose = capturedClientOptions['onWebSocketClose'] as () => void;
      onClose?.();
      if (i < MAX_RECONNECT_ATTEMPTS - 1) {
        vi.advanceTimersByTime(delayMs);
        delayMs = Math.min(delayMs * 2, 30_000);
      }
    }

    const activateCountAfterFallback = mockActivate.mock.calls.length;

    // Further closes should NOT trigger additional activates
    const onClose = capturedClientOptions['onWebSocketClose'] as () => void;
    onClose?.();
    vi.advanceTimersByTime(60_000);

    expect(mockActivate).toHaveBeenCalledTimes(activateCountAfterFallback);
  });

  // -------------------------------------------------------------------------
  // disconnect()
  // -------------------------------------------------------------------------

  it('calls deactivate() and onStatusChange("disconnected") on disconnect()', () => {
    const cb = makeCallbacks();
    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    disconnect();

    expect(mockDeactivate).toHaveBeenCalledOnce();
    expect(cb.statusHistory).toContain('disconnected');
  });

  it('resets isPolling() to false after disconnect()', () => {
    const cb = makeCallbacks();
    connect('token-1', 'tenant-a', cb.onEvent, cb.onStatusChange, cb.onFallback);

    // Exhaust reconnects to enter polling mode
    let delayMs = 1_000;
    for (let i = 0; i < MAX_RECONNECT_ATTEMPTS; i++) {
      const onClose = capturedClientOptions['onWebSocketClose'] as () => void;
      onClose?.();
      if (i < MAX_RECONNECT_ATTEMPTS - 1) {
        vi.advanceTimersByTime(delayMs);
        delayMs = Math.min(delayMs * 2, 30_000);
      }
    }
    expect(isPolling()).toBe(true);

    disconnect();
    expect(isPolling()).toBe(false);
  });

  // -------------------------------------------------------------------------
  // Event type constants (smoke test — ensures exports are correct)
  // -------------------------------------------------------------------------

  it('exports correct event type constants', () => {
    expect(EVENT_TYPE_MATCH_RESULT_CHANGED).toBe('MATCH_RESULT_CHANGED');
    expect(EVENT_TYPE_LAP_ADVANCED).toBe('LAP_ADVANCED');
    expect(EVENT_TYPE_PHASE_STATUS_CHANGED).toBe('PHASE_STATUS_CHANGED');
  });

  it('exports EVENT_TYPE_PARTIAL_SCORE_UPDATED = "PARTIAL_SCORE_UPDATED" (E65S02 AC7)', () => {
    expect(EVENT_TYPE_PARTIAL_SCORE_UPDATED).toBe('PARTIAL_SCORE_UPDATED');
  });
});
