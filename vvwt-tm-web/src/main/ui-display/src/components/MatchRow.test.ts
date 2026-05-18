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
// AC-E66S06-TEAM-COLOUR-EXPLICIT-RED
// Tests that .match-row__team has an explicit dark color set (AC1/AC5/AC6).
// RED: currently no explicit color on .match-row__team (inherits browser default).
// GREEN after fix: explicit dark color added to .match-row__team rule.
// ---------------------------------------------------------------------------
describe('E66S06 AC-E66S06-TEAM-COLOUR-EXPLICIT-RED', () => {
  /**
   * Parse a hex colour component (two hex digits) to a 0–255 integer.
   */
  function hexComponent(hex: string, offset: number): number {
    return parseInt(hex.slice(offset, offset + 2), 16);
  }

  /**
   * Extract the CSS `color` value from a rule body (not background-color).
   * Returns the trimmed value or null if absent.
   */
  function extractColorValue(ruleBody: string): string | null {
    // Match `color: <value>` but not `background-color`
    const m = ruleBody.match(/(?<![a-z-])color\s*:\s*([^;}\n]+)/);
    return m ? m[1].trim().toLowerCase() : null;
  }

  it('.match-row__team CSS rule declares an explicit color property (AC5 playing-team darker)', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const style = stripComments(extractStyleBlock(source, MATCH_ROW_SVELTE));

    const ruleMatch = style.match(/\.match-row__team\s*\{([^}]*)\}/);
    expect(ruleMatch, '.match-row__team rule must exist in MatchRow.svelte').not.toBeNull();
    const ruleBody = ruleMatch![1];

    const colorValue = extractColorValue(ruleBody);
    expect(
      colorValue,
      '.match-row__team must declare an explicit color property (AC5: darker than inherited browser default)'
    ).not.toBeNull();
  });

  it('.match-row__team explicit color is a dark shade (grey channel ≤ 50, i.e., hex ≤ #323232, AC6 WCAG AA contrast ≥ 4.5:1 vs #fff)', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const style = stripComments(extractStyleBlock(source, MATCH_ROW_SVELTE));

    const ruleMatch = style.match(/\.match-row__team\s*\{([^}]*)\}/);
    expect(ruleMatch, '.match-row__team rule must exist').not.toBeNull();
    const ruleBody = ruleMatch![1];

    const colorValue = extractColorValue(ruleBody);
    expect(colorValue, '.match-row__team must have a color').not.toBeNull();

    // Must be a hex color like #1a1a1a
    const hexMatch = colorValue!.match(/^#([0-9a-f]{6})$/);
    expect(
      hexMatch,
      `.match-row__team color must be a 6-digit hex value (got: ${colorValue})`
    ).not.toBeNull();

    // All channels ≤ 50 (decimal) → grey ≤ #323232 → contrast ≥ 12.6:1 vs white (far exceeds WCAG AA 4.5:1)
    const hex = hexMatch![1];
    const r = hexComponent(hex, 0);
    const g = hexComponent(hex, 2);
    const b = hexComponent(hex, 4);
    expect(r).toBeLessThanOrEqual(50);
    expect(g).toBeLessThanOrEqual(50);
    expect(b).toBeLessThanOrEqual(50);
  });

  it('.match-row__referee-name color is dark enough for WCAG AA (grey channel ≤ 118, i.e., #767676 threshold, AC6)', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    const style = stripComments(extractStyleBlock(source, MATCH_ROW_SVELTE));

    // Check .match-row__referee or .match-row__referee-name for color
    const refNameRuleMatch = style.match(/\.match-row__referee-name\s*\{([^}]*)\}/);
    const refRuleMatch = style.match(/\.match-row__referee(?![-])\s*\{([^}]*)\}/);

    // Collect all color declarations across referee-related rules
    const candidateBodies: string[] = [];
    if (refNameRuleMatch) candidateBodies.push(refNameRuleMatch[1]);
    if (refRuleMatch) candidateBodies.push(refRuleMatch[1]);

    // Find a color declaration in one of the referee rules
    let foundColor: string | null = null;
    for (const body of candidateBodies) {
      const c = extractColorValue(body);
      if (c) { foundColor = c; break; }
    }

    expect(
      foundColor,
      'MatchRow.svelte must declare a color on .match-row__referee or .match-row__referee-name (AC6 WCAG AA)'
    ).not.toBeNull();

    // Color must be a hex value — accept both 3-digit (#888) and 6-digit (#767676) forms
    const hex6Match = foundColor!.match(/^#([0-9a-f]{6})$/);
    const hex3Match = foundColor!.match(/^#([0-9a-f]{3})$/);
    // Expand 3-digit to 6-digit if needed (e.g. #888 → #888888)
    let hex6: string | null = null;
    if (hex6Match) {
      hex6 = hex6Match[1];
    } else if (hex3Match) {
      const s = hex3Match[1];
      hex6 = s[0] + s[0] + s[1] + s[1] + s[2] + s[2];
    }
    expect(
      hex6,
      `Referee text color must be a 3- or 6-digit hex value for verifiable WCAG check (got: ${foundColor}). Current value #888 = #888888 which fails WCAG AA (136 > 118 threshold).`
    ).not.toBeNull();

    // Grey channel ≤ 118 (#767676) → contrast ≥ 4.5:1 vs #fff (WCAG AA threshold for normal text)
    const r = hexComponent(hex6!, 0);
    const g = hexComponent(hex6!, 2);
    const b = hexComponent(hex6!, 4);
    // For a grey shade R==G==B. The luminance threshold for 4.5:1 against white is ~#767676 (118,118,118)
    // We check that all channels are ≤ 118 (i.e., at least as dark as #767676).
    expect(r).toBeLessThanOrEqual(118);
    expect(g).toBeLessThanOrEqual(118);
    expect(b).toBeLessThanOrEqual(118);
  });

  it('.match-row__referee block appears in template after the scores block (AC4: beneath the score)', () => {
    const source = readSource(MATCH_ROW_SVELTE);
    // Template section: between </script> and <style>
    const templateMatch = source.match(/<\/script>([\s\S]*?)<style/);
    expect(templateMatch, 'Template section between </script> and <style> must exist').not.toBeNull();
    const template = templateMatch![1];

    const scoresIdx = template.indexOf('match-row__scores');
    const refereeIdx = template.indexOf('match-row__referee');

    expect(scoresIdx).toBeGreaterThanOrEqual(0);
    expect(refereeIdx).toBeGreaterThanOrEqual(0);
    expect(refereeIdx).toBeGreaterThan(
      scoresIdx,
      'AC4: .match-row__referee must appear after .match-row__scores in the template (referee beneath the score)'
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
