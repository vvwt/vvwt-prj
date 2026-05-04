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
   * Story E27S02 — adds /tournaments/:tournamentId/slot-optimization route (AC-SVELTE-CANCEL-UI-AUTHORED).
   *
   * Hash-based routing decision (Brief H-2): No server-side catch-all is needed for
   * deep-link URLs because the hash fragment is never sent to the server.
   * All routing happens client-side via #/path/to/route.
   *
   * Adding new routes: import the component, add an entry to the `routes` map.
   * No changes to this file are needed for i18n locale changes (AC5).
   */
  import Router from 'svelte-spa-router';
  import InfoPortalStatus from './lib/InfoPortalStatus.svelte';
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
  import SlotOptimization from './routes/SlotOptimization.svelte';

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
    ['/tournaments/:tournamentId/slot-optimization', SlotOptimization],
    ['/devices', Devices],
  ]);

  /** Fallback: redirect unknown routes to home. */
  function routeNotFound(): void {
    window.location.hash = '#/';
  }
</script>

<!-- E44S02 AC5: VVW brand lockup — header strip with speaking alt per Brief Q-4 -->
<!-- AC13: min-width 120px ensures minimum render size per Brief C-9 -->
<header class="brand-header">
  <img src="/admin/vvw-tm-logo.svg" alt="Tournament Manager" class="brand-lockup" />
</header>

<!-- E38S09: Info Portal publisher status banner (AC4, AC10, DEC-43 D3) —
     Silently hidden when the info-portal feature is disabled (AC6).  -->
<InfoPortalStatus />
<Router {routes} on:routeEvent={routeNotFound} />

<style>
  /* E44S02 AC5 + AC13: brand lockup header */
  .brand-header {
    padding: 0.5rem 1rem;
    background: #fff;
    border-bottom: 1px solid #e0e0e0;
  }

  .brand-lockup {
    height: 2em;
    display: block;
  }
</style>
