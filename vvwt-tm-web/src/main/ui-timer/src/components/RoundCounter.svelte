<script lang="ts">
  /**
   * Round counter display (E11S04 AC6).
   *
   * Displays current round number and total rounds prominently.
   * Counter advances when a round starts.
   */
  import { _ } from 'svelte-i18n';

  interface RoundCounterProps {
    currentRound: number;
    totalRounds: number;
  }

  let { currentRound, totalRounds }: RoundCounterProps = $props();
</script>

{#if totalRounds > 0}
  <div class="round-counter" aria-label="{$_('timer.round.label')} {currentRound} / {totalRounds}">
    <span class="round-counter__label">{$_('timer.round.label')}</span>
    <span class="round-counter__value">
      {#if currentRound > 0}
        <span class="round-counter__current">{currentRound}</span>
        <span class="round-counter__sep">/</span>
        <span class="round-counter__total">{totalRounds}</span>
      {:else}
        <span class="round-counter__current">—</span>
      {/if}
    </span>
  </div>
{/if}

<style>
  .round-counter {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 0.5rem 1rem;
    min-width: 6rem;
  }

  .round-counter__label {
    font-size: 0.75rem;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: #777;
    margin-bottom: 0.1rem;
  }

  .round-counter__value {
    display: flex;
    align-items: baseline;
    gap: 0.15rem;
    font-variant-numeric: tabular-nums;
  }

  .round-counter__current {
    font-size: 2rem;
    font-weight: 700;
    color: #2c3e50;
    line-height: 1;
  }

  .round-counter__sep {
    font-size: 1rem;
    color: #aaa;
  }

  .round-counter__total {
    font-size: 1.1rem;
    color: #888;
    font-weight: 400;
  }
</style>
