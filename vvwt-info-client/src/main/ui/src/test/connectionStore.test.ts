/**
 * AC1 (testing), AC3 (sequence-gap reconnect), AC14 (backoff), AC15 (stale indicator).
 * DEC-22 Iron Law: written BEFORE connectionStore.ts exists (RED state).
 * Story: E38S08.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { get } from 'svelte/store';
import {
  createConnectionStore,
  type ConnectionState,
} from '../lib/stores/connectionStore.js';

describe('connectionStore', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('initial state is disconnected', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    const state = get(store);
    expect(state.phase).toBe('disconnected');
    expect(state.tournamentEnded).toBe(false);
    expect(state.linkExpired).toBe(false);
  });

  it('single state machine: gap triggers reconnect NOT buffer (AC3)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    // Simulate known seq = 5, receive delta with seq = 8 → gap
    const result = store.shouldReconnectOnGap(5, 8);
    expect(result).toBe(true);
  });

  it('no gap: consecutive seq does not trigger reconnect (AC3)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    const result = store.shouldReconnectOnGap(5, 6);
    expect(result).toBe(false);
  });

  it('backoff initial value is 1000ms (AC14)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    expect(store.getInitialBackoffMs()).toBe(1000);
  });

  it('backoff doubles with factor 2 (AC14)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    const next = store.computeNextBackoffMs(1000);
    // Full jitter: [0, 2000] — we verify it's within range and > 0 on average
    expect(next).toBeGreaterThanOrEqual(0);
    expect(next).toBeLessThanOrEqual(2000);
  });

  it('backoff caps at 60000ms (AC14)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    const capped = store.computeNextBackoffMs(60000);
    expect(capped).toBeLessThanOrEqual(60000);
  });

  it('reset to initial backoff after successful connection (AC14)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    store.onConnectSuccess();
    expect(store.currentBackoffMs()).toBe(1000);
  });

  it('sets tournamentEnded=true when snapshot has tournament_ended:true (AC5)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    store.applySnapshot({ tournamentId: 't', tenantId: 'ten', sequenceNumber: 1n, scheduleEntries: [], teams: [], tournament_ended: true });
    const state = get(store);
    expect(state.tournamentEnded).toBe(true);
  });

  it('sets linkExpired=true on 410 response (AC6)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    store.onGoneResponse();
    const state = get(store);
    expect(state.linkExpired).toBe(true);
  });

  it('distinct stale indicator separate from connection-lost (AC15)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    // Simulate 61s without update while connected
    store.onConnected();
    vi.advanceTimersByTime(61_000);
    const state = get(store);
    expect(state.dataStale).toBe(true);
    // Connection-lost indicator should NOT be active (we are connected)
    expect(state.connectionLost).toBe(false);
  });

  it('stale indicator clears on successful update (AC15)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    store.onConnected();
    vi.advanceTimersByTime(61_000);
    store.onSuccessfulUpdate();
    const state = get(store);
    expect(state.dataStale).toBe(false);
  });

  it('connection-lost indicator appears when WS+poll both fail (AC14)', () => {
    const store = createConnectionStore({ wsUrl: 'ws://test/stream/tok/team', pollUrl: '/poll/tok/team' });
    store.onWsFailed();
    store.onPollFailed();
    const state = get(store);
    expect(state.connectionLost).toBe(true);
  });
});
