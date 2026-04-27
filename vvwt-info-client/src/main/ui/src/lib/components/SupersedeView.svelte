<script lang="ts">
  /**
   * SupersedeView component — frozen tournament view (AC5 supersede UX).
   * Shows DE message "Dieses Turnier ist beendet"; hides auto-update indicators.
   * AC16: all strings from de.ts; no inline literals in template.
   * Story: E38S08.
   */
  import de from '../i18n/de.js';
  import type { TournamentSnapshot } from '../stores/tournamentStore.js';

  interface Props {
    snapshot?: TournamentSnapshot | null;
  }

  const { snapshot = null }: Props = $props();
</script>

<div class="supersede-view">
  <h2 class="ended-title">{de['tournament.ended']}</h2>
  <p class="ended-subtitle">{de['tournament.ended.subtitle']}</p>

  {#if snapshot !== null && snapshot.scheduleEntries.length > 0}
    <div class="frozen-schedule">
      <ul class="schedule-list">
        {#each snapshot.scheduleEntries as entry (entry.id)}
          <li class="schedule-item" data-testid="schedule-entry">
            {#if entry.type === 'MATCH'}
              <span class="entry-label">{de['schedule.match']}</span>
              <span class="entry-detail">{entry.homeTeamName}{de['schedule.vs.separator']}{entry.awayTeamName}</span>
            {:else if entry.type === 'SPECIAL_APPOINTMENT'}
              <span class="entry-label">{entry.title}</span>
            {:else if entry.type === 'PAUSE'}
              <span class="entry-label">{de['schedule.pause']}</span>
            {/if}
          </li>
        {/each}
      </ul>
    </div>
  {/if}
</div>

<style>
  .supersede-view {
    font-family: sans-serif;
    padding: 2rem;
    text-align: center;
  }

  .ended-title {
    font-size: 1.5rem;
    color: #374151;
    margin-bottom: 0.5rem;
  }

  .ended-subtitle {
    color: #6b7280;
    margin-bottom: 1.5rem;
  }

  .frozen-schedule {
    text-align: left;
    max-width: 600px;
    margin: 0 auto;
  }

  .schedule-list {
    list-style: none;
    padding: 0;
  }

  .schedule-item {
    display: flex;
    flex-direction: column;
    padding: 0.5rem 0;
    border-bottom: 1px solid #e5e7eb;
  }

  .entry-label {
    font-weight: 600;
    font-size: 0.85rem;
    color: #6b7280;
  }

  .entry-detail {
    font-size: 1rem;
    margin-top: 0.2rem;
  }
</style>
