<script lang="ts">
  /**
   * Drag-and-Drop Phase-Transition page — Story E48S08.
   *
   * Route: /tournaments/:tournamentId/phases/:phaseId/transition
   *
   * Renders the proposed team-to-(group, position) distribution from E48S07's
   * GET /api/phases/:phaseId/transition-proposal as a table (groups = columns,
   * positions = rows). Admin can drag teams between cells to correct the proposal.
   * Confirm button POSTs the updated assignment to
   * POST /api/phases/:phaseId/transition-commit.
   *
   * HTML5 native Drag-and-Drop API — no npm dependency (AC-NO-NEW-NPM-DEPENDENCY, DEC-2).
   *
   * E47 shell mechanism: registers pageTitle + backTo via pageHeader store (AC-FRONTEND-E47-HEADER).
   *
   * DEC-9: structural identity (groupNumber, groupPosition) governs slot placement;
   * teamId identifies which team occupies each slot.
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { push } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';
  import {
    fetchProposal,
    commitTransition,
    type TeamAvatarSlot,
  } from '../stores/phaseTransitionStore.js';
  import { swapSlots } from './phaseTransitionUtils.js';

  // ── Props ──────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string; phaseId?: string };
    /** Optional commit endpoint override (E48S18: Vorbereiten-Route uses /prepare). */
    commitEndpoint?: string;
    /** Optional i18n key override for the page title (E48S18: uses phases.prepareTitle). */
    pageTitleKey?: string;
  }
  let { params = {}, commitEndpoint = undefined, pageTitleKey = undefined }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');
  const phaseId = $derived(params.phaseId ?? '');

  // ── State ──────────────────────────────────────────────────────────────────
  let slots = $state<TeamAvatarSlot[]>([]);
  let loading = $state(true);
  let error = $state<string | null>(null);
  let commitError = $state<string | null>(null);
  let committing = $state(false);
  let draggingIndex = $state<number | null>(null);
  let dragOverIndex = $state<number | null>(null);

  // ── Lifecycle ──────────────────────────────────────────────────────────────
  onMount(async () => {
    const titleKey = pageTitleKey ?? 'phaseTransition.pageTitle';
    const routeKey = pageTitleKey
      ? '/tournaments/:tournamentId/phases/:phaseId/prepare'
      : '/tournaments/:tournamentId/phases/:phaseId/transition';
    pageHeader.set({
      title: get(_)(titleKey),
      backTo: resolveParent(routeKey, tournamentId),
      tournamentId: tournamentId || null,
      actions: [],
    });
    await loadProposal();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function loadProposal(): Promise<void> {
    loading = true;
    error = null;
    try {
      slots = await fetchProposal(phaseId);
    } catch (e: unknown) {
      error = e instanceof Error ? e.message : get(_)('phaseTransition.errorBanner');
    } finally {
      loading = false;
    }
  }

  // ── DnD handlers ──────────────────────────────────────────────────────────

  function handleDragStart(idx: number): void {
    draggingIndex = idx;
  }

  function handleDragOver(evt: DragEvent, idx: number): void {
    evt.preventDefault();
    dragOverIndex = idx;
  }

  function handleDragLeave(): void {
    dragOverIndex = null;
  }

  /**
   * Drop handler — swaps teamIds between source and target slots.
   * Exported module-level swapSlots is called for testable pure logic.
   */
  function handleDrop(tgtIdx: number): void {
    dragOverIndex = null;
    if (draggingIndex === null || draggingIndex === tgtIdx) {
      draggingIndex = null;
      return;
    }
    slots = swapSlots(slots, draggingIndex, tgtIdx);
    draggingIndex = null;
  }

  function handleDragEnd(): void {
    draggingIndex = null;
    dragOverIndex = null;
  }

  // ── Commit / Cancel ────────────────────────────────────────────────────────

  async function handleCommit(): Promise<void> {
    committing = true;
    commitError = null;
    try {
      await commitTransition(phaseId, slots, commitEndpoint);
      push(`/tournaments/${tournamentId}/phases`);
    } catch (e: unknown) {
      commitError = e instanceof Error ? e.message : get(_)('phaseTransition.commitError');
    } finally {
      committing = false;
    }
  }

  function handleCancel(): void {
    push(`/tournaments/${tournamentId}/phases`);
  }

  // ── Table layout derivation ───────────────────────────────────────────────

  /** Maximum group number across all slots (minimum 1 for layout guard). */
  const maxGroups = $derived(
    slots.length > 0 ? Math.max(...slots.map(s => s.groupNumber)) : 0
  );

  /** Maximum position number across all slots (minimum 1 for layout guard). */
  const maxPositions = $derived(
    slots.length > 0 ? Math.max(...slots.map(s => s.groupPosition)) : 0
  );

  /** Column headers: [1, 2, ..., maxGroups] */
  const groupColumns = $derived(
    Array.from({ length: maxGroups }, (_, i) => i + 1)
  );

  /** Row positions: [1, 2, ..., maxPositions] */
  const positionRows = $derived(
    Array.from({ length: maxPositions }, (_, i) => i + 1)
  );

  /**
   * Find the slot index in the slots array for a given (groupNumber, groupPosition) cell.
   * Returns -1 if no team is assigned to this cell.
   */
  function findSlotIndex(g: number, p: number): number {
    return slots.findIndex(s => s.groupNumber === g && s.groupPosition === p);
  }
</script>

<main class="phase-transition">
  {#if loading}
    <!-- AC-FRONTEND-LOADING-AND-ERROR-STATES: loading spinner -->
    <p class="phase-transition__loading">{$_('phaseTransition.loading')}</p>
  {:else if error}
    <!-- AC-FRONTEND-LOADING-AND-ERROR-STATES: error banner -->
    <div class="phase-transition__error-banner">{error}</div>
  {:else}
    <!-- Commit error banner (from POST failure) -->
    {#if commitError}
      <div class="phase-transition__error-banner phase-transition__error-banner--commit">
        {commitError}
      </div>
    {/if}

    <!-- AC-FRONTEND-LOAD-PROPOSAL + AC-FRONTEND-DRAGDROP-NATIVE: DnD table -->
    {#if slots.length === 0}
      <p class="phase-transition__empty">Keine Zuordnung vorhanden.</p>
    {:else}
      <div class="phase-transition__table-wrapper">
        <table class="phase-transition__table">
          <thead>
            <tr>
              <th class="phase-transition__pos-header"></th>
              {#each groupColumns as g (g)}
                <th class="phase-transition__group-header">
                  {$_('phaseTransition.groupHeader').replace('{n}', String(g))}
                </th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each positionRows as p (p)}
              <tr>
                <td class="phase-transition__pos-cell">
                  {$_('phaseTransition.positionLabel').replace('{n}', String(p))}
                </td>
                {#each groupColumns as g (g)}
                  {@const slotIdx = findSlotIndex(g, p)}
                  {@const slot = slotIdx >= 0 ? slots[slotIdx] : null}
                  <!-- AC-FRONTEND-DRAGDROP-NATIVE: draggable cells with HTML5 API -->
                  <td
                    class="phase-transition__slot{dragOverIndex === slotIdx && slotIdx >= 0 ? ' phase-transition__slot--drag-over' : ''}{draggingIndex === slotIdx && slotIdx >= 0 ? ' phase-transition__slot--dragging' : ''}"
                    draggable={slot !== null}
                    ondragstart={() => { if (slotIdx >= 0) handleDragStart(slotIdx); }}
                    ondragover={(evt: DragEvent) => { if (slotIdx >= 0) handleDragOver(evt, slotIdx); else evt.preventDefault(); }}
                    ondragleave={handleDragLeave}
                    ondrop={() => { if (draggingIndex !== null && slotIdx >= 0) handleDrop(slotIdx); else if (draggingIndex !== null) handleDrop(draggingIndex); }}
                    ondragend={handleDragEnd}
                    role="gridcell"
                    aria-label={slot ? slot.teamId : 'empty'}
                  >
                    {#if slot}
                      <span class="phase-transition__team-id">{slot.teamId}</span>
                    {/if}
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}

    <!-- AC-FRONTEND-COMMIT-BUTTON + AC-FRONTEND-CANCEL-BUTTON -->
    <div class="phase-transition__actions">
      <button
        class="btn btn--primary"
        type="button"
        disabled={committing}
        onclick={handleCommit}
      >
        {$_('phaseTransition.commitButton')}
      </button>
      <button
        class="btn btn--secondary"
        type="button"
        onclick={handleCancel}
      >
        {$_('phaseTransition.cancelButton')}
      </button>
    </div>
  {/if}
</main>

<style>
  .phase-transition {
    padding: 2rem;
    font-family: sans-serif;
  }

  .phase-transition__loading,
  .phase-transition__empty {
    color: #666;
  }

  .phase-transition__error-banner {
    background: #fdecea;
    border: 1px solid #e74c3c;
    border-radius: 4px;
    color: #c0392b;
    padding: 0.5rem 1rem;
    margin-bottom: 1rem;
  }

  .phase-transition__error-banner--commit {
    margin-top: 1rem;
  }

  .phase-transition__table-wrapper {
    overflow-x: auto;
    margin-bottom: 1.5rem;
  }

  .phase-transition__table {
    border-collapse: collapse;
    min-width: 100%;
  }

  .phase-transition__table th,
  .phase-transition__table td {
    border: 1px solid #e0e0e0;
    padding: 0.5rem 1rem;
    text-align: center;
  }

  .phase-transition__group-header {
    background: #f5f5f5;
    font-weight: 600;
  }

  .phase-transition__pos-header,
  .phase-transition__pos-cell {
    font-weight: 600;
    background: #f5f5f5;
    text-align: right;
    padding-right: 0.75rem;
    white-space: nowrap;
  }

  .phase-transition__slot {
    min-width: 6rem;
    min-height: 2.5rem;
    cursor: grab;
    user-select: none;
    transition: background 0.1s;
  }

  .phase-transition__slot[draggable='false'] {
    cursor: default;
    background: #fafafa;
  }

  /* AC-FRONTEND-SWAP-LOGIC: visual feedback on drag-over */
  .phase-transition__slot--drag-over {
    background: #d5f5e3;
    outline: 2px solid #27ae60;
  }

  .phase-transition__slot--dragging {
    opacity: 0.5;
  }

  .phase-transition__team-id {
    font-size: 0.85rem;
    word-break: break-all;
  }

  .phase-transition__actions {
    display: flex;
    gap: 0.75rem;
    margin-top: 1rem;
  }

  /* Reuse btn classes from PhaseList.svelte style precedent */
  .btn {
    display: inline-block;
    border: none;
    border-radius: 4px;
    padding: 0.4rem 1rem;
    font-size: 0.9rem;
    cursor: pointer;
  }

  .btn:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--primary:hover:not(:disabled) {
    background: #1a6a9a;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--secondary:hover:not(:disabled) {
    background: #d5dbdb;
  }
</style>
