<script lang="ts">
  /**
   * Root application component for Tournament Manager Admin SPA.
   *
   * Story E05S01 — AC4: wires the svelte-spa-router for hash-based client-side routing.
   * Story E05S04 — adds /tournaments, /tournaments/new, /tournaments/:id/edit routes.
   * Story E05S05 — adds /tournaments/:tournamentId/teams route.
   * Story E06S05 — adds /devices route (AC8).
   * Story E11S06 — adds /tournaments/:tournamentId/audio route (AC1).
   * Story E11S07 — adds /tournaments/:tournamentId/timer-link route (AC1).
   * Story E12S03 — adds /tournaments/:tournamentId/photos route (AC1–AC9).
   * Story E12S05 — adds /tournaments/:tournamentId/certificate-template route (AC1–AC9).
   * Story E52S02 — adds /tournaments/:tournamentId/certificates route (AC-URL-FE-NEW-ROUTE-CERTIFICATES).
   * Story E27S02 — adds /tournaments/:tournamentId/slot-optimization route (AC-SVELTE-CANCEL-UI-AUTHORED).
   * Story E48S05 — adds /tournaments/:tournamentId/phases route (AC-FRONTEND-PHASES-ROUTE).
   * Story E47S01 — shell foundation: persistent header with page title, back-arrow,
   *                tournament name, action buttons. Sticky on scroll. Responsive collapse.
   * Story E63S05 — adds /embedded-worker route (AC-GOV-CONTROLLER-PLACEMENT).
   *
   * Hash-based routing decision (Brief H-2): No server-side catch-all is needed for
   * deep-link URLs because the hash fragment is never sent to the server.
   * All routing happens client-side via #/path/to/route.
   *
   * Adding new routes: import the component, add an entry to the `routes` map.
   * No changes to this file are needed for i18n locale changes (AC5).
   *
   * E47S01 shell mechanism (AC1): Route components register { title, backTo, tournamentId, actions }
   * via the pageHeader writable store (stores/pageHeaderStore.ts). App.svelte subscribes and
   * renders the registered values in the persistent .brand-header.
   *
   * E47S01 sticky (AC2): .brand-header uses position:sticky + top:0.
   * E47S01 responsive (AC7/AC8/AC17):
   *   - non-danger actions collapse to icon-only (aria-label) at max-width:768px
   *   - danger actions go to Overflow-Menu (OverflowMenu.svelte) at max-width:768px
   *
   * E47S01 tournament name (AC6/AC14): resolved via tournamentStore.getTournament(tournamentId).
   *   Returns undefined when store not yet hydrated or id invalid; header renders empty slot (AC14).
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import Router, { push } from 'svelte-spa-router';
  import InfoPortalStatus from './lib/InfoPortalStatus.svelte';
  import OverflowMenu from './lib/OverflowMenu.svelte';
  import Home from './routes/Home.svelte';
  import Tournaments from './routes/Tournaments.svelte';
  import TournamentForm from './routes/TournamentForm.svelte';
  import Teams from './routes/Teams.svelte';
  import Devices from './routes/Devices.svelte';
  import DraftConfig from './routes/DraftConfig.svelte';
  import TimerAudio from './routes/TimerAudio.svelte';
  import TimerLink from './routes/TimerLink.svelte';
  import TeamPhotos from './routes/TeamPhotos.svelte';
  import CertificateTemplate from './routes/CertificateTemplate.svelte';
  import Certificates from './routes/Certificates.svelte';
  import SlotOptimization from './routes/SlotOptimization.svelte';
  import PhaseList from './routes/PhaseList.svelte';
  import PhaseTransition from './routes/PhaseTransition.svelte';
  import PhasePreparation from './routes/PhasePreparation.svelte';
  import MatchCorrection from './routes/MatchCorrection.svelte';
  import MatchOverview from './routes/MatchOverview.svelte';
  import EmbeddedWorkerControl from './routes/EmbeddedWorkerControl.svelte';
  import { pageHeader, type PageHeaderState } from './stores/pageHeaderStore.js';
  import { getTournament, type Tournament } from './stores/tournamentStore.js';
  import { _ } from 'svelte-i18n';

  /** Route map: URL pattern → Svelte component. */
  const routes = new Map([
    ['/', Home],
    ['/tournaments', Tournaments],
    ['/tournaments/new', TournamentForm],
    ['/tournaments/:id/edit', TournamentForm],
    ['/tournaments/:tournamentId/teams', Teams],
    ['/tournaments/:tournamentId/draft', DraftConfig],
    ['/tournaments/:tournamentId/audio', TimerAudio],
    ['/tournaments/:tournamentId/timer-link', TimerLink],
    ['/tournaments/:tournamentId/photos', TeamPhotos],
    ['/tournaments/:tournamentId/certificate-template', CertificateTemplate],
    ['/tournaments/:tournamentId/certificates', Certificates],
    ['/tournaments/:tournamentId/slot-optimization', SlotOptimization],
    ['/tournaments/:tournamentId/phases', PhaseList],
    ['/tournaments/:tournamentId/phases/:phaseId/transition', PhaseTransition],
    ['/tournaments/:tournamentId/phases/:phaseId/prepare', PhasePreparation],
    ['/tournaments/:tournamentId/phases/:phaseId/matches', MatchOverview],
    ['/tournaments/:tournamentId/phases/:phaseId/matches/:matchId/correction', MatchCorrection],
    ['/devices', Devices],
    ['/embedded-worker', EmbeddedWorkerControl],
  ]);

  /** Fallback: redirect unknown routes to home. */
  function routeNotFound(): void {
    window.location.hash = '#/';
  }

  // ── E47S01: Shell state ────────────────────────────────────────────────────

  /** Current registered header state from the active route component (AC1). */
  let pageHeaderState: PageHeaderState = $state(get(pageHeader));

  /** Resolved tournament name for tournament-context routes (AC6, AC14). */
  let tournamentName: string = $state('');

  /** Whether the viewport is narrow (≤768px) for responsive collapse (AC7/AC8). */
  let narrowViewport: boolean = $state(false);

  let unsubscribeHeader: (() => void) | null = null;
  let mediaQuery: MediaQueryList | null = null;
  let mediaChangeHandler: ((e: MediaQueryListEvent) => void) | null = null;

  onMount(() => {
    // Subscribe to pageHeader store — re-render on route change (AC1)
    unsubscribeHeader = pageHeader.subscribe((state) => {
      pageHeaderState = state;
      // Resolve tournament name when tournamentId changes (AC6, AC14)
      void resolveTournamentName(state.tournamentId);
    });

    // Responsive breakpoint — 768px (AC7, AC8, AC17)
    if (typeof window !== 'undefined') {
      mediaQuery = window.matchMedia('(max-width: 768px)');
      narrowViewport = mediaQuery.matches;
      mediaChangeHandler = (e: MediaQueryListEvent) => { narrowViewport = e.matches; };
      mediaQuery.addEventListener('change', mediaChangeHandler);
    }
  });

  onDestroy(() => {
    unsubscribeHeader?.();
    if (mediaQuery && mediaChangeHandler) {
      mediaQuery.removeEventListener('change', mediaChangeHandler);
    }
    mediaQuery = null;
    mediaChangeHandler = null;
  });

  /** Resolve tournament name from store — handles undefined gracefully (AC14). */
  async function resolveTournamentName(tournamentId: string | null): Promise<void> {
    if (!tournamentId) {
      tournamentName = '';
      return;
    }
    try {
      const t: Tournament | undefined = await getTournament(tournamentId).catch(() => undefined);
      // Guard: never render literal 'undefined', 'null', or '[object Object]' (AC14)
      tournamentName = t?.description ?? '';
    } catch {
      tournamentName = '';
    }
  }

  /** Non-danger actions: collapse to icon-only at narrow viewport (D-9). */
  const nonDangerActions = $derived(pageHeaderState.actions.filter(a => a.variant !== 'danger'));

  /** Danger actions: go to OverflowMenu at narrow viewport (D-11). */
  const dangerActions = $derived(pageHeaderState.actions.filter(a => a.variant === 'danger'));
</script>

<!-- E44S02 AC5: VVW brand lockup — header strip with speaking alt per Brief Q-4 -->
<!-- E47S01 AC2: position:sticky so header stays visible on scroll -->
<header class="brand-header">
  <!-- AC15: DOM order: Logo → [back-arrow] → title → [tournament-name] → spacer → actions -->

  <!-- Logo (always first) -->
  <img src="/admin/vvw-tm-logo.svg" alt="Tournament Manager" class="brand-lockup" />

  <!-- Back-arrow: only on sub-routes where backTo is set by the route component (AC5, AC15) -->
  {#if pageHeaderState.backTo}
    <button
      class="brand-header__back"
      type="button"
      aria-label="Zurück"
      onclick={() => { if (pageHeaderState.backTo) push(pageHeaderState.backTo); }}
    >
      ←
    </button>
  {/if}

  <!-- Page title (registered by route component via pageHeader store) (AC1, AC3) -->
  {#if pageHeaderState.title}
    <span class="brand-header__title">{pageHeaderState.title}</span>
  {/if}

  <!-- Tournament name: only on tournament-context routes (AC6, AC14, AC15, AC16) -->
  <!-- Guard: tournamentName is '' (empty string) when getTournament returns undefined (AC14) -->
  {#if pageHeaderState.tournamentId && tournamentName}
    <span class="brand-header__tournament-name">{tournamentName}</span>
  {/if}

  <!-- Flex spacer: pushes actions to the right (AC15) -->
  <span class="brand-header__spacer" aria-hidden="true"></span>

  <!-- Action area (AC1, AC4, AC7, AC8, AC17) -->
  {#if pageHeaderState.actions.length > 0}
    <div class="brand-header__actions">
      <!-- Non-danger actions: visible text at desktop, icon-only at narrow viewport (AC7, D-9) -->
      {#each nonDangerActions as action (action.label)}
        {#if !narrowViewport}
          <button
            class="btn brand-header__action-btn brand-header__action-btn--{action.variant}"
            type="button"
            onclick={action.handler}
          >
            {action.label}
          </button>
        {:else}
          <!-- Narrow: icon-only button with aria-label for accessibility (AC7, C-5) -->
          <button
            class="btn brand-header__action-btn brand-header__action-btn--{action.variant} brand-header__action-btn--icon"
            type="button"
            onclick={action.handler}
            aria-label={action.ariaLabel}
            title={action.ariaLabel}
          >
            <span class="brand-header__action-icon" aria-hidden="true">+</span>
          </button>
        {/if}
      {/each}

      <!-- Danger actions: direct button at desktop; OverflowMenu at narrow (AC8, D-11, AC17) -->
      {#if dangerActions.length > 0}
        {#if !narrowViewport}
          {#each dangerActions as action (action.label)}
            <button
              class="btn brand-header__action-btn brand-header__action-btn--danger"
              type="button"
              onclick={action.handler}
            >
              {action.label}
            </button>
          {/each}
        {:else}
          <!-- Narrow: all danger actions go into OverflowMenu (D-11, AC8, AC17) -->
          <OverflowMenu
            actions={dangerActions.map(a => ({ label: a.label, handler: a.handler }))}
            triggerLabel={$_('common.moreActions')}
          />
        {/if}
      {/if}
    </div>
  {/if}
</header>

<!-- E38S09: Info Portal publisher status banner (AC4, AC10, DEC-43 D3) —
     Silently hidden when the info-portal feature is disabled (AC6).  -->
<InfoPortalStatus />
<Router {routes} on:routeEvent={routeNotFound} />

<style>
  /* E44S02 AC5 + AC13: brand lockup header */
  /* E47S01 AC2: position:sticky so header stays visible on scroll. NOT position:fixed
     (which removes the header from document flow and breaks the layout cascade). */
  .brand-header {
    padding: 0.5rem 1rem;
    background: #fff;
    border-bottom: 1px solid #e0e0e0;
    position: sticky;
    top: 0;
    z-index: 100;
    display: flex;
    align-items: center;
    gap: 0.5rem;
  }

  .brand-lockup {
    height: 2em;
    display: block;
    flex-shrink: 0;
  }

  /* Back-arrow button (AC5) */
  .brand-header__back {
    background: none;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    padding: 0.2rem 0.5rem;
    cursor: pointer;
    font-size: 1rem;
    color: #2c3e50;
    flex-shrink: 0;
  }

  .brand-header__back:hover {
    background: #ecf0f1;
  }

  /* Page title (AC1) */
  .brand-header__title {
    font-size: 1.05rem;
    font-weight: 600;
    color: #2c3e50;
    white-space: nowrap;
    flex-shrink: 0;
  }

  /* Tournament name (AC6, AC16: truncate-first, drop-last per D-9)
     text-overflow:ellipsis at medium widths; hidden at narrowest (AC16) */
  .brand-header__tournament-name {
    font-size: 0.9rem;
    color: #555;
    text-overflow: ellipsis;
    overflow: hidden;
    white-space: nowrap;
    max-width: 200px;
    flex-shrink: 1;
  }

  /* Flex spacer: pushes action area to the right (AC15) */
  .brand-header__spacer {
    flex: 1;
    min-width: 0.5rem;
  }

  /* Action area */
  .brand-header__actions {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    flex-shrink: 0;
  }

  .brand-header__action-btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.4rem 0.8rem;
    font-size: 0.9rem;
    white-space: nowrap;
  }

  .brand-header__action-btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .brand-header__action-btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }

  .brand-header__action-btn--danger {
    background: #e74c3c;
    color: #fff;
  }

  /* Icon-only button at narrow viewport (AC7) */
  .brand-header__action-btn--icon {
    padding: 0.4rem 0.6rem;
    min-width: 2rem;
    text-align: center;
  }

  .brand-header__action-icon {
    font-size: 1rem;
    font-weight: bold;
  }

  /* AC16 / D-9: Hide tournament name at narrowest viewport to prevent overflow */
  @media (max-width: 480px) {
    .brand-header__tournament-name {
      display: none;
    }
  }
</style>
