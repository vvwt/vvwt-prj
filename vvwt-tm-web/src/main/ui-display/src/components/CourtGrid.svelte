<script lang="ts">
  /**
   * Left column of the Gesamtübersicht: court/match grid (E07S05, AC2, AC3).
   *
   * Layout: one column per court/field. Each column has a header with the field
   * name ("Feld 1", "Feld 2", …). Below the header, matches are displayed as rows.
   * All matches (all laps) are shown; the current lap's matches are highlighted
   * via the isCurrentLap prop passed to MatchRow (AC3).
   *
   * Data source: DisplayMatchesData from E07S04 AC2.
   * The matches array includes all laps (the API returns matches for the current lap
   * by default, but App.svelte fetches all matches without a lap filter — the lap
   * param is omitted, so the backend returns the current lap; this is sufficient for
   * V1 as E07S06 adds real-time updates).
   *
   * AC2: field columns are 1-indexed, dynamic based on phase.fieldCount.
   *   - fieldCount from DisplayPhaseOverview determines how many columns to render.
   *   - Matches are distributed to columns by their fieldNumber.
   */
  import { _ } from 'svelte-i18n';
  import type { DisplayMatchesData, MatchEntry } from '../lib/displayApi.js';
  import MatchRow from './MatchRow.svelte';

  interface Props {
    matchesData: DisplayMatchesData;
    fieldCount: number;
    currentLap: number;
  }

  const { matchesData, fieldCount, currentLap }: Props = $props();

  /**
   * Group matches by fieldNumber.
   * Matches with null fieldNumber are excluded (slot-optimization not yet run for them).
   * Returns a Map<fieldNumber, MatchEntry[]> ordered by fieldNumber ascending.
   */
  function matchesByField(matches: MatchEntry[]): Map<number, MatchEntry[]> {
    const fieldMap = new Map<number, MatchEntry[]>();

    // Initialize all fields 1..fieldCount so columns appear even if no match is scheduled
    for (let i = 1; i <= fieldCount; i++) {
      fieldMap.set(i, []);
    }

    for (const match of matches) {
      if (match.fieldNumber !== null) {
        const existing = fieldMap.get(match.fieldNumber) ?? [];
        existing.push(match);
        fieldMap.set(match.fieldNumber, existing);
      }
    }

    return fieldMap;
  }

  const fieldColumns = $derived(matchesByField(matchesData.matches));
  const fieldNumbers = $derived(Array.from(fieldColumns.keys()).sort((a, b) => a - b));
</script>

<div class="court-grid" style="--field-count: {fieldCount}">
  <!--
    AC2: one column per court/field.
    Each column has a header ("Feld 1") and a list of match rows for that field.
    CSS Grid is used for the multi-column layout (fieldCount columns of equal width).
  -->

  <!-- Field headers row -->
  <div class="court-grid__headers">
    {#each fieldNumbers as fieldNum (fieldNum)}
      <div class="court-grid__field-header">
        {$_('display.overview.field')} {fieldNum}
      </div>
    {/each}
  </div>

  <!-- Match rows per field -->
  <div class="court-grid__body">
    {#each fieldNumbers as fieldNum (fieldNum)}
      <div class="court-grid__field-column">
        {#each (fieldColumns.get(fieldNum) ?? []) as match (match.matchId)}
          <MatchRow
            {match}
            isCurrentLap={matchesData.lap === currentLap}
          />
        {/each}
      </div>
    {/each}
  </div>
</div>

<style>
  .court-grid {
    display: flex;
    flex-direction: column;
    height: 100%;
    overflow: hidden;
  }

  /* AC2: one column per field — using CSS grid with dynamic column count */
  .court-grid__headers {
    display: grid;
    grid-template-columns: repeat(var(--field-count), 1fr);
    gap: 0;
    background: #2c3e50;
    color: #ecf0f1;
    flex-shrink: 0;
    /*
     * E50S03: height matches SidebarHeader (.sidebar-header) via shared CSS custom property.
     * --field-header-height is declared on :global(#app) in App.svelte.
     * This produces visual alignment: sidebar-header and court-grid__headers form
     * a single visual line across the full screen width.
     */
    height: var(--field-header-height);
  }

  .court-grid__field-header {
    padding: 0.5rem 0.6rem;
    font-weight: 600;
    font-size: 0.95rem;
    text-align: center;
    border-right: 1px solid #3d5166;
    letter-spacing: 0.03em;
  }

  .court-grid__field-header:last-child {
    border-right: none;
  }

  .court-grid__body {
    display: grid;
    grid-template-columns: repeat(var(--field-count), 1fr);
    gap: 0;
    flex: 1;
    overflow-y: auto;
  }

  .court-grid__field-column {
    border-right: 1px solid #ddd;
    overflow-y: auto;
  }

  .court-grid__field-column:last-child {
    border-right: none;
  }
</style>
