<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Single match row for the court/match grid (E07S05, AC2, AC3).
   *
   * Displays: team A name, team B name, set scores (if in progress or completed),
   * and referee team name.
   *
   * AC3: receives `isCurrentLap` prop; when true, applies the current-lap highlight
   * class (darker background, bold border).
   *
   * AC2 data mapping (from E07S04 MatchEntry):
   *   - fieldNumber:    used by CourtGrid for column placement, not displayed in this row
   *   - teamAName:      left team name
   *   - teamBName:      right team name
   *   - setResults:     list of { setIndex, scoreA, scoreB }
   *   - matchStatus:    PENDING | IN_PROGRESS | COMPLETED
   *   - refereeTeamName: referee team, null if none
   */
  import { _ } from 'svelte-i18n';
  import type { MatchEntry } from '../lib/displayApi.js';

  interface Props {
    match: MatchEntry;
    isCurrentLap: boolean;
  }

  const { match, isCurrentLap }: Props = $props();

  /** Format set scores as "A:B A:B …" for completed or in-progress matches. */
  function formatSetScores(match: MatchEntry): string {
    if (match.setResults.length === 0) return '';
    return match.setResults
      .map(s => `${s.scoreA}:${s.scoreB}`)
      .join('  ');
  }

  /** Whether to show set scores (only for IN_PROGRESS or COMPLETED matches). */
  function showScores(match: MatchEntry): boolean {
    return match.matchStatus === 'IN_PROGRESS' || match.matchStatus === 'COMPLETED';
  }
</script>

<div
  class="match-row"
  class:match-row--current={isCurrentLap}
  class:match-row--completed={match.matchStatus === 'COMPLETED'}
>
  <div class="match-row__teams">
    <!-- AC2: team A name -->
    <span class="match-row__team match-row__team--a">{match.teamAName}</span>
    <span class="match-row__vs">{$_('display.overview.vs')}</span>
    <!-- AC2: team B name -->
    <span class="match-row__team match-row__team--b">{match.teamBName}</span>
  </div>

  <!-- AC2: set scores (only shown when in progress or completed) -->
  {#if showScores(match)}
    <div class="match-row__scores">{formatSetScores(match)}</div>
  {/if}

  <!-- AC2: referee team name (null if none assigned) -->
  {#if match.refereeTeamName}
    <div class="match-row__referee">
      <span class="match-row__referee-label">&bull;</span>
      <span class="match-row__referee-name">{match.refereeTeamName}</span>
    </div>
  {/if}
</div>

<style>
  .match-row {
    padding: 0.35rem 0.5rem;
    border-left: 3px solid transparent;
    border-bottom: 1px solid #e8e8e8;
    background: #fff;
    font-size: 0.9rem;
    line-height: 1.3;
  }

  /* AC3: current lap highlight — uniform background matching .round-row--active (E50S07) */
  .match-row--current {
    background: #d6e8ff;
    border-left-color: #2980b9;
    font-weight: 600;
  }

  .match-row--completed {
    opacity: 0.7;
  }

  .match-row__teams {
    display: flex;
    align-items: center;
    gap: 0.3rem;
    flex-wrap: wrap;
  }

  .match-row__team {
    flex: 1 1 0;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .match-row__team--a {
    text-align: right;
  }

  .match-row__team--b {
    text-align: left;
  }

  .match-row__vs {
    color: #999;
    font-size: 0.8rem;
    flex-shrink: 0;
  }

  .match-row__scores {
    font-size: 0.8rem;
    color: #444;
    margin-top: 0.15rem;
    font-family: monospace;
    letter-spacing: 0.05em;
    text-align: center;
  }

  .match-row__referee {
    font-size: 0.75rem;
    color: #888;
    margin-top: 0.1rem;
    display: flex;
    align-items: center;
    gap: 0.2rem;
  }

  .match-row__referee-label {
    color: #bbb;
  }

  .match-row__referee-name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
</style>
