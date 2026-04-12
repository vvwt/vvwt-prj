<script lang="ts">
  /**
   * Team mapping between phases — Story E05S08.
   *
   * Allows the organiser to assign teams from a completed source phase into
   * groups for the next (PENDING) target phase.
   *
   * Behaviour:
   *   AC6  — Source panel lists teams from previous COMPLETED phase per group,
   *           ordered by placement. Target panel has a move-to-group dropdown
   *           per team. The organiser assigns each team a group number and
   *           position within that group.
   *   AC7  — Validation: all teams must be assigned, no duplicates in the same
   *           (groupNumber, groupPosition) slot. Apply button is disabled until
   *           valid. Unassigned teams are highlighted in red.
   *   AC8  — Apply triggers POST /api/phases/{phaseId}/mapping and navigates
   *           back to PhaseOverview on success.
   *   AC9  — If the previous phase is not COMPLETED the endpoint returns 409.
   *           The error message is shown and the user cannot proceed.
   *   AC10 — If TeamAvatars already exist the component shows them read-only
   *           with a "Re-do mapping" button. After user confirmation the POST
   *           /api/phases/{phaseId}/mapping/redo endpoint is called.
   *   AC11 — All visible strings use the svelte-i18n $_() function.
   */

  import { onMount } from 'svelte';
  import { push } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from '../lib/api.js';

  // ── Types ──────────────────────────────────────────────────────────────────

  interface SourceTeam {
    teamId: string;
    avatarId: string;
    description: string;
    points: number;
    setsWon: number;
    setsLost: number;
    withoutAssessment: boolean;
  }

  interface SourceGroup {
    groupNumber: number;
    teams: SourceTeam[];
  }

  interface TargetAssignment {
    teamId: string;
    groupNumber: number;
    groupPosition: number;
  }

  interface MappingSuggestionResponse {
    sourcePhaseId: string;
    targetPhaseId: string;
    sourceGroups: SourceGroup[];
    suggestedAssignments: TargetAssignment[];
    hasExistingMapping: boolean;
  }

  interface AvatarEntry {
    avatarId: string;
    teamId: string;
    groupNumber: number;
    groupPosition: number;
  }

  interface MappingApplyResponse {
    phaseId: string;
    avatarsCreated: AvatarEntry[];
  }

  /** A mutable assignment row used in the target panel. */
  interface AssignmentRow {
    teamId: string;
    description: string;
    withoutAssessment: boolean;
    groupNumber: number | null;
    groupPosition: number | null;
  }

  // ── Props ──────────────────────────────────────────────────────────────────

  interface Props {
    params?: { tournamentId?: string; phaseId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');
  const phaseId = $derived(params.phaseId ?? '');

  // ── State ──────────────────────────────────────────────────────────────────

  let loading = $state(true);
  let loadError = $state<string | null>(null);

  /** Suggestion data from the server — source groups + suggested assignments. */
  let suggestion = $state<MappingSuggestionResponse | null>(null);

  /**
   * Mutable assignment rows.  Initially populated from suggestion.suggestedAssignments.
   * The user edits groupNumber / groupPosition via dropdowns.
   */
  let assignments = $state<AssignmentRow[]>([]);

  /** Total number of target groups derived from the suggestion data. */
  let targetGroupCount = $derived<number>(
    suggestion
      ? Math.max(...suggestion.suggestedAssignments.map(a => a.groupNumber), 0)
      : 0
  );

  /** Whether TeamAvatars already exist for this phase (read-only mode until redo is confirmed). */
  let alreadyMapped = $state(false);

  /** Re-do confirmation dialog visible. */
  let showRedoConfirm = $state(false);

  /** True after the organizer confirmed the redo — unlocks the redo-apply button. */
  let redoConfirmed = $state(false);

  let applying = $state(false);
  let actionError = $state<string | null>(null);

  // ── Validation ─────────────────────────────────────────────────────────────

  /** True if all assignments have a valid groupNumber and groupPosition. */
  const allAssigned = $derived(
    assignments.every(a => a.groupNumber !== null && a.groupPosition !== null)
  );

  /**
   * Set of (groupNumber, groupPosition) keys that appear more than once —
   * used to highlight duplicate slots.
   */
  const duplicateSlots = $derived<Set<string>>(() => {
    const seen = new Map<string, number>();
    for (const a of assignments) {
      if (a.groupNumber !== null && a.groupPosition !== null) {
        const key = `${a.groupNumber}:${a.groupPosition}`;
        seen.set(key, (seen.get(key) ?? 0) + 1);
      }
    }
    const dupes = new Set<string>();
    for (const [k, count] of seen) {
      if (count > 1) dupes.add(k);
    }
    return dupes;
  });

  const hasDuplicates = $derived(duplicateSlots.size > 0);

  /** Apply button is enabled only when all assigned and no duplicates. */
  const canApply = $derived(allAssigned && !hasDuplicates);

  // ── Data loading ───────────────────────────────────────────────────────────

  async function loadSuggestion(): Promise<void> {
    if (!phaseId) return;
    loading = true;
    loadError = null;
    alreadyMapped = false;
    redoConfirmed = false;
    showRedoConfirm = false;

    try {
      const resp = await apiFetch(`/api/phases/${phaseId}/mapping-suggestion`);

      if (resp.status === 409) {
        // AC9 — previous phase not COMPLETED
        const err = await resp.json().catch(() => ({}));
        const msg: string = (err as any)?.message ?? '';
        loadError = msg || $_('mapping.errorPreviousNotCompleted');
        return;
      }

      if (!resp.ok) {
        loadError = $_('mapping.loadError');
        return;
      }

      const data: MappingSuggestionResponse = await resp.json();
      suggestion = data;

      // AC10: if TeamAvatars already exist, switch to read-only mode immediately
      if (data.hasExistingMapping) {
        alreadyMapped = true;
      }

      // Populate editable rows from suggested assignments, preserving team order
      const teamMeta: Record<string, { description: string; withoutAssessment: boolean }> = {};
      for (const sg of data.sourceGroups) {
        for (const t of sg.teams) {
          teamMeta[t.teamId] = { description: t.description, withoutAssessment: t.withoutAssessment };
        }
      }
      assignments = data.suggestedAssignments.map(sa => ({
        teamId: sa.teamId,
        description: teamMeta[sa.teamId]?.description ?? sa.teamId,
        withoutAssessment: teamMeta[sa.teamId]?.withoutAssessment ?? false,
        groupNumber: sa.groupNumber,
        groupPosition: sa.groupPosition,
      }));

    } catch {
      loadError = $_('mapping.loadError');
    } finally {
      loading = false;
    }
  }

  // ── Actions ────────────────────────────────────────────────────────────────

  async function applyMapping(): Promise<void> {
    if (!canApply) return;
    applying = true;
    actionError = null;
    try {
      const body = {
        assignments: assignments.map(a => ({
          teamId: a.teamId,
          groupNumber: a.groupNumber,
          groupPosition: a.groupPosition,
        })),
      };
      const resp = await apiFetch(`/api/phases/${phaseId}/mapping`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (resp.status === 409) {
        // Already exist — switch to read-only mode
        alreadyMapped = true;
        actionError = null;
        return;
      }
      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        actionError = (err as any)?.message ?? $_('mapping.applyError');
        return;
      }

      // AC8 — navigate back to phase overview on success
      push(`/tournaments/${tournamentId}/phases`);
    } catch {
      actionError = $_('mapping.applyError');
    } finally {
      applying = false;
    }
  }

  async function redoMapping(): Promise<void> {
    if (!canApply) return;
    applying = true;
    actionError = null;
    showRedoConfirm = false;
    try {
      const body = {
        assignments: assignments.map(a => ({
          teamId: a.teamId,
          groupNumber: a.groupNumber,
          groupPosition: a.groupPosition,
        })),
      };
      const resp = await apiFetch(`/api/phases/${phaseId}/mapping/redo`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      if (!resp.ok) {
        const err = await resp.json().catch(() => ({}));
        actionError = (err as any)?.message ?? $_('mapping.applyError');
        return;
      }

      push(`/tournaments/${tournamentId}/phases`);
    } catch {
      actionError = $_('mapping.applyError');
    } finally {
      applying = false;
    }
  }

  function startRedo(): void {
    // AC10: confirmed redo — unlock the editor and the redo-apply button
    showRedoConfirm = false;
    redoConfirmed = true;
    // alreadyMapped stays true so redoMapping() endpoint is used instead of applyMapping()
  }

  function slotKey(a: AssignmentRow): string {
    return `${a.groupNumber}:${a.groupPosition}`;
  }

  function isDuplicate(a: AssignmentRow): boolean {
    if (a.groupNumber === null || a.groupPosition === null) return false;
    return duplicateSlots.has(slotKey(a));
  }

  function groupsArray(): number[] {
    return Array.from({ length: targetGroupCount }, (_, i) => i + 1);
  }

  function positionsInGroup(groupNumber: number): number[] {
    // Max position = number of teams assigned to this group (plus one for new slot)
    const assigned = assignments.filter(a => a.groupNumber === groupNumber);
    return Array.from({ length: Math.max(assigned.length, 1) }, (_, i) => i + 1);
  }

  // ── Lifecycle ──────────────────────────────────────────────────────────────

  onMount(() => {
    loadSuggestion();
  });
</script>

<div class="phase-mapping">
  <h2>{$_('mapping.title')}</h2>

  {#if loading}
    <p>{$_('mapping.loading')}</p>
  {:else if loadError}
    <!-- AC9: previous phase not completed or other load error -->
    <div class="error-box">
      <p>{loadError}</p>
    </div>
    <button class="btn btn-secondary" onclick={() => push(`/tournaments/${tournamentId}/phases`)}>
      {$_('mapping.backButton')}
    </button>

  {:else if suggestion}
    <!-- AC10: if mapping already exists, show notice + redo option -->
    {#if alreadyMapped && !showRedoConfirm}
      <div class="info-box">
        <p>{$_('mapping.alreadyMappedNotice')}</p>
        <div class="action-bar">
          <button class="btn btn-warning" onclick={() => { showRedoConfirm = true; }}>
            {$_('mapping.redoButton')}
          </button>
          <button class="btn btn-secondary" onclick={() => push(`/tournaments/${tournamentId}/phases`)}>
            {$_('mapping.backButton')}
          </button>
        </div>
      </div>
    {/if}

    {#if showRedoConfirm}
      <!-- AC10: confirmation dialog before redo -->
      <div class="confirm-dialog">
        <p>{$_('mapping.redoConfirm')}</p>
        <button class="btn btn-danger" onclick={startRedo}>
          {$_('mapping.redoConfirmButton')}
        </button>
        <button class="btn btn-secondary" onclick={() => { showRedoConfirm = false; }}>
          {$_('mapping.cancelButton')}
        </button>
      </div>
    {/if}

    <!-- AC6: source and target panels (shown always for reference; apply disabled when alreadyMapped) -->
    <div class="mapping-layout">

      <!-- Left panel: source groups from the previous phase -->
      <section class="source-panel">
        <h3>{$_('mapping.sourcePanel.title')}</h3>
        {#each suggestion.sourceGroups as group (group.groupNumber)}
          <div class="source-group">
            <h4>{$_('mapping.sourcePanel.groupLabel', { values: { n: group.groupNumber } })}</h4>
            <ol class="team-list">
              {#each group.teams as team, idx (team.teamId)}
                <li class="team-item" class:without-assessment={team.withoutAssessment}>
                  <span class="placement">{idx + 1}.</span>
                  <span class="team-name">{team.description}</span>
                  {#if team.withoutAssessment}
                    <span class="badge-no-assessment">{$_('mapping.noAssessment')}</span>
                  {/if}
                  <span class="team-stats">
                    {$_('mapping.sourcePanel.stats', {
                      values: { pts: team.points, sw: team.setsWon, sl: team.setsLost }
                    })}
                  </span>
                </li>
              {/each}
            </ol>
          </div>
        {/each}
      </section>

      <!-- Right panel: assignment editor -->
      <section class="target-panel">
        <h3>{$_('mapping.targetPanel.title')}</h3>
        <p class="hint">{$_('mapping.targetPanel.hint')}</p>

        <table class="assignment-table">
          <thead>
            <tr>
              <th>{$_('mapping.targetPanel.teamColumn')}</th>
              <th>{$_('mapping.targetPanel.groupColumn')}</th>
              <th>{$_('mapping.targetPanel.positionColumn')}</th>
            </tr>
          </thead>
          <tbody>
            {#each assignments as row (row.teamId)}
              {@const unassigned = row.groupNumber === null || row.groupPosition === null}
              {@const dup = isDuplicate(row)}
              <tr class:row-unassigned={unassigned} class:row-duplicate={dup}>
                <td class="team-cell">
                  {row.description}
                  {#if row.withoutAssessment}
                    <span class="badge-no-assessment">{$_('mapping.noAssessment')}</span>
                  {/if}
                </td>
                <td>
                  <select
                    value={row.groupNumber ?? ''}
                    onchange={(e) => {
                      const v = parseInt((e.target as HTMLSelectElement).value, 10);
                      row.groupNumber = isNaN(v) ? null : v;
                      row.groupPosition = null; // reset position when group changes
                    }}
                  >
                    <option value="">{$_('mapping.targetPanel.selectGroup')}</option>
                    {#each groupsArray() as g (g)}
                      <option value={g}>{$_('mapping.targetPanel.groupOption', { values: { n: g } })}</option>
                    {/each}
                  </select>
                </td>
                <td>
                  {#if row.groupNumber !== null}
                    <select
                      value={row.groupPosition ?? ''}
                      onchange={(e) => {
                        const v = parseInt((e.target as HTMLSelectElement).value, 10);
                        row.groupPosition = isNaN(v) ? null : v;
                      }}
                    >
                      <option value="">{$_('mapping.targetPanel.selectPosition')}</option>
                      {#each positionsInGroup(row.groupNumber) as p (p)}
                        <option value={p}>{p}</option>
                      {/each}
                    </select>
                  {:else}
                    <span class="muted">—</span>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>

        <!-- AC7: Validation feedback -->
        {#if !allAssigned}
          <p class="validation-error">{$_('mapping.validationUnassigned')}</p>
        {/if}
        {#if hasDuplicates}
          <p class="validation-error">{$_('mapping.validationDuplicates')}</p>
        {/if}

        {#if actionError}
          <p class="error">{actionError}</p>
        {/if}

        <div class="action-bar">
          <!-- AC10: after redo confirmation — use redo endpoint -->
          <!-- AC8: normal apply (only when not in read-only mode) -->
          {#if redoConfirmed}
            <button
              class="btn btn-danger"
              disabled={!canApply || applying}
              onclick={redoMapping}
            >
              {applying ? $_('mapping.applying') : $_('mapping.redoApplyButton')}
            </button>
          {:else if !alreadyMapped}
            <button
              class="btn btn-primary"
              disabled={!canApply || applying}
              onclick={applyMapping}
            >
              {applying ? $_('mapping.applying') : $_('mapping.applyButton')}
            </button>
          {/if}

          <button
            class="btn btn-secondary"
            disabled={applying}
            onclick={() => push(`/tournaments/${tournamentId}/phases`)}
          >
            {$_('mapping.cancelButton')}
          </button>
        </div>
      </section>
    </div>
  {/if}
</div>

<style>
  .phase-mapping { max-width: 1100px; margin: 0 auto; padding: 1rem; }
  .phase-mapping h2 { margin-bottom: 1rem; }

  .mapping-layout {
    display: grid;
    grid-template-columns: 1fr 1.5fr;
    gap: 1.5rem;
    align-items: start;
  }

  /* Source panel */
  .source-panel { background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 6px; padding: 1rem; }
  .source-group { margin-bottom: 1rem; }
  .source-group h4 { margin: 0 0 0.4rem; font-size: 0.95rem; color: #495057; }
  .team-list { margin: 0; padding-left: 0; list-style: none; }
  .team-item { display: flex; align-items: center; gap: 0.4rem; padding: 0.25rem 0; font-size: 0.9rem; }
  .team-item.without-assessment { color: #6c757d; }
  .placement { font-weight: 600; min-width: 1.5rem; }
  .team-name { flex: 1; }
  .team-stats { font-size: 0.8rem; color: #868e96; }

  /* Target panel */
  .target-panel { background: #fff; border: 1px solid #dee2e6; border-radius: 6px; padding: 1rem; }
  .target-panel h3 { margin-top: 0; }
  .hint { font-size: 0.85rem; color: #6c757d; margin-bottom: 0.75rem; }

  .assignment-table { width: 100%; border-collapse: collapse; margin-bottom: 0.75rem; }
  .assignment-table th, .assignment-table td {
    border: 1px solid #dee2e6; padding: 0.4rem 0.6rem; font-size: 0.9rem; text-align: left;
  }
  .assignment-table th { background: #f8f9fa; font-weight: 600; }
  .team-cell { min-width: 8rem; }

  .row-unassigned { background: #fff3cd; }
  .row-duplicate { background: #f8d7da; }

  select { padding: 0.2rem 0.4rem; border: 1px solid #ced4da; border-radius: 4px; font-size: 0.9rem; }
  .muted { color: #adb5bd; }

  /* Badges */
  .badge-no-assessment {
    display: inline-block; font-size: 0.7rem; background: #6c757d; color: #fff;
    padding: 0.1rem 0.4rem; border-radius: 3px; margin-left: 0.25rem;
  }

  /* Action bar */
  .action-bar { display: flex; gap: 0.5rem; margin-top: 0.75rem; flex-wrap: wrap; }

  /* Buttons */
  .btn { padding: 0.4rem 0.9rem; border: none; border-radius: 4px; cursor: pointer; font-size: 0.9rem; }
  .btn:disabled { opacity: 0.6; cursor: not-allowed; }
  .btn-primary { background: #0d6efd; color: #fff; }
  .btn-secondary { background: #6c757d; color: #fff; }
  .btn-warning { background: #ffc107; color: #212529; }
  .btn-danger { background: #dc3545; color: #fff; }

  /* Info / error boxes */
  .info-box { background: #d1ecf1; border: 1px solid #bee5eb; border-radius: 4px; padding: 0.75rem 1rem; margin-bottom: 0.75rem; }
  .error-box { background: #f8d7da; border: 1px solid #f5c6cb; border-radius: 4px; padding: 0.75rem 1rem; margin-bottom: 0.75rem; }
  .validation-error { color: #721c24; font-size: 0.85rem; margin: 0.25rem 0; }
  .error { color: #721c24; font-size: 0.9rem; }

  /* Confirm dialog */
  .confirm-dialog {
    border: 1px solid #ffc107; background: #fff3cd; padding: 0.75rem; border-radius: 4px; margin-top: 0.5rem;
  }
  .confirm-dialog p { margin: 0 0 0.5rem; }
</style>
