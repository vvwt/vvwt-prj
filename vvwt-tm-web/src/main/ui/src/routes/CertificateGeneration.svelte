<script lang="ts">
  /**
   * Certificate generation + download view — Story E12S07.
   *
   * AC1 (readiness overview): Loads and displays readiness checklist:
   *         - Template status: uploaded or missing (with link to E12S05 template management)
   *         - Photo status: N of M teams have a photo (with link to E12S03 photo management)
   *         - Standings status: available or not yet available
   * AC2 (generate all — SVG): "Alle Urkunden herunterladen" opens batch SVG ZIP in new tab.
   *         Disabled if no template or no standings.
   * AC3 (generate all — HTML): "Alle Urkunden drucken" opens HTML print page in new tab.
   *         Disabled if no template or no standings.
   *         Note: the server returns SVG ZIP or HTML print page based on template format.
   *         Both buttons navigate to the same URL; the server decides the response type.
   *         AC2 and AC3 are implemented as a single "generate" action for simplicity —
   *         the server's template format determines what the browser receives.
   * AC4 (single preview): Team selector + "Vorschau" button. Opens single certificate in new tab.
   * AC5 (missing photo warning): Warns before generating if some teams lack photos.
   *         The organizer can proceed or navigate to photo management.
   * AC6 (error-handling): User-friendly inline error messages — no browser error pages.
   * AC7 (i18n): All strings from svelte-i18n translation layer. German default.
   * AC8 (security): Admin-only via Spring Security /api/** BasicAuth. No frontend auth code needed.
   *
   * Props:
   *   params.tournamentId — the tournament UUID from the route
   *     (#/tournaments/:tournamentId/certificates)
   */
  import { onMount } from 'svelte';
  import { pop, push } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import { getReadiness, type CertificateReadiness } from '../stores/certificateStore.js';
  import { listTeams, type Team } from '../stores/teamStore.js';

  // ── Props ─────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** Readiness status loaded from the backend (AC1). */
  let readiness = $state<CertificateReadiness | null>(null);

  /** Team list for single-certificate preview selector (AC4). */
  let teams = $state<Team[]>([]);

  /** Currently selected team ID in the preview selector (AC4). */
  let selectedTeamId = $state<string>('');

  /** Photo warning dialog visibility (AC5). */
  let showPhotoWarning = $state(false);

  /**
   * Pending action captured when the organizer clicked "generate" but has missing photos.
   * Stored as a function so we can execute it after confirmation (AC5).
   */
  let pendingGenerateAction = $state<(() => void) | null>(null);

  /** Per-action busy flags — prevents double-clicks. */
  let generating = $state(false);

  // ── Derived readiness signals (AC1) ───────────────────────────────────────
  const templateOk = $derived(readiness?.templateUploaded ?? false);
  const standingsOk = $derived(readiness?.standingsAvailable ?? false);
  const totalTeams = $derived(readiness?.totalTeams ?? 0);
  const teamsWithPhoto = $derived(readiness?.teamsWithPhoto ?? 0);
  const missingPhotoCount = $derived(totalTeams - teamsWithPhoto);

  /** True if all prerequisites for generation are met. */
  const canGenerate = $derived(templateOk && standingsOk);

  // ── Init ──────────────────────────────────────────────────────────────────
  onMount(async () => {
    if (!tournamentId) {
      loadError = $_('certificates.error.noTournament');
      loading = false;
      return;
    }
    await Promise.all([loadReadiness(), loadTeams()]);
    loading = false;
  });

  async function loadReadiness(): Promise<void> {
    loadError = null;
    try {
      readiness = await getReadiness(tournamentId);
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    }
  }

  async function loadTeams(): Promise<void> {
    try {
      teams = await listTeams(tournamentId);
      if (teams.length > 0) {
        selectedTeamId = teams[0].id;
      }
    } catch {
      // Non-fatal — preview selector will be empty; main readiness display still works.
    }
  }

  // ── Generate actions (AC2, AC3) ───────────────────────────────────────────

  /**
   * Opens the batch certificate URL in a new browser tab.
   * The server determines the response type based on the uploaded template format:
   *   - SVG template → triggers ZIP download (AC2)
   *   - HTML template → opens print-ready HTML page (AC3)
   * Both cases are handled by a single URL because the server adapts automatically.
   *
   * Protected by photo-warning check (AC5).
   */
  function handleGenerateAll(): void {
    if (!canGenerate) return;

    if (missingPhotoCount > 0) {
      // AC5: warn before generation if some teams lack photos
      pendingGenerateAction = () => openGenerateAllUrl();
      showPhotoWarning = true;
    } else {
      openGenerateAllUrl();
    }
  }

  function openGenerateAllUrl(): void {
    generating = true;
    const url = `/print/tournaments/${tournamentId}/certificates`;
    window.open(url, '_blank', 'noopener');
    // Reset busy flag after a short delay — the download/print happens in the new tab
    setTimeout(() => { generating = false; }, 1000);
  }

  // ── Single preview (AC4) ──────────────────────────────────────────────────

  /**
   * Opens a single certificate for the selected team in a new tab (AC4).
   * The server returns the certificate in the template's format (SVG attachment or HTML page).
   */
  function handlePreview(): void {
    if (!canGenerate || !selectedTeamId) return;
    const url = `/print/tournaments/${tournamentId}/certificates/${selectedTeamId}`;
    window.open(url, '_blank', 'noopener');
  }

  // ── Photo warning dialog (AC5) ────────────────────────────────────────────

  function confirmGenerateWithMissingPhotos(): void {
    showPhotoWarning = false;
    if (pendingGenerateAction) {
      pendingGenerateAction();
      pendingGenerateAction = null;
    }
  }

  function cancelPhotoWarning(): void {
    showPhotoWarning = false;
    pendingGenerateAction = null;
  }

  function navigateToPhotos(): void {
    showPhotoWarning = false;
    pendingGenerateAction = null;
    push(`/tournaments/${tournamentId}/photos`);
  }
</script>

<main class="cert-gen">
  <!-- ── Header ──────────────────────────────────────────────────────────── -->
  <div class="cert-gen__header">
    <h1>{$_('certificates.title')}</h1>
    <button class="btn btn--secondary" onclick={() => pop()}>
      {$_('certificates.backButton')}
    </button>
  </div>

  {#if loading}
    <p class="cert-gen__loading">…</p>
  {:else if loadError}
    <p class="cert-gen__error" role="alert">{loadError}</p>
  {:else}
    <!-- ── Readiness checklist (AC1) ─────────────────────────────────────── -->
    <section class="cert-gen__section">
      <h2 class="cert-gen__section-title">{$_('certificates.readinessTitle')}</h2>

      <ul class="checklist">
        <!-- Template status -->
        <li class="checklist__item" class:checklist__item--ok={templateOk}
                                   class:checklist__item--warn={!templateOk}>
          <span class="checklist__icon">{templateOk ? '✓' : '⚠'}</span>
          {#if templateOk}
            <span>{$_('certificates.readiness.templateOk')}</span>
          {:else}
            <span>
              {$_('certificates.readiness.templateMissing')}
              <button class="btn-link"
                      onclick={() => push(`/tournaments/${tournamentId}/certificate-template`)}>
                {$_('certificates.readiness.templateLink')}
              </button>
            </span>
          {/if}
        </li>

        <!-- Photo status -->
        <li class="checklist__item" class:checklist__item--ok={missingPhotoCount === 0 && totalTeams > 0}
                                   class:checklist__item--partial={missingPhotoCount > 0}
                                   class:checklist__item--neutral={totalTeams === 0}>
          <span class="checklist__icon">{missingPhotoCount === 0 && totalTeams > 0 ? '✓' : 'ℹ'}</span>
          <span>
            {$_('certificates.readiness.photoStatus', {
              values: { withPhoto: teamsWithPhoto, total: totalTeams }
            })}
          </span>
          {#if missingPhotoCount > 0}
            <button class="btn-link"
                    onclick={() => push(`/tournaments/${tournamentId}/photos`)}>
              {$_('certificates.readiness.photoLink')}
            </button>
          {/if}
        </li>

        <!-- Standings status -->
        <li class="checklist__item" class:checklist__item--ok={standingsOk}
                                   class:checklist__item--warn={!standingsOk}>
          <span class="checklist__icon">{standingsOk ? '✓' : '⚠'}</span>
          {#if standingsOk}
            <span>{$_('certificates.readiness.standingsOk')}</span>
          {:else}
            <span>{$_('certificates.readiness.standingsMissing')}</span>
          {/if}
        </li>
      </ul>
    </section>

    <!-- ── Generation actions (AC2, AC3) ─────────────────────────────────── -->
    <section class="cert-gen__section">
      <h2 class="cert-gen__section-title">{$_('certificates.generateTitle')}</h2>

      <p class="cert-gen__description">{$_('certificates.generateDescription')}</p>

      <div class="cert-gen__actions">
        <!-- AC2/AC3: Batch generate — opens batch URL in new tab -->
        <button
          class="btn btn--primary"
          disabled={!canGenerate || generating}
          onclick={handleGenerateAll}
          title={!templateOk ? $_('certificates.disabledNoTemplate')
                 : !standingsOk ? $_('certificates.disabledNoStandings') : ''}
        >
          {generating ? $_('certificates.generatingButton') : $_('certificates.generateAllButton')}
        </button>
      </div>

      {#if !canGenerate}
        <p class="cert-gen__hint">
          {#if !templateOk && !standingsOk}
            {$_('certificates.hintBothMissing')}
          {:else if !templateOk}
            {$_('certificates.hintNoTemplate')}
          {:else}
            {$_('certificates.hintNoStandings')}
          {/if}
        </p>
      {/if}
    </section>

    <!-- ── Single preview (AC4) ──────────────────────────────────────────── -->
    <section class="cert-gen__section">
      <h2 class="cert-gen__section-title">{$_('certificates.previewTitle')}</h2>
      <p class="cert-gen__description">{$_('certificates.previewDescription')}</p>

      <div class="cert-gen__preview-controls">
        {#if teams.length === 0}
          <p class="cert-gen__hint">{$_('certificates.previewNoTeams')}</p>
        {:else}
          <label for="team-select" class="cert-gen__label">
            {$_('certificates.previewTeamLabel')}
          </label>
          <select id="team-select" class="cert-gen__select" bind:value={selectedTeamId}>
            {#each teams as team (team.id)}
              <option value={team.id}>
                {team.teamNumber}. {team.description}
              </option>
            {/each}
          </select>
          <button
            class="btn btn--secondary"
            disabled={!canGenerate || !selectedTeamId}
            onclick={handlePreview}
          >
            {$_('certificates.previewButton')}
          </button>
        {/if}
      </div>
    </section>
  {/if}

  <!-- ── Photo warning dialog (AC5) ────────────────────────────────────── -->
  {#if showPhotoWarning}
    <div class="cert-gen__overlay" role="dialog" aria-modal="true"
         aria-labelledby="photo-warning-title">
      <div class="cert-gen__dialog">
        <h2 id="photo-warning-title" class="cert-gen__dialog-title">
          {$_('certificates.photoWarning.title')}
        </h2>
        <p class="cert-gen__dialog-body">
          {$_('certificates.photoWarning.message', {
            values: { missing: missingPhotoCount }
          })}
        </p>
        <div class="cert-gen__dialog-actions">
          <button class="btn btn--primary" onclick={confirmGenerateWithMissingPhotos}>
            {$_('certificates.photoWarning.proceedButton')}
          </button>
          <button class="btn btn--secondary" onclick={navigateToPhotos}>
            {$_('certificates.photoWarning.uploadPhotosButton')}
          </button>
          <button class="btn btn--secondary" onclick={cancelPhotoWarning}>
            {$_('certificates.photoWarning.cancelButton')}
          </button>
        </div>
      </div>
    </div>
  {/if}
</main>

<style>
  .cert-gen {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 700px;
  }

  .cert-gen__header {
    display: flex;
    align-items: center;
    gap: 1rem;
    margin-bottom: 1.5rem;
  }

  .cert-gen__header h1 {
    margin: 0;
    flex: 1;
  }

  .cert-gen__loading {
    color: #666;
  }

  .cert-gen__error {
    color: #c0392b;
  }

  /* ── Sections ──────────────────────────────────────────────────────────── */

  .cert-gen__section {
    margin-bottom: 2rem;
    padding: 1.25rem;
    border: 1px solid #ddd;
    border-radius: 6px;
    background: #fafafa;
  }

  .cert-gen__section-title {
    margin: 0 0 1rem 0;
    font-size: 1.1rem;
    color: #2c3e50;
  }

  .cert-gen__description {
    font-size: 0.9rem;
    color: #555;
    margin: 0 0 1rem 0;
  }

  .cert-gen__hint {
    font-size: 0.85rem;
    color: #888;
    font-style: italic;
    margin-top: 0.5rem;
  }

  .cert-gen__label {
    font-size: 0.9rem;
    color: #2c3e50;
  }

  /* ── Checklist (AC1) ───────────────────────────────────────────────────── */

  .checklist {
    list-style: none;
    padding: 0;
    margin: 0;
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .checklist__item {
    display: flex;
    align-items: baseline;
    gap: 0.5rem;
    padding: 0.5rem 0.75rem;
    border-radius: 4px;
    font-size: 0.9rem;
  }

  .checklist__item--ok {
    background: #eafaf1;
    color: #1a5c37;
  }

  .checklist__item--warn {
    background: #fef9e7;
    color: #7d6608;
  }

  .checklist__item--partial {
    background: #fef9e7;
    color: #7d6608;
  }

  .checklist__item--neutral {
    background: #f4f6f7;
    color: #566573;
  }

  .checklist__icon {
    font-style: normal;
    flex-shrink: 0;
    min-width: 1.2rem;
    text-align: center;
  }

  /* ── Actions ───────────────────────────────────────────────────────────── */

  .cert-gen__actions {
    display: flex;
    gap: 0.75rem;
    flex-wrap: wrap;
    align-items: center;
  }

  /* ── Preview controls (AC4) ────────────────────────────────────────────── */

  .cert-gen__preview-controls {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    flex-wrap: wrap;
  }

  .cert-gen__select {
    padding: 0.4rem 0.6rem;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    font-size: 0.9rem;
    background: #fff;
    flex: 1;
    min-width: 200px;
  }

  /* ── Photo warning overlay (AC5) ───────────────────────────────────────── */

  .cert-gen__overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 100;
  }

  .cert-gen__dialog {
    background: #fff;
    border-radius: 8px;
    padding: 2rem;
    max-width: 480px;
    width: 90%;
    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.2);
  }

  .cert-gen__dialog-title {
    margin: 0 0 1rem 0;
    font-size: 1.1rem;
    color: #2c3e50;
  }

  .cert-gen__dialog-body {
    font-size: 0.9rem;
    color: #555;
    margin: 0 0 1.5rem 0;
  }

  .cert-gen__dialog-actions {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
  }

  /* ── Buttons ───────────────────────────────────────────────────────────── */

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

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--primary:not(:disabled):hover {
    background: #2471a3;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  /* ── Inline link button ────────────────────────────────────────────────── */

  .btn-link {
    background: none;
    border: none;
    cursor: pointer;
    color: #2980b9;
    font-size: inherit;
    padding: 0;
    text-decoration: underline;
    margin-left: 0.25rem;
  }

  .btn-link:hover {
    color: #1a5276;
  }
</style>
