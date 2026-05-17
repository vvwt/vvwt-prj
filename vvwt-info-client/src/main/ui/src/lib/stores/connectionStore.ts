// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { writable, get } from 'svelte/store';
import type { Writable } from 'svelte/store';

export interface TournamentSnapshotPayload {
  tournamentId: string;
  tenantId: string;
  sequenceNumber: bigint;
  scheduleEntries: unknown[];
  teams: unknown[];
  tournament_ended: boolean;
}

export interface ConnectionState {
  phase: 'disconnected' | 'connecting' | 'connected' | 'reconnecting';
  tournamentEnded: boolean;
  linkExpired: boolean;
  connectionLost: boolean;
  dataStale: boolean;
}

export interface ConnectionStoreApi {
  subscribe: Writable<ConnectionState>['subscribe'];
  /** Returns true if seq is non-consecutive (gap detected) → reconnect required (AC3). */
  shouldReconnectOnGap(lastKnownSeq: number | bigint, incomingSeq: number | bigint): boolean;
  /** Initial backoff: 1000ms (AC14). */
  getInitialBackoffMs(): number;
  /** Next backoff with full jitter, cap 60s (AC14). */
  computeNextBackoffMs(currentBackoffMs: number): number;
  /** Current backoff value. */
  currentBackoffMs(): number;
  /** Called after successful WS connection — resets backoff (AC14). */
  onConnectSuccess(): void;
  /** Called when WS connect is established (starts stale-data timer). */
  onConnected(): void;
  /** Called when a successful update arrives — clears stale indicator (AC15). */
  onSuccessfulUpdate(): void;
  /** Called when WS connection fails. */
  onWsFailed(): void;
  /** Called when HTTP poll fails. */
  onPollFailed(): void;
  /** Sets tournamentEnded flag from snapshot (AC5). */
  applySnapshot(snapshot: TournamentSnapshotPayload): void;
  /** Sets linkExpired=true on 410 response (AC6). */
  onGoneResponse(): void;
}

const INITIAL_BACKOFF_MS = 1000;
const BACKOFF_FACTOR = 2;
const MAX_BACKOFF_MS = 60_000;
const STALE_THRESHOLD_MS = 60_000; // AC15: 60s

export function createConnectionStore(config: {
  wsUrl: string;
  pollUrl: string;
}): ConnectionStoreApi {
  const store = writable<ConnectionState>({
    phase: 'disconnected',
    tournamentEnded: false,
    linkExpired: false,
    connectionLost: false,
    dataStale: false,
  });

  // Use config to avoid unused-var lint error; actual WS lifecycle is in App.svelte
  void config;

  let _currentBackoffMs = INITIAL_BACKOFF_MS;
  let _wsFailed = false;
  let _pollFailed = false;
  let _staleTimer: ReturnType<typeof setTimeout> | null = null;

  function resetStaleTimer(): void {
    if (_staleTimer !== null) {
      clearTimeout(_staleTimer);
    }
    store.update((s) => ({ ...s, dataStale: false }));
    _staleTimer = setTimeout(() => {
      const state = get(store);
      // Only mark stale if currently connected (not in connection-lost state)
      if (!state.connectionLost && state.phase === 'connected') {
        store.update((s) => ({ ...s, dataStale: true }));
      }
    }, STALE_THRESHOLD_MS);
  }

  return {
    subscribe: store.subscribe,

    shouldReconnectOnGap(
      lastKnownSeq: number | bigint,
      incomingSeq: number | bigint,
    ): boolean {
      // AC3: gap = incoming seq is NOT consecutive (lastKnownSeq + 1)
      const expected = BigInt(lastKnownSeq) + 1n;
      return BigInt(incomingSeq) !== expected;
    },

    getInitialBackoffMs(): number {
      return INITIAL_BACKOFF_MS;
    },

    computeNextBackoffMs(currentBackoffMs: number): number {
      // Full jitter: random [0, min(cap, currentBackoff * factor)] (AC14)
      const uncapped = currentBackoffMs * BACKOFF_FACTOR;
      const capped = Math.min(uncapped, MAX_BACKOFF_MS);
      return Math.random() * capped;
    },

    currentBackoffMs(): number {
      return _currentBackoffMs;
    },

    onConnectSuccess(): void {
      _currentBackoffMs = INITIAL_BACKOFF_MS;
      _wsFailed = false;
      _pollFailed = false;
      store.update((s) => ({ ...s, connectionLost: false, phase: 'connected' }));
    },

    onConnected(): void {
      store.update((s) => ({ ...s, phase: 'connected', connectionLost: false }));
      resetStaleTimer();
    },

    onSuccessfulUpdate(): void {
      store.update((s) => ({ ...s, dataStale: false }));
      resetStaleTimer();
    },

    onWsFailed(): void {
      _wsFailed = true;
      store.update((s) => ({
        ...s,
        phase: 'disconnected',
        connectionLost: _wsFailed && _pollFailed,
      }));
    },

    onPollFailed(): void {
      _pollFailed = true;
      store.update((s) => ({
        ...s,
        connectionLost: _wsFailed && _pollFailed,
      }));
    },

    applySnapshot(snapshot: TournamentSnapshotPayload): void {
      store.update((s) => ({
        ...s,
        tournamentEnded: snapshot.tournament_ended,
      }));
    },

    onGoneResponse(): void {
      store.update((s) => ({
        ...s,
        linkExpired: true,
        phase: 'disconnected',
      }));
    },
  };
}
