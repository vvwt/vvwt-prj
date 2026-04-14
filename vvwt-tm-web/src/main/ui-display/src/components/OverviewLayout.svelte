<script lang="ts">
  /**
   * Two-column layout container for the Gesamtübersicht (E07S05, AC1).
   *
   * AC1: CSS Grid with two columns:
   *   - Left (main area): flexible, displays courts/matches grid (CourtGrid)
   *   - Right (sidebar): fixed-width 28em, displays group standings (GroupStandingsPanel)
   *
   * Proportions approximate the legacy Gesamtübersicht layout as described in the
   * story and referenced from vvw-tournaments-info-ui/controls/main/view.stache.
   */
  import type { DisplayMatchesData, DisplayGroupStandings, DisplayPhaseOverview } from '../lib/displayApi.js';
  import CourtGrid from './CourtGrid.svelte';
  import GroupStandingsPanel from './GroupStandingsPanel.svelte';

  interface Props {
    phaseData: DisplayPhaseOverview;
    matchesData: DisplayMatchesData;
    standingsData: DisplayGroupStandings;
  }

  const { phaseData, matchesData, standingsData }: Props = $props();
</script>

<!--
  AC1: two-column CSS Grid layout — approximately 4fr / 28em.
  The left column grows with available width; the right sidebar is fixed at 28em.
  On narrow viewports, the sidebar stacks below the court grid (not a V1 concern —
  display screens are wide-format, but this degrades gracefully).
-->
<div class="overview-layout">
  <!-- Left column: courts/matches grid (AC2, AC3) -->
  <div class="overview-layout__main">
    <CourtGrid
      matchesData={matchesData}
      fieldCount={phaseData.fieldCount}
      currentLap={phaseData.currentLap}
    />
  </div>

  <!-- Right sidebar: group standings (AC4) -->
  <div class="overview-layout__sidebar">
    <GroupStandingsPanel standingsData={standingsData} />
  </div>
</div>

<style>
  .overview-layout {
    display: grid;
    /* AC1: ~4fr left (courts) / 28em right (standings) */
    grid-template-columns: 4fr 28em;
    height: 100%;
    overflow: hidden;
  }

  .overview-layout__main {
    overflow: hidden;
    border-right: 2px solid #ccc;
  }

  .overview-layout__sidebar {
    width: 28em;
    overflow: hidden;
  }

  /* Narrow viewport fallback: stack vertically */
  @media (max-width: 900px) {
    .overview-layout {
      grid-template-columns: 1fr;
      grid-template-rows: auto auto;
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
