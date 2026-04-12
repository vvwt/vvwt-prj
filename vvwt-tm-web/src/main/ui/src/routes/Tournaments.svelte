<script lang="ts">
  /**
   * Tournaments list view — Story E05S04 AC7, AC9, AC11; updated E05S05 (teams nav button).
   *
   * Shows all tournaments for the current tenant. The organizer can:
   *   - View tournament list (AC7)
   *   - Select the "working" tournament (AC9)
   *   - Navigate to the create form
   *   - Navigate to the edit form (DRAFT only)
   *   - Delete a tournament (DRAFT only, via confirmation)
   *   - Navigate to team management for any tournament (E05S05)
   *
   * All visible strings use the svelte-i18n `$_()` function (AC11 — no hardcoded strings).
   */
  import { onMount } from 'svelte';
  import { push } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import {
    listTournaments,
    deleteTournament,
    selectTournament,
    selectedTournamentId,
    type Tournament,
  } from '../stores/tournamentStore.js';

  let tournaments: Tournament[] = $state([]);
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let deleteError = $state<string | null>(null);

  onMount(async () => {
    await loadTournaments();
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

  function formatAppointment(appointment: string | null): string {
    if (!appointment) return '—';
    // Display as date only (remove time portion for cleaner table display)
    const dt = new Date(appointment);
    return dt.toLocaleDateString();
  }
</script>

<main class="tournaments">
  <div class="tournaments__header">
    <h1>{$_('tournaments.title')}</h1>
    <button class="btn btn--primary" onclick={() => push('/tournaments/new')}>
      {$_('tournaments.createButton')}
    </button>
  </div>

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
              <!-- E05S06: navigate to draft configuration (DRAFT tournaments) -->
              {#if t.status === 'DRAFT'}
                <button class="btn btn--secondary btn--sm"
                        onclick={() => push(`/tournaments/${t.id}/draft`)}>
                  {$_('tournaments.draftButton')}
                </button>
              {/if}
              {#if t.status === 'DRAFT'}
                <button class="btn btn--secondary btn--sm"
                        onclick={() => push(`/tournaments/${t.id}/edit`)}>
                  {$_('tournaments.editButton')}
                </button>
                <button class="btn btn--danger btn--sm" onclick={() => handleDelete(t.id)}>
                  {$_('tournaments.deleteButton')}
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

  .tournaments__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 1.5rem;
  }

  .tournaments__header h1 {
    margin: 0;
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
