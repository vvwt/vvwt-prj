<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Team photo management view — Story E12S03.
   *
   * AC1 (photo overview): Team list with per-row photo status indicator.
   *         Teams with a photo show a thumbnail; teams without show a placeholder.
   * AC2 (missing count): Summary line shows "X von Y Mannschaften haben ein Foto".
   * AC3 (upload): Each team row has an upload button. File selection triggers immediate
   *         upload. UI updates without page reload.
   * AC4 (preview): Clicking a thumbnail opens an inline larger preview.
   * AC5 (replace): Uploading for a team with an existing photo replaces it.
   *         Cache-busted URL ensures the new image is shown.
   * AC6 (delete): Delete action removes the photo with confirmation.
   * AC7 (error-handling): File-too-large and invalid-format errors shown inline
   *         near the upload area (not as dialogs or console errors).
   * AC8 (i18n): All labels and messages from svelte-i18n translation layer.
   * AC9 (security): Admin-only via Spring Security /api/** BasicAuth (E05S02).
   *         No frontend auth code needed — browser credentials are cached.
   *
   * Props:
   *   params.tournamentId — the tournament UUID from the route (#/tournaments/:tournamentId/photos)
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';
  import {
    listTeams,
    type Team,
  } from '../stores/teamStore.js';
  import {
    uploadPhoto,
    deletePhoto,
    getPhotoUrl,
    PhotoUploadError,
  } from '../stores/photoStore.js';

  // ── Props ────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ────────────────────────────────────────────────────────────────
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /**
   * List of teams with their current hasPhoto status.
   * hasPhoto may be updated locally on upload/delete without reloading the full list.
   */
  let teams = $state<Team[]>([]);

  /**
   * Cache-busting timestamps per team ID.
   * Updated after each successful upload to force the browser to reload the thumbnail.
   */
  let photoBust = $state<Record<string, number>>({});

  /** Per-team upload-in-progress flag. */
  let uploading = $state<Record<string, boolean>>({});

  /** Per-team delete-in-progress flag. */
  let deleting = $state<Record<string, boolean>>({});

  /** Per-team inline error message (upload or delete failure). */
  let errors = $state<Record<string, string>>({});

  /**
   * Currently previewed team ID (null = no preview open).
   * AC4: clicking the thumbnail sets this to open the larger preview.
   */
  let previewTeamId = $state<string | null>(null);

  // ── Derived counts (AC2) ─────────────────────────────────────────────────
  const totalTeams = $derived(teams.length);
  const teamsWithPhoto = $derived(teams.filter(t => t.hasPhoto).length);

  // ── Init ─────────────────────────────────────────────────────────────────
  onMount(async () => {
    // E47S01 AC3/AC5/AC6/AC12: register title, back-arrow, and tournament context in persistent header
    pageHeader.set({
      title: get(_)('photos.title'),
      backTo: resolveParent('/tournaments/:tournamentId/photos', tournamentId),
      tournamentId: tournamentId || null,
      actions: [],
    });
    if (!tournamentId) {
      loadError = $_('photos.error.noTournament');
      loading = false;
      return;
    }
    await loadTeams();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function loadTeams(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      teams = await listTeams(tournamentId);
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  function clearError(teamId: string): void {
    const next = { ...errors };
    delete next[teamId];
    errors = next;
  }

  function setError(teamId: string, msg: string): void {
    errors = { ...errors, [teamId]: msg };
  }

  function setUploading(teamId: string, value: boolean): void {
    const next = { ...uploading };
    if (value) next[teamId] = true; else delete next[teamId];
    uploading = next;
  }

  function setDeleting(teamId: string, value: boolean): void {
    const next = { ...deleting };
    if (value) next[teamId] = true; else delete next[teamId];
    deleting = next;
  }

  /** Updates the hasPhoto flag for a single team in the local teams list. */
  function setHasPhoto(teamId: string, value: boolean): void {
    teams = teams.map(t => t.id === teamId ? { ...t, hasPhoto: value } : t);
  }

  // ── Upload (AC3, AC5) ─────────────────────────────────────────────────────

  function handleUploadClick(teamId: string): void {
    const input = document.getElementById(`file-input-${teamId}`) as HTMLInputElement | null;
    if (input) {
      input.value = '';
      input.click();
    }
  }

  async function handleFileSelected(team: Team, event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    // AC7: client-side format validation (defence in depth — server validates too)
    const lowerName = file.name.toLowerCase();
    if (!lowerName.endsWith('.jpg') && !lowerName.endsWith('.jpeg') && !lowerName.endsWith('.png')) {
      setError(team.id, $_('photos.uploadFormatError'));
      return;
    }

    clearError(team.id);
    setUploading(team.id, true);

    // Close preview if open for this team
    if (previewTeamId === team.id) previewTeamId = null;

    try {
      await uploadPhoto(tournamentId, team.id, file);
      // AC3/AC5: update local state — set hasPhoto=true + bust cache for thumbnail
      setHasPhoto(team.id, true);
      photoBust = { ...photoBust, [team.id]: Date.now() };
    } catch (e: unknown) {
      // AC4 (E12S08): when the server returns a photo-specific messageKey (e.g.
      // error.photo.tooLarge), resolve it through the i18n layer so the error is shown
      // in German with the correct message. Fall back to the raw message or generic key.
      if (e instanceof PhotoUploadError && e.messageKey) {
        setError(team.id, $_(e.messageKey, { default: e.message }));
      } else {
        setError(team.id, e instanceof Error ? e.message : $_('photos.uploadError'));
      }
    } finally {
      setUploading(team.id, false);
    }
  }

  // ── Delete (AC6) ─────────────────────────────────────────────────────────

  async function handleDelete(team: Team): Promise<void> {
    if (!confirm($_('photos.deleteConfirm', { values: { name: team.description } }))) return;

    // Close preview if open for this team
    if (previewTeamId === team.id) previewTeamId = null;

    clearError(team.id);
    setDeleting(team.id, true);
    try {
      await deletePhoto(tournamentId, team.id);
      setHasPhoto(team.id, false);
      // Remove bust entry — photo gone
      const next = { ...photoBust };
      delete next[team.id];
      photoBust = next;
    } catch (e: unknown) {
      setError(team.id, e instanceof Error ? e.message : $_('photos.deleteError'));
    } finally {
      setDeleting(team.id, false);
    }
  }

  // ── Preview (AC4) ─────────────────────────────────────────────────────────

  function handleThumbnailClick(teamId: string): void {
    previewTeamId = previewTeamId === teamId ? null : teamId;
  }

  function closePreview(): void {
    previewTeamId = null;
  }
</script>

<main class="team-photos">

  {#if loading}
    <p class="team-photos__loading">…</p>
  {:else if loadError}
    <p class="team-photos__error">{loadError}</p>
  {:else}
    <!-- AC2: Missing count summary -->
    <p class="team-photos__summary">
      {$_('photos.summary', { values: { withPhoto: teamsWithPhoto, total: totalTeams } })}
    </p>

    {#if teams.length === 0}
      <p class="team-photos__empty">{$_('photos.empty')}</p>
    {:else}
      <div class="photo-grid">
        {#each teams as team (team.id)}
          {@const isUploading = uploading[team.id] ?? false}
          {@const isDeleting = deleting[team.id] ?? false}
          {@const rowError = errors[team.id] ?? null}
          {@const isPreviewing = previewTeamId === team.id}
          {@const bust = photoBust[team.id]}

          <div class="photo-row">
            <!-- Team info -->
            <div class="photo-row__info">
              <span class="photo-row__number">{team.teamNumber}</span>
              <span class="photo-row__name">{team.description}</span>
            </div>

            <!-- AC1: Photo status indicator -->
            <div class="photo-row__status">
              {#if team.hasPhoto}
                <!-- Thumbnail preview (AC1, AC4) -->
                <button
                  class="photo-row__thumb-btn"
                  onclick={() => handleThumbnailClick(team.id)}
                  title={$_('photos.previewHint')}
                  aria-label={$_('photos.previewLabel', { values: { name: team.description } })}
                >
                  <img
                    class="photo-row__thumb"
                    src={getPhotoUrl(tournamentId, team.id, bust)}
                    alt={team.description}
                  />
                </button>
              {:else}
                <!-- Placeholder icon (AC1) -->
                <span class="photo-row__placeholder" aria-label={$_('photos.noPhotoLabel')}>
                  📷
                </span>
              {/if}
            </div>

            <!-- Actions -->
            <div class="photo-row__actions">
              <!-- Hidden file input (AC3: .jpg/.jpeg/.png filter) -->
              <input
                id="file-input-{team.id}"
                type="file"
                accept=".jpg,.jpeg,.png,image/jpeg,image/png"
                class="photo-row__file-input"
                onchange={(e) => handleFileSelected(team, e)}
              />

              <!-- Upload / Replace button (AC3, AC5) -->
              <button
                class="btn btn--secondary btn--sm"
                disabled={isUploading || isDeleting}
                onclick={() => handleUploadClick(team.id)}
              >
                {isUploading
                  ? $_('photos.uploadingButton')
                  : team.hasPhoto
                    ? $_('photos.replaceButton')
                    : $_('photos.uploadButton')}
              </button>

              <!-- Delete button (AC6) — only when photo exists -->
              {#if team.hasPhoto}
                <button
                  class="btn btn--danger btn--sm"
                  disabled={isUploading || isDeleting}
                  onclick={() => handleDelete(team)}
                >
                  {isDeleting ? '…' : $_('photos.deleteButton')}
                </button>
              {/if}
            </div>

            <!-- AC7: Per-row inline error -->
            {#if rowError}
              <div class="photo-row__error" role="alert">{rowError}</div>
            {/if}

            <!-- AC4: Inline larger preview panel (opens below the row) -->
            {#if isPreviewing && team.hasPhoto}
              <div class="photo-row__preview-panel">
                <img
                  class="photo-row__preview-img"
                  src={getPhotoUrl(tournamentId, team.id, bust)}
                  alt={$_('photos.previewAlt', { values: { name: team.description } })}
                />
                <button class="btn btn--secondary btn--sm photo-row__preview-close"
                        onclick={closePreview}>
                  {$_('photos.previewCloseButton')}
                </button>
              </div>
            {/if}
          </div>
        {/each}
      </div>
    {/if}
  {/if}
</main>

<style>
  .team-photos {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 860px;
  }

  .team-photos__summary {
    font-size: 0.9rem;
    color: #555;
    margin-bottom: 1.25rem;
  }

  .team-photos__loading,
  .team-photos__empty {
    color: #666;
  }

  .team-photos__error {
    color: #c0392b;
  }

  /* ── Photo grid ──────────────────────────────────────────────────────────── */

  .photo-grid {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .photo-row {
    display: grid;
    grid-template-columns: 1fr 80px auto;
    grid-template-areas:
      "info  status  actions"
      "error error   error"
      "preview preview preview";
    gap: 0.5rem;
    align-items: center;
    padding: 0.75rem 1rem;
    border: 1px solid #ddd;
    border-radius: 6px;
    background: #fafafa;
  }

  .photo-row__info {
    grid-area: info;
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }

  .photo-row__number {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 2rem;
    height: 2rem;
    background: #ecf0f1;
    border-radius: 4px;
    font-size: 0.85rem;
    font-weight: 600;
    color: #2c3e50;
    flex-shrink: 0;
  }

  .photo-row__name {
    font-size: 0.95rem;
    color: #2c3e50;
  }

  .photo-row__status {
    grid-area: status;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  /* Thumbnail (AC1, AC4) */
  .photo-row__thumb-btn {
    border: none;
    background: transparent;
    cursor: pointer;
    padding: 0;
    border-radius: 4px;
    overflow: hidden;
    display: block;
    width: 60px;
    height: 60px;
  }

  .photo-row__thumb {
    width: 60px;
    height: 60px;
    object-fit: cover;
    border-radius: 4px;
    border: 1px solid #ccc;
    display: block;
  }

  .photo-row__thumb-btn:hover .photo-row__thumb {
    border-color: #2980b9;
  }

  /* Placeholder icon when no photo (AC1) */
  .photo-row__placeholder {
    font-size: 2rem;
    opacity: 0.35;
    cursor: default;
  }

  .photo-row__actions {
    grid-area: actions;
    display: flex;
    gap: 0.4rem;
    align-items: center;
    flex-wrap: nowrap;
  }

  .photo-row__file-input {
    display: none;
  }

  /* AC7: inline error below the row */
  .photo-row__error {
    grid-area: error;
    color: #c0392b;
    font-size: 0.85rem;
    padding-top: 0.1rem;
  }

  /* AC4: inline larger preview panel */
  .photo-row__preview-panel {
    grid-area: preview;
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 0.5rem;
    padding-top: 0.5rem;
    border-top: 1px solid #e8e8e8;
    margin-top: 0.25rem;
  }

  .photo-row__preview-img {
    max-width: 100%;
    max-height: 320px;
    object-fit: contain;
    border-radius: 4px;
    border: 1px solid #ccc;
  }

  .photo-row__preview-close {
    align-self: flex-start;
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

  .btn--sm {
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
  }
</style>
