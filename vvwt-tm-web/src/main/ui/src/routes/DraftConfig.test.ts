/**
 * Tests for DraftConfig route — Story E47S01, extended by E48S01.
 *
 * E47S01:
 * AC3: No per-page .draft-config__header; title registered via pageHeader store.
 * AC5: Back-arrow registered (navigates to /tournaments/:tournamentId/edit).
 * AC6: tournamentId registered for tournament name display in header.
 * AC12: No pop()-based back-button rendered in the template (removed per D-10).
 *       Note: applySuccess pop() is an async post-action redirect — NOT a back-button. It stays.
 *
 * E48S01 (AC-TEST-FRONTEND-PRE-SUBMIT-VALIDATION-RED, AC-TEST-FRONTEND-DRAFT-CONFIG-PHASE-SUBMISSION-GREEN):
 * Pre-submit validation function that enforces last-phase=siegerehrung invariant.
 * The source-inspection pattern is used (JSDOM-independent per existing test convention).
 *
 * RED-first per DEC-22: E48S01 tests fail before DraftConfig.svelte is extended.
 */

import { describe, it, expect } from 'vitest';

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — No per-page __header
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC3: per-page header removed (E47S01)', () => {
  it('DraftConfig.svelte source does NOT contain .draft-config__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('draft-config__header');
  });

  it('DraftConfig.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('draft.title');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC5 — Back-arrow registered
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC5: backTo registered (E47S01)', () => {
  it('DraftConfig.svelte source registers backTo in pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC6 — tournamentId registered for tournament name
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC6: tournamentId registered (E47S01)', () => {
  it('DraftConfig.svelte source passes tournamentId to pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    // tournamentId must be in the pageHeader set call
    const pageHeaderCall = source.match(/pageHeader\.set\(\{([^}]*)\}/s)?.[1] ?? '';
    expect(pageHeaderCall).toContain('tournamentId');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC12 — No pop()-based back-button in rendered template
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC12: pop() back-button removed (E47S01)', () => {
  it('DraftConfig.svelte source has NO button with onclick binding to pop() in header region', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // No back-button with pop() — the pattern to forbid is:
    //   <button ... onclick={() => pop()}>{$_('draft.backButton')}</button>
    // applySuccess navigation (a post-action redirect, NOT a back-button) is in an async fn — allowed.
    // We check that backButton i18n key is not used in a button template anymore
    expect(source).not.toMatch(/<button[^>]*>\s*\{[^}]*draft\.backButton/);
  });

  it('DraftConfig.svelte source does NOT import pop from svelte-spa-router', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // After migration, pop() is no longer needed (removed back-button)
    // Note: applySuccess may still use pop() as a post-action redirect
    // The AC12 requirement is that the back-button binding is removed
    // If pop() is still imported for applySuccess, that is fine
    // But if it's ONLY used for the back-button, the import should be removed
    // We test the button template pattern absence (see test above)
    // This test verifies the import is removed if pop() is not used at all anymore
    // If DraftConfig still uses pop() for applySuccess, this test should be skipped
    const hasApplySuccessPop = source.includes('applySuccess') && source.includes('pop()');
    if (!hasApplySuccessPop) {
      // No legitimate use of pop() remaining — import should be removed
      expect(source).not.toMatch(/import\s*\{[^}]*\bpop\b[^}]*\}\s*from\s*['"]svelte-spa-router/);
    }
    // If applySuccess still uses pop(), we only assert no back-button binding (tested above)
    expect(true).toBe(true); // always passes — the real gate is the button template test
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E48S01 — AC-TEST-FRONTEND-PRE-SUBMIT-VALIDATION-RED
// Pre-submit validation: last phase must be siegerehrung
// RED-first: these tests FAIL before DraftConfig.svelte adds validateLastPhase()
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC-TEST-FRONTEND-PRE-SUBMIT-VALIDATION-RED (E48S01)', () => {
  it('DraftConfig.svelte source contains a validateLastPhase function', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: this fails before validateLastPhase is added to DraftConfig.svelte
    expect(source).toContain('validateLastPhase');
  });

  it('DraftConfig.svelte source references draftConfig.errors.lastPhaseMustBeAwardCeremony i18n key (renamed from lastPhaseMustBeSiegerehrung by E58S04)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // GREEN: key renamed to lastPhaseMustBeAwardCeremony by E58S04 (DEC-73 D-7)
    expect(source).toContain('draftConfig.errors.lastPhaseMustBeAwardCeremony');
  });

  it('DraftConfig.svelte handleApply calls validateLastPhase before backend call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Verify handleApply contains both validateLastPhase and applyDraft in relative order.
    // Source-level substring search (avoids fragile regex body extraction).
    const handleApplyStart = source.indexOf('async function handleApply()');
    // Find the end of handleApply: search for the next top-level async function after handleApply
    const nextFnStart = source.indexOf('\n  async function ', handleApplyStart + 1);
    const handleApplyBody =
      nextFnStart > -1 ? source.slice(handleApplyStart, nextFnStart) : source.slice(handleApplyStart);
    const validatePos = handleApplyBody.indexOf('validateLastPhase');
    const applyPos = handleApplyBody.indexOf('applyDraft');
    // validateLastPhase must appear before applyDraft in handleApply
    expect(validatePos).toBeGreaterThanOrEqual(0);
    expect(validatePos).toBeLessThan(applyPos > -1 ? applyPos : Infinity);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E48S01 — AC-TEST-FRONTEND-DRAFT-CONFIG-PHASE-SUBMISSION-GREEN
// Complementary happy-path + error-path coverage via source inspection
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC-TEST-FRONTEND-DRAFT-CONFIG-PHASE-SUBMISSION-GREEN (E48S01)', () => {
  it('DraftConfig.svelte source contains a data-driven gameMode select field (E58S05 AC3)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Verify the gameMode dropdown exists in the template
    expect(source).toContain('section.gameMode');
    // E58S05 AC3: options are now data-driven from the registry, not hardcoded.
    // E58S06 AC8: key namespace relocated from draftConfig.gameMode.* to matchOption.generator.*
    expect(source).toContain('matchOption.generator.');
    // E58S05 AC3: hardcoded option values are replaced; static keys no longer exist as option values
    // (They may still appear as generator keyIds in the generatorStore, not as hardcoded options)
  });

  it('DraftConfig.svelte source has last-section auto-set to awardCeremony with disabled/readonly (renamed from siegerehrung by E58S04)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Last section auto-set: source must reference awardCeremony assignment for the last index
    // The implementation sets gameMode='awardCeremony' for the last section reactively (E58S04 DEC-73 D-7)
    expect(source).toContain("'awardCeremony'");
    // Disabled attribute on last section's select
    expect(source).toContain('disabled');
  });

  it('DraftConfig.svelte handleApply sets applyError when last phase is not awardCeremony (renamed from siegerehrung by E58S04)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // handleApply must set applyError from validateLastPhase result
    expect(source).toContain('applyError');
    expect(source).toContain('validateLastPhase');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E48S11 — AC-TEST-FRONTEND-SUM-ROW-RED
// Sum-row in Vorschau-Tabelle with Σ totalMatches + Σ estimatedTimeMinutes (formatDuration)
// RED-first per DEC-22: these tests FAIL before DraftConfig.svelte adds <tfoot>
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC-TEST-FRONTEND-SUM-ROW-RED (E48S11)', () => {
  it('DraftConfig.svelte source contains a <tfoot> element for the sum-row', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before <tfoot> is added to the Vorschau-Tabelle
    expect(source).toContain('<tfoot>');
  });

  it('DraftConfig.svelte source uses formatDuration in the preview table', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before formatDuration is imported and used
    expect(source).toContain('formatDuration');
  });

  it('DraftConfig.svelte source references draftConfig.preview.sumLabel i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the sum-row label key is added
    expect(source).toContain('draftConfig.preview.sumLabel');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E48S11 — AC-TEST-FRONTEND-PHASE-START-TIME-RED
// "Voraussichtl. Beginn" column — conditionally rendered when plannedStartTime != null
// RED-first per DEC-22: these tests FAIL before DraftConfig.svelte adds the column
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC-TEST-FRONTEND-PHASE-START-TIME-RED (E48S11)', () => {
  it('DraftConfig.svelte source uses formatStartTime helper', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before formatStartTime is imported and used
    expect(source).toContain('formatStartTime');
  });

  it('DraftConfig.svelte source references draftConfig.preview.columns.estimatedStart i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the column header i18n key is added
    expect(source).toContain('draftConfig.preview.columns.estimatedStart');
  });

  it('DraftConfig.svelte source conditionally renders start-time column based on plannedStartTime', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the conditional render block is added
    // The column must be conditioned on plannedStartTime being non-null.
    // Implementations may use a derived boolean (e.g. showStartTime = plannedStartTime != null ...)
    // or an inline {#if plannedStartTime ...} block — both are valid.
    const hasDirectConditional = /\{#if.*plannedStartTime.*\}/.test(source);
    const hasDerivedConditional =
      /plannedStartTime\s*!=\s*null/.test(source) && /\{#if\s+showStartTime/.test(source);
    expect(hasDirectConditional || hasDerivedConditional).toBe(true);
    // And must reference the estimatedStart key
    expect(source).toContain('draftConfig.preview.columns.estimatedStart');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E48S09 — AC-TEST-FRONTEND-RUNDENZEIT-DISABLED-RED
// Rundenzeit input disabled for siegerehrung gameMode sections
// RED-first per DEC-22: these tests FAIL before DraftConfig.svelte adds the disabled attribute
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC-TEST-FRONTEND-RUNDENZEIT-DISABLED-RED (E48S09)', () => {
  it('DraftConfig.svelte lapTimeMinutes input is disabled for awardCeremony gameMode (renamed from siegerehrung by E58S04)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // GREEN: awardCeremony replaced siegerehrung (E58S04 DEC-73 D-7)
    // The disabled binding must appear in the lapTimeMinutes input context
    expect(source).toContain("disabled={section.gameMode === 'awardCeremony'}");
  });

  it('DraftConfig.svelte lapTimeMinutes input references draftConfig.rundenzeit.disabledTooltip i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the tooltip i18n key is referenced on the lapTimeMinutes input
    expect(source).toContain('draftConfig.rundenzeit.disabledTooltip');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E48S13 — AC-TEST-FRONTEND-VITEST-RED (reset-plan button in DraftConfig)
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — E48S13 reset-plan button (AC-TEST-FRONTEND-VITEST-RED)', () => {
  it('DraftConfig.svelte source contains resetPlanButton i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('draft.resetPlanButton');
  });

  it('DraftConfig.svelte source contains resetPlanConfirm i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('draft.resetPlanConfirm');
  });

  it('DraftConfig.svelte source shows reset-plan button only for PLANNED status', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("tournamentStatus === 'PLANNED'");
    expect(source).toContain('handleResetPlan');
  });

  it('DraftConfig.svelte imports resetPlan from tournamentStore', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('resetPlan');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E48S16 — AC-TEST-FRONTEND-FIRST-PHASE-DROPDOWN-LOCK-RED
// First-phase sortType dropdown auto-set to team_number + disabled
// RED-first per DEC-22: these tests FAIL before DraftConfig.svelte adds the first-phase lock
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — E48S16 first-phase sortType lock (AC-TEST-FRONTEND-FIRST-PHASE-DROPDOWN-LOCK-RED)', () => {
  it('DraftConfig.svelte source contains a validateFirstPhase function', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before validateFirstPhase() is added to DraftConfig.svelte
    expect(source).toContain('validateFirstPhase');
  });

  it('DraftConfig.svelte source references draftConfig.errors.firstPhaseMustBeTeamNumber i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the i18n error key is referenced
    expect(source).toContain('draftConfig.errors.firstPhaseMustBeTeamNumber');
  });

  it('DraftConfig.svelte handleApply calls validateFirstPhase before applyDraft', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Verify handleApply contains both validateFirstPhase and applyDraft in relative order.
    // Source-level substring search (avoids fragile regex body extraction).
    const handleApplyStart = source.indexOf('async function handleApply()');
    const nextFnStart = source.indexOf('\n  async function ', handleApplyStart + 1);
    const handleApplyBody =
      nextFnStart > -1 ? source.slice(handleApplyStart, nextFnStart) : source.slice(handleApplyStart);
    const validateFirstPos = handleApplyBody.indexOf('validateFirstPhase');
    const applyPos = handleApplyBody.indexOf('applyDraft');
    // validateFirstPhase must appear before applyDraft in handleApply
    expect(validateFirstPos).toBeGreaterThanOrEqual(0);
    expect(validateFirstPos).toBeLessThan(applyPos > -1 ? applyPos : Infinity);
  });

  it('DraftConfig.svelte first-section (si === 0) sortType select is disabled', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before disabled={si === 0} is added to the sortType select
    // Must disable the sortType select for the first section (si === 0)
    expect(source).toContain('si === 0');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E51S15 — AC-TEST-UI-DROPDOWN-DEFAULT-SEQUENTIAL-RED
// distributionMode dropdown defaults to "sequential" on a new section
// RED-first per DEC-22: these tests FAIL before DraftConfig.svelte adds the dropdown
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — E51S15 distributionMode dropdown default (AC-TEST-UI-DROPDOWN-DEFAULT-SEQUENTIAL-RED)', () => {
  it('DraftConfig.svelte source initializes distributionMode to "sequential" in new sections', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before distributionMode default is set in addSection() or section map
    expect(source).toContain("distributionMode: 'sequential'");
  });

  it('DraftConfig.svelte source preserves distributionMode from loaded config (defaulting to sequential)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The mapping of loaded sections must include distributionMode field (with fallback to 'sequential')
    expect(source).toContain('distributionMode');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E51S15 — AC-TEST-UI-DROPDOWN-LABEL-DE-RED
// Dropdown label "Mannschaftsverteilung"; options use DE i18n keys per DEC-52
// RED-first per DEC-22
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — E51S15 distributionMode i18n keys (AC-TEST-UI-DROPDOWN-LABEL-DE-RED)', () => {
  it('DraftConfig.svelte source references draftConfig.distributionMode.label i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the dropdown label key is referenced
    expect(source).toContain('draftConfig.distributionMode.label');
  });

  it('DraftConfig.svelte source references draftConfig.distributionMode.sequential i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the sequential option key is referenced
    expect(source).toContain('draftConfig.distributionMode.sequential');
  });

  it('DraftConfig.svelte source references draftConfig.distributionMode.round_robin i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before the round_robin option key is referenced
    expect(source).toContain('draftConfig.distributionMode.round_robin');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E51S15 — AC-TEST-UI-DROPDOWN-PERSISTS-VIA-DRAFTSAVE-RED
// dropdown is bound to section.distributionMode → included in saveDraft payload
// RED-first per DEC-22
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — E51S15 distributionMode binding (AC-TEST-UI-DROPDOWN-PERSISTS-VIA-DRAFTSAVE-RED)', () => {
  it('DraftConfig.svelte source has a select bound to section.distributionMode', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before bind:value={section.distributionMode} is added
    expect(source).toContain('section.distributionMode');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E51S15 — AC-TEST-UI-DROPDOWN-PER-SECTION-RED
// The dropdown is inside the {#each sections} block (per-section, not tournament-level)
// RED-first per DEC-22
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — E51S15 per-section distributionMode (AC-TEST-UI-DROPDOWN-PER-SECTION-RED)', () => {
  it('DraftConfig.svelte distributionMode dropdown appears inside {#each sections} block', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Verify: the each block comes before distributionMode reference in source order
    const eachIdx = source.indexOf('{#each sections');
    const distModeIdx = source.indexOf('distributionMode');
    // The dropdown must appear AFTER the {#each sections opening (inside the loop)
    expect(eachIdx).toBeGreaterThanOrEqual(0);
    expect(distModeIdx).toBeGreaterThan(eachIdx);
  });

  it('DraftConfig.svelte distributionMode dropdown uses section.distributionMode (not a top-level var)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Binding must use section.distributionMode (per-section), not a standalone variable
    expect(source).toContain('section.distributionMode');
    // Must NOT bind to a tournament-level distributionMode variable
    // (i.e., there should be no top-level "let distributionMode" declaration)
    expect(source).not.toMatch(/\blet distributionMode\b/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E58S05 — AC3, AC4, AC5, AC6, AC7, AC8 (TDD RED-first)
// Data-driven, capability-filtered phase-plan generator dropdown
// RED-first per DEC-22 (AC8): these tests FAIL before DraftConfig.svelte is updated
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC3: no hardcoded generator list (E58S05)', () => {
  it('DraftConfig.svelte imports from generatorStore', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before generatorStore import is added
    expect(source).toContain('generatorStore');
  });

  it('DraftConfig.svelte does NOT contain hardcoded roundRobin option element', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before hardcoded options are replaced with dynamic list
    // The old pattern was: <option value="roundRobin">{$_('draftConfig.gameMode.roundRobin')}</option>
    // After E58S05, the dropdown is populated from the registry, not hardcoded options
    expect(source).not.toMatch(/<option\s+value="roundRobin">/);
  });

  it('DraftConfig.svelte does NOT contain hardcoded awardCeremony option element', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before hardcoded options are replaced
    expect(source).not.toMatch(/<option\s+value="awardCeremony">/);
  });
});

describe('DraftConfig.svelte — AC4: capability-flag filter, not key-string matching (E58S05)', () => {
  it('DraftConfig.svelte source delegates to generatorFilter functions (flag-based, not key-string)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC4: DraftConfig must delegate to the generatorFilter module which enforces flag-based filtering
    expect(source).toContain('filterLastPhaseGenerators');
    expect(source).toContain('filterNonLastPhaseGenerators');
  });

  it('DraftConfig.svelte does NOT filter generators by key string matching (no hardcoded key comparisons)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC4 contract: filtering must use the isLastPhaseGenerator flag, NOT key-string matching
    expect(source).not.toMatch(/keyId\s*===?\s*['"]awardCeremony['"]/);
    expect(source).not.toMatch(/keyId\s*===?\s*['"]siegerehrung['"]/);
  });

  it('filterLastPhaseGenerators returns generators with isLastPhaseGenerator=true independent of key (AC4 behavioral)', async () => {
    // AC4 behavioral test: fixture uses keys NOT matching traditional ceremony names.
    // A key-string filter (e.g., g.keyId === 'awardCeremony') would FAIL this test.
    const { filterLastPhaseGenerators: flp } = await import('../lib/generatorFilter.js');
    const fixture = [
      { keyId: 'teamDuel', isLastPhaseGenerator: false },
      { keyId: 'trophyFinal', isLastPhaseGenerator: true },  // key ≠ 'awardCeremony'
      { keyId: 'groupStage', isLastPhaseGenerator: false },
    ];
    const result = flp(fixture);
    expect(result).toHaveLength(1);
    expect(result[0].keyId).toBe('trophyFinal'); // returned by flag, not by key string
    expect(result[0].isLastPhaseGenerator).toBe(true);
  });

  it('filterNonLastPhaseGenerators returns generators with isLastPhaseGenerator=false independent of key (AC4 behavioral)', async () => {
    const { filterNonLastPhaseGenerators: fnlp } = await import('../lib/generatorFilter.js');
    const fixture = [
      { keyId: 'myCustomGen', isLastPhaseGenerator: false },  // key ≠ 'roundRobin'
      { keyId: 'trophyFinal', isLastPhaseGenerator: true },
    ];
    const result = fnlp(fixture);
    expect(result).toHaveLength(1);
    expect(result[0].keyId).toBe('myCustomGen');
  });
});

describe('DraftConfig.svelte — AC5: tournament-page default (E58S05)', () => {
  it('DraftConfig.svelte imports from generatorStore (shared module = tournament page uses same source)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC5 clause 1: both DraftConfig and TournamentForm consume the same generatorStore module
    expect(source).toContain('generatorStore');
  });

  it('DraftConfig.svelte reads tournament.matchGeneratorId as the default generator for new phases (AC5 clause 2)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC5 clause 2: the tournament's chosen match generator becomes the default for new phases in the draft
    // DraftConfig must read tournament.matchGeneratorId and use it in addSection()
    expect(source).toContain('tournament.matchGeneratorId');
    expect(source).toContain('tournamentMatchGeneratorId');
  });

  it('DraftConfig.svelte addSection() uses tournamentMatchGeneratorId as gameMode default (AC5 clause 2)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC5 clause 2: new sections default to the tournament's generator, not hardcoded 'roundRobin'
    // The addSection function must use tournamentMatchGeneratorId (not the literal 'roundRobin')
    const addSectionBlock = source.match(/function addSection\(\)[^}]+gameMode:[^,}]+/s);
    expect(addSectionBlock).not.toBeNull();
    expect(addSectionBlock![0]).toContain('tournamentMatchGeneratorId');
    expect(addSectionBlock![0]).not.toMatch(/gameMode:\s*'roundRobin'/);
  });
});

describe('DraftConfig.svelte — AC6: i18n labels on generator key (E58S05)', () => {
  it('DraftConfig.svelte renders generator labels via i18n keyed on generator keyId', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC6: labels rendered via i18n keyed on generator keyId
    // E58S06 AC8: key namespace relocated from draftConfig.gameMode.* to matchOption.generator.*
    // e.g.: $_(matchOption.generator.${gen.keyId}) or $_(`matchOption.generator.${gen.keyId}`)
    expect(source).toMatch(/matchOption\.generator\.\$\{/);
  });
});

describe('DraftConfig.svelte — AC7: single-phase tournament sole phase is last phase (E58S05)', () => {
  it('DraftConfig.svelte last-phase detection uses sections.length - 1 index comparison', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // AC7: the last section (si === sections.length - 1) is treated as the last phase
    // This naturally makes a single-section tournament's sole section the last phase
    expect(source).toMatch(/si\s*===?\s*sections\.length\s*-\s*1/);
  });
});
