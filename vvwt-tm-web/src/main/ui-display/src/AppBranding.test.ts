/**
 * Brand lockup height invariant test for TM Display SPA.
 *
 * Originally authored for E44S04 AC3 (height: 2em for brand-lockup).
 * Updated for E50S02: brand-lockup height relaxed to 1.8em per
 * AC-IMPL-LOGO-HEIGHT-FIELD-HEADER-EQUIVALENT (story notes: "This story relaxes
 * the AC13 minimum-render-size if necessary"). The logo must still be visually
 * identifiable (AC13 intent preserved); height: 1.8em within the <=~3em structural
 * envelope satisfies this.
 *
 * E44S04 AC3 POSITIVE assertion updated from height:2em → height:1.8em.
 * NEGATIVE assertions unchanged (no min-width, no height:auto).
 *
 * DEC-22, DEC-2. Story: E50S02 — unified header band layout fix.
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

  // Pre-process step 3: locate .brand-lockup rule body
  const ruleMatch = stripped.match(/(?::global\()?\.brand-lockup\)?\s*\{([^}]*)\}/);
  if (!ruleMatch) throw new Error('No .brand-lockup rule found in <style> block');
  return ruleMatch[1];
}

describe('TM display branding — lockup height invariant (E44S04 AC3, updated E50S02)', () => {
  const source = readFileSync(APP_SVELTE, 'utf-8');
  const ruleBody = extractBrandLockupRuleBody(source);

  it('POSITIVE: .brand-lockup declares height: 1.8em (E50S02 — relaxed from 2em per AC-IMPL-LOGO-HEIGHT-FIELD-HEADER-EQUIVALENT)', () => {
    expect(ruleBody).toMatch(/\bheight\s*:\s*1\.8em\s*[;}]/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare min-width:', () => {
    expect(ruleBody).not.toMatch(/\bmin-width\s*:/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare height: auto', () => {
    expect(ruleBody).not.toMatch(/\bheight\s*:\s*auto\b/);
  });
});
