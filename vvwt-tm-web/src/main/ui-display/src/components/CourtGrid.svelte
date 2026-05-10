<script lang="ts">
  /**
   * Left column of the Gesamtübersicht: court/match grid (E07S05, AC2, AC3).
   *
   * E50S04 multi-round display (T3 + T4 + T5):
   *   T3 — All rounds visible: matches for every lapNumber are shown simultaneously,
   *         grouped into one round-row per lap per field column.
   *   T4 — Vertical fill: each field column uses display:grid with
   *         grid-template-rows: repeat(N, 1fr) so N lap rows share the available height equally.
   *   T5 — Active-round highlight: the round row matching currentLap gets the CSS class
   *         round-row--active (darker background) so the audience can identify the current round.
   *
   * Layout: one column per court/field. Each column has a header ("Feld 1", "Feld 2", …).
   * Below the header, one round-row per lap (sorted ascending by lapNumber), each containing
   * all MatchRow components for that lap+field combination.
   *
   * Props (unchanged per AC-GOVERNANCE-NO-OUT-OF-SCOPE-REFACTOR):
   *   matchesData  — all matches across all laps (BE filter removed by E50S04)
   *   fieldCount   — number of field columns from phase overview
   *   currentLap   — currently active lap from phase overview (T5 highlight source)
   *
   * AC2: field columns are 1-indexed, dynamic based on phase.fieldCount.
   * AC3: currentLap from DisplayPhaseOverview.currentLap (empirical O-7, App.svelte:49).
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

  /**
   * Extract distinct lap numbers from matches for a given field column.
   * Returns lap numbers sorted ascending (lap 1 at top per AC-TEST-MULTI-ROUND-LAP-ASCENDING-ORDER-RED).
   * Matches with null lapNumber are excluded (not yet slot-optimized).
   *
   * @param fieldMatches — all matches for a single field column
   */
  function distinctLapsSorted(fieldMatches: MatchEntry[]): number[] {
    const lapSet = new Set<number>();
    for (const m of fieldMatches) {
      if (m.lapNumber !== null) {
        lapSet.add(m.lapNumber);
      }
    }
    return Array.from(lapSet).sort((a, b) => a - b);
  }

  /**
   * Return matches for a specific lap within a field's match list.
   * Used to populate each round-row with its MatchRow components.
   */
  function matchesForLap(fieldMatches: MatchEntry[], lap: number): MatchEntry[] {
    return fieldMatches.filter((m) => m.lapNumber === lap);
  }

  const fieldColumns = $derived(matchesByField(matchesData.matches));
  const fieldNumbers = $derived(Array.from(fieldColumns.keys()).sort((a, b) => a - b));

  /**
   * Compute the maximum distinct lap count across all fields.
   * Used to set grid-template-rows: repeat(N, 1fr) for vertical fill (T4).
   * If zero (no matches), falls back to 1 to avoid degenerate grid.
   */
  const maxLapCount = $derived(
    Math.max(
      1,
      ...fieldNumbers.map((fn) => distinctLapsSorted(fieldColumns.get(fn) ?? []).length)
    )
  );
</script>

<div class="court-grid" style="--field-count: {fieldCount}">
  <!--
    AC2: one column per court/field.
    Each column has a header ("Feld 1") and a list of round rows (one per lap).
    CSS Grid is used for the multi-column layout (fieldCount columns of equal width).
    E50S04 T4: --lap-count drives grid-template-rows: repeat(N, 1fr) on .court-grid__field-column.
  -->

  <!-- Field headers row -->
  <div class="court-grid__headers">
    {#each fieldNumbers as fieldNum (fieldNum)}
      <div class="court-grid__field-header">
        {$_('display.overview.field')} {fieldNum}
      </div>
    {/each}
  </div>

  <!-- Match rows per field — one round-row per lap (E50S04 T3 multi-round) -->
  <div class="court-grid__body">
    {#each fieldNumbers as fieldNum (fieldNum)}
      {@const fieldMatches = fieldColumns.get(fieldNum) ?? []}
      {@const laps = distinctLapsSorted(fieldMatches)}
      <div
        class="court-grid__field-column"
        style="--lap-count: {Math.max(1, laps.length)}"
      >
        {#each laps as lap (lap)}
          <!--
            E50S04 T3: one round-row per distinct lapNumber per field column.
            E50S04 T5: round-row--active applied to the row matching currentLap.
            Ascending order by lapNumber: lap 1 at top (distinctLapsSorted returns sorted array).
          -->
          <div
            class="round-row"
            class:round-row--active={lap === currentLap}
          >
            {#each matchesForLap(fieldMatches, lap) as match (match.matchId)}
              <MatchRow
                {match}
                isCurrentLap={lap === currentLap}
              />
            {/each}
          </div>
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
    overflow: hidden;
  }

  /*
   * E50S04 T4 — Vertical fill: each field column uses display:grid with
   * grid-template-rows: repeat(N, 1fr) so all N round-rows share the available
   * height equally (AC-TEST-VERTICAL-FILL-EQUAL-DISTRIBUTION-RED).
   * --lap-count is set inline per field column to the number of distinct laps.
   */
  .court-grid__field-column {
    display: grid;
    grid-template-rows: repeat(var(--lap-count, 1), 1fr);
    border-right: 1px solid #ddd;
    overflow: hidden;
  }

  .court-grid__field-column:last-child {
    border-right: none;
  }

  /*
   * E50S04 T3 — Base round-row style (inactive rounds).
   * Each round-row spans one lap's matches within a field column.
   * Border separates rounds visually.
   */
  .round-row {
    border-bottom: 2px solid #bbb;
    overflow-y: auto;
    background: #fff;
  }

  .round-row:last-child {
    border-bottom: none;
  }

  /*
   * E50S04 T5 — Active-round highlight (color-only per Brief D-3 + D-4).
   * Background color: #d6e8ff (R:214 G:232 B:255) vs inactive #fff (R:255 G:255 B:255).
   * RGB-Δ: R: 255-214 = 41 ≥ 30 ✓ (AC-TEST-ACTIVE-ROUND-HIGHLIGHT-CSS-CONTRAST-RED).
   * No size change (D-3/D-4 explicit color-only scope).
   */
  .round-row--active {
    background: #d6e8ff;
    border-bottom-color: #2980b9;
  }
</style>
