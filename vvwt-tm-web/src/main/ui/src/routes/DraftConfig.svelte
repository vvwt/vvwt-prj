<script lang="ts">
  /**
   * Draft configuration view — Story E05S06 (AC2–AC5); extended by E08S05, E47S01, E48S01, E48S16.
   *
   * E08S05 extensions:
   *   AC2 — break configuration per section (afterLapNumber, durationMinutes, label)
   *   AC3 — timeline preview (start/end times per lap per section when plannedStartTime is set)
   *   AC4 — start time is set in TournamentForm.svelte (not here)
   *   AC5 — break add/remove UI within each section card
   *   AC6 — time column in preview table when timeline data is present
   *   AC8 — client-side break position validation (afterLapNumber in [1, totalLaps-1])
   *   AC9 — duplicate break position detection per section
   *  AC10 — all visible strings sourced from svelte-i18n
   *  AC11 — tenant-scoped via auth (existing infra)
   *
   * E48S01 extensions:
   *  AC-FRONTEND-GAMEMODE-DROPDOWN — per-phase gameMode select (roundRobin / siegerehrung)
   *  AC-FRONTEND-PRE-SUBMIT-VALIDATION-MIRROR — validateLastPhase() guards handleApply()
   *
   * E48S16 extensions:
   *  AC-FRONTEND-FIRST-PHASE-DROPDOWN-LOCKED — first section (si===0) sortType select auto-set to
   *    team_number and disabled (AC-GOVERNANCE-NO-PHASE-ENTITY-CHANGES: sortType stays in draft_json)
   *  AC-FRONTEND-PRE-SUBMIT-VALIDATION-MIRROR — validateFirstPhase() guards handleApply() before
   *    validateLastPhase() — mirrors backend validateFirstPhaseTeamNumber()
   *
   * Props:
   *   params.tournamentId — the tournament UUID from the route
   */
  import { onMount, onDestroy } from 'svelte';
  import { pop } from 'svelte-spa-router';
  import { get } from 'svelte/store';
  import { _ } from 'svelte-i18n';
  import { pageHeader, resetPageHeader } from '../stores/pageHeaderStore.js';
  import { resolveParent } from '../lib/parentRouteMap.js';
  import {
    getDraft,
    saveDraft,
    previewDraft,
    applyDraft,
    type DraftSection,
    type DraftBreak,
    type DraftPreview,
  } from '../stores/draftStore.js';
  import { getTournament, resetPlan } from '../stores/tournamentStore.js';
  import { formatDuration } from '../lib/formatDuration.js';
  import { formatStartTime } from '../lib/formatStartTime.js';
  import {
    getGeneratorList,
    type MatchGeneratorInfo,
  } from '../stores/generatorStore.js';

  // ── Props ────────────────────────────────────────────────────────────────
  interface Props {
    params?: { tournamentId?: string };
  }
  let { params = {} }: Props = $props();
  const tournamentId = $derived(params.tournamentId ?? '');

  // ── State ────────────────────────────────────────────────────────────────
  let sections = $state<DraftSection[]>([]);
  let loading = $state(true);
  let saving = $state(false);
  let previewing = $state(false);
  let applying = $state(false);
  let loadError = $state<string | null>(null);
  let saveError = $state<string | null>(null);
  let previewError = $state<string | null>(null);
  let applyError = $state<string | null>(null);
  let applySuccess = $state(false);
  let preview = $state<DraftPreview | null>(null);
  let plannedStartTime = $state<string | null>(null);
  // Derived preview aggregates (E48S12 — moved from {#if} block to script for Svelte 5 @const compatibility)
  let totalMatches = $derived(preview ? preview.sections.reduce((s, p) => s + p.totalMatches, 0) : 0);
  let totalMinutes = $derived(preview ? preview.sections.reduce((s, p) => s + p.estimatedTimeMinutes, 0) : 0);
  let showStartTime = $derived(plannedStartTime != null && formatStartTime(plannedStartTime, 0) !== '');
  /** Tournament status (E48S13): controls reset-plan button visibility (PLANNED only). */
  let tournamentStatus = $state<string | null>(null);
  let resetPlanError = $state<string | null>(null);
  let resettingPlan = $state(false);
  /** Break validation errors: key = `${sectionIdx}-${breakIdx}`, value = error message. */
  let breakErrors = $state<Record<string, string>>({});
  /**
   * Registry-sourced generator list. Loaded once from /api/match-generators via generatorStore.
   * E58S05 AC3: replaces hardcoded roundRobin/awardCeremony option list.
   */
  let generators = $state<MatchGeneratorInfo[]>([]);

  // ── Init ─────────────────────────────────────────────────────────────────
  onMount(async () => {
    // E47S01 AC3/AC5/AC6/AC12: register title, back-arrow, and tournament context in persistent header
    pageHeader.set({
      title: get(_)('draft.title'),
      backTo: resolveParent('/tournaments/:tournamentId/draft', tournamentId),
      tournamentId: tournamentId || null,
      actions: [],
    });
    if (!tournamentId) {
      loadError = $_('draft.error.noTournament');
      loading = false;
      return;
    }
    try {
      const [config, tournament, gens] = await Promise.all([
        getDraft(tournamentId),
        getTournament(tournamentId),
        // E58S05 AC3: load generator list from registry (shared module, fetched once)
        getGeneratorList(),
      ]);
      sections = config.sections.map(s => ({
        ...s,
        breaks: s.breaks ?? [],
      }));
      plannedStartTime = tournament.plannedStartTime ?? null;
      tournamentStatus = tournament.status ?? null;
      generators = gens;
    } catch (e: unknown) {
      loadError = e instanceof Error ? e.message : String(e);
    } finally {
      loading = false;
    }
  });

  onDestroy(() => {
    resetPageHeader();
  });

  // ── Section management ───────────────────────────────────────────────────

  function addSection(): void {
    const next = sections.length + 1;
    sections = [
      ...sections,
      {
        sectionNumber: next,
        sortType: 'team_number',
        groupCount: 1,
        gameMode: 'roundRobin',
        lapBreakTimeMinutes: 5,
        sectionBreakTimeMinutes: 15,
        lapTimeMinutes: 15,
        setQuantity: 1,
        // E51S15 (AC-TEST-UI-DROPDOWN-DEFAULT-SEQUENTIAL-RED): new sections default to 'sequential'
        distributionMode: 'sequential',
        breaks: [],
      },
    ];
    // Auto-set last section to siegerehrung (AC-FRONTEND-GAMEMODE-DROPDOWN, E48S01)
    enforceLastSectionAwardCeremony();
  }

  function removeSection(idx: number): void {
    sections = sections
      .filter((_, i) => i !== idx)
      .map((s, i) => ({ ...s, sectionNumber: i + 1 }));
    // Auto-set last section to siegerehrung after removal (AC-FRONTEND-GAMEMODE-DROPDOWN)
    enforceLastSectionAwardCeremony();
    // Clear break errors for removed section
    const newErrors: Record<string, string> = {};
    for (const [key, val] of Object.entries(breakErrors)) {
      if (!key.startsWith(`${idx}-`)) {
        newErrors[key] = val;
      }
    }
    breakErrors = newErrors;
  }

  // ── gameMode helpers (E48S01) ────────────────────────────────────────────

  /**
   * Returns the key of the last-phase generator from the registry
   * (the first generator with isLastPhaseGenerator==true).
   * Falls back to 'awardCeremony' if the registry is not yet loaded.
   * E58S05 AC4: uses the capability flag, NOT string key matching.
   */
  function getLastPhaseGeneratorKey(): string {
    const lastPhaseGen = generators.find(g => g.isLastPhaseGenerator);
    return lastPhaseGen?.keyId ?? 'awardCeremony';
  }

  /**
   * Ensures the last section always has a last-phase-capable gameMode.
   * Called after addSection() and removeSection() to maintain the invariant in the UI.
   * E58S05 AC4: uses isLastPhaseGenerator flag, not key-string matching.
   * AC-FRONTEND-GAMEMODE-DROPDOWN: last-section auto-set on every structural change.
   */
  function enforceLastSectionAwardCeremony(): void {
    if (sections.length === 0) return;
    const lastIdx = sections.length - 1;
    const lastPhaseKey = getLastPhaseGeneratorKey();
    if (sections[lastIdx].gameMode !== lastPhaseKey) {
      sections = sections.map((s, i) =>
        i === lastIdx ? { ...s, gameMode: lastPhaseKey } : s
      );
    }
  }

  /**
   * Pre-submit validation: verifies the first section (lowest sectionNumber) has
   * sortType='team_number'. Returns an i18n error string if invalid, or null if valid.
   * AC-FRONTEND-PRE-SUBMIT-VALIDATION-MIRROR (E48S16) — mirrors backend
   * DraftConfig.validateFirstPhaseTeamNumber().
   */
  function validateFirstPhase(): string | null {
    if (sections.length === 0) return null;
    const firstSection = sections.reduce((min, s) =>
      s.sectionNumber < min.sectionNumber ? s : min
    );
    if (firstSection.sortType !== 'team_number') {
      return $_('draftConfig.errors.firstPhaseMustBeTeamNumber');
    }
    return null;
  }

  /**
   * Pre-submit validation: verifies the last section has a last-phase-capable gameMode.
   * Returns an i18n error key string if invalid, or null if valid.
   * AC-FRONTEND-PRE-SUBMIT-VALIDATION-MIRROR (E48S01); updated by E58S05 to use registry flag.
   */
  function validateLastPhase(): string | null {
    if (sections.length === 0) return null;
    const lastSection = sections.reduce((max, s) =>
      s.sectionNumber > max.sectionNumber ? s : max
    );
    // E58S05 AC4: check via registry flag — if generators loaded, verify the selected key is
    // a last-phase generator; if not yet loaded, fall back to the known key
    const lastPhaseKey = getLastPhaseGeneratorKey();
    if (lastSection.gameMode !== lastPhaseKey) {
      return $_('draftConfig.errors.lastPhaseMustBeAwardCeremony');
    }
    return null;
  }

  // ── Break management ─────────────────────────────────────────────────────

  function addBreak(sectionIdx: number): void {
    const s = sections[sectionIdx];
    const newBreak: DraftBreak = { afterLapNumber: 1, durationMinutes: 15, label: null };
    sections = sections.map((sec, i) =>
      i === sectionIdx ? { ...sec, breaks: [...sec.breaks, newBreak] } : sec
    );
  }

  function removeBreak(sectionIdx: number, breakIdx: number): void {
    sections = sections.map((sec, i) =>
      i === sectionIdx
        ? { ...sec, breaks: sec.breaks.filter((_, bi) => bi !== breakIdx) }
        : sec
    );
    // Remove validation error for this break
    const key = `${sectionIdx}-${breakIdx}`;
    const newErrors = { ...breakErrors };
    delete newErrors[key];
    breakErrors = newErrors;
  }

  function updateBreak(sectionIdx: number, breakIdx: number, patch: Partial<DraftBreak>): void {
    sections = sections.map((sec, i) =>
      i === sectionIdx
        ? {
            ...sec,
            breaks: sec.breaks.map((b, bi) =>
              bi === breakIdx ? { ...b, ...patch } : b
            ),
          }
        : sec
    );
    // Clear validation error on edit
    const key = `${sectionIdx}-${breakIdx}`;
    if (breakErrors[key]) {
      const newErrors = { ...breakErrors };
      delete newErrors[key];
      breakErrors = newErrors;
    }
  }

  // ── Client-side break validation (AC8, AC9) ──────────────────────────────

  /**
   * Validates all break positions across all sections.
   * Returns true if all are valid, false otherwise.
   * Sets breakErrors for each invalid break.
   */
  function validateBreaks(): boolean {
    const errors: Record<string, string> = {};
    let valid = true;

    for (let si = 0; si < sections.length; si++) {
      const s = sections[si];
      // Estimate total laps: we don't know team count here, so we validate only
      // that afterLapNumber >= 1 and durationMinutes > 0.
      // Server-side validates against actual lap count (AC8).
      const seenPositions = new Set<number>();

      for (let bi = 0; bi < s.breaks.length; bi++) {
        const b = s.breaks[bi];
        const key = `${si}-${bi}`;

        if (b.afterLapNumber < 1) {
          errors[key] = $_('draft.break.error.afterLapMin');
          valid = false;
        } else if (b.durationMinutes < 1) {
          errors[key] = $_('draft.break.error.durationMin');
          valid = false;
        } else if (seenPositions.has(b.afterLapNumber)) {
          // AC9: duplicate position within the same section
          errors[key] = $_('draft.break.error.duplicate');
          valid = false;
        } else {
          seenPositions.add(b.afterLapNumber);
        }
      }
    }

    breakErrors = errors;
    return valid;
  }

  // ── Save ─────────────────────────────────────────────────────────────────

  async function handleSave(): Promise<void> {
    if (!validateBreaks()) return;
    saveError = null;
    saving = true;
    try {
      await saveDraft(tournamentId, { sections });
    } catch (e: unknown) {
      saveError = e instanceof Error ? e.message : $_('draft.error.saveFailed');
    } finally {
      saving = false;
    }
  }

  // ── Preview ───────────────────────────────────────────────────────────────

  async function handlePreview(): Promise<void> {
    if (!validateBreaks()) return;
    previewError = null;
    preview = null;
    previewing = true;
    try {
      preview = await previewDraft(tournamentId, { sections });
    } catch (e: unknown) {
      previewError = e instanceof Error ? e.message : $_('draft.error.previewFailed');
    } finally {
      previewing = false;
    }
  }

  // ── Apply ─────────────────────────────────────────────────────────────────

  async function handleApply(): Promise<void> {
    // AC-FRONTEND-PRE-SUBMIT-VALIDATION-MIRROR (E48S16): first-phase sortType=team_number
    const firstPhaseError = validateFirstPhase();
    if (firstPhaseError !== null) {
      applyError = firstPhaseError;
      return;
    }
    // AC-FRONTEND-PRE-SUBMIT-VALIDATION-MIRROR (E48S01): validate before backend call
    const lastPhaseError = validateLastPhase();
    if (lastPhaseError !== null) {
      applyError = lastPhaseError;
      return;
    }
    if (!confirm($_('draft.applyConfirm'))) return;
    applyError = null;
    applying = true;
    try {
      await applyDraft(tournamentId, { sections });
      applySuccess = true;
    } catch (e: unknown) {
      applyError = e instanceof Error ? e.message : $_('draft.error.applyFailed');
    } finally {
      applying = false;
    }
  }

  // ── Reset-Plan (E48S13 AC-IMPL-FE-RESET-PLAN-BUTTON) ─────────────────────

  /**
   * Resets the Phasenplan back to DRAFT for a PLANNED tournament.
   * Dialog fires on click; on success: re-fetches tournament + draft, renders DRAFT-edit mode.
   * On 409: shows typed messageKey via i18n.
   */
  async function handleResetPlan(): Promise<void> {
    if (!confirm($_('draft.resetPlanConfirm'))) return;
    resetPlanError = null;
    resettingPlan = true;
    try {
      const updated = await resetPlan(tournamentId);
      tournamentStatus = updated.status;
      // Re-fetch draft to re-populate form with preserved draftJson
      const config = await getDraft(tournamentId);
      sections = config.sections.map(s => ({ ...s, breaks: s.breaks ?? [] }));
      applySuccess = false;
    } catch (e: unknown) {
      const apiErr = e && typeof e === 'object' && 'apiError' in e
        ? (e as { apiError: { messageKey?: string } }).apiError
        : null;
      const msgKey = apiErr?.messageKey;
      resetPlanError = msgKey
        ? $_(`${msgKey}`, { default: e instanceof Error ? e.message : $_('draft.error.resetPlanFailed') })
        : (e instanceof Error ? e.message : $_('draft.error.resetPlanFailed'));
    } finally {
      resettingPlan = false;
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  function formatTime(t: string): string {
    // LocalTime serializes as HH:mm:ss — display as HH:mm
    return t ? t.substring(0, 5) : '';
  }
</script>

<main class="draft-config">

  {#if loading}
    <p class="draft-config__loading">…</p>
  {:else if loadError}
    <p class="draft-config__error">{loadError}</p>
  {:else if applySuccess}
    <div class="draft-config__success">
      <p>{$_('draft.applySuccess')}</p>
      <button class="btn btn--primary" onclick={() => pop()}>
        {$_('draft.backButton')}
      </button>
    </div>
  {:else}
    <!-- Section list -->
    {#each sections as section, si (section.sectionNumber)}
      <div class="section-card">
        <div class="section-card__header">
          <h2>{$_('draft.section.title', { values: { number: section.sectionNumber } })}</h2>
          {#if sections.length > 1}
            <button class="btn btn--danger btn--sm" onclick={() => removeSection(si)}>
              {$_('draft.section.removeButton')}
            </button>
          {/if}
        </div>

        <div class="section-card__fields">
          <!-- Sort type (E48S16 AC-FRONTEND-FIRST-PHASE-DROPDOWN-LOCKED: first section disabled) -->
          <div class="form__field">
            <label>{$_('draft.section.fields.sortType')}</label>
            <select
              bind:value={section.sortType}
              disabled={si === 0}
              title={si === 0 ? $_('draftConfig.errors.firstPhaseMustBeTeamNumber') : undefined}
            >
              <option value="team_number">{$_('draft.sortType.team_number')}</option>
              <option value="placement_group">{$_('draft.sortType.placement_group')}</option>
              <option value="group_placement">{$_('draft.sortType.group_placement')}</option>
            </select>
          </div>

          <!-- Game mode (E48S01 AC-FRONTEND-GAMEMODE-DROPDOWN; E58S05 AC3/AC4 data-driven) -->
          <div class="form__field">
            <label>{$_('draft.section.fields.gameMode')}</label>
            <select
              bind:value={section.gameMode}
              disabled={si === sections.length - 1}
              title={si === sections.length - 1 ? $_('draftConfig.errors.lastPhaseMustBeAwardCeremony') : undefined}
            >
              {#each (si === sections.length - 1
                ? generators.filter(g => g.isLastPhaseGenerator)
                : generators.filter(g => !g.isLastPhaseGenerator)) as gen (gen.keyId)}
                <option value={gen.keyId}>{$_(`draftConfig.gameMode.${gen.keyId}`)}</option>
              {/each}
            </select>
          </div>

          <!-- Distribution mode (E51S15 AC-TEST-UI-DROPDOWN-PER-SECTION-RED) -->
          <div class="form__field">
            <label>{$_('draftConfig.distributionMode.label')}</label>
            <select bind:value={section.distributionMode}>
              <option value="sequential">{$_('draftConfig.distributionMode.sequential')}</option>
              <option value="round_robin">{$_('draftConfig.distributionMode.round_robin')}</option>
            </select>
          </div>

          <!-- Group count -->
          <div class="form__field">
            <label>{$_('draft.section.fields.groupCount')}</label>
            <input type="number" min="1" bind:value={section.groupCount} />
          </div>

          <!-- Lap time (E48S09 AC-IMPL-FRONTEND-RUNDENZEIT-DISABLED + AC-IMPL-FRONTEND-RUNDENZEIT-TOOLTIP) -->
          <div class="form__field">
            <label>{$_('draft.section.fields.lapTimeMinutes')}</label>
            <input
              type="number"
              min="1"
              bind:value={section.lapTimeMinutes}
              disabled={section.gameMode === 'awardCeremony'}
              title={section.gameMode === 'awardCeremony' ? $_('draftConfig.rundenzeit.disabledTooltip') : undefined}
            />
          </div>

          <!-- Lap break -->
          <div class="form__field">
            <label>{$_('draft.section.fields.lapBreakTimeMinutes')}</label>
            <input type="number" min="0" bind:value={section.lapBreakTimeMinutes} />
          </div>

          <!-- Section break -->
          <div class="form__field">
            <label>{$_('draft.section.fields.sectionBreakTimeMinutes')}</label>
            <input type="number" min="0" bind:value={section.sectionBreakTimeMinutes} />
          </div>

          <!-- Set quantity -->
          <div class="form__field">
            <label>{$_('draft.section.fields.setQuantity')}</label>
            <input type="number" min="1" bind:value={section.setQuantity} />
          </div>
        </div>

        <!-- Intra-phase breaks (E08S05 AC5) -->
        <div class="section-card__breaks">
          <h3>{$_('draft.break.title')}</h3>
          {#if section.breaks.length === 0}
            <p class="section-card__breaks-empty">{$_('draft.break.empty')}</p>
          {:else}
            {#each section.breaks as brk, bi (bi)}
              <div class="break-row">
                <div class="form__field form__field--inline">
                  <label>{$_('draft.break.fields.afterLapNumber')}</label>
                  <input
                    type="number"
                    min="1"
                    class:input--error={!!breakErrors[`${si}-${bi}`]}
                    bind:value={brk.afterLapNumber}
                    oninput={() => updateBreak(si, bi, { afterLapNumber: brk.afterLapNumber })}
                  />
                </div>
                <div class="form__field form__field--inline">
                  <label>{$_('draft.break.fields.durationMinutes')}</label>
                  <input
                    type="number"
                    min="1"
                    class:input--error={!!breakErrors[`${si}-${bi}`]}
                    bind:value={brk.durationMinutes}
                    oninput={() => updateBreak(si, bi, { durationMinutes: brk.durationMinutes })}
                  />
                </div>
                <div class="form__field form__field--inline">
                  <label>{$_('draft.break.fields.label')}</label>
                  <input
                    type="text"
                    placeholder={$_('draft.break.fields.labelPlaceholder')}
                    value={brk.label ?? ''}
                    oninput={(e) => updateBreak(si, bi, { label: (e.target as HTMLInputElement).value || null })}
                  />
                </div>
                <button class="btn btn--danger btn--sm" onclick={() => removeBreak(si, bi)}>
                  {$_('draft.break.removeButton')}
                </button>
                {#if breakErrors[`${si}-${bi}`]}
                  <span class="form__field-error">{breakErrors[`${si}-${bi}`]}</span>
                {/if}
              </div>
            {/each}
          {/if}
          <button class="btn btn--secondary btn--sm" onclick={() => addBreak(si)}>
            {$_('draft.break.addButton')}
          </button>
        </div>
      </div>
    {/each}

    <!-- Add section -->
    <button class="btn btn--secondary" onclick={addSection}>
      {$_('draft.addSectionButton')}
    </button>

    <!-- Actions -->
    <div class="draft-config__actions">
      {#if saveError}
        <p class="draft-config__error">{saveError}</p>
      {/if}
      <button class="btn btn--primary" onclick={handleSave} disabled={saving}>
        {saving ? $_('draft.savingButton') : $_('draft.saveButton')}
      </button>
      <button class="btn btn--secondary" onclick={handlePreview} disabled={previewing || sections.length === 0}>
        {previewing ? $_('draft.previewingButton') : $_('draft.previewButton')}
      </button>
      {#if preview}
        <button class="btn btn--primary" onclick={handleApply} disabled={applying}>
          {applying ? $_('draft.applyingButton') : $_('draft.applyButton')}
        </button>
      {/if}
      {#if applyError}
        <p class="draft-config__error">{applyError}</p>
      {/if}
      <!-- E48S13 AC-IMPL-FE-RESET-PLAN-BUTTON: visible only for PLANNED tournaments -->
      {#if tournamentStatus === 'PLANNED'}
        <button class="btn btn--danger" onclick={handleResetPlan} disabled={resettingPlan}>
          {$_('draft.resetPlanButton')}
        </button>
        {#if resetPlanError}
          <p class="draft-config__error">{resetPlanError}</p>
        {/if}
      {/if}
    </div>

    <!-- Preview table (E08S05 AC3, AC6) -->
    {#if previewError}
      <p class="draft-config__error">{previewError}</p>
    {/if}
    {#if preview}
      <div class="preview-section">
        <h2>{$_('draft.preview.title')}</h2>

        <!-- Structural preview -->
        <table class="preview-table">
          <thead>
            <tr>
              <th>{$_('draft.preview.columns.phase')}</th>
              <th>{$_('draft.preview.columns.groups')}</th>
              <th>{$_('draft.preview.columns.teamsPerGroup')}</th>
              <th>{$_('draft.preview.columns.matchesPerGroup')}</th>
              <th>{$_('draft.preview.columns.totalLaps')}</th>
              <th>{$_('draft.preview.columns.totalMatches')}</th>
              <th>{$_('draft.preview.columns.estimatedTime')}</th>
              {#if showStartTime}
                <th>{$_('draftConfig.preview.columns.estimatedStart')}</th>
              {/if}
            </tr>
          </thead>
          <tbody>
            {#each preview.sections as ps, idx (ps.phaseNumber)}
              {@const offsetBefore = preview.sections.slice(0, idx).reduce((s, p) => s + p.estimatedTimeMinutes, 0)}
              <tr>
                <td>{ps.phaseNumber}</td>
                <td>{ps.groupCount}</td>
                <td>{ps.teamsPerGroup}</td>
                <td>{ps.matchesPerGroup}</td>
                <td>{ps.totalLaps}</td>
                <td>{ps.totalMatches}</td>
                <td>{formatDuration(ps.estimatedTimeMinutes)}</td>
                {#if showStartTime}
                  <td>{formatStartTime(plannedStartTime!, offsetBefore)}</td>
                {/if}
              </tr>
            {/each}
          </tbody>
          <tfoot>
            <tr class="preview-table__sum-row">
              <td>{$_('draftConfig.preview.sumLabel')}</td>
              <td></td>
              <td></td>
              <td></td>
              <td></td>
              <td>{totalMatches}</td>
              <td>{formatDuration(totalMinutes)}</td>
              {#if showStartTime}
                <td>{formatStartTime(plannedStartTime!, totalMinutes)}</td>
              {/if}
            </tr>
          </tfoot>
        </table>

        <!-- Timeline table (only when plannedStartTime is set — AC6) -->
        {#if preview.timeline && preview.timeline.length > 0}
          <h3>{$_('draft.preview.timelineTitle')}</h3>
          <table class="preview-table preview-table--timeline">
            <thead>
              <tr>
                <th>{$_('draft.preview.timeline.phase')}</th>
                <th>{$_('draft.preview.timeline.lap')}</th>
                <th>{$_('draft.preview.timeline.type')}</th>
                <th>{$_('draft.preview.timeline.time')}</th>
                <th>{$_('draft.preview.timeline.label')}</th>
              </tr>
            </thead>
            <tbody>
              {#each preview.timeline as entry, idx (idx)}
                <tr class:timeline-row--break={entry.type !== 'MATCH_ROUND'}>
                  <td>{entry.phaseNumber}</td>
                  <td>{entry.lapNumber > 0 ? entry.lapNumber : '—'}</td>
                  <td>{$_(`draft.preview.entryType.${entry.type}`, { default: entry.type })}</td>
                  <td>{formatTime(entry.startTime)}–{formatTime(entry.endTime)}</td>
                  <td>{entry.label ?? ''}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        {/if}
      </div>
    {/if}
  {/if}
</main>

<style>
  .draft-config {
    padding: 2rem;
    font-family: sans-serif;
    max-width: 900px;
  }

  .draft-config__actions {
    margin-top: 1.5rem;
    display: flex;
    gap: 0.75rem;
    align-items: center;
    flex-wrap: wrap;
  }

  .draft-config__error {
    color: #c0392b;
    margin-bottom: 0.5rem;
  }

  .draft-config__loading {
    color: #666;
  }

  .draft-config__success {
    color: #27ae60;
  }

  .section-card {
    border: 1px solid #ddd;
    border-radius: 6px;
    padding: 1.25rem;
    margin-bottom: 1rem;
    background: #fafafa;
  }

  .section-card__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 1rem;
  }

  .section-card__header h2 {
    margin: 0;
    font-size: 1rem;
  }

  .section-card__fields {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 0.75rem;
    margin-bottom: 1rem;
  }

  .section-card__breaks {
    border-top: 1px solid #eee;
    padding-top: 1rem;
    margin-top: 0.5rem;
  }

  .section-card__breaks h3 {
    margin: 0 0 0.75rem 0;
    font-size: 0.9rem;
    font-weight: 600;
  }

  .section-card__breaks-empty {
    color: #999;
    font-size: 0.85rem;
    margin-bottom: 0.5rem;
  }

  .break-row {
    display: flex;
    align-items: flex-end;
    gap: 0.5rem;
    margin-bottom: 0.5rem;
    flex-wrap: wrap;
  }

  .form__field {
    display: flex;
    flex-direction: column;
    gap: 0.2rem;
  }

  .form__field--inline {
    min-width: 120px;
  }

  .form__field label {
    font-weight: 600;
    font-size: 0.85rem;
  }

  .form__field input,
  .form__field select {
    padding: 0.35rem 0.5rem;
    border: 1px solid #bdc3c7;
    border-radius: 4px;
    font-size: 0.9rem;
  }

  .form__field-error {
    color: #c0392b;
    font-size: 0.8rem;
    align-self: center;
  }

  .input--error {
    border-color: #e74c3c !important;
  }

  .preview-section {
    margin-top: 2rem;
  }

  .preview-section h2,
  .preview-section h3 {
    margin-bottom: 0.75rem;
  }

  .preview-table {
    width: 100%;
    border-collapse: collapse;
    margin-bottom: 1.5rem;
    font-size: 0.9rem;
  }

  .preview-table th,
  .preview-table td {
    text-align: left;
    padding: 0.4rem 0.6rem;
    border-bottom: 1px solid #e0e0e0;
  }

  .preview-table th {
    font-weight: 600;
    background: #f5f5f5;
  }

  .timeline-row--break td {
    color: #7f8c8d;
    font-style: italic;
  }

  .preview-table__sum-row td {
    font-weight: bold;
    border-top: 2px solid #ccc;
  }

  .btn {
    cursor: pointer;
    border: none;
    border-radius: 4px;
    padding: 0.5rem 1rem;
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

  .btn--danger {
    background: #e74c3c;
    color: #fff;
  }

  .btn--sm {
    padding: 0.25rem 0.6rem;
    font-size: 0.8rem;
  }
</style>
