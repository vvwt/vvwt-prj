<script lang="ts">
  /**
   * Error state panel for the Gesamtübersicht display (E07S05, AC9).
   *
   * Shows a descriptive error message and a retry button. The retry button
   * callback is provided by the parent (App.svelte) which re-triggers data loading.
   *
   * Error types (AC9):
   *   'noPhase'      — no active tournament (AC6)
   *   'unauthorized' — device token invalid; shows re-register instruction
   *   'generic'      — network/server error; retry button shown
   */
  import { _ } from 'svelte-i18n';

  interface Props {
    errorType: 'noPhase' | 'unauthorized' | 'generic';
    onRetry: () => void;
  }

  const { errorType, onRetry }: Props = $props();
</script>

<div class="error-panel" role="alert">
  {#if errorType === 'noPhase'}
    <p class="error-panel__message error-panel__message--info">
      {$_('display.overview.noActivePhase')}
    </p>
  {:else if errorType === 'unauthorized'}
    <p class="error-panel__message error-panel__message--warning">
      {$_('display.overview.errorUnauthorized')}
    </p>
  {:else}
    <p class="error-panel__message error-panel__message--error">
      {$_('display.overview.errorLoad')}
    </p>
    <button class="error-panel__retry" type="button" onclick={onRetry}>
      {$_('display.overview.errorRetry')}
    </button>
  {/if}
</div>

<style>
  .error-panel {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    min-height: 200px;
    padding: 2rem;
  }

  .error-panel__message {
    font-size: 1.4rem;
    margin-bottom: 1.5rem;
    text-align: center;
  }

  .error-panel__message--info {
    color: #555;
  }

  .error-panel__message--warning {
    color: #c0392b;
  }

  .error-panel__message--error {
    color: #c0392b;
  }

  .error-panel__retry {
    padding: 0.6rem 1.4rem;
    font-size: 1rem;
    background: #2980b9;
    color: #fff;
    border: none;
    border-radius: 4px;
    cursor: pointer;
  }

  .error-panel__retry:hover {
    background: #2471a3;
  }
</style>
