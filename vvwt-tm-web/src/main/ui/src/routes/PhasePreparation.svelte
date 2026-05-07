<script lang="ts">
  /**
   * Thin wrapper for the Vorbereiten-Route (E48S18).
   *
   * Route: /tournaments/:tournamentId/phases/:phaseId/prepare
   *
   * Reuses PhaseTransition.svelte with:
   *   - commitEndpoint → POST /api/phases/:phaseId/prepare  (E48S17 endpoint)
   *   - pageTitleKey   → 'phases.prepareTitle'
   *
   * svelte-spa-router does not support per-route component props, so a thin wrapper
   * is the canonical pattern (DEC-9, AC-IMPL-FRONTEND-PREPARE-ROUTE).
   */
  import PhaseTransition from './PhaseTransition.svelte';

  interface Props {
    params?: { tournamentId?: string; phaseId?: string };
  }
  let { params = {} }: Props = $props();

  const phaseId = $derived(params.phaseId ?? '');
  const prepareEndpoint = $derived(`/api/phases/${phaseId}/prepare`);
</script>

<PhaseTransition
  {params}
  commitEndpoint={prepareEndpoint}
  pageTitleKey="phases.prepareTitle"
/>
