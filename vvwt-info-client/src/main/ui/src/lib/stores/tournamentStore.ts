/**
 * Tournament state store — holds the current tournament snapshot.
 *
 * Stores the latest snapshot received from the server (WS or poll fallback).
 * Delta application is handled at the WS message layer in App.svelte.
 * lastKnownSeq is used by the connection store for gap detection (AC3).
 *
 * Story: E38S08. DEC-22: authored after RED-first tests.
 */

import { writable } from 'svelte/store';
import type { Writable } from 'svelte/store';

export interface ScheduleEntryBase {
  type: string;
  id: string;
}

export interface MatchEntry extends ScheduleEntryBase {
  type: 'MATCH';
  homeTeamName: string;
  awayTeamName: string;
  roundNumber: number;
}

export interface SpecialAppointmentEntry extends ScheduleEntryBase {
  type: 'SPECIAL_APPOINTMENT';
  title: string;
}

export interface PauseEntry extends ScheduleEntryBase {
  type: 'PAUSE';
  label: string;
}

export type ScheduleEntry = MatchEntry | SpecialAppointmentEntry | PauseEntry;

export interface TeamEntry {
  teamId: string;
  name: string;
  number: number;
}

export interface TournamentSnapshot {
  tournamentId: string;
  tenantId: string;
  sequenceNumber: bigint;
  scheduleEntries: ScheduleEntry[];
  teams: TeamEntry[];
  tournament_ended: boolean;
}

export interface TournamentState {
  snapshot: TournamentSnapshot | null;
  lastKnownSeq: bigint;
}

export interface TournamentStoreApi {
  subscribe: Writable<TournamentState>['subscribe'];
  applySnapshot(snapshot: TournamentSnapshot): void;
}

export function createTournamentStore(): TournamentStoreApi {
  const store = writable<TournamentState>({
    snapshot: null,
    lastKnownSeq: -1n,
  });

  return {
    subscribe: store.subscribe,

    applySnapshot(snapshot: TournamentSnapshot): void {
      store.update(() => ({
        snapshot,
        lastKnownSeq: BigInt(snapshot.sequenceNumber),
      }));
    },
  };
}
