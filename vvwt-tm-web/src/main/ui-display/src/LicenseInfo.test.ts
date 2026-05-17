// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';

const BASE = new URL(import.meta.url).pathname;

const APP_SVELTE = resolve(BASE, '..', 'App.svelte');
const LICENSE_PANEL_SVELTE = resolve(BASE, '..', 'components', 'LicenseInfoPanel.svelte');

function readSource(path: string): string {
  return readFileSync(path, 'utf-8');
}

describe('E59S03 AC3 — Display SPA info-panel toggle (source inspection)', () => {
  it('LicenseInfoPanel.svelte file exists', () => {
    expect(existsSync(LICENSE_PANEL_SVELTE)).toBe(true);
  });

  it('App.svelte declares a boolean toggle state variable (e.g. licenseInfoOpen)', () => {
    const src = readSource(APP_SVELTE);
    // Match: let licenseInfoOpen: boolean = $state(false)
    //    or: let licenseInfoOpen = $state(false)
    //    or: let licenseInfoOpen = $state<boolean>(false)
    expect(src).toMatch(/let\s+\w*(licenseInfo|infoPanel|sourceInfo)\w*(?:\s*:\s*boolean)?\s*=\s*\$state[(<].*?(?:false|boolean)/);
  });

  it('App.svelte renders a persistently-visible button/control to open the info panel with an accessible label', () => {
    const src = readSource(APP_SVELTE);
    // The entry point must have an aria-label or title referencing source/license
    expect(src).toMatch(/aria-label\s*=\s*["'][^"']*(?:source|Source|license|License|AGPL)[^"']*["']/);
  });

  it('App.svelte conditionally shows LicenseInfoPanel based on the toggle variable', () => {
    const src = readSource(APP_SVELTE);
    // Expect an {#if ...licenseInfo...} or {#if ...infoPanel...} block containing LicenseInfoPanel
    expect(src).toMatch(/\{#if\s+\w*(licenseInfo|infoPanel|sourceInfo)\w*/);
  });

  it('App.svelte imports LicenseInfoPanel component', () => {
    const src = readSource(APP_SVELTE);
    expect(src).toContain('LicenseInfoPanel');
  });
});

describe('E59S03 AC3/AC5 — LicenseInfoPanel content', () => {
  it('LicenseInfoPanel.svelte contains the GitHub source link to vvwt/vvwt-prj', () => {
    if (!existsSync(LICENSE_PANEL_SVELTE)) return; // skip if file missing (RED phase)
    const src = readSource(LICENSE_PANEL_SVELTE);
    expect(src).toContain('https://github.com/vvwt/vvwt-prj');
  });

  it('LicenseInfoPanel.svelte mentions AGPL-3.0-or-later to satisfy AC5', () => {
    if (!existsSync(LICENSE_PANEL_SVELTE)) return;
    const src = readSource(LICENSE_PANEL_SVELTE);
    expect(src).toContain('AGPL-3.0-or-later');
  });

  it('LicenseInfoPanel.svelte carries the DEC-76 SPDX license header (AC8)', () => {
    if (!existsSync(LICENSE_PANEL_SVELTE)) return;
    const src = readSource(LICENSE_PANEL_SVELTE);
    expect(src).toContain('SPDX-License-Identifier: AGPL-3.0-or-later');
  });
});
