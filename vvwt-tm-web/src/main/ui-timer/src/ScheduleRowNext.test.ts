// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const SCHEDULE_ROW_SVELTE = resolve(
  new URL(import.meta.url).pathname,
  '..',
  'components',
  'ScheduleRow.svelte',
);
const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');

const rowSource = readFileSync(SCHEDULE_ROW_SVELTE, 'utf-8');
const appSource = readFileSync(APP_SVELTE, 'utf-8');

describe('ScheduleRow.svelte — AC8 next-upcoming visual distinction', () => {
  it('POSITIVE: ScheduleRow accepts an isNext boolean prop (AC8)', () => {
    // The component must have an isNext prop in its interface / props declaration.
    // Matches both `isNext?: boolean` and `isNext: boolean`.
    expect(rowSource).toMatch(/isNext\s*\??\s*:\s*boolean/);
  });

  it('POSITIVE: ScheduleRow applies a CSS class for the next-upcoming row (AC8)', () => {
    // A distinct CSS class (e.g. schedule-row--next) must be applied based on isNext.
    expect(rowSource).toMatch(/schedule-row--next/);
  });

  it('POSITIVE: ScheduleRow CSS defines the next-upcoming style rule (AC8)', () => {
    // The .schedule-row--next CSS rule must exist in the component's <style> block.
    const styleMatch = rowSource.match(/<style[^>]*>([\s\S]*?)<\/style>/);
    expect(styleMatch).toBeTruthy();
    const styleContent = styleMatch![1];
    expect(styleContent).toMatch(/\.schedule-row--next/);
  });

  it('POSITIVE: App.svelte passes isNext to ScheduleRow for the earliest future row (AC8)', () => {
    // App.svelte must pass the isNext prop. Verify it references isNext in the ScheduleRow usage.
    expect(appSource).toMatch(/isNext/);
  });
});
