<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Home route — placeholder for E05S01.
   *
   * Story E05S01 — AC4: a placeholder route at /admin/ showing a "Tournament Manager" heading.
   * Story E06S05 — AC8: adds navigation links to all admin sections, including device management.
   * Story E47S02 — AC3/AC7/AC11: title relocated to persistent header via pageHeader store.
   *   Home is a top-level route (no back-arrow per D-5, no tournament context per D-6,
   *   no header action buttons per D-4). Uses existing home.heading key per Brief D-15
   *   (no rename — the key already carries the German string for the heading).
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { push } from 'svelte-spa-router';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';

  onMount(() => {
    // E47S02 AC3/AC7/AC11: register page title using home.heading key per Brief D-15.
    // backTo: null — Home is top-level (no back-arrow per D-5).
    // tournamentId: null — no tournament context (no tournament-name per D-6).
    // actions: [] — no header action buttons (no primary CTA on home per D-4).
    pageHeader.set({
      title: get(_)('home.heading'),
      backTo: null,
      tournamentId: null,
      actions: [],
    });
  });

  onDestroy(() => {
    resetPageHeader();
  });
</script>

<main>
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
