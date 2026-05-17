<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Timeline component — renders the per-team schedule with all three ScheduleEntry subtypes.
   * AC4 (testing): Match/"Spiel", SpecialAppointment/title, Pause/"Pause".
   * AC16 (governance): all strings from de.ts i18n table; no inline literals in template.
   * Story: E38S08.
   */
  import de from '../i18n/de.js';
  import type { ScheduleEntry } from '../stores/tournamentStore.js';

  interface Props {
    entries: ScheduleEntry[];
  }

  const { entries }: Props = $props();
</script>

<div class="timeline">
  {#if entries.length === 0}
    <p class="empty">{de['app.loading']}</p>
  {:else}
    <ul class="schedule-list">
      {#each entries as entry (entry.id)}
        <li class="schedule-item" data-testid="schedule-entry" data-type={entry.type}>
          {#if entry.type === 'MATCH'}
            <span class="entry-label">{de['schedule.match']}</span>
            <span class="entry-detail">
              {entry.homeTeamName}{de['schedule.vs.separator']}{entry.awayTeamName}
            </span>
            <span class="entry-meta">{de['schedule.round']} {entry.roundNumber}</span>
          {:else if entry.type === 'SPECIAL_APPOINTMENT'}
            <span class="entry-label">{entry.title}</span>
          {:else if entry.type === 'PAUSE'}
            <span class="entry-label">{de['schedule.pause']}</span>
            {#if entry.label}
              <span class="entry-detail">{entry.label}</span>
            {/if}
          {/if}
        </li>
      {/each}
    </ul>
  {/if}
</div>

<style>
  .timeline {
    font-family: sans-serif;
    padding: 1rem;
  }

  .schedule-list {
    list-style: none;
    padding: 0;
    margin: 0;
  }

  .schedule-item {
    display: flex;
    flex-direction: column;
    padding: 0.75rem 0;
    border-bottom: 1px solid #eee;
  }

  .entry-label {
    font-weight: bold;
    font-size: 0.9rem;
    color: #333;
  }

  .entry-detail {
    font-size: 1rem;
    margin-top: 0.25rem;
  }

  .entry-meta {
    font-size: 0.8rem;
    color: #888;
    margin-top: 0.2rem;
  }

  .empty {
    color: #888;
    font-style: italic;
  }
</style>
