<script lang="ts">
  /**
   * Root application component for Tournament Manager Admin SPA.
   *
   * Story E05S01 — AC4: wires the svelte-spa-router for hash-based client-side routing.
   *
   * Hash-based routing decision (Brief H-2): No server-side catch-all is needed for
   * deep-link URLs because the hash fragment is never sent to the server.
   * All routing happens client-side via #/path/to/route.
   *
   * Adding new routes: import the component, add an entry to the `routes` map.
   * No changes to this file are needed for i18n locale changes (AC5).
   */
  import Router from 'svelte-spa-router';
  import { wrap } from 'svelte-spa-router/wrap';
  import Home from './routes/Home.svelte';

  /** Route map: URL pattern → Svelte component. */
  const routes = new Map([
    ['/', Home],
    // Future E05 routes added here (e.g., '/tournaments', '/teams')
  ]);

  /** Fallback: redirect unknown routes to home. */
  function routeNotFound(): void {
    window.location.hash = '#/';
  }
</script>

<Router {routes} on:routeEvent={routeNotFound} />
