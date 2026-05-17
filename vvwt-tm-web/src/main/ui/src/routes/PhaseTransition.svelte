<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Drag-and-Drop Phase-Transition page — Story E48S08 + E48S20 + E51S13.
   *
   * Route: /tournaments/:tournamentId/phases/:phaseId/transition
   *
   * E48S20: Two-pane layout:
   *   - Source pane (left, read-only): shows each team's current (fromPhase) slot with
   *     a human-readable slot label ("Nr. 5" for Phase 1; "Gruppe 1, Platz 2" for Phase 2+)
   *     and the team name/description. Source pane is read-only — NOT a drag source.
   *   - Target pane (right, interactive): shows the proposed assignment for the next phase.
   *     All (group, position) cells are rendered explicitly, including empty ones ("— leer —").
   *     Admin can drag teams between target cells to correct the proposal.
   *
   * E51S13 (Bug 2a): source-pane label now driven by {@code slot.sortType} via
   *   {@code sourceLabelBySortType}. This fixes "Gruppe undefined, Platz undefined"
   *   for Phase-1 teams.
   *
   * DEC-9: teamId UUID must NOT appear in DOM. Organizer-facing labels: teamNumber + teamDescription.
   * teamId is retained in slots array as the internal swap-key for the commit payload.
   *
   * HTML5 native Drag-and-Drop API — no npm dependency (AC-NO-NEW-NPM-DEPENDENCY, DEC-2).
   *
   * E47 shell mechanism: registers pageTitle + backTo via pageHeader store (AC-FRONTEND-E47-HEADER).
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
    sourceLabelBySortType,
    type TeamAvatarSlot,
    type TeamAvatarAssignment,
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

  // ── DnD handlers (target pane only) ───────────────────────────────────────

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
   * Drop handler — swaps all team-bound fields between source and target slots.
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
      // Map slots → commit-payload (only structural identity fields needed by server)
      const assignments: TeamAvatarAssignment[] = slots.map(s => ({
        teamId: s.teamId,
        groupNumber: s.groupNumber,
        groupPosition: s.groupPosition,
      }));
      await commitTransition(phaseId, assignments, commitEndpoint);
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

  // ── Source-pane label derivation (E51S13 — sortType-driven) ─────────────

  /**
   * Returns the human-readable source-slot label for a slot.
   *
   * E51S13: delegates to {@code sourceLabelBySortType} which switches on {@code slot.sortType}
   * (Brief D-9 root-cause-fix; E51S13 Bug 2a).
   *
   * - sortType="team_number" → "Nr. {n}" (Phase 1 case; existing i18n key)
   * - sortType="placement_group" / "group_placement" → "Gruppe {g}, Platz {p}" (Phase 2+ case)
   * - null/unknown → "" (defensive fallback — never renders "undefined")
   *
   * AC-IMPL-FRONTEND-SOURCE-PANE-LABEL, AC-TEST-FRONTEND-NO-UNDEFINED-RENDER-RED
   */
  function sourceLabel(slot: TeamAvatarSlot): string {
    return sourceLabelBySortType(slot, (key, opts) => {
      const translated = get(_)(key);
      if (!opts?.values) return translated;
      // Replace {placeholder} tokens from the values record
      return Object.entries(opts.values).reduce(
        (s, [k, v]) => s.replace(`{${k}}`, String(v)),
        translated
      );
    });
  }

  // ── Target-pane grid layout derivation ──────────────────────────────────

  /** Maximum group number across all slots (minimum 0 for empty guard). */
  const maxGroups = $derived(
    slots.length > 0 ? Math.max(...slots.map(s => s.groupNumber)) : 0
  );

  /** Maximum position number across all slots (minimum 0 for empty guard). */
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

  /** Sorted source-pane slots for display (sorted by teamNumber ascending). */
  const sourcePaneSlots = $derived(
    [...slots].sort((a, b) => a.teamNumber - b.teamNumber)
  );
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

    {#if slots.length === 0}
      <p class="phase-transition__empty">Keine Zuordnung vorhanden.</p>
    {:else}
      <!-- E48S20: Two-pane layout: source (left, read-only) + target (right, interactive) -->
      <div class="phase-transition__panes">

        <!-- Source pane (read-only): shows fromPhase slots + team labels. NOT a drag source. -->
        <section class="phase-transition__source-pane" aria-label={$_('phaseTransition.sourcePaneHeading')}>
          <h2 class="phase-transition__pane-heading">{$_('phaseTransition.sourcePaneHeading')}</h2>
          <table class="phase-transition__source-table">
            <thead>
              <tr>
                <th>{$_('phaseTransition.teamColumnHeading')}</th>
                <th>{$_('phaseTransition.sourcePaneHeading')}</th>
              </tr>
            </thead>
            <tbody>
              {#each sourcePaneSlots as slot (slot.teamId)}
                <!-- Source pane is read-only: no draggable, no dragstart (AC-TEST-FRONTEND-SOURCE-PANE-NOT-DROP-TARGET-RED) -->
                <tr class="phase-transition__source-row" role="row">
                  <td class="phase-transition__team-label">{slot.teamDescription}</td>
                  <td class="phase-transition__source-slot-label">{sourceLabel(slot)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </section>

        <!-- Target pane (interactive): explicit grid of all (group, position) cells. -->
        <section class="phase-transition__target-pane" aria-label={$_('phaseTransition.targetPaneHeading')}>
          <h2 class="phase-transition__pane-heading">{$_('phaseTransition.targetPaneHeading')}</h2>
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
                      <!-- AC-TEST-FRONTEND-ALL-TARGET-SLOTS-VISIBLE-RED: all cells rendered, empty shown -->
                      <td
                        class="phase-transition__slot{dragOverIndex === slotIdx && slotIdx >= 0 ? ' phase-transition__slot--drag-over' : ''}{draggingIndex === slotIdx && slotIdx >= 0 ? ' phase-transition__slot--dragging' : ''}"
                        draggable={slot !== null}
                        ondragstart={() => { if (slotIdx >= 0) handleDragStart(slotIdx); }}
                        ondragover={(evt: DragEvent) => { if (slotIdx >= 0) handleDragOver(evt, slotIdx); else evt.preventDefault(); }}
                        ondragleave={handleDragLeave}
                        ondrop={() => { if (draggingIndex !== null && slotIdx >= 0) handleDrop(slotIdx); else if (draggingIndex !== null) handleDrop(draggingIndex); }}
                        ondragend={handleDragEnd}
                        role="gridcell"
                        aria-label={slot ? slot.teamDescription : $_(	'phaseTransition.targetLabelEmpty')}
                      >
                        {#if slot}
                          <!-- DEC-9: render teamDescription, NOT teamId (UUID must not appear in DOM) -->
                          <span class="phase-transition__team-label">{slot.teamDescription}</span>
                        {:else}
                          <!-- Empty cell indicator (AC-TEST-FRONTEND-ALL-TARGET-SLOTS-VISIBLE-RED) -->
                          <span class="phase-transition__empty-cell">{$_('phaseTransition.targetLabelEmpty')}</span>
                        {/if}
                      </td>
                    {/each}
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        </section>

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

  /* E48S20: two-pane side-by-side layout */
  .phase-transition__panes {
    display: flex;
    gap: 2rem;
    align-items: flex-start;
    margin-bottom: 1.5rem;
  }

  .phase-transition__source-pane {
    flex: 0 0 auto;
    min-width: 14rem;
  }

  .phase-transition__target-pane {
    flex: 1 1 auto;
    overflow-x: auto;
  }

  .phase-transition__pane-heading {
    font-size: 1rem;
    font-weight: 600;
    margin-bottom: 0.5rem;
    color: #2c3e50;
  }

  /* Source pane table */
  .phase-transition__source-table {
    border-collapse: collapse;
    width: 100%;
  }

  .phase-transition__source-table th,
  .phase-transition__source-table td {
    border: 1px solid #e0e0e0;
    padding: 0.4rem 0.75rem;
    text-align: left;
    font-size: 0.9rem;
  }

  .phase-transition__source-table th {
    background: #f5f5f5;
    font-weight: 600;
  }

  /* Source rows are read-only — no hover cursor change */
  .phase-transition__source-row {
    cursor: default;
  }

  .phase-transition__source-slot-label {
    color: #555;
    white-space: nowrap;
  }

  .phase-transition__table-wrapper {
    overflow-x: auto;
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
    min-width: 8rem;
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

  /* DEC-9: render team name, not UUID */
  .phase-transition__team-label {
    font-size: 0.9rem;
    font-weight: 500;
  }

  /* Empty cell indicator (E48S20) */
  .phase-transition__empty-cell {
    font-size: 0.85rem;
    color: #aaa;
    font-style: italic;
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
