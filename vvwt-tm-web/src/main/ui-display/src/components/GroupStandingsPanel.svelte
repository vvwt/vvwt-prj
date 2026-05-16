<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Right column of the Gesamtübersicht: group standings panel (E07S05, AC4).
   *
   * Renders one table per group. Each table has:
   *   - Header: "Gruppe {groupNumber}"
   *   - Columns: position (rank), Mannschaft (team name), P (points), S (sets won:lost),
   *     B (balls won:lost)
   *   - Rows: one per team, ordered by position (1 = best, per E07S04 D-33 sort)
   *
   * AC4 column mapping from E07S04 TeamRanking DTO:
   *   position    → rank column
   *   teamName    → Mannschaft column
   *   points      → P column
   *   setsWon / setsLost  → S column (shown as "won:lost")
   *   ballsWon / ballsLost → B column (shown as "won:lost")
   *
   * Matches legacy grouptable layout from vvw-tournaments-info-ui/controls/grouptable/view.stache.
   */
  import { _ } from 'svelte-i18n';
  import type { DisplayGroupStandings } from '../lib/displayApi.js';

  interface Props {
    standingsData: DisplayGroupStandings;
  }

  const { standingsData }: Props = $props();
</script>

<div class="standings-panel">
  {#each standingsData.groups as group (group.groupNumber)}
    <div class="standings-panel__group">
      <!-- AC4: group header "Gruppe N" -->
      <div class="standings-panel__group-header">
        {$_('display.overview.group')}: {group.groupNumber}
      </div>

      <!-- AC4: column headers matching legacy grouptable: pos, Mannschaft, P, S, B -->
      <div class="standings-panel__row standings-panel__row--header">
        <div class="standings-panel__cell standings-panel__cell--pos">&nbsp;</div>
        <div class="standings-panel__cell standings-panel__cell--team">
          {$_('display.overview.teamColumn')}
        </div>
        <div class="standings-panel__cell standings-panel__cell--stat">
          {$_('display.overview.pointsColumn')}
        </div>
        <div class="standings-panel__cell standings-panel__cell--stat">
          {$_('display.overview.setsColumn')}
        </div>
        <div class="standings-panel__cell standings-panel__cell--stat">
          {$_('display.overview.ballsColumn')}
        </div>
      </div>

      <!-- AC4: one row per team, ordered by position -->
      {#each group.rankings as ranking (ranking.position)}
        <div class="standings-panel__row">
          <!-- Position (rank) -->
          <div class="standings-panel__cell standings-panel__cell--pos">
            {ranking.position}
          </div>
          <!-- Team name — truncated if too long -->
          <div
            class="standings-panel__cell standings-panel__cell--team"
            title={ranking.teamName}
          >
            {ranking.teamName}
          </div>
          <!-- Points -->
          <div class="standings-panel__cell standings-panel__cell--stat">
            {ranking.points}
          </div>
          <!-- Sets won:lost -->
          <div class="standings-panel__cell standings-panel__cell--stat">
            {ranking.setsWon}:{ranking.setsLost}
          </div>
          <!-- Balls won:lost -->
          <div class="standings-panel__cell standings-panel__cell--stat">
            {ranking.ballsWon}:{ranking.ballsLost}
          </div>
        </div>
      {/each}
    </div>
  {/each}
</div>

<style>
  .standings-panel {
    display: flex;
    flex-direction: column;
    gap: 1rem;
    overflow-y: auto;
    height: 100%;
    padding: 0.5rem 0.5rem 0.5rem 0.75rem;
  }

  .standings-panel__group {
    background: #fff;
    border: 1px solid #ddd;
    border-radius: 4px;
    overflow: hidden;
  }

  /* AC4: "Gruppe N" header — dark background, white text (matches legacy grouptable) */
  .standings-panel__group-header {
    background: #2c3e50;
    color: #ecf0f1;
    padding: 0.4rem 0.6rem;
    font-weight: 600;
    font-size: 0.9rem;
    letter-spacing: 0.03em;
  }

  .standings-panel__row {
    display: grid;
    /* pos | team (flex) | P | S | B */
    grid-template-columns: 2rem 1fr 2.5rem 4rem 4rem;
    align-items: center;
    border-bottom: 1px solid #f0f0f0;
    font-size: 0.85rem;
  }

  .standings-panel__row:last-child {
    border-bottom: none;
  }

  .standings-panel__row--header {
    background: #f5f7fa;
    font-weight: 600;
    font-size: 0.8rem;
    color: #555;
  }

  .standings-panel__cell {
    padding: 0.3rem 0.4rem;
  }

  .standings-panel__cell--pos {
    text-align: center;
    color: #777;
    font-size: 0.8rem;
  }

  .standings-panel__cell--team {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .standings-panel__cell--stat {
    text-align: center;
    font-family: monospace;
    font-size: 0.82rem;
    color: #333;
  }
</style>
