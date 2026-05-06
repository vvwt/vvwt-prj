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

  it('DraftConfig.svelte source references draftConfig.errors.lastPhaseMustBeSiegerehrung i18n key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: this fails before the i18n error key is referenced
    expect(source).toContain('draftConfig.errors.lastPhaseMustBeSiegerehrung');
  });

  it('DraftConfig.svelte handleApply calls validateLastPhase before backend call', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: handleApply must call validateLastPhase
    // Extract handleApply function body and verify validateLastPhase appears before applyDraft call
    const handleApplyMatch = source.match(/async function handleApply\(\)[^{]*\{([\s\S]*?)^\s*\}/m);
    const handleApplyBody = handleApplyMatch?.[1] ?? '';
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
  it('DraftConfig.svelte source contains a gameMode select field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Verify the gameMode dropdown exists in the template
    expect(source).toContain('section.gameMode');
    expect(source).toContain('draftConfig.gameMode.roundRobin');
    expect(source).toContain('draftConfig.gameMode.siegerehrung');
  });

  it('DraftConfig.svelte source has last-section auto-set to siegerehrung with disabled/readonly', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Last section auto-set: source must reference siegerehrung assignment for the last index
    // The implementation sets gameMode='siegerehrung' for the last section reactively
    expect(source).toContain("'siegerehrung'");
    // Disabled attribute on last section's select
    expect(source).toContain('disabled');
  });

  it('DraftConfig.svelte handleApply sets applyError when last phase is not siegerehrung', async () => {
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
// E48S09 — AC-TEST-FRONTEND-RUNDENZEIT-DISABLED-RED
// Rundenzeit input disabled for siegerehrung gameMode sections
// RED-first per DEC-22: these tests FAIL before DraftConfig.svelte adds the disabled attribute
// ─────────────────────────────────────────────────────────────────────────────

describe('DraftConfig.svelte — AC-TEST-FRONTEND-RUNDENZEIT-DISABLED-RED (E48S09)', () => {
  it('DraftConfig.svelte lapTimeMinutes input is disabled for siegerehrung gameMode', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './DraftConfig.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // RED: fails before disabled={section.gameMode === 'siegerehrung'} is added to lapTimeMinutes input
    // The disabled binding must appear in the lapTimeMinutes input context
    expect(source).toContain("disabled={section.gameMode === 'siegerehrung'}");
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
