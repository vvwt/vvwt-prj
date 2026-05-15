<script lang="ts">
  /**
   * Two-column layout container for the Gesamtübersicht (E07S05, AC1; E07S06, AC6).
   *
   * AC1: CSS Grid with two columns:
   *   - Left (main area): flexible, displays courts/matches grid (CourtGrid)
   *   - Right (sidebar): fixed-width 28em, displays group standings (GroupStandingsPanel)
   *
   * E07S06 AC6: When {@code phaseData.preparationPreview} is true (phase is PENDING/PREPARATION
   * but matches are already generated for preview), a prominent "Vorschau" banner is shown
   * spanning the full width above the grid and standings.
   *
   * E50S03 AC-GOVERNANCE-OVERVIEWLAYOUT-ADDITIVE-ONLY:
   *   Only additive change — new `sidebarHeader?: Snippet` prop.
   *   Rendered via {@render sidebarHeader?.()} at the top of overview-layout__sidebar.
   *   No structural changes to the two-column grid, CourtGrid, GroupStandingsPanel, MatchRow.
   *
   * Proportions approximate the legacy Gesamtübersicht layout as described in the
   * story and referenced from vvw-tournaments-info-ui/controls/main/view.stache.
   */
  import { _ } from 'svelte-i18n';
  import type { Snippet } from 'svelte';
  import type { DisplayMatchesData, DisplayGroupStandings, DisplayPhaseOverview } from '../lib/displayApi.js';
  import CourtGrid from './CourtGrid.svelte';
  import GroupStandingsPanel from './GroupStandingsPanel.svelte';

  interface Props {
    phaseData: DisplayPhaseOverview;
    matchesData: DisplayMatchesData;
    standingsData: DisplayGroupStandings;
    /**
     * E50S03: Optional snippet rendered at the top of the sidebar.
     * Used to inject SidebarHeader (brand logo + connection indicator) via
     * App.svelte's snippet prop — keeps OverviewLayout free of branding concerns.
     */
    sidebarHeader?: Snippet;
  }

  const { phaseData, matchesData, standingsData, sidebarHeader }: Props = $props();
</script>

<!--
  AC1: two-column CSS Grid layout — approximately 4fr / 28em.
  The left column grows with available width; the right sidebar is fixed at 28em.
  On narrow viewports, the sidebar stacks below the court grid (not a V1 concern —
  display screens are wide-format, but this degrades gracefully).
-->
<div class="overview-layout">
  <!-- E07S06 AC6: preparation preview banner (PENDING phase with matches generated) -->
  {#if phaseData.preparationPreview}
    <div class="overview-layout__preview-banner" role="status">
      {$_('display.overview.preparationPreview')}
    </div>
  {/if}

  <!-- Left column: courts/matches grid (AC2, AC3) -->
  <div class="overview-layout__main">
    <CourtGrid
      matchesData={matchesData}
      fieldCount={phaseData.fieldCount}
      currentLap={phaseData.currentLap}
    />
  </div>

  <!-- Right sidebar: sidebar-header (E50S03) + group standings (AC4) -->
  <div class="overview-layout__sidebar">
    <!--
      E50S03: Render SidebarHeader snippet at the top of the sidebar.
      Optional — gracefully absent if caller does not provide it.
      Positioned ABOVE GroupStandingsPanel per AC-TEST-LAYOUT-SIDEBAR-HEADER-RENDERED-RED.
    -->
    {@render sidebarHeader?.()}
    <GroupStandingsPanel standingsData={standingsData} />
  </div>
</div>

<style>
  .overview-layout {
    display: grid;
    /* AC1: ~4fr left (courts) / 28em right (standings) */
    grid-template-columns: 4fr 28em;
    /* E07S06 AC6: preview banner spans full width via subgrid/column-span trick */
    grid-template-rows: auto 1fr;
    height: 100%;
    overflow: hidden;
  }

  /* E07S06 AC6: preview banner spans both columns */
  .overview-layout__preview-banner {
    grid-column: 1 / -1;
    background: #f39c12;
    color: #fff;
    font-size: 1.25rem;
    font-weight: bold;
    text-align: center;
    padding: 0.4rem 1rem;
    letter-spacing: 0.15em;
    text-transform: uppercase;
  }

  .overview-layout__main {
    /*
     * E50S06: explicit grid-row: 2 places __main in the 1fr row regardless of
     * whether the preview banner is rendered. Without this, CSS auto-placement
     * assigns both __main and __sidebar to row 1 (auto = content-sized) when no
     * banner element is present, leaving the 1fr row empty and both children
     * collapsed to content height (height ≈ 0). The real-browser regression test
     * (OverviewLayout.layout-browser.test.ts) was RED on HEAD without this fix.
     * AC-TEST-VERTICAL-FILL-REAL-ENGINE-RED / AC-ERROR-FILL-ALL-BANNER-ABSENT-STATES.
     */
    grid-row: 2;
    overflow: hidden;
    border-right: 2px solid #ccc;
  }

  .overview-layout__sidebar {
    /*
     * E50S06: explicit grid-row: 2 (same rationale as __main above).
     * Both content children are anchored to the 1fr row unconditionally.
     */
    grid-row: 2;
    width: 28em;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  /* Narrow viewport fallback: stack vertically */
  @media (max-width: 900px) {
    .overview-layout {
      grid-template-columns: 1fr;
      grid-template-rows: auto auto auto;
    }

    .overview-layout__main {
      border-right: none;
      border-bottom: 2px solid #ccc;
    }

    .overview-layout__sidebar {
      width: 100%;
    }
  }
</style>
