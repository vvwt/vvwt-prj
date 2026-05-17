<!--
  SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
  SPDX-License-Identifier: AGPL-3.0-or-later
-->
<script lang="ts">
  /**
   * Tournament create/edit form — Story E05S04 AC8, AC10, AC11.
   *
   * Used for both creating a new tournament (POST /api/tournaments) and editing
   * an existing DRAFT tournament (PUT /api/tournaments/{id}).
   *
   * Props:
   *   - id (optional): if provided, loads the tournament and performs PUT; otherwise POST
   *
   * AC8: form includes all AC3 fields; dropdowns for matchFormat, scoringRuleId,
   *   setValidationRuleId, matchGeneratorId are populated from /api/scoring/rules.
   * AC10: API validation errors are displayed inline next to the relevant field.
   * AC11: all visible strings sourced from svelte-i18n $_ function.
   */
  import { onMount, onDestroy } from 'svelte';
  import { get } from 'svelte/store';
  import { pop } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import {
    getTournament,
    createTournament,
    updateTournament,
    getTournamentRules,
    type TournamentRules,
    type TournamentCreateRequest,
  } from '../stores/tournamentStore.js';
  import { getGeneratorList, type MatchGeneratorInfo } from '../stores/generatorStore.js';
  import {
    filterNonLastPhaseGenerators,
    resolveNonLastDefault,
  } from '../lib/generatorFilter.js';

  // ── Props ────────────────────────────────────────────────────────────────
  interface Props {
    params?: { id?: string };
  }
  let { params = {} }: Props = $props();
  // Use $derived to reactively read params.id (Svelte 5 requires derived for reactive prop access)
  const editId = $derived(params.id);

  // ── Form state ───────────────────────────────────────────────────────────
  let description = $state('');
  let appointment = $state('');      // string in datetime-local input format, or ''
  let plannedStartTime = $state(''); // string in HH:mm format, or '' (E08S05 AC4)
  let teamCount = $state(2);
  let fieldCount = $state(1);
  let matchFormat = $state('');
  let scoringRuleId = $state('');
  let setValidationRuleId = $state('');
  let matchGeneratorId = $state('');
  let optimize = $state(true); // E51S07: default true per DEC-55 D-5
  let seedMannschaftsfoto = $state(true); // E53S05: default true — Vorbelegung checked by default

  let rules = $state<TournamentRules | null>(null);
  let generators = $state<MatchGeneratorInfo[]>([]); // E58S05 AC2/AC5: shared generator list
  // E58S06 AC1: only non-last-phase generators appear in the tournament-form dropdown
  let nonLastGenerators = $derived(filterNonLastPhaseGenerators(generators));
  let loading = $state(true);
  let saving = $state(false);
  let saveError = $state<string | null>(null);
  /** Field-level errors from the API (AC10). Key = field name, value = error message. */
  let fieldErrors = $state<Record<string, string>>({});

  // ── Init ─────────────────────────────────────────────────────────────────
  onMount(async () => {
    // E47S02 AC3/AC4/AC5/AC7/AC8/AC9: register page title + back-arrow via pageHeader store.
    // TournamentForm is used for both /tournaments/new and /tournaments/:id/edit.
    // backTo: '/tournaments' — both routes are sub-routes of /tournaments (Brief D-12, AC5).
    // tournamentId: null — D-6 carve-out: the form contains the tournament name → no header-name.
    // actions: [] — Save/Cancel STAY in form__actions footer per D-13 (HTML form-association).
    pageHeader.set({
      title: get(_)(editId ? 'tournamentForm.editTitle' : 'tournamentForm.createTitle'),
      backTo: '/tournaments',
      tournamentId: null,
      actions: [],
    });
    try {
      [rules, generators] = await Promise.all([getTournamentRules(), getGeneratorList()]);
      // Set default selections to first option in each list
      if (rules.matchFormats.length > 0) matchFormat = rules.matchFormats[0];
      if (rules.scoringRuleIds.length > 0) scoringRuleId = rules.scoringRuleIds[0];
      if (rules.setValidationRuleIds.length > 0) setValidationRuleId = rules.setValidationRuleIds[0];
      // E58S06 AC2: default to the first non-last-phase generator (capability filter — DEC-73 D-5)
      // Falls back to null if no non-last generator exists (AC11 graceful degradation)
      const defaultGen = resolveNonLastDefault(generators, '');
      if (defaultGen !== null) matchGeneratorId = defaultGen;

      if (editId) {
        // Load existing tournament for edit
        const t = await getTournament(editId);
        description = t.description;
        appointment = t.appointment ? t.appointment.substring(0, 16) : '';  // datetime-local format
        plannedStartTime = t.plannedStartTime ?? '';  // HH:mm or '' (E08S05 AC4)
        teamCount = t.teamCount;
        fieldCount = t.fieldCount;
        matchFormat = t.matchFormat;
        scoringRuleId = t.scoringRuleId;
        setValidationRuleId = t.setValidationRuleId;
        matchGeneratorId = t.matchGeneratorId;
        optimize = t.optimize ?? true;  // E51S07: load optimize from tournament; default true
      }
    } catch (e: unknown) {
      saveError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  });

  // ── Cleanup ──────────────────────────────────────────────────────────────
  onDestroy(() => {
    resetPageHeader();
  });

  // ── Submit ────────────────────────────────────────────────────────────────
  async function handleSubmit(e: Event): Promise<void> {
    e.preventDefault();
    saveError = null;
    fieldErrors = {};
    saving = true;

    // Parse appointment: convert datetime-local string to ISO-8601 or null
    const appointmentValue = appointment.trim() ? appointment.trim() + ':00' : null;

    try {
      if (editId) {
        await updateTournament(editId, {
          description,
          appointment: appointmentValue,
          teamCount,
          fieldCount,
          matchFormat,
          scoringRuleId,
          setValidationRuleId,
          matchGeneratorId,
          plannedStartTime: plannedStartTime.trim() ? plannedStartTime.trim() : null,  // E08S05 AC4
          optimize,  // E51S07: pass optimize field
        });
      } else {
        const req: TournamentCreateRequest = {
          description,
          appointment: appointmentValue,
          teamCount,
          fieldCount,
          matchFormat,
          scoringRuleId,
          setValidationRuleId,
          matchGeneratorId,
          plannedStartTime: plannedStartTime.trim() ? plannedStartTime.trim() : null,  // E48S14
          optimize,  // E51S07: pass optimize field
          seedMannschaftsfoto,  // E53S05: pass Vorbelegung flag
        };
        await createTournament(req);
      }
      pop();  // Navigate back to the tournament list
    } catch (e: unknown) {
      if (e && typeof e === 'object' && 'apiError' in e) {
        const apiErr = (e as { apiError: { fieldErrors?: Array<{ field: string; message: string }> ; message?: string } }).apiError;
        // AC10: render field-level errors inline
        if (apiErr.fieldErrors?.length) {
          const fe: Record<string, string> = {};
          for (const ferr of apiErr.fieldErrors) {
            fe[ferr.field] = ferr.message;
          }
          fieldErrors = fe;
        } else {
          saveError = apiErr.message ?? $_('tournamentForm.saveError');
        }
      } else {
        saveError = e instanceof Error ? e.message : $_('tournamentForm.saveError');
      }
    } finally {
      saving = false;
    }
  }
</script>

<main class="tournament-form">
  {#if loading}
    <p>…</p>
  {:else}
    <form onsubmit={handleSubmit} novalidate>

      {#if saveError}
        <p class="form__error">{saveError}</p>
      {/if}

      <!-- Description -->
      <div class="form__field">
        <label for="description">{$_('tournamentForm.fields.description')}</label>
        <input id="description" type="text" bind:value={description} required />
        {#if fieldErrors['description']}
          <span class="form__field-error">{fieldErrors['description']}</span>
        {/if}
      </div>

      <!-- Appointment (optional) -->
      <div class="form__field">
        <label for="appointment">{$_('tournamentForm.fields.appointment')}</label>
        <input id="appointment" type="datetime-local" bind:value={appointment} />
        {#if fieldErrors['appointment']}
          <span class="form__field-error">{fieldErrors['appointment']}</span>
        {/if}
      </div>

      <!-- Planned start time (optional, E08S05 AC4) -->
      <div class="form__field">
        <label for="plannedStartTime">{$_('tournamentForm.fields.plannedStartTime')}</label>
        <input id="plannedStartTime" type="time" bind:value={plannedStartTime} />
        {#if fieldErrors['plannedStartTime']}
          <span class="form__field-error">{fieldErrors['plannedStartTime']}</span>
        {/if}
      </div>

      <!-- Team count -->
      <div class="form__field">
        <label for="teamCount">{$_('tournamentForm.fields.teamCount')}</label>
        <input id="teamCount" type="number" min="2" bind:value={teamCount} required />
        {#if fieldErrors['teamCount']}
          <span class="form__field-error">{fieldErrors['teamCount']}</span>
        {/if}
      </div>

      <!-- Field count -->
      <div class="form__field">
        <label for="fieldCount">{$_('tournamentForm.fields.fieldCount')}</label>
        <input id="fieldCount" type="number" min="1" bind:value={fieldCount} required />
        {#if fieldErrors['fieldCount']}
          <span class="form__field-error">{fieldErrors['fieldCount']}</span>
        {/if}
      </div>

      <!-- Match format -->
      {#if rules}
        <!-- E58S06 AC6/AC7: render German i18n labels via matchOption.matchFormat.* namespace (AC8) -->
        <div class="form__field">
          <label for="matchFormat">{$_('tournamentForm.fields.matchFormat')}</label>
          <select id="matchFormat" bind:value={matchFormat} required>
            {#each rules.matchFormats as fmt}
              <option value={fmt}>{$_(`matchOption.matchFormat.${fmt}`, { default: fmt })}</option>
            {/each}
          </select>
          {#if fieldErrors['matchFormat']}
            <span class="form__field-error">{fieldErrors['matchFormat']}</span>
          {/if}
        </div>

        <!-- E58S06 AC6/AC7: render German i18n labels via matchOption.scoringRule.* namespace (AC8) -->
        <div class="form__field">
          <label for="scoringRuleId">{$_('tournamentForm.fields.scoringRuleId')}</label>
          <select id="scoringRuleId" bind:value={scoringRuleId} required>
            {#each rules.scoringRuleIds as id}
              <option value={id}>{$_(`matchOption.scoringRule.${id}`, { default: id })}</option>
            {/each}
          </select>
          {#if fieldErrors['scoringRuleId']}
            <span class="form__field-error">{fieldErrors['scoringRuleId']}</span>
          {/if}
        </div>

        <!-- E58S06 AC6/AC7: render German i18n labels via matchOption.setValidationRule.* namespace (AC8) -->
        <div class="form__field">
          <label for="setValidationRuleId">{$_('tournamentForm.fields.setValidationRuleId')}</label>
          <select id="setValidationRuleId" bind:value={setValidationRuleId} required>
            {#each rules.setValidationRuleIds as id}
              <option value={id}>{$_(`matchOption.setValidationRule.${id}`, { default: id })}</option>
            {/each}
          </select>
          {#if fieldErrors['setValidationRuleId']}
            <span class="form__field-error">{fieldErrors['setValidationRuleId']}</span>
          {/if}
        </div>

        <!-- E58S06 AC1: capability-filtered to non-last-phase generators only (DEC-73 D-5)
             E58S06 AC6/AC7: German labels via matchOption.generator.* namespace (AC8) -->
        <div class="form__field">
          <label for="matchGeneratorId">{$_('tournamentForm.fields.matchGeneratorId')}</label>
          <select id="matchGeneratorId" bind:value={matchGeneratorId} required>
            {#each nonLastGenerators as gen (gen.keyId)}
              <option value={gen.keyId}>{$_(`matchOption.generator.${gen.keyId}`, { default: gen.keyId })}</option>
            {/each}
          </select>
          {#if fieldErrors['matchGeneratorId']}
            <span class="form__field-error">{fieldErrors['matchGeneratorId']}</span>
          {/if}
        </div>
      {/if}

      <!-- Slot-optimization checkbox (E51S07, DEC-55 D-5) -->
      <div class="form__field form__field--checkbox">
        <label for="optimize" title={$_('slotopt.checkbox.tooltip')}>
          <input id="optimize" type="checkbox" bind:checked={optimize} />
          {$_('slotopt.checkbox.label')}
        </label>
      </div>

      <!-- Mannschaftsfoto Vorbelegung checkbox (E53S05 AC3, analogous to optimize/E51S07) -->
      <div class="form__field form__field--checkbox">
        <label for="seedMannschaftsfoto" title={$_('mannschaftsfoto.checkbox.tooltip')}>
          <input id="seedMannschaftsfoto" type="checkbox" bind:checked={seedMannschaftsfoto} />
          {$_('mannschaftsfoto.checkbox.label')}
        </label>
      </div>

      <div class="form__actions">
        <button type="submit" class="btn btn--primary" disabled={saving}>
          {$_('tournamentForm.saveButton')}
        </button>
        <button type="button" class="btn btn--secondary" onclick={() => pop()}>
          {$_('tournamentForm.cancelButton')}
        </button>
      </div>

    </form>
  {/if}
</main>

<style>
  .tournament-form {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 600px;
  }

  .form__field {
    margin-bottom: 1.2rem;
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }

  .form__field label {
    font-weight: 600;
    font-size: 0.9rem;
  }

  .form__field input,
  .form__field select {
    padding: 0.4rem 0.6rem;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    font-size: 0.9rem;
  }

  .form__field-error {
    color: #c0392b;
    font-size: 0.8rem;
  }

  .form__error {
    color: #c0392b;
    margin-bottom: 1rem;
    padding: 0.5rem;
    background: #fdecea;
    border-radius: 4px;
  }

  .form__actions {
    display: flex;
    gap: 0.75rem;
    margin-top: 1.5rem;
  }

  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1.25rem;
    font-size: 0.9rem;
  }

  .btn--primary {
    background: #2980b9;
    color: #fff;
  }

  .btn--primary:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .btn--secondary {
    background: #ecf0f1;
    color: #2c3e50;
    border: 1px solid #bdc3c7;
  }
</style>
