<script lang="ts">
  /**
   * Clock synchronization dialog (E11S03 AC2).
   *
   * Displayed on every page load before timer data is fetched. Prompts the user
   * to confirm the device time or enter the venue's wall clock time (HH:MM).
   *
   * The computed offset (venue time − device time, in seconds) is passed to
   * `onConfirm`. The dialog cannot be dismissed without confirming — this is
   * intentional: the organizer must acknowledge the time before the schedule
   * is displayed, and this interaction also provides the user gesture required
   * by modern browsers to unlock the Web Audio API AudioContext (H-2).
   *
   * AC2: "Not persisted — asked on every page load."
   */
  import { onDestroy } from 'svelte';
  import { _ } from 'svelte-i18n';

  /** Called when the user confirms or adjusts the time. */
  let { onConfirm }: { onConfirm: (offsetSeconds: number) => void } = $props();

  // ── State ─────────────────────────────────────────────────────────────────

  /** Formatted device time string, updated every second. */
  let deviceTimeDisplay = $state('');
  /** Whether the user is in "adjust" mode (entering a custom time). */
  let adjustMode = $state(false);
  /** The venue time entered by the user in adjust mode. */
  let venueTimeInput = $state('');
  /** Validation error message for the time input. */
  let inputError = $state('');

  // ── Clock display ─────────────────────────────────────────────────────────

  /**
   * Returns the current device time as "HH:MM" (without seconds, for clarity).
   */
  function getDeviceTimeString(): string {
    const now = new Date();
    const h = String(now.getHours()).padStart(2, '0');
    const m = String(now.getMinutes()).padStart(2, '0');
    return `${h}:${m}`;
  }

  function updateClock(): void {
    deviceTimeDisplay = getDeviceTimeString();
  }

  updateClock();
  const clockInterval = setInterval(updateClock, 1000);
  onDestroy(() => clearInterval(clockInterval));

  // ── Confirm / Adjust ──────────────────────────────────────────────────────

  /**
   * User accepts the device time: offset is 0.
   */
  function handleAccept(): void {
    onConfirm(0);
  }

  /**
   * Switches to "adjust" mode, pre-filling the input with the current device time.
   */
  function handleAdjust(): void {
    venueTimeInput = getDeviceTimeString();
    adjustMode = true;
    inputError = '';
  }

  /**
   * Parses the entered venue time, computes the offset, and calls onConfirm.
   * Validates HH:MM format; shows an error if invalid.
   */
  function handleSave(): void {
    inputError = '';
    const match = venueTimeInput.trim().match(/^(\d{1,2}):(\d{2})$/);
    if (!match) {
      inputError = $_('timer.clockSync.invalidTime');
      return;
    }
    const h = parseInt(match[1], 10);
    const m = parseInt(match[2], 10);
    if (h < 0 || h > 23 || m < 0 || m > 59) {
      inputError = $_('timer.clockSync.invalidTime');
      return;
    }

    const now = new Date();
    const deviceSeconds = now.getHours() * 3600 + now.getMinutes() * 60 + now.getSeconds();
    const venueSeconds = h * 3600 + m * 60;
    // Offset = venue − device; may be negative (device ahead of venue)
    const offset = venueSeconds - deviceSeconds;
    onConfirm(offset);
  }

  function handleInputKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter') handleSave();
  }
</script>

<!--
  Full-screen modal — no dismiss button. The organizer must confirm the time
  before the schedule is shown (AC2: "asked on every page load").
-->
<div class="clock-dialog-overlay" role="dialog" aria-modal="true" aria-labelledby="clock-dialog-title">
  <div class="clock-dialog">
    <h2 id="clock-dialog-title" class="clock-dialog__title">
      {$_('timer.clockSync.title')}
    </h2>

    <p class="clock-dialog__hint">{$_('timer.clockSync.hint')}</p>

    {#if !adjustMode}
      <!-- Accept mode: show device time, offer accept or adjust -->
      <div class="clock-dialog__time-display" aria-label={$_('timer.clockSync.prompt')}>
        <span class="clock-dialog__time-label">{$_('timer.clockSync.prompt')}</span>
        <span class="clock-dialog__time-value">{deviceTimeDisplay}</span>
      </div>

      <div class="clock-dialog__actions">
        <button class="clock-dialog__btn clock-dialog__btn--primary" onclick={handleAccept}>
          {$_('timer.clockSync.accept')}
        </button>
        <button class="clock-dialog__btn clock-dialog__btn--secondary" onclick={handleAdjust}>
          {$_('timer.clockSync.adjustLabel')}
        </button>
      </div>

    {:else}
      <!-- Adjust mode: enter venue time -->
      <div class="clock-dialog__adjust">
        <label class="clock-dialog__adjust-label" for="venue-time-input">
          {$_('timer.clockSync.adjustLabel')}
        </label>
        <input
          id="venue-time-input"
          class="clock-dialog__adjust-input"
          type="text"
          inputmode="numeric"
          placeholder="HH:MM"
          maxlength="5"
          bind:value={venueTimeInput}
          onkeydown={handleInputKeydown}
          aria-describedby={inputError ? 'venue-time-error' : undefined}
          aria-invalid={inputError ? 'true' : undefined}
        />
        {#if inputError}
          <p id="venue-time-error" class="clock-dialog__error" role="alert">{inputError}</p>
        {/if}
      </div>

      <div class="clock-dialog__actions">
        <button class="clock-dialog__btn clock-dialog__btn--primary" onclick={handleSave}>
          {$_('timer.clockSync.save')}
        </button>
      </div>
    {/if}
  </div>
</div>

<style>
  .clock-dialog-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.6);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 100;
  }

  .clock-dialog {
    background: #fff;
    border-radius: 8px;
    padding: 2rem;
    width: min(420px, 90vw);
    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.24);
  }

  .clock-dialog__title {
    margin: 0 0 1rem;
    font-size: 1.25rem;
    color: #1a2332;
  }

  .clock-dialog__hint {
    margin: 0 0 1.5rem;
    font-size: 0.9rem;
    color: #555;
    line-height: 1.5;
  }

  .clock-dialog__time-display {
    display: flex;
    flex-direction: column;
    align-items: center;
    margin-bottom: 1.5rem;
  }

  .clock-dialog__time-label {
    font-size: 0.85rem;
    color: #777;
    margin-bottom: 0.25rem;
  }

  .clock-dialog__time-value {
    font-size: 3rem;
    font-weight: 700;
    color: #1a2332;
    letter-spacing: 0.05em;
    font-variant-numeric: tabular-nums;
  }

  .clock-dialog__adjust {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    margin-bottom: 1.5rem;
  }

  .clock-dialog__adjust-label {
    font-size: 0.9rem;
    color: #333;
  }

  .clock-dialog__adjust-input {
    font-size: 2rem;
    padding: 0.5rem 0.75rem;
    border: 2px solid #2980b9;
    border-radius: 4px;
    text-align: center;
    font-variant-numeric: tabular-nums;
    color: #1a2332;
    outline: none;
    width: 100%;
    box-sizing: border-box;
  }

  .clock-dialog__adjust-input:focus {
    border-color: #1a6eae;
    box-shadow: 0 0 0 3px rgba(41, 128, 185, 0.2);
  }

  .clock-dialog__error {
    margin: 0;
    font-size: 0.85rem;
    color: #c0392b;
  }

  .clock-dialog__actions {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }

  .clock-dialog__btn {
    padding: 0.75rem 1.5rem;
    border: none;
    border-radius: 4px;
    font-size: 1rem;
    cursor: pointer;
    transition: background 0.15s;
  }

  .clock-dialog__btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .clock-dialog__btn--primary:hover {
    background: #1a6eae;
  }

  .clock-dialog__btn--secondary {
    background: #ecf0f1;
    color: #333;
  }

  .clock-dialog__btn--secondary:hover {
    background: #d5dbdb;
  }
</style>
