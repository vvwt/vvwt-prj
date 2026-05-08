<script lang="ts">
  /**
   * Thin wrapper for the Vorbereiten-Route (E48S18 / E51S06 rollback of E48S21).
   *
   * Route: /tournaments/:tournamentId/phases/:phaseId/prepare
   *
   * Reuses PhaseTransition.svelte with:
   *   - commitEndpoint  → default (POST /api/phases/:phaseId/transition-commit) — E51S06 rollback
   *   - pageTitleKey    → 'phases.prepareTitle'
   *
   * The E48S21 commitEndpoint override (POST /prepare) has been removed. The drag&drop UI now
   * commits via the standard transition-commit endpoint (E51S06 / DEC-55 D-10).
   *
   * svelte-spa-router does not support per-route component props, so a thin wrapper
   * is the canonical pattern (DEC-9, AC-IMPL-FRONTEND-PREPARE-ROUTE).
   */
  import PhaseTransition from './PhaseTransition.svelte';

  interface Props {
    params?: { tournamentId?: string; phaseId?: string };
  }
  let { params = {} }: Props = $props();
</script>

<PhaseTransition
  {params}
  pageTitleKey="phases.prepareTitle"
/>
