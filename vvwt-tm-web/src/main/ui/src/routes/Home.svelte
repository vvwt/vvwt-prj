<script lang="ts">
  /**
   * Home route — placeholder for E05S01.
   *
   * Story E05S01 — AC4: a placeholder route at /admin/ showing a "Tournament Manager" heading.
   * Story E06S05 — AC8: adds navigation links to all admin sections, including device management.
   */
  import { _ } from 'svelte-i18n';
  import { push } from 'svelte-spa-router';
</script>

<main>
  <h1>{$_('home.heading')}</h1>
  <nav class="home__nav">
    <button class="btn btn--secondary" onclick={() => push('/tournaments')}>
      {$_('nav.tournaments')}
    </button>
    <button class="btn btn--secondary" onclick={() => push('/devices')}>
      {$_('nav.devices')}
    </button>
  </nav>
</main>

<style>
  main {
    padding: 2rem;
    font-family: sans-serif;
  }

  .home__nav {
    display: flex;
    gap: 0.75rem;
    margin-top: 1.5rem;
    flex-wrap: wrap;
  }

  .btn {
    cursor: pointer;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    padding: 0.5rem 1rem;
    font-size: 0.9rem;
    background: #ecf0f1;
    color: #2c3e50;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
  }
</style>
