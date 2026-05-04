/**
 * AC2 — Brand lockup height invariant test for TM Timer SPA.
 *
 * DEC-22 Iron Law: written BEFORE App.svelte .brand-lockup CSS edit (RED state).
 * RED: current App.svelte has `:global(.brand-lockup) { min-width: 120px; height: auto; display: block; }` —
 *   POSITIVE regex fails (no height:2em), NEGATIVE for min-width:* matches (fails), NEGATIVE for height:auto matches (fails).
 *
 * Strategy: identical to AC1. The `:global(.brand-lockup)` selector form is accepted by the
 * rule-body locator regex `(?::global\()?\.brand-lockup\)?` in pre-process step 3.
 *
 * DEC-22, DEC-42. Story: E44S04 — brand-lockup oversize fix.
 */

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** Resolve App.svelte path relative to this test file's directory. */
const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');

function extractBrandLockupRuleBody(source: string): string {
  // Pre-process step 1: extract <style>...</style> block content
  const styleMatch = source.match(/<style[^>]*>([\s\S]*?)<\/style>/);
  if (!styleMatch) throw new Error('No <style> block found in App.svelte');
  const styleContent = styleMatch[1];

  // Pre-process step 2: strip CSS block comments
  const stripped = styleContent.replace(/\/\*[\s\S]*?\*\//g, '');

  // Pre-process step 3: locate .brand-lockup rule body (accepts :global() wrapper)
  const ruleMatch = stripped.match(/(?::global\()?\.brand-lockup\)?\s*\{([^}]*)\}/);
  if (!ruleMatch) throw new Error('No .brand-lockup rule found in <style> block');
  return ruleMatch[1];
}

describe('TM timer branding — lockup height invariant (E44S04 AC2)', () => {
  const source = readFileSync(APP_SVELTE, 'utf-8');
  const ruleBody = extractBrandLockupRuleBody(source);

  it('POSITIVE: .brand-lockup declares height: 2em', () => {
    expect(ruleBody).toMatch(/\bheight\s*:\s*2em\s*[;}]/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare min-width:', () => {
    expect(ruleBody).not.toMatch(/\bmin-width\s*:/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare height: auto', () => {
    expect(ruleBody).not.toMatch(/\bheight\s*:\s*auto\b/);
  });
});
