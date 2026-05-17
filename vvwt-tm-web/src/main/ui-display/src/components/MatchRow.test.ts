// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = new URL(import.meta.url).pathname;
const MATCH_ROW_SVELTE = resolve(BASE, '..', 'MatchRow.svelte');
const COURT_GRID_SVELTE = resolve(BASE, '..', 'CourtGrid.svelte');

function readSource(path: string): string {
  return readFileSync(path, 'utf-8');
}

function extractStyleBlock(source: string, filePath: string): string {
  const m = source.match(/<style[^>]*>([\s\S]*?)<\/style>/);
  if (!m) throw new Error('No <style> block found in ' + filePath);
  return m[1];
}

function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

/**
 * Extract the background or background-color value from a CSS rule body.
 * Returns the value string (trimmed, lowercased) or null if not found.
 *
 * Accepts both shorthand `background: <value>` and longhand `background-color: <value>`.
 * Returns the first match (shorthand preferred if both present).
 */
function extractBackgroundValue(ruleBody: string): string | null {
  // Try shorthand first (e.g., `background: #d6e8ff`)
  // Then longhand (e.g., `background-color: #d6e8ff`)
  // Match property: value; up to semicolon or end of string
  const shorthandMatch = ruleBody.match(/\bbackground\s*:\s*([^;}\n]+)/);
  const longhandMatch = ruleBody.match(/\bbackground-color\s*:\s*([^;}\n]+)/);
  const match = shorthandMatch ?? longhandMatch;
  return match ? match[1].trim().toLowerCase() : null;
}

// ---------------------------------------------------------------------------
// AC-TEST-SCORES-CENTERED-RED
// ---------------------------------------------------------------------------
describe('E50S07 AC-TEST-SCORES-CENTERED-RED', () => {
  /**
   * MatchRow.svelte .match-row__scores CSS rule MUST contain text-align: center.
   *
   * RED: current MatchRow.svelte has no text-align on .match-row__scores (defaults to left).
   * GREEN after fix: text-align: center added to .match-row__scores rule.
   */
  it('.match-row__scores CSS rule declares text-align: center', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const style = stripComments(extractStyleBlock(source, MATCH_ROW_SVELTE));

    const ruleMatch = style.match(/\.match-row__scores\s*\{([^}]*)\}/);
    expect(ruleMatch, '.match-row__scores rule must exist in MatchRow.svelte').not.toBeNull();
    const ruleBody = ruleMatch![1];

    expect(ruleBody).toMatch(
      /text-align\s*:\s*center/,
      '.match-row__scores must have text-align: center (defect: currently absent → left-aligned default)'
    );
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-ACTIVE-HIGHLIGHT-UNIFORM-RED
// ---------------------------------------------------------------------------
describe('E50S07 AC-TEST-ACTIVE-HIGHLIGHT-UNIFORM-RED', () => {
  /**
   * Both .match-row--current (MatchRow.svelte) and .round-row--active (CourtGrid.svelte)
   * MUST resolve to the SAME background color — no two-tone seam.
   *
   * RED: current code uses #eef4fb for .match-row--current and #d6e8ff for .round-row--active.
   * GREEN after fix: both use #d6e8ff (or any single uniform value).
   */
  it('.match-row--current CSS rule declares a background color', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const style = stripComments(extractStyleBlock(source, MATCH_ROW_SVELTE));

    const ruleMatch = style.match(/\.match-row--current\s*\{([^}]*)\}/);
    expect(ruleMatch, '.match-row--current rule must exist in MatchRow.svelte').not.toBeNull();
    const ruleBody = ruleMatch![1];

    const bgValue = extractBackgroundValue(ruleBody);
    expect(bgValue, '.match-row--current must declare a background or background-color').not.toBeNull();
  });

  it('.round-row--active CSS rule declares a background color', () => {
    const source = readSource(COURT_GRID_SVELTE);
    const style = stripComments(extractStyleBlock(source, COURT_GRID_SVELTE));

    const ruleMatch = style.match(/\.round-row--active\s*\{([^}]*)\}/);
    expect(ruleMatch, '.round-row--active rule must exist in CourtGrid.svelte').not.toBeNull();
    const ruleBody = ruleMatch![1];

    const bgValue = extractBackgroundValue(ruleBody);
    expect(bgValue, '.round-row--active must declare a background or background-color').not.toBeNull();
  });

  it('.match-row--current and .round-row--active resolve to the SAME background color (no two-tone seam)', () => {
    const matchRowSource = readSource(MATCH_ROW_SVELTE);
    const matchRowStyle = stripComments(extractStyleBlock(matchRowSource, MATCH_ROW_SVELTE));
    const courtGridSource = readSource(COURT_GRID_SVELTE);
    const courtGridStyle = stripComments(extractStyleBlock(courtGridSource, COURT_GRID_SVELTE));

    const currentRuleMatch = matchRowStyle.match(/\.match-row--current\s*\{([^}]*)\}/);
    expect(currentRuleMatch).not.toBeNull();
    const currentBg = extractBackgroundValue(currentRuleMatch![1]);
    expect(currentBg, '.match-row--current must have a background value').not.toBeNull();

    const activeRuleMatch = courtGridStyle.match(/\.round-row--active\s*\{([^}]*)\}/);
    expect(activeRuleMatch).not.toBeNull();
    const activeBg = extractBackgroundValue(activeRuleMatch![1]);
    expect(activeBg, '.round-row--active must have a background value').not.toBeNull();

    expect(currentBg).toBe(
      activeBg,
      `.match-row--current background (${currentBg}) must equal .round-row--active background (${activeBg}) — defect: two different blue tints cause a two-tone seam`
    );
  });
});

// ---------------------------------------------------------------------------
// AC-TEST-INACTIVE-ROWS-UNCHANGED-GREEN (regression guard)
// ---------------------------------------------------------------------------
describe('E50S07 AC-TEST-INACTIVE-ROWS-UNCHANGED-GREEN', () => {
  /**
   * Pre-existing .match-row base rule and .match-row--completed rule must remain.
   *
   * GREEN: these pass immediately against current code and must remain GREEN after fix.
   */
  it('MatchRow.svelte .match-row base CSS rule still exists (inactive rows unchanged)', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const style = stripComments(extractStyleBlock(source, MATCH_ROW_SVELTE));
    expect(style).toMatch(/\.match-row\s*\{/);
  });

  it('MatchRow.svelte .match-row--completed CSS rule still exists (completed appearance preserved)', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const style = stripComments(extractStyleBlock(source, MATCH_ROW_SVELTE));
    expect(style).toMatch(/\.match-row--completed\s*\{/);
  });

  it('MatchRow.svelte Props interface still declares match and isCurrentLap props', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const scriptMatch = source.match(/<script[^>]*>([\s\S]*?)<\/script>/);
    expect(scriptMatch).not.toBeNull();
    const script = scriptMatch![1];
    expect(script).toMatch(/match\s*:/);
    expect(script).toMatch(/isCurrentLap\s*:/);
  });
});
