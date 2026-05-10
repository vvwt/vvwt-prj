<script lang="ts">
  /**
   * Sidebar-top header component for the Display SPA (E50S03).
   *
   * Contains the brand logo (left) and WebSocket connection status indicator (right)
   * positioned at the top of the right-column sidebar, at the same height as the
   * court-grid field-header band (`court-grid__headers`).
   *
   * Height equivalence: uses CSS custom property `--field-header-height: 2.8em`
   * declared globally on `:global(#app)` in App.svelte. CourtGrid.svelte's
   * `.court-grid__headers` rule also uses this variable — ensuring visual alignment
   * across the full-width field-header row.
   *
   * E44S02 AC13 floor: brand-lockup height remains >= 1.8em.
   * E07S06 AC7: ConnectionStatus 5-state public API preserved (status prop).
   * E50S03 AC-GOVERNANCE-NO-NEW-NPM-DEPENDENCY: no new npm packages added.
   * E50S03 AC-SECURITY-NO-NEW-AUTH-SURFACE: layout-only, no new fetch/auth.
   */
  import { _ } from 'svelte-i18n';
  import ConnectionStatusIndicator from './ConnectionStatus.svelte';
  import type { ConnectionStatus } from '../lib/websocket.js';

  interface Props {
    /** Current WebSocket connection status (E07S06 AC7 contract). */
    status: ConnectionStatus;
  }

  const { status }: Props = $props();
</script>

<!--
  E50S03: Sidebar-top header band — brand logo (left) + connection status indicator (right).
  Flex row, height = var(--field-header-height) = 2.8em (same as court-grid__headers).
  ConnectionStatusIndicator always rendered (deterministic — shows 'disconnected' during loading).
  E44S02 AC13: brand-lockup height 1.8em (>= floor).
-->
<div class="sidebar-header">
  <img src="/display/vvw-tm-logo.svg" alt="Tournament Manager" class="brand-lockup" />
  <ConnectionStatusIndicator {status} />
</div>

<style>
  /*
   * E50S03: sidebar-top header band.
   * Height matches court-grid__headers via shared --field-header-height CSS custom property.
   * Flex row: logo left, indicator right (via margin-left: auto in ConnectionStatus.svelte).
   * E44S02 AC5 + AC13: brand lockup preserved; height floor 1.8em via brand-lockup rule.
   */
  .sidebar-header {
    display: flex;
    align-items: center;
    padding: 0 1rem;
    height: var(--field-header-height);
    background: #fff;
    border-bottom: 1px solid #e0e0e0;
    flex-shrink: 0;
  }

  /*
   * E44S02 AC13 floor: brand-lockup height >= 1.8em.
   * Reduced from 1.8em (E50S02) to fit within --field-header-height band.
   */
  .brand-lockup {
    height: 1.8em;
    display: block;
  }
</style>
