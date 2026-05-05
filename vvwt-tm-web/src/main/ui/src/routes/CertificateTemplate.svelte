<script lang="ts">
  /**
   * Certificate Template management view — Story E12S05.
   *
   * AC1 (upload): File picker (.html/.svg); immediate upload on file selection.
   * AC2 (status display): Shows "Keine Vorlage" when empty, or metadata when present.
   * AC3 (replace): Upload new template replaces existing; UI reflects new metadata immediately.
   * AC4 (delete): Delete button with confirm dialog; reverts to no-template state.
   * AC5 (variable reference): Always-visible table of Mustache variables from /api/certificate-template/variables.
   * AC6 (preview): SVG → inline rendered div; HTML → iframe pointing to API endpoint.
   * AC7 (error-handling): Inline error messages near upload area.
   * AC8 (i18n): All strings from de.json, German default.
   * AC9 (security): Admin-only via Spring Security BasicAuth (no frontend auth code needed).
   *
   * Props:
   *   params.tournamentId — the tournament UUID from the route
   *     (#/tournaments/:tournamentId/certificate-template)
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';
  import {
    getTemplateMetadata,
    uploadTemplate,
    deleteTemplate,
    listVariables,
    type CertificateTemplateMetadata,
    type CertificateTemplateVariable,
  } from '../stores/certificateTemplateStore.js';

  // ── Props ─────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** Current template metadata; null when no template is uploaded. */
  let metadata = $state<CertificateTemplateMetadata | null>(null);

  /** Available Mustache template variables (AC5). */
  let variables = $state<CertificateTemplateVariable[]>([]);
  let variablesError = $state<string | null>(null);

  /** Upload state. */
  let uploading = $state(false);
  let uploadProgress = $state(0);
  let uploadError = $state<string | null>(null);

  /** Delete state. */
  let deleting = $state(false);
  let deleteError = $state<string | null>(null);

  /** Preview state (AC6). */
  let previewLoading = $state(false);
  let previewError = $state<string | null>(null);
  /** Inline SVG content for SVG previews. */
  let svgContent = $state<string | null>(null);

  // ── Init ──────────────────────────────────────────────────────────────────
  onMount(async () => {
    // E47S01 AC3/AC5/AC6/AC12: register title, back-arrow, and tournament context in persistent header
    pageHeader.set({
      title: get(_)('certificateTemplate.title'),
      backTo: resolveParent('/tournaments/:tournamentId/certificate-template', tournamentId),
      tournamentId: tournamentId || null,
      actions: [],
    });
    if (!tournamentId) {
      loadError = $_('certificateTemplate.error.noTournament');
      loading = false;
      return;
    }
    await Promise.all([loadMetadata(), loadVariables()]);
    loading = false;
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function loadMetadata(): Promise<void> {
    loadError = null;
    try {
      metadata = await getTemplateMetadata(tournamentId);
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    }
  }

  async function loadVariables(): Promise<void> {
    variablesError = null;
    try {
      variables = await listVariables();
    } catch (e: unknown) {
      variablesError = e instanceof Error ? e.message : String(e);
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  function formatSize(bytes: number): string {
    return (bytes / 1024).toFixed(1);
  }

  function formatDate(iso: string): string {
    try {
      return new Date(iso).toLocaleString('de-DE');
    } catch {
      return iso;
    }
  }

  function isValidExtension(file: File): boolean {
    const name = file.name.toLowerCase();
    return name.endsWith('.html') || name.endsWith('.svg');
  }

  // ── Upload ────────────────────────────────────────────────────────────────

  function handleUploadClick(): void {
    const input = document.getElementById('cert-file-input') as HTMLInputElement | null;
    if (input) {
      input.value = '';
      input.click();
    }
  }

  async function handleFileSelected(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;

    // AC7: client-side format validation
    if (!isValidExtension(file)) {
      uploadError = $_('certificateTemplate.uploadError') + ' (nur .html oder .svg erlaubt)';
      return;
    }

    // AC3: confirm before replacing an existing template
    if (metadata !== null) {
      if (!confirm($_('certificateTemplate.replaceConfirm'))) {
        return;
      }
    }

    uploadError = null;
    uploading = true;
    uploadProgress = 0;
    svgContent = null;  // reset preview on new upload
    previewError = null;

    try {
      metadata = await uploadTemplate(tournamentId, file, (pct) => {
        uploadProgress = pct;
      });
      // AC6: auto-load preview after upload
      if (metadata) {
        await loadPreview();
      }
    } catch (e: unknown) {
      uploadError = e instanceof Error ? e.message : $_('certificateTemplate.uploadError');
    } finally {
      uploading = false;
      uploadProgress = 0;
    }
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  async function handleDelete(): Promise<void> {
    if (!confirm($_('certificateTemplate.deleteConfirm'))) return;

    deleteError = null;
    deleting = true;
    svgContent = null;
    previewError = null;
    try {
      await deleteTemplate(tournamentId);
      metadata = null;
    } catch (e: unknown) {
      deleteError = e instanceof Error ? e.message : $_('certificateTemplate.deleteError');
    } finally {
      deleting = false;
    }
  }

  // ── Preview (AC6) ─────────────────────────────────────────────────────────

  /**
   * Loads the SVG template content for inline preview.
   * HTML templates are shown via iframe (no fetch needed — browser loads directly).
   */
  async function loadPreview(): Promise<void> {
    if (!metadata || metadata.format !== 'svg') return;
    previewLoading = true;
    previewError = null;
    svgContent = null;
    try {
      const response = await fetch(
        `/api/tournaments/${tournamentId}/certificate-template`,
        { credentials: 'same-origin' },
      );
      if (!response.ok) {
        previewError = $_('certificateTemplate.previewError');
        return;
      }
      svgContent = await response.text();
    } catch {
      previewError = $_('certificateTemplate.previewError');
    } finally {
      previewLoading = false;
    }
  }

  // Trigger SVG preview load when metadata changes to an SVG template.
  $effect(() => {
    if (metadata && metadata.format === 'svg' && svgContent === null && !previewLoading) {
      loadPreview();
    }
  });
</script>

<main class="cert-template">

  {#if loading}
    <p class="cert-template__loading">…</p>
  {:else if loadError}
    <p class="cert-template__error">{loadError}</p>
  {:else}
    <!-- ── Template Status (AC2) ─────────────────────────────────────────── -->
    <section class="cert-template__section">
      <h2 class="cert-template__section-title">{$_('certificateTemplate.title')}</h2>

      {#if metadata}
        <!-- Template exists — show metadata -->
        <dl class="cert-template__meta">
          <dt>{$_('certificateTemplate.metaFilename')}</dt>
          <dd>{metadata.filename}</dd>
          <dt>{$_('certificateTemplate.metaFormat')}</dt>
          <dd>{metadata.format.toUpperCase()}</dd>
          <dt>{$_('certificateTemplate.metaUploadedAt')}</dt>
          <dd>{formatDate(metadata.uploadedAt)}</dd>
          <dt>{$_('certificateTemplate.metaFileSize')}</dt>
          <dd>{$_('certificateTemplate.sizeLabel', { values: { size: formatSize(metadata.fileSizeBytes) } })}</dd>
        </dl>
      {:else}
        <!-- No template -->
        <p class="cert-template__no-template">{$_('certificateTemplate.noTemplate')}</p>
      {/if}

      <!-- Upload area (AC1) -->
      <div class="cert-template__upload">
        <!-- Hidden file input -->
        <input
          id="cert-file-input"
          type="file"
          accept=".html,.svg,text/html,image/svg+xml"
          class="cert-template__file-input"
          onchange={handleFileSelected}
        />

        <button
          class="btn btn--secondary"
          disabled={uploading || deleting}
          onclick={handleUploadClick}
        >
          {#if uploading}
            {$_('certificateTemplate.uploadingButton')}
          {:else if metadata}
            {$_('certificateTemplate.replaceButton')}
          {:else}
            {$_('certificateTemplate.uploadButton')}
          {/if}
        </button>

        {#if metadata}
          <button
            class="btn btn--danger"
            disabled={uploading || deleting}
            onclick={handleDelete}
          >
            {deleting ? '…' : $_('certificateTemplate.deleteButton')}
          </button>
        {/if}
      </div>

      <!-- Upload progress bar (AC1) -->
      {#if uploading}
        <div class="cert-template__progress">
          <div class="progress-bar">
            <div class="progress-bar__fill" style="width: {uploadProgress}%"></div>
          </div>
          <span class="progress-bar__label">{uploadProgress}%</span>
        </div>
      {/if}

      <!-- Inline error messages (AC7) -->
      {#if uploadError}
        <p class="cert-template__error cert-template__error--inline">{uploadError}</p>
      {/if}
      {#if deleteError}
        <p class="cert-template__error cert-template__error--inline">{deleteError}</p>
      {/if}
    </section>

    <!-- ── Preview (AC6) ─────────────────────────────────────────────────── -->
    {#if metadata}
      <section class="cert-template__section">
        <h2 class="cert-template__section-title">{$_('certificateTemplate.previewTitle')}</h2>

        {#if previewLoading}
          <p class="cert-template__loading">…</p>
        {:else if previewError}
          <p class="cert-template__error">{previewError}</p>
        {:else if metadata.format === 'svg' && svgContent}
          <!-- SVG: inline render (AC6 — "render preview with sample data") -->
          <!-- Note: template was uploaded by authenticated admin; inline render is acceptable -->
          <!-- SVG was uploaded by the authenticated admin; inline render is acceptable.
               svelte-ignore html_dangerous_html -->
          <div
            class="cert-template__preview cert-template__preview--svg"
            role="img"
            aria-label="Urkunden-Vorschau"
          >
            {@html svgContent}
          </div>
        {:else if metadata.format === 'html'}
          <!-- HTML: iframe pointing to API endpoint (AC6) -->
          <iframe
            class="cert-template__preview cert-template__preview--html"
            src="/api/tournaments/{tournamentId}/certificate-template"
            title="Urkunden-Vorschau"
            sandbox="allow-same-origin"
          ></iframe>
        {/if}
      </section>
    {/if}

    <!-- ── Variable Reference (AC5) ─────────────────────────────────────── -->
    <section class="cert-template__section">
      <h2 class="cert-template__section-title">{$_('certificateTemplate.variablesTitle')}</h2>

      {#if variablesError}
        <p class="cert-template__error">{variablesError}</p>
      {:else if variables.length === 0}
        <p class="cert-template__loading">…</p>
      {:else}
        <table class="cert-template__vars-table">
          <thead>
            <tr>
              <th>{$_('certificateTemplate.columns.variable')}</th>
              <th>{$_('certificateTemplate.columns.type')}</th>
              <th>{$_('certificateTemplate.columns.example')}</th>
            </tr>
          </thead>
          <tbody>
            {#each variables as v (v.name)}
              {@const placeholder = '{{' + v.name + '}}'}
              <tr>
                <td><code class="cert-template__var-name">{placeholder}</code></td>
                <td>{v.type}</td>
                <td><span class="cert-template__var-example">{v.example}</span></td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>
  {/if}
</main>

<style>
  .cert-template {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 900px;
  }

  .cert-template__loading {
    color: #666;
  }

  .cert-template__error {
    color: #c0392b;
  }

  .cert-template__error--inline {
    font-size: 0.9rem;
    margin-top: 0.5rem;
  }

  /* ── Sections ──────────────────────────────────────────────────────────── */

  .cert-template__section {
    margin-bottom: 2rem;
    padding: 1.25rem;
    border: 1px solid #ddd;
    border-radius: 6px;
    background: #fafafa;
  }

  .cert-template__section-title {
    margin: 0 0 1rem 0;
    font-size: 1.1rem;
    color: #2c3e50;
  }

  /* ── Metadata ──────────────────────────────────────────────────────────── */

  .cert-template__no-template {
    font-size: 0.9rem;
    color: #aaa;
    font-style: italic;
    margin: 0 0 1rem 0;
  }

  .cert-template__meta {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: 0.3rem 1rem;
    margin: 0 0 1rem 0;
    font-size: 0.9rem;
  }

  .cert-template__meta dt {
    font-weight: 600;
    color: #555;
  }

  .cert-template__meta dd {
    margin: 0;
    color: #2c3e50;
  }

  /* ── Upload area ───────────────────────────────────────────────────────── */

  .cert-template__upload {
    display: flex;
    gap: 0.5rem;
    align-items: center;
    flex-wrap: wrap;
  }

  .cert-template__file-input {
    display: none;
  }

  /* ── Progress bar ──────────────────────────────────────────────────────── */

  .cert-template__progress {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-top: 0.5rem;
  }

  .progress-bar {
    flex: 1;
    height: 6px;
    background: #ecf0f1;
    border-radius: 3px;
    overflow: hidden;
    max-width: 300px;
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
  }

  /* ── Preview ───────────────────────────────────────────────────────────── */

  .cert-template__preview {
    border: 1px solid #ccc;
    border-radius: 4px;
    overflow: auto;
    background: #fff;
  }

  .cert-template__preview--svg {
    padding: 1rem;
    min-height: 200px;
    max-height: 600px;
  }

  .cert-template__preview--svg :global(svg) {
    max-width: 100%;
    height: auto;
  }

  .cert-template__preview--html {
    width: 100%;
    height: 500px;
    border: none;
  }

  /* ── Variable table (AC5) ──────────────────────────────────────────────── */

  .cert-template__vars-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.9rem;
  }

  .cert-template__vars-table th,
  .cert-template__vars-table td {
    text-align: left;
    padding: 0.4rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .cert-template__vars-table th {
    font-weight: 600;
    background: #f5f5f5;
    color: #555;
  }

  .cert-template__var-name {
    font-family: monospace;
    font-size: 0.85rem;
    background: #f0f4f8;
    padding: 0.1rem 0.3rem;
    border-radius: 3px;
    color: #2c3e50;
  }

  .cert-template__var-example {
    color: #7f8c8d;
    font-style: italic;
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

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--danger {
    background: #e74c3c;
    color: #fff;
  }
</style>
