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
  import Devices from './routes/Devices.svelte';
  import DraftConfig from './routes/DraftConfig.svelte';
  import TimerAudio from './routes/TimerAudio.svelte';
  import TimerLink from './routes/TimerLink.svelte';
  import TeamPhotos from './routes/TeamPhotos.svelte';
  import CertificateTemplate from './routes/CertificateTemplate.svelte';

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
    ['/devices', Devices],
  ]);

  /** Fallback: redirect unknown routes to home. */
  function routeNotFound(): void {
    window.location.hash = '#/';
  }
</script>

<Router {routes} on:routeEvent={routeNotFound} />
