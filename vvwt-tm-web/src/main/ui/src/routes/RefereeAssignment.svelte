<script lang="ts">
  /**
   * Referee assignment view for a phase — Story E05S09 AC5–AC10.
   *
   * Receives the selected tournament ID and phase ID via route parameters
   * {@code params.tournamentId} and {@code params.phaseId}.
   *
   * The organizer can:
   *   - View all referee assignments organized by lap (AC5)
   *   - See which assignments are manual overrides (highlighted badge) (AC5)
   *   - Override a referee by selecting an eligible team from a dropdown (AC6)
   *   - See ineligible teams grayed out in the dropdown (AC8)
   *   - View the per-team assignment count summary (AC7)
   *   - Re-run auto-assignment for non-manually-overridden matches (AC4)
   *   - See a message when no referee teams are configured (AC9)
   *
   * All visible strings use the svelte-i18n $_() function (AC10).
   */
  import { onMount } from 'svelte';
  import { _ } from 'svelte-i18n';
  import { apiFetch } from '../lib/api.js';

  // ── Props ─────────────────────────────────────────────────────────────────

  /** Route params from svelte-spa-router */
  export let params: { tournamentId?: string; phaseId?: string } = {};

  // ── Types ─────────────────────────────────────────────────────────────────

  interface RefereeAssignmentEntry {
    matchId: string;
    lapNumber: number | null;
    fieldNumber: number | null;
    team1Description: string;
    team2Description: string;
    refereeTeamName: string | null;
    refereeTeamId: string | null;
    refereeTeamAvatarId: string | null;
    isManualOverride: boolean;
  }

  interface TeamAssignmentSummary {
    teamId: string;
    teamName: string;
    assignedCount: number;
  }

  interface RefereeTeamOption {
    avatarId: string;
    teamId: string;
    teamName: string;
  }

  interface RefereeAssignmentOverview {
    assignments: RefereeAssignmentEntry[];
    teamSummary: TeamAssignmentSummary[];
    allRefereeTeams: RefereeTeamOption[];
  }

  // ── State ─────────────────────────────────────────────────────────────────

  let overview: RefereeAssignmentOverview | null = null;
  let loading = true;
  let loadError = '';
  let reassigning = false;
  let reassignError = '';

  /**
   * Map of matchId → override state:
   * { open: boolean, selectedAvatarId: string | null, saving: boolean, error: string }
   */
  let overrideState: Record<string, {
    open: boolean;
    selectedAvatarId: string | null;
    saving: boolean;
    error: string;
  }> = {};

  // ── Derived ───────────────────────────────────────────────────────────────

  /** Assignments grouped by lapNumber */
  $: lapGroups = groupByLap(overview?.assignments ?? []);

  /** Returns true if no referee teams are configured (AC9) */
  $: noRefereeTeams = (overview?.allRefereeTeams?.length ?? 0) === 0;

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  onMount(() => {
    if (params.phaseId) {
      loadAssignments();
    }
  });

  // ── Data loading ──────────────────────────────────────────────────────────

  async function loadAssignments(): Promise<void> {
    loading = true;
    loadError = '';
    try {
      const resp = await apiFetch(`/api/phases/${params.phaseId}/referee-assignments`);
      if (!resp.ok) {
        loadError = $_('referees.loadError');
        return;
      }
      overview = await resp.json();
      initOverrideState();
    } catch {
      loadError = $_('referees.loadError');
    } finally {
      loading = false;
    }
  }

  function initOverrideState(): void {
    if (!overview) return;
    const newState: typeof overrideState = {};
    for (const entry of overview.assignments) {
      newState[entry.matchId] = {
        open: false,
        selectedAvatarId: null,
        saving: false,
        error: '',
      };
    }
    overrideState = newState;
  }

  // ── Override actions ──────────────────────────────────────────────────────

  function openOverride(matchId: string): void {
    overrideState = {
      ...overrideState,
      [matchId]: { ...overrideState[matchId], open: true, selectedAvatarId: null, error: '' },
    };
  }

  function closeOverride(matchId: string): void {
    overrideState = {
      ...overrideState,
      [matchId]: { ...overrideState[matchId], open: false, selectedAvatarId: null, error: '' },
    };
  }

  async function confirmOverride(matchId: string): Promise<void> {
    const state = overrideState[matchId];
    if (!state || !state.selectedAvatarId) return;

    overrideState = {
      ...overrideState,
      [matchId]: { ...state, saving: true, error: '' },
    };

    try {
      const resp = await apiFetch(
        `/api/phases/${params.phaseId}/matches/${matchId}/referee`,
        {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refereeTeamAvatarId: state.selectedAvatarId }),
        }
      );
      if (resp.status === 409) {
        const body = await resp.json();
        overrideState = {
          ...overrideState,
          [matchId]: { ...overrideState[matchId], saving: false, error: body.message ?? $_('referees.overrideConflict') },
        };
        return;
      }
      if (!resp.ok) {
        overrideState = {
          ...overrideState,
          [matchId]: { ...overrideState[matchId], saving: false, error: $_('referees.overrideError') },
        };
        return;
      }
      await loadAssignments();
    } catch {
      overrideState = {
        ...overrideState,
        [matchId]: { ...overrideState[matchId], saving: false, error: $_('referees.overrideError') },
      };
    }
  }

  async function clearOverride(matchId: string): Promise<void> {
    overrideState = {
      ...overrideState,
      [matchId]: { ...overrideState[matchId], saving: true, error: '' },
    };

    try {
      const resp = await apiFetch(
        `/api/phases/${params.phaseId}/matches/${matchId}/referee`,
        { method: 'DELETE' }
      );
      if (!resp.ok) {
        overrideState = {
          ...overrideState,
          [matchId]: { ...overrideState[matchId], saving: false, error: $_('referees.clearError') },
        };
        return;
      }
      await loadAssignments();
    } catch {
      overrideState = {
        ...overrideState,
        [matchId]: { ...overrideState[matchId], saving: false, error: $_('referees.clearError') },
      };
    }
  }

  // ── Reassign action ───────────────────────────────────────────────────────

  async function reassignAll(): Promise<void> {
    reassigning = true;
    reassignError = '';
    try {
      const resp = await apiFetch(
        `/api/phases/${params.phaseId}/referee-assignments/reassign`,
        { method: 'POST' }
      );
      if (!resp.ok) {
        reassignError = $_('referees.reassignError');
        return;
      }
      overview = await resp.json();
      initOverrideState();
    } catch {
      reassignError = $_('referees.reassignError');
    } finally {
      reassigning = false;
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  function groupByLap(
    assignments: RefereeAssignmentEntry[]
  ): Array<{ lapNumber: number | null; matches: RefereeAssignmentEntry[] }> {
    const map = new Map<string, RefereeAssignmentEntry[]>();
    for (const entry of assignments) {
      const key = entry.lapNumber != null ? String(entry.lapNumber) : 'null';
      if (!map.has(key)) map.set(key, []);
      map.get(key)!.push(entry);
    }
    // Sort by lapNumber ascending; null laps last
    return Array.from(map.entries())
      .sort(([a], [b]) => {
        if (a === 'null') return 1;
        if (b === 'null') return -1;
        return Number(a) - Number(b);
      })
      .map(([key, matches]) => ({
        lapNumber: key === 'null' ? null : Number(key),
        matches,
      }));
  }

  /**
   * Returns the set of team UUIDs that are playing in the given lap.
   * Used to determine eligible teams for the override dropdown (AC8).
   */
  function getPlayingTeamAvatarIds(lapNumber: number | null): Set<string> {
    if (!overview || lapNumber == null) return new Set();
    const playingAvatarIds = new Set<string>();
    for (const entry of overview.assignments) {
      if (entry.lapNumber !== lapNumber) continue;
      // We don't have the avatar IDs in the entry, but we can compare by description match.
      // Actually the overview has allRefereeTeams with avatarId; to detect playing teams
      // we need team1/team2 avatar IDs. These are not in the entry — the backend computes
      // eligibility and we only have team descriptions.
      // Pragmatic approach: the backend returns allRefereeTeams; the ineligible ones
      // are those whose team plays in this lap. We derive this by checking team names
      // against team1/team2 descriptions in the same lap.
      // This is approximate (description-based). The backend is the authoritative safety net (409).
      // For the dropdown we gray out teams whose name matches a playing team in this lap.
    }
    return playingAvatarIds;
  }

  /**
   * Returns the set of team names that are playing in the given lap.
   * Used to gray out ineligible teams in the override dropdown (AC8).
   */
  function getPlayingTeamNamesInLap(lapNumber: number | null): Set<string> {
    if (!overview || lapNumber == null) return new Set();
    const playing = new Set<string>();
    for (const entry of overview.assignments) {
      if (entry.lapNumber !== lapNumber) continue;
      playing.add(entry.team1Description);
      playing.add(entry.team2Description);
    }
    return playing;
  }

  /** Whether the given referee team option is eligible for a given lap. */
  function isEligibleForLap(option: RefereeTeamOption, lapNumber: number | null): boolean {
    if (lapNumber == null) return true;
    const playing = getPlayingTeamNamesInLap(lapNumber);
    return !playing.has(option.teamName);
  }
</script>

<!-- ── Template ─────────────────────────────────────────────────────── -->

<div class="referee-assignment">
  <h2>{$_('referees.title')}</h2>

  {#if loading}
    <p class="loading">{$_('referees.loading')}</p>
  {:else if loadError}
    <p class="error">{loadError}</p>
  {:else if overview}

    <!-- No referee teams configured (AC9) -->
    {#if noRefereeTeams}
      <div class="notice warning">
        <p>{$_('referees.noRefereeTeams')}</p>
      </div>
    {/if}

    <!-- Reassign button (AC4) -->
    <div class="actions-bar">
      <button
        class="btn-secondary"
        on:click={reassignAll}
        disabled={reassigning}
      >
        {reassigning ? $_('referees.reassigning') : $_('referees.reassignButton')}
      </button>
      {#if reassignError}
        <span class="error inline-error">{reassignError}</span>
      {/if}
    </div>

    <!-- Assignment table by lap (AC5) -->
    {#if overview.assignments.length === 0}
      <p class="empty">{$_('referees.noAssignments')}</p>
    {:else}
      {#each lapGroups as { lapNumber, matches }}
        <section class="lap-section">
          <h3 class="lap-header">
            {lapNumber != null
              ? $_('referees.lapLabel', { values: { n: lapNumber } })
              : $_('referees.lapUnscheduled')}
          </h3>
          <table class="assignment-table">
            <thead>
              <tr>
                <th>{$_('referees.columns.field')}</th>
                <th>{$_('referees.columns.team1')}</th>
                <th>{$_('referees.columns.team2')}</th>
                <th>{$_('referees.columns.referee')}</th>
                <th>{$_('referees.columns.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {#each matches as entry (entry.matchId)}
                {@const state = overrideState[entry.matchId] ?? { open: false, selectedAvatarId: null, saving: false, error: '' }}
                <tr class:manual-override={entry.isManualOverride}>
                  <td>{entry.fieldNumber ?? '—'}</td>
                  <td>{entry.team1Description}</td>
                  <td>{entry.team2Description}</td>
                  <td>
                    {#if entry.refereeTeamName}
                      {entry.refereeTeamName}
                      {#if entry.isManualOverride}
                        <span class="badge badge-manual">{$_('referees.manualBadge')}</span>
                      {/if}
                    {:else}
                      <span class="unassigned">{$_('referees.unassigned')}</span>
                    {/if}
                  </td>
                  <td>
                    {#if state.saving}
                      <span class="saving">{$_('referees.saving')}</span>
                    {:else if state.open}
                      <!-- Override dropdown (AC6, AC8) -->
                      <div class="override-panel">
                        <select
                          bind:value={state.selectedAvatarId}
                          on:change={() => {
                            overrideState = { ...overrideState, [entry.matchId]: { ...state } };
                          }}
                        >
                          <option value={null}>{$_('referees.selectTeam')}</option>
                          {#each (overview?.allRefereeTeams ?? []) as option (option.avatarId)}
                            {@const eligible = isEligibleForLap(option, entry.lapNumber)}
                            <option
                              value={option.avatarId}
                              disabled={!eligible}
                              class:ineligible={!eligible}
                            >
                              {option.teamName}{!eligible ? ' (' + $_('referees.playing') + ')' : ''}
                            </option>
                          {/each}
                        </select>
                        <button
                          class="btn-primary btn-small"
                          on:click={() => confirmOverride(entry.matchId)}
                          disabled={!state.selectedAvatarId}
                        >
                          {$_('referees.confirmOverride')}
                        </button>
                        <button
                          class="btn-secondary btn-small"
                          on:click={() => closeOverride(entry.matchId)}
                        >
                          {$_('referees.cancelButton')}
                        </button>
                        {#if state.error}
                          <p class="error">{state.error}</p>
                        {/if}
                      </div>
                    {:else}
                      <div class="action-buttons">
                        <button
                          class="btn-secondary btn-small"
                          on:click={() => openOverride(entry.matchId)}
                        >
                          {$_('referees.overrideButton')}
                        </button>
                        {#if entry.isManualOverride}
                          <button
                            class="btn-secondary btn-small btn-clear"
                            on:click={() => clearOverride(entry.matchId)}
                          >
                            {$_('referees.clearOverrideButton')}
                          </button>
                        {/if}
                        {#if state.error}
                          <p class="error">{state.error}</p>
                        {/if}
                      </div>
                    {/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </section>
      {/each}
    {/if}

    <!-- Referee distribution summary (AC7) -->
    {#if overview.teamSummary.length > 0}
      <section class="summary-section">
        <h3>{$_('referees.summary.title')}</h3>
        <table class="summary-table">
          <thead>
            <tr>
              <th>{$_('referees.summary.teamColumn')}</th>
              <th>{$_('referees.summary.assignedCountColumn')}</th>
            </tr>
          </thead>
          <tbody>
            {#each overview.teamSummary as row (row.teamId)}
              <tr>
                <td>{row.teamName}</td>
                <td>{row.assignedCount}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      </section>
    {/if}

  {/if}
</div>

<style>
  .referee-assignment {
    padding: 1rem;
  }

  h2 {
    margin-bottom: 1rem;
  }

  .loading, .empty {
    color: #666;
    font-style: italic;
  }

  .error {
    color: #c00;
  }

  .inline-error {
    margin-left: 0.5rem;
    font-size: 0.875rem;
  }

  .notice.warning {
    background: #fff3cd;
    border: 1px solid #ffc107;
    border-radius: 4px;
    padding: 0.75rem 1rem;
    margin-bottom: 1rem;
  }

  .actions-bar {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 1.5rem;
  }

  .lap-section {
    margin-bottom: 2rem;
  }

  .lap-header {
    font-size: 1rem;
    font-weight: 600;
    margin-bottom: 0.5rem;
    padding: 0.25rem 0;
    border-bottom: 2px solid #dee2e6;
  }

  .assignment-table,
  .summary-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.9rem;
  }

  .assignment-table th,
  .assignment-table td,
  .summary-table th,
  .summary-table td {
    padding: 0.4rem 0.6rem;
    text-align: left;
    border-bottom: 1px solid #dee2e6;
  }

  .assignment-table th,
  .summary-table th {
    background: #f8f9fa;
    font-weight: 600;
  }

  tr.manual-override {
    background: #fff8e1;
  }

  .badge {
    display: inline-block;
    font-size: 0.7rem;
    font-weight: 600;
    padding: 0.1rem 0.4rem;
    border-radius: 3px;
    margin-left: 0.3rem;
    text-transform: uppercase;
  }

  .badge-manual {
    background: #ffc107;
    color: #333;
  }

  .unassigned {
    color: #999;
    font-style: italic;
  }

  .saving {
    color: #666;
    font-style: italic;
  }

  .override-panel {
    display: flex;
    flex-direction: column;
    gap: 0.3rem;
  }

  .override-panel select {
    width: 100%;
    max-width: 220px;
    padding: 0.2rem 0.4rem;
    font-size: 0.875rem;
  }

  .action-buttons {
    display: flex;
    flex-wrap: wrap;
    gap: 0.3rem;
    align-items: center;
  }

  .btn-primary, .btn-secondary {
    padding: 0.35rem 0.75rem;
    border: none;
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }

  .btn-primary {
    background: #0d6efd;
    color: white;
  }

  .btn-primary:disabled {
    background: #6c9bd1;
    cursor: not-allowed;
  }

  .btn-secondary {
    background: #6c757d;
    color: white;
  }

  .btn-secondary:disabled {
    background: #aaa;
    cursor: not-allowed;
  }

  .btn-small {
    padding: 0.2rem 0.5rem;
    font-size: 0.8rem;
  }

  .btn-clear {
    background: #dc3545;
  }

  .summary-section {
    margin-top: 2rem;
  }

  option.ineligible {
    color: #aaa;
  }
</style>
