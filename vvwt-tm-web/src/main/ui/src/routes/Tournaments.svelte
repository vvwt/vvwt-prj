<script lang="ts">
  /**
   * Tournaments list view — Story E05S04 AC7, AC9, AC11; updated E05S05 (teams nav button).
   * Updated E12S03: adds "Fotos" navigation button to team photo management.
   * Updated E12S05: adds "Urkunden-Vorlage" navigation button to certificate template management.
   *
   * Shows all tournaments for the current tenant. The organizer can:
   *   - View tournament list (AC7)
   *   - Select the "working" tournament (AC9)
   *   - Navigate to the create form
   *   - Navigate to the edit form (DRAFT only)
   *   - Delete a tournament (DRAFT only, via confirmation)
   *   - Navigate to team management for any tournament (E05S05)
   *   - Navigate to team photo management for any tournament (E12S03)
   *   - Navigate to certificate template management for any tournament (E12S05)
   *
   * All visible strings use the svelte-i18n `$_()` function (AC11 — no hardcoded strings).
   */
  import { onMount, onDestroy } from 'svelte';
  import { push } from 'svelte-spa-router';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import {
    listTournaments,
    deleteTournament,
    cascadeDeleteTournament,
    selectTournament,
    selectedTournamentId,
    activate,
    complete,
    cancelTournament,
    type Tournament,
  } from '../stores/tournamentStore.js';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';

  let tournaments: Tournament[] = $state([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let deleteError = $state<string | null>(null);
  let cascadeDeleteError = $state<string | null>(null);
  let lifecycleError = $state<string | null>(null);

  onMount(async () => {
    // E47S01 AC1/AC3/AC4: register title and create button in persistent header (top-level: no backTo, no tournamentId)
    pageHeader.set({
      title: get(_)('tournaments.title'),
      backTo: null,
      tournamentId: null,
      actions: [
        {
          label: get(_)('tournaments.createButton'),
          ariaLabel: get(_)('tournaments.createButton'),
          handler: () => push('/tournaments/new'),
          variant: 'primary',
        },
      ],
    });
    await loadTournaments();
  });

  onDestroy(() => {
    resetPageHeader();
  });

  async function loadTournaments(): Promise<void> {
    loading = true;
    loadError = null;
    try {
      tournaments = await listTournaments();
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  }

  async function handleDelete(id: string): Promise<void> {
    if (!confirm($_('tournaments.deleteConfirm'))) return;
    deleteError = null;
    try {
      await deleteTournament(id);
      // If the deleted tournament was selected, clear selection
      if ($selectedTournamentId === id) {
        import('../stores/tournamentStore.js').then(m => m.clearSelection());
      }
      await loadTournaments();
    } catch (e: unknown) {
      deleteError = e instanceof Error ? e.message : $_('tournaments.deleteError');
    }
  }

  function handleSelect(id: string): void {
    selectTournament(id);
  }

  async function handleActivate(id: string): Promise<void> {
    if (!confirm($_('tournaments.activateConfirm'))) return;
    lifecycleError = null;
    try {
      await activate(id);
      await loadTournaments();
    } catch (e: unknown) {
      lifecycleError = e instanceof Error ? e.message : $_('tournaments.lifecycleError');
    }
  }

  async function handleComplete(id: string): Promise<void> {
    if (!confirm($_('tournaments.completeConfirm'))) return;
    lifecycleError = null;
    try {
      await complete(id);
      await loadTournaments();
    } catch (e: unknown) {
      lifecycleError = e instanceof Error ? e.message : $_('tournaments.lifecycleError');
    }
  }

  async function handleCancel(id: string): Promise<void> {
    if (!confirm($_('tournaments.cancelConfirm'))) return;
    lifecycleError = null;
    try {
      await cancelTournament(id);
      await loadTournaments();
    } catch (e: unknown) {
      lifecycleError = e instanceof Error ? e.message : $_('tournaments.lifecycleError');
    }
  }

  /**
   * Cascade-delete handler (E48S13, AC-IMPL-FE-CASCADE-DELETE-BUTTON).
   * Visible for DRAFT/PLANNED/CANCELLED; dialog fires regardless of source status (Brief Q-4).
   * On success: reloads the tournament list.
   * On 409: shows typed messageKey via i18n (apiError.messageKey lookup).
   */
  async function handleCascadeDelete(id: string): Promise<void> {
    if (!confirm($_('tournaments.cascadeDeleteConfirm'))) return;
    cascadeDeleteError = null;
    try {
      await cascadeDeleteTournament(id);
      if ($selectedTournamentId === id) {
        import('../stores/tournamentStore.js').then(m => m.clearSelection());
      }
      await loadTournaments();
    } catch (e: unknown) {
      const apiErr = e && typeof e === 'object' && 'apiError' in e
        ? (e as { apiError: { messageKey?: string } }).apiError
        : null;
      const msgKey = apiErr?.messageKey;
      cascadeDeleteError = msgKey
        ? $_(`${msgKey}`, { default: e instanceof Error ? e.message : $_('tournaments.cascadeDeleteError') })
        : (e instanceof Error ? e.message : $_('tournaments.cascadeDeleteError'));
    }
  }

  function formatAppointment(appointment: string | null): string {
    if (!appointment) return '—';
    // Display as date only (remove time portion for cleaner table display)
    const dt = new Date(appointment);
    return dt.toLocaleDateString();
  }
</script>

<main class="tournaments">

  {#if loading}
    <p class="tournaments__loading">…</p>
  {:else if loadError}
    <p class="tournaments__error">{loadError}</p>
  {:else if tournaments.length === 0}
    <p class="tournaments__empty">{$_('tournaments.empty')}</p>
  {:else}
    {#if deleteError}
      <p class="tournaments__error">{deleteError}</p>
    {/if}
    {#if cascadeDeleteError}
      <p class="tournaments__error">{cascadeDeleteError}</p>
    {/if}
    {#if lifecycleError}
      <p class="tournaments__error">{lifecycleError}</p>
    {/if}
    <table class="tournaments__table">
      <thead>
        <tr>
          <th>{$_('tournaments.columns.description')}</th>
          <th>{$_('tournaments.columns.appointment')}</th>
          <th>{$_('tournaments.columns.status')}</th>
          <th>{$_('tournaments.columns.teamCount')}</th>
          <th>{$_('tournaments.columns.fieldCount')}</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        {#each tournaments as t (t.id)}
          <tr class:tournaments__row--selected={$selectedTournamentId === t.id}>
            <td>{t.description}</td>
            <td>{formatAppointment(t.appointment)}</td>
            <td>{$_(`tournaments.status.${t.status}`, { default: t.status })}</td>
            <td>{t.teamCount}</td>
            <td>{t.fieldCount}</td>
            <td class="tournaments__actions">
              {#if $selectedTournamentId === t.id}
                <span class="badge badge--selected">{$_('tournaments.selectedLabel')}</span>
              {:else}
                <button class="btn btn--secondary btn--sm" onclick={() => handleSelect(t.id)}>
                  {$_('tournaments.selectButton')}
                </button>
              {/if}
              <!-- E05S05: navigate to team management -->
              <button class="btn btn--secondary btn--sm"
                      onclick={() => push(`/tournaments/${t.id}/teams`)}>
                {$_('tournaments.teamsButton')}
              </button>
              <!-- E12S03: navigate to team photo management -->
              <button class="btn btn--secondary btn--sm"
                      onclick={() => push(`/tournaments/${t.id}/photos`)}>
                {$_('tournaments.teamPhotosButton')}
              </button>
              <!-- E12S05: navigate to certificate template management -->
              <button class="btn btn--secondary btn--sm"
                      onclick={() => push(`/tournaments/${t.id}/certificate-template`)}>
                {$_('tournaments.certificateTemplateButton')}
              </button>
              <!-- E11S06: navigate to timer audio management -->
              <button class="btn btn--secondary btn--sm"
                      onclick={() => push(`/tournaments/${t.id}/audio`)}>
                {$_('tournaments.timerAudioButton')}
              </button>
              <!-- E11S07 AC1/AC3: timer link + QR code — shown for PLANNED and ACTIVE tournaments -->
              {#if t.status === 'PLANNED' || t.status === 'ACTIVE'}
                <button class="btn btn--secondary btn--sm"
                        onclick={() => push(`/tournaments/${t.id}/timer-link`)}>
                  {$_('tournaments.timerLinkButton')}
                </button>
              {/if}
              <!-- E48S05 AC-FRONTEND-NAV-FROM-TOURNAMENTS: Phasen nav for PLANNED/ACTIVE/COMPLETED/CANCELLED -->
              {#if t.status === 'PLANNED' || t.status === 'ACTIVE' || t.status === 'COMPLETED' || t.status === 'CANCELLED'}
                <button class="btn btn--secondary btn--sm"
                        onclick={() => push(`/tournaments/${t.id}/phases`)}>
                  {$_('tournaments.phasesButton')}
                </button>
              {/if}
              <!-- E48S03 AC-FRONTEND-LIFECYCLE-BUTTONS + AC-FRONTEND-VISIBILITY-RULES -->
              {#if t.status === 'DRAFT'}
                <button class="btn btn--secondary btn--sm"
                        onclick={() => push(`/tournaments/${t.id}/edit`)}>
                  {$_('tournaments.editButton')}
                </button>
                <!-- E08S05 AC5: navigate to draft configuration (breaks + start time + preview) -->
                <button class="btn btn--secondary btn--sm"
                        onclick={() => push(`/tournaments/${t.id}/draft`)}>
                  {$_('tournaments.draftButton')}
                </button>
                <button class="btn btn--danger btn--sm" onclick={() => handleDelete(t.id)}>
                  {$_('tournaments.deleteButton')}
                </button>
                <!-- E48S13 AC-IMPL-FE-CASCADE-DELETE-BUTTON: cascade-delete for DRAFT -->
                <button class="btn btn--danger btn--sm" onclick={() => handleCascadeDelete(t.id)}>
                  {$_('tournaments.cascadeDeleteButton')}
                </button>
              {/if}
              {#if t.status === 'PLANNED'}
                <!-- PLANNED: activate or cancel -->
                <button class="btn btn--primary btn--sm" onclick={() => handleActivate(t.id)}>
                  {$_('tournaments.activateButton')}
                </button>
                <button class="btn btn--danger btn--sm" onclick={() => handleCancel(t.id)}>
                  {$_('tournaments.cancelButton')}
                </button>
                <!-- E48S13 AC-IMPL-FE-CASCADE-DELETE-BUTTON: cascade-delete for PLANNED -->
                <button class="btn btn--danger btn--sm" onclick={() => handleCascadeDelete(t.id)}>
                  {$_('tournaments.cascadeDeleteButton')}
                </button>
              {/if}
              {#if t.status === 'ACTIVE'}
                <!-- ACTIVE: complete or cancel -->
                <button class="btn btn--primary btn--sm" onclick={() => handleComplete(t.id)}>
                  {$_('tournaments.completeButton')}
                </button>
                <button class="btn btn--danger btn--sm" onclick={() => handleCancel(t.id)}>
                  {$_('tournaments.cancelButton')}
                </button>
              {/if}
              <!-- E48S13 AC-IMPL-FE-CASCADE-DELETE-BUTTON: cascade-delete for CANCELLED -->
              {#if t.status === 'CANCELLED'}
                <button class="btn btn--danger btn--sm" onclick={() => handleCascadeDelete(t.id)}>
                  {$_('tournaments.cascadeDeleteButton')}
                </button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</main>

<style>
  .tournaments {
    padding: 2rem;
    font-family: sans-serif;
  }

  .tournaments__table {
    width: 100%;
    border-collapse: collapse;
  }

  .tournaments__table th,
  .tournaments__table td {
    text-align: left;
    padding: 0.5rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .tournaments__table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .tournaments__row--selected td {
    background: #e8f4fd;
  }

  .tournaments__actions {
    white-space: nowrap;
    display: flex;
    gap: 0.4rem;
    align-items: center;
  }

  .tournaments__error {
    color: #c0392b;
    margin-bottom: 1rem;
  }

  .tournaments__loading,
  .tournaments__empty {
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

  .badge--selected {
    display: inline-block;
    background: #27ae60;
    color: #fff;
    border-radius: 4px;
    padding: 0.2rem 0.5rem;
    font-size: 0.75rem;
    font-weight: 600;
  }
</style>
