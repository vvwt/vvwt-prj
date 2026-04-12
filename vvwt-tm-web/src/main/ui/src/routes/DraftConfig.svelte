<script lang="ts">
  /**
   * Draft configuration view — Story E05S06 AC8, AC9, AC10, AC12, AC13.
   *
   * Receives the selected tournament ID via route parameter `params.tournamentId`.
   *
   * The organizer can:
   *   - View and edit sections (AC8: add, edit, remove)
   *   - Preview the resulting phases/groups/matches (AC9)
   *   - Apply the draft to create phases, with confirmation dialog (AC10)
   *   - Apply button is hidden for non-DRAFT tournaments (AC12)
   *
   * All visible strings use the svelte-i18n `$_()` function (AC13).
   */
  import { onMount } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from '../lib/api.js';

  // ── Types ─────────────────────────────────────────────────────────────────

  interface DraftSection {
    sectionNumber: number;
    sortType: string;
    groupCount: number;
    gameMode: string;
    lapBreakTimeMinutes: number;
    sectionBreakTimeMinutes: number;
    lapTimeMinutes: number;
    setQuantity: number;
  }

  interface DraftPreviewSection {
    phaseNumber: number;
    groupCount: number;
    teamsPerGroup: number;
    matchesPerGroup: number;
    totalLaps: number;
    totalMatches: number;
    estimatedTimeMinutes: number;
  }

  // ── Props ─────────────────────────────────────────────────────────────────

  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────

  /** Current sections list (mutable — organizer can add/edit/remove). */
  let sections = $state<DraftSection[]>([]);

  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let saveError = $state<string | null>(null);
  let saving = $state(false);

  /** Preview results — null if not yet requested. */
  let previewSections = $state<DraftPreviewSection[] | null>(null);
  let previewing = $state(false);
  let previewError = $state<string | null>(null);

  /** Apply state. */
  let applying = $state(false);
  let applyError = $state<string | null>(null);
  let appliedPhaseCount = $state<number | null>(null);

  /**
   * Whether the tournament is in DRAFT status.
   * Only DRAFT tournaments may have their draft modified or applied (AC2, AC12).
   */
  let isDraft = $state(true);

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  onMount(async () => {
    await load();
  });

  async function load(): Promise<void> {
    if (!tournamentId) {
      loadError = $_('draft.noTournament');
      loading = false;
      return;
    }
    loading = true;
    loadError = null;

    try {
      // Load tournament to check status (AC12)
      const tRes = await apiFetch(`/api/tournaments/${tournamentId}`);
      if (!tRes.ok) {
        loadError = $_('draft.loadError');
        return;
      }
      const tournament = await tRes.json();
      isDraft = tournament.status === 'DRAFT';

      // Load draft config (AC3)
      const dRes = await apiFetch(`/api/tournaments/${tournamentId}/draft`);
      if (!dRes.ok) {
        loadError = $_('draft.loadError');
        return;
      }
      const draft = await dRes.json();
      sections = draft.sections ?? [];
    } catch {
      loadError = $_('draft.loadError');
    } finally {
      loading = false;
    }
  }

  // ── Section management (AC8) ──────────────────────────────────────────────

  function addSection(): void {
    const nextNumber = sections.length + 1;
    sections = [
      ...sections,
      {
        sectionNumber: nextNumber,
        sortType: 'team_number',
        groupCount: 2,
        gameMode: 'roundrobin',
        lapBreakTimeMinutes: 5,
        sectionBreakTimeMinutes: 10,
        lapTimeMinutes: 15,
        setQuantity: 1,
      },
    ];
    previewSections = null; // invalidate preview
  }

  function removeSection(index: number): void {
    sections = sections.filter((_, i) => i !== index)
      .map((s, i) => ({ ...s, sectionNumber: i + 1 }));
    previewSections = null;
  }

  function updateSection(index: number, field: keyof DraftSection, value: string | number): void {
    sections = sections.map((s, i) =>
      i === index ? { ...s, [field]: value } : s
    );
    previewSections = null;
  }

  // ── Save draft (AC2) ──────────────────────────────────────────────────────

  async function saveDraft(): Promise<void> {
    if (!tournamentId || !isDraft) return;
    saving = true;
    saveError = null;
    try {
      const res = await apiFetch(`/api/tournaments/${tournamentId}/draft`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sections }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        saveError = body.message ?? $_('draft.saveError');
      }
    } catch {
      saveError = $_('draft.saveError');
    } finally {
      saving = false;
    }
  }

  // ── Preview (AC4, AC9) ────────────────────────────────────────────────────

  async function previewDraft(): Promise<void> {
    if (!tournamentId) return;
    previewing = true;
    previewError = null;
    previewSections = null;

    // Save first, then preview
    await saveDraft();
    if (saveError) {
      previewError = saveError;
      previewing = false;
      return;
    }

    try {
      const res = await apiFetch(`/api/tournaments/${tournamentId}/draft/preview`, {
        method: 'POST',
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        previewError = body.message ?? $_('draft.previewError');
      } else {
        const data = await res.json();
        previewSections = data.sections ?? [];
      }
    } catch {
      previewError = $_('draft.previewError');
    } finally {
      previewing = false;
    }
  }

  // ── Apply (AC5, AC10) ─────────────────────────────────────────────────────

  async function applyDraft(): Promise<void> {
    if (!tournamentId || !isDraft) return;
    if (!confirm($_('draft.applyConfirm'))) return;

    applying = true;
    applyError = null;

    // Save draft before apply
    await saveDraft();
    if (saveError) {
      applyError = saveError;
      applying = false;
      return;
    }

    try {
      const res = await apiFetch(`/api/tournaments/${tournamentId}/draft/apply`, {
        method: 'POST',
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        applyError = body.message ?? $_('draft.applyError');
      } else {
        const data = await res.json();
        appliedPhaseCount = (data.phaseIds ?? []).length;
        isDraft = false; // tournament is now PLANNED
      }
    } catch {
      applyError = $_('draft.applyError');
    } finally {
      applying = false;
    }
  }
</script>

<main class="draft">
  <h1>{$_('draft.title')}</h1>

  {#if loading}
    <p class="draft__loading">…</p>
  {:else if loadError}
    <p class="draft__error">{loadError}</p>
  {:else}

    <!-- Applied notice (AC12: Apply button hidden after apply) -->
    {#if appliedPhaseCount !== null}
      <div class="draft__notice">
        {$_('draft.apply.phasesCreated', { values: { count: appliedPhaseCount } })}
      </div>
    {/if}

    <!-- Section list (AC8) -->
    <section class="draft__sections">
      <h2>{$_('draft.sections.title')}</h2>

      {#if sections.length === 0}
        <p class="draft__empty">{$_('draft.sections.empty')}</p>
      {/if}

      {#each sections as section, i (section.sectionNumber)}
        <div class="draft__section-card">
          <div class="draft__section-header">
            <strong>{$_('draft.sections.fields.sectionNumber')} {section.sectionNumber}</strong>
            {#if isDraft}
              <button class="btn btn--danger btn--sm" onclick={() => removeSection(i)}>
                {$_('draft.sections.removeButton')}
              </button>
            {/if}
          </div>

          <div class="draft__section-fields">
            <!-- Sort type (AC1) -->
            <label class="draft__field">
              <span>{$_('draft.sections.fields.sortType')}</span>
              <select
                value={section.sortType}
                onchange={(e) => updateSection(i, 'sortType', (e.target as HTMLSelectElement).value)}
                disabled={!isDraft}
              >
                <option value="team_number">{$_('draft.sections.sortTypes.team_number')}</option>
                <option value="placement_group">{$_('draft.sections.sortTypes.placement_group')}</option>
                <option value="group_placement">{$_('draft.sections.sortTypes.group_placement')}</option>
              </select>
            </label>

            <!-- Group count (AC1) -->
            <label class="draft__field">
              <span>{$_('draft.sections.fields.groupCount')}</span>
              <input
                type="number"
                min="1"
                value={section.groupCount}
                oninput={(e) => updateSection(i, 'groupCount', parseInt((e.target as HTMLInputElement).value, 10) || 1)}
                disabled={!isDraft}
                class="draft__input draft__input--number"
              />
            </label>

            <!-- Game mode (AC1) -->
            <label class="draft__field">
              <span>{$_('draft.sections.fields.gameMode')}</span>
              <select
                value={section.gameMode}
                onchange={(e) => updateSection(i, 'gameMode', (e.target as HTMLSelectElement).value)}
                disabled={!isDraft}
              >
                <option value="roundrobin">{$_('draft.sections.gameModes.roundrobin')}</option>
              </select>
            </label>

            <!-- Lap time (AC1) -->
            <label class="draft__field">
              <span>{$_('draft.sections.fields.lapTimeMinutes')}</span>
              <input
                type="number"
                min="1"
                value={section.lapTimeMinutes}
                oninput={(e) => updateSection(i, 'lapTimeMinutes', parseInt((e.target as HTMLInputElement).value, 10) || 1)}
                disabled={!isDraft}
                class="draft__input draft__input--number"
              />
            </label>

            <!-- Lap break time (AC1) -->
            <label class="draft__field">
              <span>{$_('draft.sections.fields.lapBreakTimeMinutes')}</span>
              <input
                type="number"
                min="0"
                value={section.lapBreakTimeMinutes}
                oninput={(e) => updateSection(i, 'lapBreakTimeMinutes', Math.max(0, parseInt((e.target as HTMLInputElement).value, 10) || 0))}
                disabled={!isDraft}
                class="draft__input draft__input--number"
              />
            </label>

            <!-- Section break time (AC1) -->
            <label class="draft__field">
              <span>{$_('draft.sections.fields.sectionBreakTimeMinutes')}</span>
              <input
                type="number"
                min="0"
                value={section.sectionBreakTimeMinutes}
                oninput={(e) => updateSection(i, 'sectionBreakTimeMinutes', Math.max(0, parseInt((e.target as HTMLInputElement).value, 10) || 0))}
                disabled={!isDraft}
                class="draft__input draft__input--number"
              />
            </label>

            <!-- Set quantity (AC1) -->
            <label class="draft__field">
              <span>{$_('draft.sections.fields.setQuantity')}</span>
              <input
                type="number"
                min="1"
                value={section.setQuantity}
                oninput={(e) => updateSection(i, 'setQuantity', parseInt((e.target as HTMLInputElement).value, 10) || 1)}
                disabled={!isDraft}
                class="draft__input draft__input--number"
              />
            </label>
          </div>
        </div>
      {/each}

      {#if isDraft}
        <div class="draft__actions">
          <button class="btn btn--secondary" onclick={addSection}>
            {$_('draft.addSectionButton')}
          </button>
          <button class="btn btn--primary" onclick={saveDraft} disabled={saving}>
            {$_('draft.saveButton')}
          </button>
          {#if saveError}
            <span class="draft__error-inline">{saveError}</span>
          {/if}
        </div>
      {/if}
    </section>

    <!-- Preview (AC9) -->
    <section class="draft__preview">
      <div class="draft__preview-header">
        <h2>{$_('draft.preview.title')}</h2>
        <button
          class="btn btn--secondary"
          onclick={previewDraft}
          disabled={previewing || sections.length === 0}
        >
          {$_('draft.previewButton')}
        </button>
      </div>

      {#if previewError}
        <p class="draft__error">{previewError}</p>
      {:else if previewSections !== null}
        <table class="draft__preview-table">
          <thead>
            <tr>
              <th>{$_('draft.preview.columns.phaseNumber')}</th>
              <th>{$_('draft.preview.columns.groupCount')}</th>
              <th>{$_('draft.preview.columns.teamsPerGroup')}</th>
              <th>{$_('draft.preview.columns.matchesPerGroup')}</th>
              <th>{$_('draft.preview.columns.totalLaps')}</th>
              <th>{$_('draft.preview.columns.totalMatches')}</th>
              <th>{$_('draft.preview.columns.estimatedTimeMinutes')}</th>
            </tr>
          </thead>
          <tbody>
            {#each previewSections as row (row.phaseNumber)}
              <tr>
                <td>{row.phaseNumber}</td>
                <td>{row.groupCount}</td>
                <td>{row.teamsPerGroup}</td>
                <td>{row.matchesPerGroup}</td>
                <td>{row.totalLaps}</td>
                <td>{row.totalMatches}</td>
                <td>{row.estimatedTimeMinutes}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>

    <!-- Apply (AC10) — hidden for non-DRAFT tournaments (AC12) -->
    {#if isDraft && appliedPhaseCount === null}
      <section class="draft__apply">
        <button
          class="btn btn--primary draft__apply-btn"
          onclick={applyDraft}
          disabled={applying || sections.length === 0}
        >
          {$_('draft.applyButton')}
        </button>
        {#if applyError}
          <p class="draft__error">{applyError}</p>
        {/if}
      </section>
    {/if}

  {/if}
</main>

<style>
  .draft {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 900px;
  }

  .draft h1 {
    margin-bottom: 1.5rem;
  }

  .draft h2 {
    margin: 0 0 0.75rem;
    font-size: 1.1rem;
  }

  .draft__sections,
  .draft__preview,
  .draft__apply {
    margin-bottom: 2rem;
  }

  .draft__section-card {
    border: 1px solid #ddd;
    border-radius: 6px;
    padding: 1rem;
    margin-bottom: 0.75rem;
    background: #fafafa;
  }

  .draft__section-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 0.75rem;
  }

  .draft__section-fields {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 0.6rem 1rem;
  }

  .draft__field {
    display: flex;
    flex-direction: column;
    font-size: 0.875rem;
  }

  .draft__field span {
    font-weight: 600;
    margin-bottom: 0.2rem;
    color: #444;
  }

  .draft__field select,
  .draft__input {
    border: 1px solid #ccc;
    border-radius: 3px;
    padding: 0.25rem 0.4rem;
    font-size: 0.875rem;
    background: #fff;
  }

  .draft__input--number {
    width: 5rem;
  }

  .draft__actions {
    display: flex;
    gap: 0.75rem;
    align-items: center;
    margin-top: 0.75rem;
  }

  .draft__preview-header {
    display: flex;
    align-items: center;
    gap: 1rem;
    margin-bottom: 0.75rem;
  }

  .draft__preview-table {
    width: 100%;
    border-collapse: collapse;
  }

  .draft__preview-table th,
  .draft__preview-table td {
    text-align: left;
    padding: 0.35rem 0.6rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .draft__preview-table th {
    font-weight: 600;
    background: #f5f5f5;
    font-size: 0.85rem;
  }

  .draft__apply-btn {
    font-size: 1rem;
    padding: 0.6rem 1.5rem;
  }

  .draft__notice {
    background: #e8f5e9;
    border: 1px solid #a5d6a7;
    border-radius: 4px;
    padding: 0.75rem 1rem;
    margin-bottom: 1rem;
    color: #2e7d32;
  }

  .draft__error {
    color: #c0392b;
  }

  .draft__error-inline {
    color: #c0392b;
    font-size: 0.875rem;
  }

  .draft__loading,
  .draft__empty {
    color: #666;
  }

  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    font-size: 0.9rem;
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

  .btn--danger {
    background: #e74c3c;
    color: #fff;
  }

  .btn--sm {
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
  }

  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
</style>
