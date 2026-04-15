<script lang="ts">
  /**
   * Activity types management view — Story E08S06 AC3, AC4, AC5, AC6, AC8.
   *
   * Shows the list of configured activity types for the given tournament with
   * add / edit / delete actions and an assignment preview table.
   *
   * Route: /tournaments/:tournamentId/activities
   *
   * AC3 — Activity type list with name, rule, capacity, and actions.
   * AC4 — Create/edit form with name, assignment rule dropdown (V1: FIRST_FREE_ROUND only),
   *        and optional capacity per round.
   * AC5 — Preview table: columns = activity types, rows = rounds, cells = team names.
   *        Unassigned teams shown below with a warning.
   * AC6 — Empty state before any activity types are created.
   *        Informational message when phase is not prepared.
   * AC8 — All visible strings sourced from svelte-i18n.
   */
  import { onMount } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { push } from 'svelte-spa-router';
  import type {
    ActivityType,
    ActivityAssignmentPreview,
    ActivityTypeAssignment,
  } from '../stores/activityStore.js';
  import {
    listActivityTypes,
    createActivityType,
    updateActivityType,
    deleteActivityType,
    getActivityAssignmentPreview,
  } from '../stores/activityStore.js';

  // ─── Props (from svelte-spa-router) ───────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ─── State ─────────────────────────────────────────────────────
  let activityTypes: ActivityType[] = $state([]);
  let preview: ActivityAssignmentPreview | null = $state(null);

  let loadError = $state<string | null>(null);
  let loading = $state(true);
  let previewLoading = $state(false);
  let previewError = $state<string | null>(null);

  // Form state
  let showForm = $state(false);
  let editingId = $state<string | null>(null);
  let formName = $state('');
  let formRule = $state('FIRST_FREE_ROUND');
  let formCapacity = $state<string>('');  // string for input binding; parsed to int or null
  let formSortOrder = $state(0);
  let formError = $state<string | null>(null);
  let formSaving = $state(false);

  // Delete state
  let deleteError = $state<string | null>(null);

  // ─── Lifecycle ─────────────────────────────────────────────────
  onMount(async () => {
    if (!tournamentId) {
      loadError = $_('activityTypes.noTournament');
      loading = false;
      return;
    }
    await loadAll();
  });

  async function loadAll(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      activityTypes = await listActivityTypes(tournamentId);
      await loadPreview();
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function loadPreview(): Promise<void> {
    previewLoading = true;
    previewError = null;
    try {
      preview = await getActivityAssignmentPreview(tournamentId);
    } catch (e: unknown) {
      previewError = e instanceof Error ? e.message : String(e);
    } finally {
      previewLoading = false;
    }
  }

  // ─── Form helpers ──────────────────────────────────────────────

  function openCreateForm(): void {
    editingId = null;
    formName = '';
    formRule = 'FIRST_FREE_ROUND';
    formCapacity = '';
    formSortOrder = activityTypes.length + 1;
    formError = null;
    showForm = true;
  }

  function openEditForm(at: ActivityType): void {
    editingId = at.id;
    formName = at.name;
    formRule = at.assignmentRule;
    formCapacity = at.capacityPerRound !== null ? String(at.capacityPerRound) : '';
    formSortOrder = at.sortOrder;
    formError = null;
    showForm = true;
  }

  function cancelForm(): void {
    showForm = false;
    formError = null;
  }

  async function submitForm(): Promise<void> {
    formError = null;
    formSaving = true;
    const capacityParsed = formCapacity.trim() === '' ? null : parseInt(formCapacity, 10);
    if (capacityParsed !== null && (isNaN(capacityParsed) || capacityParsed < 1)) {
      formError = $_('activityTypes.form.capacityInvalid');
      formSaving = false;
      return;
    }
    const data = {
      name: formName.trim(),
      assignmentRule: formRule,
      capacityPerRound: capacityParsed,
      sortOrder: formSortOrder,
    };
    try {
      if (editingId) {
        await updateActivityType(tournamentId, editingId, data);
      } else {
        await createActivityType(tournamentId, data);
      }
      showForm = false;
      await loadAll();
    } catch (e: unknown) {
      // Surface API error message (handles 409 duplicate name — AC7)
      const anyErr = e as { apiError?: { message?: string }; message?: string };
      formError = anyErr.apiError?.message ?? anyErr.message ?? $_('activityTypes.saveError');
    } finally {
      formSaving = false;
    }
  }

  // ─── Delete ────────────────────────────────────────────────────

  async function handleDelete(at: ActivityType): Promise<void> {
    if (!confirm($_('activityTypes.deleteConfirm', { values: { name: at.name } }))) return;
    deleteError = null;
    try {
      await deleteActivityType(tournamentId, at.id);
      await loadAll();
    } catch (e: unknown) {
      deleteError = e instanceof Error ? e.message : $_('activityTypes.deleteError');
    }
  }

  // ─── Preview helpers ───────────────────────────────────────────

  /** True when preview is empty (no assignments AND phaseId is null — not prepared). */
  function isPhaseNotPrepared(): boolean {
    return preview !== null
        && preview.assignments.length === 0
        && preview.phaseId === null;
  }

  /** True when preview has data to display. */
  function hasPreviewData(): boolean {
    return preview !== null && preview.assignments.length > 0;
  }

  /** Collect all unique lap numbers across all activity types in the preview. */
  function allLapNumbers(assignments: ActivityTypeAssignment[]): number[] {
    const lapSet = new Set<number>();
    for (const a of assignments) {
      for (const e of a.entries) {
        lapSet.add(e.lapNumber);
      }
    }
    return [...lapSet].sort((a, b) => a - b);
  }

  /** Get team names for a given activity type and lap number in the preview. */
  function teamsForLap(assignment: ActivityTypeAssignment, lap: number): string {
    const entry = assignment.entries.find(e => e.lapNumber === lap);
    if (!entry || entry.teams.length === 0) return '–';
    return entry.teams.map(t => `${t.teamNumber}. ${t.teamName}`).join(', ');
  }
</script>

<main class="activity-types">
  <div class="activity-types__header">
    <div class="activity-types__nav">
      <button class="btn btn--secondary btn--sm" onclick={() => push('/tournaments')}>
        ← {$_('activityTypes.backButton')}
      </button>
    </div>
    <h1>{$_('activityTypes.title')}</h1>
    <button class="btn btn--primary" onclick={openCreateForm}>
      {$_('activityTypes.createButton')}
    </button>
  </div>

  {#if loading}
    <p class="activity-types__loading">…</p>
  {:else if loadError}
    <p class="activity-types__error">{loadError}</p>
  {:else}

    <!-- ─── Activity Type List (AC3) ─── -->
    {#if activityTypes.length === 0}
      <!-- AC6: empty state -->
      <div class="activity-types__empty">
        <p>{$_('activityTypes.empty')}</p>
        <button class="btn btn--primary" onclick={openCreateForm}>
          {$_('activityTypes.createButton')}
        </button>
      </div>
    {:else}
      {#if deleteError}
        <p class="activity-types__error">{deleteError}</p>
      {/if}
      <table class="activity-types__table">
        <thead>
          <tr>
            <th>{$_('activityTypes.columns.name')}</th>
            <th>{$_('activityTypes.columns.rule')}</th>
            <th>{$_('activityTypes.columns.capacity')}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {#each activityTypes as at (at.id)}
            <tr>
              <td>{at.name}</td>
              <td>{$_(`activityTypes.rules.${at.assignmentRule}`, { default: at.assignmentRule })}</td>
              <td>{at.capacityPerRound !== null ? at.capacityPerRound : $_('activityTypes.capacityUnlimited')}</td>
              <td class="activity-types__actions">
                <button class="btn btn--secondary btn--sm" onclick={() => openEditForm(at)}>
                  {$_('activityTypes.editButton')}
                </button>
                <button class="btn btn--danger btn--sm" onclick={() => handleDelete(at)}>
                  {$_('activityTypes.deleteButton')}
                </button>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    {/if}

    <!-- ─── Create / Edit Form (AC4) ─── -->
    {#if showForm}
      <div class="activity-types__form-overlay">
        <div class="activity-types__form">
          <h2>{editingId ? $_('activityTypes.form.editTitle') : $_('activityTypes.form.createTitle')}</h2>

          {#if formError}
            <p class="form__error">{formError}</p>
          {/if}

          <label class="form__label">
            {$_('activityTypes.form.nameLabel')}
            <input class="form__input" type="text" bind:value={formName}
                   placeholder={$_('activityTypes.form.namePlaceholder')} />
          </label>

          <!-- Assignment rule dropdown: V1 shows only FIRST_FREE_ROUND (AC4) -->
          <label class="form__label">
            {$_('activityTypes.form.ruleLabel')}
            <select class="form__select" bind:value={formRule}>
              <option value="FIRST_FREE_ROUND">
                {$_('activityTypes.rules.FIRST_FREE_ROUND')}
              </option>
            </select>
          </label>

          <!-- Capacity: optional number input (AC4) -->
          <label class="form__label">
            {$_('activityTypes.form.capacityLabel')}
            <input class="form__input" type="number" min="1" bind:value={formCapacity} />
          </label>

          <div class="form__actions">
            <button class="btn btn--primary" onclick={submitForm} disabled={formSaving}>
              {formSaving ? '…' : $_('activityTypes.saveButton')}
            </button>
            <button class="btn btn--secondary" onclick={cancelForm} disabled={formSaving}>
              {$_('activityTypes.cancelButton')}
            </button>
          </div>
        </div>
      </div>
    {/if}

    <!-- ─── Assignment Preview (AC5, AC6) ─── -->
    <section class="activity-types__preview">
      <h2>{$_('activityTypes.preview.title')}</h2>

      {#if previewLoading}
        <p class="activity-types__loading">…</p>
      {:else if previewError}
        <p class="activity-types__error">{previewError}</p>
      {:else if activityTypes.length === 0}
        <!-- AC6: no activity types yet — suppress preview section -->
        <!-- (empty state already shown above) -->
      {:else if isPhaseNotPrepared()}
        <!-- AC6: phase not prepared message -->
        <p class="activity-types__info">{$_('activityTypes.preview.notPrepared')}</p>
      {:else if hasPreviewData() && preview}
        <!-- AC5: preview table -->
        {@const laps = allLapNumbers(preview.assignments)}
        <table class="preview__table">
          <thead>
            <tr>
              <th>{$_('activityTypes.columns.sortOrder')}</th>
              {#each preview.assignments as a}
                <th>{a.activityTypeName}</th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each laps as lap}
              <tr>
                <td class="preview__lap">{$_('activityTypes.preview.lapLabel', { values: { lap } })}</td>
                {#each preview.assignments as a}
                  <td>{teamsForLap(a, lap)}</td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>

        <!-- Unassigned teams warning (AC5) -->
        {#each preview.unassigned as u}
          {#if u.teams.length > 0}
            <div class="preview__warning">
              <strong>{$_('activityTypes.preview.unassignedNote')} {u.activityTypeName}:</strong>
              <span>{u.teams.map(t => `${t.teamNumber}. ${t.teamName}`).join(', ')}</span>
            </div>
          {/if}
        {/each}

      {:else if preview && preview.assignments.length === 0 && preview.phaseId !== null}
        <!-- Phase exists but no assignments yet (e.g. no teams or no slotted matches) -->
        <p class="activity-types__info">{$_('activityTypes.preview.empty')}</p>
      {/if}
    </section>

  {/if}
</main>

<style>
  .activity-types {
    padding: 2rem;
    font-family: sans-serif;
  }

  .activity-types__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 1.5rem;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .activity-types__header h1 {
    margin: 0;
  }

  .activity-types__nav {
    flex: 0 0 auto;
  }

  .activity-types__table {
    width: 100%;
    border-collapse: collapse;
    margin-bottom: 2rem;
  }

  .activity-types__table th,
  .activity-types__table td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .activity-types__table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .activity-types__actions {
    white-space: nowrap;
    display: flex;
    gap: 0.4rem;
    align-items: center;
  }

  .activity-types__empty {
    padding: 2rem;
    text-align: center;
    color: #666;
  }

  .activity-types__error {
    color: #c0392b;
    margin-bottom: 1rem;
  }

  .activity-types__loading,
  .activity-types__info {
    color: #666;
  }

  /* ─── Form overlay (AC4) ─── */
  .activity-types__form-overlay {
    position: fixed;
    inset: 0;
    background: rgba(0,0,0,0.4);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 100;
  }

  .activity-types__form {
    background: #fff;
    border-radius: 8px;
    padding: 2rem;
    min-width: 360px;
    max-width: 480px;
    width: 100%;
    box-shadow: 0 4px 24px rgba(0,0,0,0.18);
  }

  .activity-types__form h2 {
    margin: 0 0 1.5rem;
  }

  .form__label {
    display: flex;
    flex-direction: column;
    margin-bottom: 1rem;
    font-size: 0.9rem;
    font-weight: 500;
    gap: 0.25rem;
  }

  .form__input,
  .form__select {
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    padding: 0.4rem 0.6rem;
    font-size: 0.9rem;
  }

  .form__error {
    color: #c0392b;
    margin-bottom: 1rem;
    font-size: 0.9rem;
  }

  .form__actions {
    display: flex;
    gap: 0.75rem;
    margin-top: 1.5rem;
  }

  /* ─── Preview table (AC5) ─── */
  .activity-types__preview {
    margin-top: 2rem;
  }

  .activity-types__preview h2 {
    margin-bottom: 1rem;
  }

  .preview__table {
    width: 100%;
    border-collapse: collapse;
    margin-bottom: 1rem;
  }

  .preview__table th,
  .preview__table td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .preview__table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .preview__lap {
    font-weight: 600;
    color: #555;
  }

  .preview__warning {
    background: #fff3cd;
    border: 1px solid #ffc107;
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin-bottom: 0.5rem;
    font-size: 0.9rem;
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
  }

  /* ─── Shared button styles ─── */
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
    opacity: 0.6;
    cursor: not-allowed;
  }
</style>
