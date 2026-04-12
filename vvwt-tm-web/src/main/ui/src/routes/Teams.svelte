<script lang="ts">
  /**
   * Team management grid view — Story E05S05 AC6, AC7, AC8, AC9, AC10, AC11, AC12.
   *
   * Receives the selected tournament ID via the route parameter `params.tournamentId`.
   *
   * The organizer can:
   *   - View all teams in a grid (AC6)
   *   - Add a new team row with auto-suggested team number (AC7)
   *   - Edit team name and flags inline; unsaved changes are highlighted (AC8)
   *   - See live team count and participating team count (AC9)
   *   - Delete a team with confirmation (AC4)
   *
   * All visible strings use the svelte-i18n `$_()` function (AC12 — no hardcoded strings).
   */
  import { onMount } from 'svelte';
  import { _ } from 'svelte-i18n';
  import {
    listTeams,
    createTeam,
    updateTeam,
    deleteTeam,
    type Team,
  } from '../stores/teamStore.js';

  // ── Props (Svelte 5 runes) ────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ─── State ────────────────────────────────────────────────────
  let teams = $state<Team[]>([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /**
   * Tracks per-row inline edit state.
   * Key: team.id — Value: mutable draft of the row being edited.
   */
  let editDrafts = $state<Record<string, Partial<Team>>>({});

  /** Per-row save error message. */
  let saveErrors = $state<Record<string, string>>({});

  /** Form state for the "add team" inline row (AC7). */
  let addRow = $state<{
    description: string;
    teamNumber: string;
    participate: boolean;
    refereeAssignment: boolean;
    withoutAssessment: boolean;
    saving: boolean;
    error: string | null;
  } | null>(null);

  // ─── Derived counts (AC9) ─────────────────────────────────────
  const totalTeams = $derived(teams.length);
  const participatingTeams = $derived(teams.filter(t => t.participate).length);

  // ─── Lifecycle ───────────────────────────────────────────────
  onMount(async () => {
    await load();
  });

  async function load(): Promise<void> {
    if (!tournamentId) {
      loadError = $_('teams.error.noTournament');
      loading = false;
      return;
    }
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

  // ─── Inline edit helpers (AC8) ───────────────────────────────

  /** Returns true if the given team row has unsaved changes. */
  function isDirty(teamId: string): boolean {
    return teamId in editDrafts;
  }

  /** Returns the editable value for a field, preferring the draft over the persisted value. */
  function draftValue<K extends keyof Team>(teamId: string, field: K, persisted: Team[K]): Team[K] {
    const draft = editDrafts[teamId];
    return draft && field in draft ? (draft[field] as Team[K]) : persisted;
  }

  /** Sets a draft field value, creating the draft if it doesn't exist. */
  function setDraft(teamId: string, field: keyof Team, value: unknown): void {
    editDrafts = {
      ...editDrafts,
      [teamId]: { ...(editDrafts[teamId] ?? {}), [field]: value },
    };
  }

  /** Saves the draft for a team row (on blur or explicit save). */
  async function saveDraft(team: Team): Promise<void> {
    const draft = editDrafts[team.id];
    if (!draft) return;

    const { description, teamNumber, participate, refereeAssignment, withoutAssessment } = draft;
    try {
      const updated = await updateTeam(tournamentId, team.id, {
        description: description != null ? String(description) : team.description,
        teamNumber: teamNumber != null ? Number(teamNumber) : null,
        participate: participate != null ? Boolean(participate) : team.participate,
        refereeAssignment: refereeAssignment != null ? Boolean(refereeAssignment) : team.refereeAssignment,
        withoutAssessment: withoutAssessment != null ? Boolean(withoutAssessment) : team.withoutAssessment,
      });
      // Merge updated record back into the list
      teams = teams.map(t => t.id === updated.id ? updated : t);
      // Clear draft and error
      const newDrafts = { ...editDrafts };
      delete newDrafts[team.id];
      editDrafts = newDrafts;
      const newErrors = { ...saveErrors };
      delete newErrors[team.id];
      saveErrors = newErrors;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      saveErrors = { ...saveErrors, [team.id]: msg };
    }
  }

  // ─── Add row (AC7) ───────────────────────────────────────────

  function openAddRow(): void {
    // Auto-suggest next team number (AC7)
    const maxNumber = teams.reduce((max, t) => Math.max(max, t.teamNumber), 0);
    addRow = {
      description: '',
      teamNumber: String(maxNumber + 1),
      participate: true,
      refereeAssignment: false,
      withoutAssessment: false,
      saving: false,
      error: null,
    };
  }

  async function submitAddRow(): Promise<void> {
    if (!addRow) return;
    addRow = { ...addRow, saving: true, error: null };
    try {
      const parsedNumber = parseInt(addRow.teamNumber, 10);
      const teamNumber = isNaN(parsedNumber) || parsedNumber < 1 ? 0 : parsedNumber;
      const created = await createTeam(tournamentId, {
        description: addRow.description,
        teamNumber: teamNumber > 0 ? teamNumber : undefined,
        participate: addRow.participate,
        refereeAssignment: addRow.refereeAssignment,
        withoutAssessment: addRow.withoutAssessment,
      });
      teams = [...teams, created].sort((a, b) => a.teamNumber - b.teamNumber);
      addRow = null;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      addRow = { ...addRow!, saving: false, error: msg };
    }
  }

  function cancelAddRow(): void {
    addRow = null;
  }

  // ─── Delete (AC4) ────────────────────────────────────────────

  let deleteError = $state<string | null>(null);

  async function handleDelete(team: Team): Promise<void> {
    if (!confirm($_('teams.deleteConfirm', { values: { name: team.description } }))) return;
    deleteError = null;
    try {
      await deleteTeam(tournamentId, team.id);
      teams = teams.filter(t => t.id !== team.id);
    } catch (e: unknown) {
      deleteError = e instanceof Error ? e.message : $_('teams.deleteError');
    }
  }
</script>

<main class="teams">
  <div class="teams__header">
    <h1>{$_('teams.title')}</h1>
    <button class="btn btn--primary" onclick={openAddRow} disabled={addRow !== null}>
      {$_('teams.addButton')}
    </button>
  </div>

  <!-- AC9: team count indicator -->
  <div class="teams__summary">
    <span>{$_('teams.totalCount', { values: { count: totalTeams } })}</span>
    <span>{$_('teams.participatingCount', { values: { count: participatingTeams } })}</span>
  </div>

  {#if loading}
    <p class="teams__loading">…</p>
  {:else if loadError}
    <p class="teams__error">{loadError}</p>
  {:else}
    {#if deleteError}
      <p class="teams__error">{deleteError}</p>
    {/if}

    <table class="teams__table">
      <thead>
        <tr>
          <th>{$_('teams.columns.number')}</th>
          <th>{$_('teams.columns.description')}</th>
          <th class="teams__flag-col">{$_('teams.columns.participate')}</th>
          <th class="teams__flag-col">{$_('teams.columns.refereeAssignment')}</th>
          <th class="teams__flag-col">{$_('teams.columns.withoutAssessment')}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {#each teams as team (team.id)}
          {@const dirty = isDirty(team.id)}
          <tr class:teams__row--dirty={dirty}>
            <!-- Team number: editable inline (AC8) -->
            <td>
              <input
                class="teams__input teams__input--number"
                type="number"
                min="1"
                value={draftValue(team.id, 'teamNumber', team.teamNumber)}
                oninput={(e) => setDraft(team.id, 'teamNumber', parseInt((e.target as HTMLInputElement).value, 10))}
                onblur={() => saveDraft(team)}
                aria-label={$_('teams.columns.number')}
              />
            </td>
            <!-- Description: editable inline (AC8) -->
            <td>
              <input
                class="teams__input teams__input--desc"
                type="text"
                value={draftValue(team.id, 'description', team.description)}
                oninput={(e) => setDraft(team.id, 'description', (e.target as HTMLInputElement).value)}
                onblur={() => saveDraft(team)}
                aria-label={$_('teams.columns.description')}
              />
            </td>
            <!-- Flags: inline checkboxes, save on change (AC8) -->
            <td class="teams__flag-col">
              <input
                type="checkbox"
                checked={draftValue(team.id, 'participate', team.participate)}
                onchange={(e) => { setDraft(team.id, 'participate', (e.target as HTMLInputElement).checked); saveDraft(team); }}
                aria-label={$_('teams.columns.participate')}
              />
            </td>
            <td class="teams__flag-col">
              <input
                type="checkbox"
                checked={draftValue(team.id, 'refereeAssignment', team.refereeAssignment)}
                onchange={(e) => { setDraft(team.id, 'refereeAssignment', (e.target as HTMLInputElement).checked); saveDraft(team); }}
                aria-label={$_('teams.columns.refereeAssignment')}
              />
            </td>
            <td class="teams__flag-col">
              <input
                type="checkbox"
                checked={draftValue(team.id, 'withoutAssessment', team.withoutAssessment)}
                onchange={(e) => { setDraft(team.id, 'withoutAssessment', (e.target as HTMLInputElement).checked); saveDraft(team); }}
                aria-label={$_('teams.columns.withoutAssessment')}
              />
            </td>
            <!-- Actions: dirty indicator + delete -->
            <td class="teams__actions">
              {#if dirty}
                <span class="badge badge--dirty" title={$_('teams.unsavedHint')}>●</span>
              {/if}
              {#if saveErrors[team.id]}
                <span class="teams__row-error" title={saveErrors[team.id]}>⚠</span>
              {/if}
              <button class="btn btn--danger btn--sm" onclick={() => handleDelete(team)}>
                {$_('teams.deleteButton')}
              </button>
            </td>
          </tr>
        {/each}

        <!-- AC7: inline add-team row -->
        {#if addRow !== null}
          <tr class="teams__row--add">
            <td>
              <input
                class="teams__input teams__input--number"
                type="number"
                min="1"
                bind:value={addRow.teamNumber}
                aria-label={$_('teams.columns.number')}
              />
            </td>
            <td>
              <input
                class="teams__input teams__input--desc"
                type="text"
                bind:value={addRow.description}
                placeholder={$_('teams.addPlaceholder')}
                aria-label={$_('teams.columns.description')}
              />
            </td>
            <td class="teams__flag-col">
              <input type="checkbox" bind:checked={addRow.participate} aria-label={$_('teams.columns.participate')} />
            </td>
            <td class="teams__flag-col">
              <input type="checkbox" bind:checked={addRow.refereeAssignment} aria-label={$_('teams.columns.refereeAssignment')} />
            </td>
            <td class="teams__flag-col">
              <input type="checkbox" bind:checked={addRow.withoutAssessment} aria-label={$_('teams.columns.withoutAssessment')} />
            </td>
            <td class="teams__actions">
              {#if addRow.error}
                <span class="teams__row-error" title={addRow.error}>⚠</span>
              {/if}
              <button class="btn btn--primary btn--sm" onclick={submitAddRow} disabled={addRow.saving}>
                {$_('teams.saveButton')}
              </button>
              <button class="btn btn--secondary btn--sm" onclick={cancelAddRow} disabled={addRow.saving}>
                {$_('teams.cancelButton')}
              </button>
            </td>
          </tr>
        {/if}
      </tbody>
    </table>

    {#if teams.length === 0 && addRow === null}
      <p class="teams__empty">{$_('teams.empty')}</p>
    {/if}
  {/if}
</main>

<style>
  .teams {
    padding: 2rem;
    font-family: sans-serif;
  }

  .teams__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 0.75rem;
  }

  .teams__header h1 {
    margin: 0;
  }

  .teams__summary {
    display: flex;
    gap: 1.5rem;
    font-size: 0.9rem;
    color: #555;
    margin-bottom: 1rem;
  }

  .teams__table {
    width: 100%;
    border-collapse: collapse;
  }

  .teams__table th,
  .teams__table td {
    text-align: left;
    padding: 0.4rem 0.6rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .teams__table th {
    font-weight: 600;
    background: #f5f5f5;
    font-size: 0.85rem;
  }

  .teams__flag-col {
    text-align: center;
    width: 6rem;
  }

  .teams__row--dirty td {
    background: #fffbea;
  }

  .teams__row--add td {
    background: #f0f8ff;
  }

  .teams__actions {
    display: flex;
    gap: 0.4rem;
    align-items: center;
    white-space: nowrap;
  }

  .teams__input {
    border: 1px solid #ccc;
    border-radius: 3px;
    padding: 0.25rem 0.4rem;
    font-size: 0.875rem;
  }

  .teams__input--number {
    width: 5rem;
  }

  .teams__input--desc {
    width: 100%;
    min-width: 10rem;
  }

  .teams__error {
    color: #c0392b;
    margin-bottom: 0.75rem;
  }

  .teams__row-error {
    color: #e74c3c;
    cursor: help;
    font-size: 1rem;
  }

  .teams__loading,
  .teams__empty {
    color: #666;
  }

  .badge--dirty {
    color: #e67e22;
    font-size: 0.75rem;
    cursor: default;
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
