<script lang="ts">
  /**
   * Certificate generation + download UI — Story E52S02.
   *
   * Restores admin-UI access to CertificateRenderController (E24S05):
   *   GET /certificate/tournaments/{tid}/print/{teamId} — single team certificate
   *   GET /certificate/tournaments/{tid}/print           — all team certificates (ZIP)
   *
   * Generation shape: window.open (shape a per Brief T-3).
   *   - Single team: opens /certificate/tournaments/{tid}/print/{teamId} in new tab (HTML).
   *   - All teams:   opens /certificate/tournaments/{tid}/print in new tab (ZIP download).
   *
   * AC-IMPL-DELIVERY-SHAPE-DOCUMENTED: shape (a) window.open chosen.
   * Rationale: BE endpoints are GET with server-rendered Mustache/HTML/ZIP output.
   * The browser handles Content-Type + Content-Disposition natively (HTML renders inline,
   * ZIP triggers download). No blob manipulation or iframe injection required.
   * Consistent with E52S01's shape (a) for /print/tournaments/{tid} (Zeitpläne button).
   *
   * Error handling:
   *   AC-ERROR-MISSING-TEAM-PHOTOS: pre-fetch teams on mount; show warning if any hasPhoto===false.
   *   AC-ERROR-MISSING-PLACEMENT-DATA: guard on load failure; show actionable message.
   *
   * AC-SECURITY-NO-NEW-AUTH-SURFACE: reuses existing Spring Security /certificate/** wiring
   *   (E24S05 SecurityConfig). SPA-bundle admin-login required. No new @RequestMapping.
   *
   * Props:
   *   params.tournamentId — the tournament UUID from the route
   *     (#/tournaments/:tournamentId/certificates)
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';
  import { getTournament } from '../stores/tournamentStore.js';
  import { listTeams, type Team } from '../stores/teamStore.js';

  // ── Props ─────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ─────────────────────────────────────────────────────────────────
  let loading = $state(true);
  let loadError = $state<string | null>(null);
  let teams = $state<Team[]>([]);

  /** Teams that lack a photo — shown in warning per AC-ERROR-MISSING-TEAM-PHOTOS. */
  const teamsWithoutPhoto = $derived(teams.filter(t => !t.hasPhoto));

  // ── Init ──────────────────────────────────────────────────────────────────
  onMount(async () => {
    pageHeader.set({
      title: get(_)('certificates.title'),
      backTo: resolveParent('/tournaments/:tournamentId/certificates', tournamentId),
      tournamentId: tournamentId || null,
      actions: [],
    });

    if (!tournamentId) {
      loadError = get(_)('certificates.error.noTournament');
      loading = false;
      return;
    }

    try {
      // Verify tournament exists + is COMPLETED; fetch teams for missing-photo check.
      const [tournament, teamList] = await Promise.all([
        getTournament(tournamentId),
        listTeams(tournamentId),
      ]);

      if (tournament.status !== 'COMPLETED') {
        loadError = get(_)('certificates.error.notCompleted');
        loading = false;
        return;
      }

      teams = teamList;
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : get(_)('certificates.error.loadTeams');
    } finally {
      loading = false;
    }
  });

  onDestroy(() => {
    resetPageHeader();
  });

  // ── Actions ───────────────────────────────────────────────────────────────

  /**
   * Opens the single-team certificate in a new browser tab.
   * BE endpoint: GET /certificate/tournaments/{tid}/print/{teamId} (CertificateRenderController, E24S05).
   * AC-IMPL-NEW-COMPONENT-CALLS-RENDER-CONTROLLER.
   */
  function openSingleCertificate(teamId: string): void {
    window.open(`/certificate/tournaments/${tournamentId}/print/${teamId}`, '_blank');
  }

  /**
   * Opens the all-teams certificate ZIP in a new browser tab (triggers browser download).
   * BE endpoint: GET /certificate/tournaments/{tid}/print (CertificateRenderController, E24S05).
   * AC-IMPL-NEW-COMPONENT-CALLS-RENDER-CONTROLLER.
   */
  function openAllCertificates(): void {
    window.open(`/certificate/tournaments/${tournamentId}/print`, '_blank');
  }
</script>

<main class="certificates">

  {#if loading}
    <p class="certificates__loading">…</p>
  {:else if loadError}
    <p class="certificates__error">{loadError}</p>
  {:else}
    <!-- ── Missing-photo warning (AC-ERROR-MISSING-TEAM-PHOTOS) ──────────── -->
    {#if teamsWithoutPhoto.length > 0}
      <div class="certificates__warning" role="alert">
        {$_('certificates.missingPhotosWarning', {
          values: { count: teamsWithoutPhoto.length, total: teams.length },
        })}
      </div>
    {/if}

    <!-- ── All-teams section ─────────────────────────────────────────────── -->
    <section class="certificates__section">
      <button
        class="btn btn--primary"
        onclick={openAllCertificates}
      >
        {$_('certificates.allTeamsButton')}
      </button>
    </section>

    <!-- ── Per-team section ──────────────────────────────────────────────── -->
    {#if teams.length > 0}
      <section class="certificates__section">
        <table class="certificates__table">
          <thead>
            <tr>
              <th>#</th>
              <th>{$_('certificates.singleTeamLabel')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {#each teams as team (team.id)}
              <tr>
                <td>{team.teamNumber}</td>
                <td>{team.description}</td>
                <td>
                  <button
                    class="btn btn--secondary btn--sm"
                    onclick={() => openSingleCertificate(team.id)}
                  >
                    {$_('certificates.generateButton')}
                  </button>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </section>
    {/if}
  {/if}
</main>

<style>
  .certificates {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 900px;
  }

  .certificates__loading {
    color: #666;
  }

  .certificates__error {
    color: #c0392b;
  }

  .certificates__warning {
    background: #fff3cd;
    border: 1px solid #ffc107;
    border-radius: 4px;
    padding: 0.75rem 1rem;
    margin-bottom: 1.5rem;
    color: #856404;
    font-size: 0.9rem;
  }

  .certificates__section {
    margin-bottom: 2rem;
    padding: 1.25rem;
    border: 1px solid #ddd;
    border-radius: 6px;
    background: #fafafa;
  }

  .certificates__table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.9rem;
  }

  .certificates__table th,
  .certificates__table td {
    text-align: left;
    padding: 0.4rem 0.75rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .certificates__table th {
    font-weight: 600;
    background: #f5f5f5;
    color: #555;
  }

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

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .btn--sm {
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
  }
</style>
