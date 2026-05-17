<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Countdown display component (E11S04 AC2).
   *
   * Displays MM:SS countdown to the next audio event.
   * Updates at the interval set by the parent (via the snapshot prop).
   */
  import { _ } from 'svelte-i18n';
  import type { CountdownSnapshot } from '../lib/countdownEngine.js';

  interface CountdownProps {
    snapshot: CountdownSnapshot;
  }

  let { snapshot }: CountdownProps = $props();
</script>

<div class="countdown" aria-live="off" aria-label={$_('timer.countdown.label')}>
  <span class="countdown__display" class:countdown__display--done={snapshot.activeEventIndex === -1}>
    {snapshot.countdownDisplay}
  </span>
  {#if snapshot.activeEventIndex === -1}
    <span class="countdown__finished">{$_('timer.countdown.finished')}</span>
  {/if}
</div>

<style>
  .countdown {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 0.75rem 1rem;
  }

  .countdown__display {
    font-size: 3.5rem;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    color: #2c3e50;
    letter-spacing: 0.05em;
    line-height: 1;
  }

  .countdown__display--done {
    color: #aaa;
  }

  .countdown__finished {
    font-size: 0.85rem;
    color: #777;
    margin-top: 0.25rem;
  }
</style>
