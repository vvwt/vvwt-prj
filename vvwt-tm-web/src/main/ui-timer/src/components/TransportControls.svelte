<script lang="ts">
  /**
   * Transport controls component (E11S04 AC5).
   *
   * Play / Pause / Stop buttons.
   * - Play: starts or resumes countdown
   * - Pause: freezes countdown and pauses playing audio
   * - Stop: stops playback and resets to current schedule position
   */
  import { _ } from 'svelte-i18n';
  import type { TransportState } from '../lib/countdownEngine.js';

  interface TransportControlsProps {
    transportState: TransportState;
    onPlay: () => void;
    onPause: () => void;
    onStop: () => void;
  }

  let { transportState, onPlay, onPause, onStop }: TransportControlsProps = $props();
</script>

<div class="transport" role="group" aria-label={$_('timer.transport.label')}>
  <!-- Play button: shown when STOPPED or PAUSED -->
  <button
    class="transport__btn transport__btn--play"
    onclick={onPlay}
    disabled={transportState === 'PLAYING'}
    aria-label={$_('timer.transport.play')}
    aria-pressed={transportState === 'PLAYING'}
    title={$_('timer.transport.play')}
  >
    ▶
  </button>

  <!-- Pause button: shown when PLAYING -->
  <button
    class="transport__btn transport__btn--pause"
    onclick={onPause}
    disabled={transportState !== 'PLAYING'}
    aria-label={$_('timer.transport.pause')}
    aria-pressed={transportState === 'PAUSED'}
    title={$_('timer.transport.pause')}
  >
    ⏸
  </button>

  <!-- Stop button: always available when not STOPPED -->
  <button
    class="transport__btn transport__btn--stop"
    onclick={onStop}
    disabled={transportState === 'STOPPED'}
    aria-label={$_('timer.transport.stop')}
    title={$_('timer.transport.stop')}
  >
    ⏹
  </button>
</div>

<style>
  .transport {
    display: flex;
    gap: 0.75rem;
    align-items: center;
    justify-content: center;
    padding: 0.5rem 1rem;
  }

  .transport__btn {
    width: 3rem;
    height: 3rem;
    border: none;
    border-radius: 50%;
    font-size: 1.2rem;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background 0.15s, opacity 0.15s;
  }

  .transport__btn:disabled {
    opacity: 0.35;
    cursor: not-allowed;
  }

  .transport__btn--play {
    background: #27ae60;
    color: #fff;
  }

  .transport__btn--play:not(:disabled):hover {
    background: #219a52;
  }

  .transport__btn--pause {
    background: #f39c12;
    color: #fff;
  }

  .transport__btn--pause:not(:disabled):hover {
    background: #d68910;
  }

  .transport__btn--stop {
    background: #c0392b;
    color: #fff;
  }

  .transport__btn--stop:not(:disabled):hover {
    background: #a93226;
  }
</style>
