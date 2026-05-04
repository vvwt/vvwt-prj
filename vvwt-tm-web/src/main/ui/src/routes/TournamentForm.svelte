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
  import { onMount } from 'svelte';
  import { pop } from 'svelte-spa-router';
  import { _ } from 'svelte-i18n';
  import {
    getTournament,
    createTournament,
    updateTournament,
    getTournamentRules,
    type TournamentRules,
    type TournamentCreateRequest,
  } from '../stores/tournamentStore.js';

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

  let rules = $state<TournamentRules | null>(null);
  let loading = $state(true);
  let saving = $state(false);
  let saveError = $state<string | null>(null);
  /** Field-level errors from the API (AC10). Key = field name, value = error message. */
  let fieldErrors = $state<Record<string, string>>({});

  // ── Init ─────────────────────────────────────────────────────────────────
  onMount(async () => {
    try {
      rules = await getTournamentRules();
      // Set default selections to first option in each list
      if (rules.matchFormats.length > 0) matchFormat = rules.matchFormats[0];
      if (rules.scoringRuleIds.length > 0) scoringRuleId = rules.scoringRuleIds[0];
      if (rules.setValidationRuleIds.length > 0) setValidationRuleId = rules.setValidationRuleIds[0];
      if (rules.matchGeneratorIds.length > 0) matchGeneratorId = rules.matchGeneratorIds[0];

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
      }
    } catch (e: unknown) {
      saveError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
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
  <h1>
    {editId ? $_('tournamentForm.editTitle') : $_('tournamentForm.createTitle')}
  </h1>

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
        <div class="form__field">
          <label for="matchFormat">{$_('tournamentForm.fields.matchFormat')}</label>
          <select id="matchFormat" bind:value={matchFormat} required>
            {#each rules.matchFormats as fmt}
              <option value={fmt}>{fmt}</option>
            {/each}
          </select>
          {#if fieldErrors['matchFormat']}
            <span class="form__field-error">{fieldErrors['matchFormat']}</span>
          {/if}
        </div>

        <!-- Scoring rule -->
        <div class="form__field">
          <label for="scoringRuleId">{$_('tournamentForm.fields.scoringRuleId')}</label>
          <select id="scoringRuleId" bind:value={scoringRuleId} required>
            {#each rules.scoringRuleIds as id}
              <option value={id}>{id}</option>
            {/each}
          </select>
          {#if fieldErrors['scoringRuleId']}
            <span class="form__field-error">{fieldErrors['scoringRuleId']}</span>
          {/if}
        </div>

        <!-- Set validation rule -->
        <div class="form__field">
          <label for="setValidationRuleId">{$_('tournamentForm.fields.setValidationRuleId')}</label>
          <select id="setValidationRuleId" bind:value={setValidationRuleId} required>
            {#each rules.setValidationRuleIds as id}
              <option value={id}>{id}</option>
            {/each}
          </select>
          {#if fieldErrors['setValidationRuleId']}
            <span class="form__field-error">{fieldErrors['setValidationRuleId']}</span>
          {/if}
        </div>

        <!-- Match generator -->
        <div class="form__field">
          <label for="matchGeneratorId">{$_('tournamentForm.fields.matchGeneratorId')}</label>
          <select id="matchGeneratorId" bind:value={matchGeneratorId} required>
            {#each rules.matchGeneratorIds as id}
              <option value={id}>{id}</option>
            {/each}
          </select>
          {#if fieldErrors['matchGeneratorId']}
            <span class="form__field-error">{fieldErrors['matchGeneratorId']}</span>
          {/if}
        </div>
      {/if}

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
