// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const SIDEBAR_HEADER_SVELTE = resolve(
  new URL(import.meta.url).pathname,
  '..',
  'components',
  'SidebarHeader.svelte'
);
const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');

function extractBrandLockupRuleBodyFrom(source: string): string {
  // Pre-process step 1: extract <style>...</style> block content
  const styleMatch = source.match(/<style[^>]*>([\s\S]*?)<\/style>/);
  if (!styleMatch) throw new Error('No <style> block found');
  const styleContent = styleMatch[1];

  // Pre-process step 2: strip CSS block comments
  const stripped = styleContent.replace(/\/\*[\s\S]*?\*\//g, '');

  // Pre-process step 3: locate .brand-lockup rule body
  const ruleMatch = stripped.match(/(?::global\()?\.brand-lockup\)?\s*\{([^}]*)\}/);
  if (!ruleMatch) throw new Error('No .brand-lockup rule found in <style> block');
  return ruleMatch[1];
}

describe('TM display branding -- lockup height invariant (E44S04 AC3, updated E50S02, E50S03)', () => {
  // E50S03: .brand-lockup is now in SidebarHeader.svelte
  const sidebarHeaderSource = readFileSync(SIDEBAR_HEADER_SVELTE, 'utf-8');
  const ruleBody = extractBrandLockupRuleBodyFrom(sidebarHeaderSource);

  it('POSITIVE: SidebarHeader .brand-lockup declares height: 1.8em (E50S02 -- relaxed from 2em per AC-IMPL-LOGO-HEIGHT-FIELD-HEADER-EQUIVALENT)', () => {
    expect(ruleBody).toMatch(/\bheight\s*:\s*1\.8em\s*[;}]/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare min-width:', () => {
    expect(ruleBody).not.toMatch(/\bmin-width\s*:/);
  });

  it('NEGATIVE: .brand-lockup does NOT declare height: auto', () => {
    expect(ruleBody).not.toMatch(/\bheight\s*:\s*auto\b/);
  });

  it('E50S03: App.svelte does NOT have a .brand-lockup CSS rule (moved to SidebarHeader.svelte)', () => {
    const appSource = readFileSync(APP_SVELTE, 'utf-8');
    const styleMatch = appSource.match(/<style[^>]*>([\s\S]*?)<\/style>/);
    if (!styleMatch) {
      // No style block is also acceptable
      return;
    }
    const stripped = styleMatch[1].replace(/\/\*[\s\S]*?\*\//g, '');
    expect(stripped).not.toMatch(/\.brand-lockup\s*\{/);
  });
});
