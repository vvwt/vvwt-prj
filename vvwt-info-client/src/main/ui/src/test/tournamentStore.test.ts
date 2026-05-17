// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { get } from 'svelte/store';
import { createTournamentStore } from '../lib/stores/tournamentStore.js';

describe('tournamentStore', () => {
  it('initial state is null snapshot', () => {
    const store = createTournamentStore();
    const state = get(store);
    expect(state.snapshot).toBeNull();
    expect(state.lastKnownSeq).toBe(-1n);
  });

  it('applySnapshot sets snapshot and updates lastKnownSeq', () => {
    const store = createTournamentStore();
    const snapshot = {
      tournamentId: 't1',
      tenantId: 'ten1',
      sequenceNumber: 5n,
      scheduleEntries: [],
      teams: [],
      tournament_ended: false,
    };
    store.applySnapshot(snapshot);
    const state = get(store);
    expect(state.snapshot).toEqual(snapshot);
    expect(state.lastKnownSeq).toBe(5n);
  });

  it('empty snapshot is handled without error', () => {
    const store = createTournamentStore();
    const snapshot = {
      tournamentId: 't2',
      tenantId: 'ten2',
      sequenceNumber: 0n,
      scheduleEntries: [],
      teams: [],
      tournament_ended: false,
    };
    store.applySnapshot(snapshot);
    const state = get(store);
    expect(state.snapshot?.scheduleEntries).toHaveLength(0);
  });

  it('tournament_ended:true is preserved from snapshot (AC5)', () => {
    const store = createTournamentStore();
    const snapshot = {
      tournamentId: 't3',
      tenantId: 'ten3',
      sequenceNumber: 2n,
      scheduleEntries: [],
      teams: [],
      tournament_ended: true,
    };
    store.applySnapshot(snapshot);
    const state = get(store);
    expect(state.snapshot?.tournament_ended).toBe(true);
  });
});
