<script lang="ts">
  /**
   * Root application component for Tournament Manager Admin SPA.
   *
   * Story E05S01 — AC4: wires the svelte-spa-router for hash-based client-side routing.
   * Story E05S04 — adds /tournaments, /tournaments/new, /tournaments/:id/edit routes.
   * Story E05S05 — adds /tournaments/:tournamentId/teams route.
   * Story E05S06 — adds /tournaments/:tournamentId/draft route.
   * Story E05S07 — adds /tournaments/:tournamentId/phases route.
   * Story E05S09 — adds /tournaments/:tournamentId/phases/:phaseId/referees route.
   *
   * Hash-based routing decision (Brief H-2): No server-side catch-all is needed for
   * deep-link URLs because the hash fragment is never sent to the server.
   * All routing happens client-side via #/path/to/route.
   *
   * Adding new routes: import the component, add an entry to the `routes` map.
   * No changes to this file are needed for i18n locale changes (AC5).
   */
  import Router from 'svelte-spa-router';
  import Home from './routes/Home.svelte';
  import Tournaments from './routes/Tournaments.svelte';
  import TournamentForm from './routes/TournamentForm.svelte';
  import Teams from './routes/Teams.svelte';
  import DraftConfig from './routes/DraftConfig.svelte';
  import PhaseOverview from './routes/PhaseOverview.svelte';
  import RefereeAssignment from './routes/RefereeAssignment.svelte';

  /** Route map: URL pattern → Svelte component. */
  const routes = new Map([
    ['/', Home],
    ['/tournaments', Tournaments],
    ['/tournaments/new', TournamentForm],
    ['/tournaments/:id/edit', TournamentForm],
    ['/tournaments/:tournamentId/teams', Teams],
    ['/tournaments/:tournamentId/draft', DraftConfig],
    ['/tournaments/:tournamentId/phases', PhaseOverview],
    ['/tournaments/:tournamentId/phases/:phaseId/referees', RefereeAssignment],
  ]);

  /** Fallback: redirect unknown routes to home. */
  function routeNotFound(): void {
    window.location.hash = '#/';
  }
</script>

<Router {routes} on:routeEvent={routeNotFound} />
