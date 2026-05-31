<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  // E70S02 — RED-first DEC-22 jscpd Svelte fixture (file B).
  // Intentional near-duplicate of jscpd-svelte-fixture-a.svelte.
  // DO NOT REMOVE — regression guard for the jscpd Svelte gate.
  let label: string = 'Team Name';
  let value: string = '';
  let maxLength: number = 50;
  let errorMessage: string = '';
  let isValid: boolean = true;

  function handleInput(event: Event): void {
    const target = event.target as HTMLInputElement;
    value = target.value.substring(0, maxLength);
    isValid = value.trim().length > 0;
    errorMessage = isValid ? '' : 'Field is required';
  }

  function handleBlur(): void {
    isValid = value.trim().length > 0;
    errorMessage = isValid ? '' : 'Field is required';
  }

  function reset(): void {
    value = '';
    isValid = true;
    errorMessage = '';
  }
</script>

<div class="fixture-b-field">
  <label for="fixture-b">{label}</label>
  <input
    id="fixture-b"
    type="text"
    bind:value
    on:input={handleInput}
    on:blur={handleBlur}
    class:error={!isValid}
    maxlength={maxLength}
  />
  {#if errorMessage}
    <span class="error-msg">{errorMessage}</span>
  {/if}
  <span class="hint">Max {maxLength} characters</span>
  <button on:click={reset} type="button">Reset</button>
</div>

<style>
  .fixture-b-field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 8px;
  }
  label {
    font-weight: bold;
    font-size: 0.9rem;
  }
  input {
    border: 1px solid #ccc;
    border-radius: 4px;
    padding: 6px 8px;
  }
  input.error {
    border-color: red;
  }
  .error-msg {
    color: red;
    font-size: 0.8rem;
  }
  .hint {
    color: #666;
    font-size: 0.75rem;
  }
</style>
