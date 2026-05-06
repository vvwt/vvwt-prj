<script lang="ts">
  /**
   * Timer Audio management view — Story E11S06.
   *
   * AC1: Shows three rows (START/END/PAUSE) under "Timer Audio" in the admin console.
   * AC2: Upload button with .mp3 file picker and progress bar.
   * AC3: Status per row — filename + size when uploaded, "Keine Datei" when absent.
   * AC4: Confirm dialog before replacing an existing file.
   * AC5: Delete button with confirm dialog.
   * AC6: Play/Stop button for inline audio preview.
   * AC7: Inline per-row error messages for upload/delete failures.
   *
   * E11S07 AC3: "Timer-Link" header button navigates to the timer link & QR code view
   *   (#/tournaments/:tournamentId/timer-link). The button is always shown in the header
   *   because TimerAudio does not carry tournament status; the link is useful pre-tournament
   *   (for DRAFT) to allow organizers to prepare the QR code in advance.
   *
   * Props:
   *   params.tournamentId — the tournament UUID from the route (#/tournaments/:tournamentId/audio)
   */
  import { onMount, onDestroy } from 'svelte';
  import { push } from 'svelte-spa-router';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';
  import {
    listAudio,
    uploadAudio,
    deleteAudio,
    type AudioCategory,
    type AudioMetadata,
  } from '../stores/audioStore.js';

  // ── Props ────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── Constants ────────────────────────────────────────────────────────────
  const CATEGORIES: AudioCategory[] = ['START', 'END', 'PAUSE'];

  // ── State ────────────────────────────────────────────────────────────────
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** Map category → metadata (present = file uploaded). */
  let audioFiles = $state<Map<AudioCategory, AudioMetadata>>(new Map());

  /** Per-category upload-in-progress flag. */
  let uploading = $state<Map<AudioCategory, boolean>>(new Map());

  /** Per-category upload progress 0–100. */
  let uploadProgress = $state<Map<AudioCategory, number>>(new Map());

  /** Per-category delete-in-progress flag. */
  let deleting = $state<Map<AudioCategory, boolean>>(new Map());

  /** Per-category inline error message (upload or delete failure). */
  let errors = $state<Map<AudioCategory, string>>(new Map());

  /** Category currently being previewed (null = none playing). */
  let previewCategory = $state<AudioCategory | null>(null);

  /** HTMLAudioElement reference for the currently playing preview. */
  let audioElement = $state<HTMLAudioElement | null>(null);

  // ── Init ─────────────────────────────────────────────────────────────────
  onMount(async () => {
    // E47S01 AC3/AC5/AC6/AC12: register title, back-arrow, tournament context, and timerLink action
    const actions = tournamentId
      ? [
          {
            label: get(_)('audio.timerLinkButton'),
            ariaLabel: get(_)('audio.timerLinkButton'),
            handler: () => push(`/tournaments/${tournamentId}/timer-link`),
            variant: 'secondary' as const,
          },
        ]
      : [];
    pageHeader.set({
      title: get(_)('audio.title'),
      backTo: resolveParent('/tournaments/:tournamentId/audio', tournamentId),
      tournamentId: tournamentId || null,
      actions,
    });
    if (!tournamentId) {
      loadError = $_('audio.error.noTournament');
      loading = false;
      return;
    }
    await loadAudioFiles();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function loadAudioFiles(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      const list = await listAudio(tournamentId);
      const map = new Map<AudioCategory, AudioMetadata>();
      for (const meta of list) {
        map.set(meta.category, meta);
      }
      audioFiles = map;
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  function formatSize(bytes: number): string {
    // Display size in KB, rounded to 1 decimal
    return (bytes / 1024).toFixed(1);
  }

  function clearError(category: AudioCategory): void {
    const next = new Map(errors);
    next.delete(category);
    errors = next;
  }

  function setError(category: AudioCategory, msg: string): void {
    const next = new Map(errors);
    next.set(category, msg);
    errors = next;
  }

  function setUploading(category: AudioCategory, value: boolean): void {
    const next = new Map(uploading);
    if (value) next.set(category, true); else next.delete(category);
    uploading = next;
  }

  function setUploadProgress(category: AudioCategory, value: number): void {
    const next = new Map(uploadProgress);
    next.set(category, value);
    uploadProgress = next;
  }

  function clearUploadProgress(category: AudioCategory): void {
    const next = new Map(uploadProgress);
    next.delete(category);
    uploadProgress = next;
  }

  function setDeleting(category: AudioCategory, value: boolean): void {
    const next = new Map(deleting);
    if (value) next.set(category, true); else next.delete(category);
    deleting = next;
  }

  // ── Upload ────────────────────────────────────────────────────────────────

  function handleUploadClick(category: AudioCategory): void {
    // Trigger hidden file input
    const input = document.getElementById(`file-input-${category}`) as HTMLInputElement | null;
    if (input) {
      input.value = '';   // reset so the same file can be re-selected
      input.click();
    }
  }

  async function handleFileSelected(
    category: AudioCategory,
    event: Event,
  ): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    // AC7: client-side format validation
    if (!file.name.toLowerCase().endsWith('.mp3')) {
      setError(category, $_('audio.uploadError') + ' (nur .mp3 erlaubt)');
      return;
    }

    // AC4: confirm before replacing an existing file
    if (audioFiles.has(category)) {
      const catLabel = $_(`audio.categories.${category}`);
      if (!confirm($_('audio.replaceConfirm', { values: { category: catLabel } }))) {
        return;
      }
    }

    clearError(category);
    setUploading(category, true);
    setUploadProgress(category, 0);

    // Stop preview if currently playing this category
    if (previewCategory === category) {
      stopPreview();
    }

    try {
      const meta = await uploadAudio(tournamentId, category, file, (pct) => {
        setUploadProgress(category, pct);
      });
      const next = new Map(audioFiles);
      next.set(category, meta);
      audioFiles = next;
    } catch (e: unknown) {
      setError(category, e instanceof Error ? e.message : $_('audio.uploadError'));
    } finally {
      setUploading(category, false);
      clearUploadProgress(category);
    }
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  async function handleDelete(category: AudioCategory): Promise<void> {
    const catLabel = $_(`audio.categories.${category}`);
    if (!confirm($_('audio.deleteConfirm', { values: { category: catLabel } }))) return;

    // Stop preview if currently playing this category
    if (previewCategory === category) {
      stopPreview();
    }

    clearError(category);
    setDeleting(category, true);
    try {
      await deleteAudio(tournamentId, category);
      const next = new Map(audioFiles);
      next.delete(category);
      audioFiles = next;
    } catch (e: unknown) {
      setError(category, e instanceof Error ? e.message : $_('audio.deleteError'));
    } finally {
      setDeleting(category, false);
    }
  }

  // ── Preview ───────────────────────────────────────────────────────────────

  function handlePreviewToggle(category: AudioCategory): void {
    if (previewCategory === category) {
      stopPreview();
    } else {
      startPreview(category);
    }
  }

  function startPreview(category: AudioCategory): void {
    stopPreview();  // stop any existing preview first
    const url = `/api/audio/tournaments/${tournamentId}/${category}/stream`;
    const audio = new Audio(url);
    audio.addEventListener('ended', () => {
      if (previewCategory === category) {
        previewCategory = null;
        audioElement = null;
      }
    });
    audio.play().catch(() => {
      previewCategory = null;
      audioElement = null;
    });
    audioElement = audio;
    previewCategory = category;
  }

  function stopPreview(): void {
    if (audioElement) {
      audioElement.pause();
      audioElement.src = '';
      audioElement = null;
    }
    previewCategory = null;
  }
</script>

<main class="timer-audio">

  {#if loading}
    <p class="timer-audio__loading">…</p>
  {:else if loadError}
    <p class="timer-audio__error">{loadError}</p>
  {:else}
    <div class="audio-table">
      {#each CATEGORIES as category (category)}
        {@const meta = audioFiles.get(category)}
        {@const isUploading = uploading.get(category) ?? false}
        {@const progress = uploadProgress.get(category) ?? 0}
        {@const isDeleting = deleting.get(category) ?? false}
        {@const rowError = errors.get(category) ?? null}
        {@const isPreviewing = previewCategory === category}

        <div class="audio-row">
          <!-- Category label -->
          <div class="audio-row__label">
            <strong>{$_(`audio.categories.${category}`)}</strong>
          </div>

          <!-- Status -->
          <div class="audio-row__status">
            {#if meta}
              <span class="audio-row__filename">{meta.filename}</span>
              <span class="audio-row__size">
                ({$_('audio.sizeLabel', { values: { size: formatSize(meta.sizeBytes) } })})
              </span>
            {:else}
              <span class="audio-row__no-file">{$_('audio.noFile')}</span>
            {/if}
          </div>

          <!-- Upload progress bar (AC2) -->
          {#if isUploading}
            <div class="audio-row__progress">
              <div class="progress-bar">
                <div class="progress-bar__fill" style="width: {progress}%"></div>
              </div>
              <span class="progress-bar__label">{progress}%</span>
            </div>
          {/if}

          <!-- Actions -->
          <div class="audio-row__actions">
            <!-- Hidden file input (AC2: .mp3 filter) -->
            <input
              id="file-input-{category}"
              type="file"
              accept=".mp3,audio/mpeg"
              class="audio-row__file-input"
              onchange={(e) => handleFileSelected(category, e)}
            />

            <!-- Upload button (AC2) -->
            <button
              class="btn btn--secondary btn--sm"
              disabled={isUploading || isDeleting}
              onclick={() => handleUploadClick(category)}
            >
              {isUploading ? $_('audio.uploadingButton') : $_('audio.uploadButton')}
            </button>

            <!-- Preview play/stop button (AC6) -->
            {#if meta}
              <button
                class="btn btn--sm"
                class:btn--preview={!isPreviewing}
                class:btn--preview-stop={isPreviewing}
                disabled={isUploading || isDeleting}
                onclick={() => handlePreviewToggle(category)}
              >
                {isPreviewing ? $_('audio.stopButton') : $_('audio.playButton')}
              </button>
            {/if}

            <!-- Delete button (AC5) -->
            {#if meta}
              <button
                class="btn btn--danger btn--sm"
                disabled={isUploading || isDeleting}
                onclick={() => handleDelete(category)}
              >
                {isDeleting ? '…' : $_('audio.deleteButton')}
              </button>
            {/if}
          </div>

          <!-- Per-row error (AC7) -->
          {#if rowError}
            <div class="audio-row__error">{rowError}</div>
          {/if}
        </div>
      {/each}
    </div>
  {/if}
</main>

<style>
  .timer-audio {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 800px;
  }

  .timer-audio__loading {
    color: #666;
  }

  .timer-audio__error {
    color: #c0392b;
  }

  /* ── Audio table ─────────────────────────────────────────────────────────── */

  .audio-table {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }

  .audio-row {
    display: grid;
    grid-template-columns: 150px 1fr auto;
    gap: 0.75rem;
    align-items: center;
    padding: 1rem;
    border: 1px solid #ddd;
    border-radius: 6px;
    background: #fafafa;
    /* grid areas: label | status | actions; error spans full width */
    grid-template-areas:
      "label status actions"
      "label progress actions"
      "error error error";
  }

  .audio-row__label {
    grid-area: label;
    font-size: 0.95rem;
  }

  .audio-row__status {
    grid-area: status;
    display: flex;
    align-items: center;
    gap: 0.4rem;
    flex-wrap: wrap;
  }

  .audio-row__filename {
    font-size: 0.9rem;
    color: #2c3e50;
  }

  .audio-row__size {
    font-size: 0.8rem;
    color: #7f8c8d;
  }

  .audio-row__no-file {
    font-size: 0.9rem;
    color: #aaa;
    font-style: italic;
  }

  .audio-row__progress {
    grid-area: progress;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }

  .progress-bar {
    flex: 1;
    height: 6px;
    background: #ecf0f1;
    border-radius: 3px;
    overflow: hidden;
  }

  .progress-bar__fill {
    height: 100%;
    background: #2980b9;
    border-radius: 3px;
    transition: width 0.1s ease;
  }

  .progress-bar__label {
    font-size: 0.75rem;
    color: #666;
    min-width: 2.5rem;
    text-align: right;
  }

  .audio-row__actions {
    grid-area: actions;
    display: flex;
    gap: 0.4rem;
    align-items: center;
    flex-wrap: nowrap;
  }

  .audio-row__file-input {
    display: none;
  }

  .audio-row__error {
    grid-area: error;
    color: #c0392b;
    font-size: 0.85rem;
    padding-top: 0.25rem;
  }

  /* ── Buttons ─────────────────────────────────────────────────────────────── */

  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    font-size: 0.9rem;
    white-space: nowrap;
  }

  .btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--danger {
    background: #e74c3c;
    color: #fff;
  }

  .btn--preview {
    background: #27ae60;
    color: #fff;
  }

  .btn--preview-stop {
    background: #e67e22;
    color: #fff;
  }

  .btn--sm {
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
  }
</style>
