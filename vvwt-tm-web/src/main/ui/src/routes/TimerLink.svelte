<script lang="ts">
  /**
   * Timer Link & QR Code view — Story E11S07.
   *
   * AC1 (timer link): Shows the timer URL as a clickable link that opens in a new tab.
   * AC2 (QR code): Renders the timer URL as a QR code (SVG, client-side) large enough
   *   to scan from ~1 meter on a laptop screen.
   * AC3 (placement): Route reached from Tournaments list (PLANNED/ACTIVE) and TimerAudio header.
   * AC4 (copy to clipboard): "Copy URL" button copies the timer URL with visual confirmation.
   * AC5 (URL stability): URL is derived deterministically from tournamentId + window.location.origin.
   * AC6 (error-handling): Note shown explaining that "No schedule" is displayed until phases
   *   are configured.
   *
   * QR code generation uses the same `qrcode` library pattern established in Devices.svelte (E06S05).
   * No additional dependencies required — `qrcode@1.5.4` is already in package.json.
   *
   * Props:
   *   params.tournamentId — the tournament UUID from the route (#/tournaments/:tournamentId/timer-link)
   */
  import { onMount } from 'svelte';
  import { pop } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import * as QRCodeLib from 'qrcode';

  // ── Props ────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── Derived timer URL (AC5 — deterministic from origin + tournamentId) ───
  const timerUrl = $derived(
    tournamentId
      ? `${window.location.origin}/timer/${tournamentId}`
      : ''
  );

  // ── State ────────────────────────────────────────────────────────────────

  /** SVG markup for the QR code (AC2). Built on mount. */
  let qrSvg = $state('');

  /** True when the "copy" confirmation is shown (AC4). */
  let copied = $state(false);

  /** Error from clipboard write (AC4 edge case). */
  let copyError = $state<string | null>(null);

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  onMount(() => {
    if (timerUrl) {
      qrSvg = buildQrSvg(timerUrl);
    }
  });

  // ── QR code rendering (AC2) ───────────────────────────────────────────────

  /**
   * Builds an inline SVG QR code for the given text.
   *
   * Uses the same pattern as Devices.svelte (E06S05): calls QRCodeLib.create() to
   * get the raw module data and manually constructs an SVG with a fixed cell size so
   * the result is large enough to scan from ~1 meter on a laptop screen.
   *
   * Cell size of 5px at error-correction level M produces a QR code of roughly 200–250px
   * for a standard URL. The white padding (16px each side) ensures scanner compatibility.
   *
   * @param text  The URL to encode
   * @returns     An SVG string, or an error-indicator SVG if generation fails
   */
  function buildQrSvg(text: string): string {
    try {
      const qr = (QRCodeLib as unknown as {
        create: (text: string, opts: { errorCorrectionLevel: string }) => {
          modules: { data: Uint8ClampedArray | boolean[]; size: number };
        };
      }).create(text, { errorCorrectionLevel: 'M' });

      const size = qr.modules.size;
      const data = qr.modules.data;
      const cellSize = 5;    // 5px cells → ~200–250px QR for typical URLs (AC2: scannable at 1m)
      const padding = 16;    // white quiet zone
      const totalSize = size * cellSize + padding * 2;

      let rects = '';
      for (let row = 0; row < size; row++) {
        for (let col = 0; col < size; col++) {
          if (data[row * size + col]) {
            const x = padding + col * cellSize;
            const y = padding + row * cellSize;
            rects += `<rect x="${x}" y="${y}" width="${cellSize}" height="${cellSize}" fill="black"/>`;
          }
        }
      }

      return (
        `<svg xmlns="http://www.w3.org/2000/svg" width="${totalSize}" height="${totalSize}" ` +
        `viewBox="0 0 ${totalSize} ${totalSize}">` +
        `<rect width="${totalSize}" height="${totalSize}" fill="white"/>` +
        rects +
        `</svg>`
      );
    } catch {
      return (
        `<svg xmlns="http://www.w3.org/2000/svg" width="260" height="60">` +
        `<rect width="260" height="60" fill="#fff" stroke="#ccc"/>` +
        `<text x="10" y="35" font-size="11" fill="#c0392b">${$_('timerLink.qrError')}</text>` +
        `</svg>`
      );
    }
  }

  // ── Clipboard (AC4) ───────────────────────────────────────────────────────

  /**
   * Copies the timer URL to the clipboard and shows a "Kopiert!" confirmation
   * for 2 seconds before reverting to the default button label (AC4).
   *
   * Falls back to a visible error message when the Clipboard API is unavailable
   * (e.g. non-HTTPS insecure context).
   */
  async function handleCopy(): Promise<void> {
    copyError = null;
    try {
      await navigator.clipboard.writeText(timerUrl);
      copied = true;
      setTimeout(() => {
        copied = false;
      }, 2000);
    } catch {
      copyError = 'URL konnte nicht kopiert werden.';
    }
  }
</script>

<main class="timer-link">
  <div class="timer-link__header">
    <h1>{$_('timerLink.title')}</h1>
    <button class="btn btn--secondary" onclick={() => pop()}>
      {$_('timerLink.backButton')}
    </button>
  </div>

  {#if !tournamentId}
    <!-- AC6 / edge case: no tournamentId in route params -->
    <p class="timer-link__error">{$_('timerLink.error.noTournament')}</p>
  {:else}
    <!-- AC6: always show the "no schedule" note so the organizer understands
         that phases must be configured for the timer to display a schedule. -->
    <div class="timer-link__note">
      <span class="timer-link__note-icon" aria-hidden="true">ℹ</span>
      {$_('timerLink.noScheduleNote')}
    </div>

    <!-- AC1: clickable timer URL (opens in new tab) -->
    <section class="timer-link__section">
      <h2 class="timer-link__section-title">{$_('timerLink.urlLabel')}</h2>
      <div class="timer-link__url-row">
        <a
          class="timer-link__url"
          href={timerUrl}
          target="_blank"
          rel="noopener noreferrer"
        >{timerUrl}</a>

        <!-- AC4: copy-to-clipboard button with visual confirmation -->
        <button
          class="btn btn--secondary btn--sm timer-link__copy-btn"
          onclick={handleCopy}
          aria-label={$_('timerLink.copyButton')}
        >
          {copied ? $_('timerLink.copiedButton') : $_('timerLink.copyButton')}
        </button>

        <!-- AC1: explicit "open in new tab" button -->
        <a
          class="btn btn--primary btn--sm"
          href={timerUrl}
          target="_blank"
          rel="noopener noreferrer"
        >{$_('timerLink.openButton')}</a>
      </div>

      {#if copyError}
        <p class="timer-link__copy-error">{copyError}</p>
      {/if}
    </section>

    <!-- AC2: QR code (client-side SVG rendering) -->
    <section class="timer-link__section">
      <h2 class="timer-link__section-title">{$_('timerLink.qrLabel')}</h2>
      <div class="timer-link__qr">
        {@html qrSvg}
      </div>
    </section>
  {/if}
</main>

<style>
  .timer-link {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 700px;
  }

  .timer-link__header {
    display: flex;
    align-items: center;
    gap: 1rem;
    margin-bottom: 1.5rem;
  }

  .timer-link__header h1 {
    margin: 0;
    flex: 1;
  }

  /* ── Informational note (AC6) ─────────────────────────────────────────────── */

  .timer-link__note {
    display: flex;
    align-items: flex-start;
    gap: 0.5rem;
    background: #eaf3fb;
    border: 1px solid #aed6f1;
    border-radius: 6px;
    padding: 0.75rem 1rem;
    margin-bottom: 1.5rem;
    font-size: 0.9rem;
    color: #154360;
  }

  .timer-link__note-icon {
    font-style: normal;
    font-weight: 700;
    flex-shrink: 0;
  }

  /* ── Sections ─────────────────────────────────────────────────────────────── */

  .timer-link__section {
    margin-bottom: 2rem;
  }

  .timer-link__section-title {
    font-size: 1rem;
    font-weight: 600;
    margin: 0 0 0.75rem 0;
    color: #2c3e50;
  }

  /* ── URL row (AC1, AC4) ───────────────────────────────────────────────────── */

  .timer-link__url-row {
    display: flex;
    align-items: center;
    gap: 0.6rem;
    flex-wrap: wrap;
  }

  .timer-link__url {
    font-family: monospace;
    font-size: 0.9rem;
    color: #2980b9;
    word-break: break-all;
    flex: 1;
    min-width: 200px;
  }

  .timer-link__copy-btn {
    white-space: nowrap;
  }

  .timer-link__copy-error {
    color: #c0392b;
    font-size: 0.85rem;
    margin: 0.4rem 0 0;
  }

  .timer-link__error {
    color: #c0392b;
  }

  /* ── QR code container (AC2) ──────────────────────────────────────────────── */

  .timer-link__qr {
    display: inline-block;
    border: 1px solid #ddd;
    border-radius: 6px;
    padding: 0.5rem;
    background: #fff;
  }

  /* ── Buttons ──────────────────────────────────────────────────────────────── */

  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    font-size: 0.9rem;
    text-decoration: none;
    display: inline-block;
    white-space: nowrap;
    line-height: 1.4;
  }

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--sm {
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
  }
</style>
