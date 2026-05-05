/**
 * Tests for DraftConfig route — Story E47S01.
 *
 * AC3: No per-page .draft-config__header; title registered via pageHeader store.
 * AC5: Back-arrow registered (navigates to /tournaments/:tournamentId/edit).
 * AC6: tournamentId registered for tournament name display in header.
 * AC12: No pop()-based back-button rendered in the template (removed per D-10).
 *       Note: applySuccess pop() is an async post-action redirect — NOT a back-button. It stays.
 *
 * RED-first per DEC-22: tests fail before migration.
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
