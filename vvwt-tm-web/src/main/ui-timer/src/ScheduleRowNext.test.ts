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

/**
 * AC14/E11S13 — REFACTOR Phase-3 on TDD-authored ScheduleRowNext.test.ts (E11S09 AC8).
 *
 * The .schedule-row--next visual treatment and isNext prop were REMOVED by E11S13.
 * These tests previously asserted the existence of the removed surface (they PASSed
 * on pre-fix E11S09/E11S12 code). After the E11S13 fix:
 *   - Each test was changed from POSITIVE (assert presence) to NEGATIVE (assert absence).
 *   - Each test FAILs against pre-fix code (which still has isNext/--next).
 *   - Each test PASSes against post-fix code (which has removed isNext/--next).
 *
 * RED-on-old attestation (AC14): all four tests below fail against the E11S12-era
 * ScheduleRow.svelte and App.svelte because those files contain the isNext prop,
 * the schedule-row--next CSS class, and the getNextUpcomingIndex binding.
 */

describe('ScheduleRow.svelte — AC7/AC8/E11S13 next-upcoming treatment removed', () => {
  it('NEGATIVE: ScheduleRow MUST NOT have an isNext boolean prop (AC8/E11S13)', () => {
    // After E11S13, the isNext prop is removed from ScheduleRowProps.
    // This test FAILs against pre-fix code (which has `isNext?: boolean`).
    // This test PASSes against post-fix code (isNext removed).
    expect(rowSource).not.toMatch(/isNext\s*\??\s*:\s*boolean/);
  });

  it('NEGATIVE: ScheduleRow statusClass MUST NOT branch on schedule-row--next (AC7/E11S13)', () => {
    // After E11S13, the schedule-row--next class is no longer applied in the statusClass derived.
    // We check the statusClass declaration to verify the branch is absent.
    // This test FAILs against pre-fix code (statusClass has `isNext ? 'schedule-row--next'` branch).
    // This test PASSes against post-fix code (statusClass only branches on playing/done).
    const statusClassMatch = rowSource.match(/const\s+statusClass\s*=\s*\$derived\s*\(([\s\S]*?)\);/);
    expect(statusClassMatch, 'statusClass $derived not found in ScheduleRow.svelte').toBeTruthy();
    if (statusClassMatch) {
      expect(statusClassMatch[1]).not.toMatch(/schedule-row--next/);
    }
  });

  it('NEGATIVE: ScheduleRow CSS MUST NOT define .schedule-row--next selector rule (AC7/E11S13)', () => {
    // After E11S13, the .schedule-row--next CSS selector rule is removed from the <style> block.
    // We extract the style block and verify no selector matches .schedule-row--next.
    // This test FAILs against pre-fix code (which defines the rule with that selector).
    // This test PASSes against post-fix code (selector absent from style block).
    const styleMatch = rowSource.match(/<style[^>]*>([\s\S]*?)<\/style>/);
    expect(styleMatch).toBeTruthy();
    const styleContent = styleMatch![1];
    // Match only CSS selector declarations (lines containing a CSS class selector), not comments
    // A CSS selector rule for .schedule-row--next would look like: .schedule-row--next {
    expect(styleContent).not.toMatch(/\.schedule-row--next\s*\{/);
  });

  it('NEGATIVE: App.svelte MUST NOT bind isNext prop at ScheduleRow call-site (AC8/E11S13)', () => {
    // After E11S13, the isNext prop binding is removed from the ScheduleRow call-site in App.svelte.
    // We look for the ScheduleRow element usage and verify isNext= is not present as a prop binding.
    // This test FAILs against pre-fix code (which has `isNext={i === getNextUpcomingIndex()}`).
    // This test PASSes against post-fix code (isNext prop binding absent).
    // Match the ScheduleRow element usage — prop bindings use the pattern `propName={...}` or `{propName}`
    expect(appSource).not.toMatch(/isNext\s*=\s*\{/);
  });

  it('POSITIVE: .schedule-row--playing MUST have a left-border stripe (AC9/E11S13)', () => {
    // After E11S13, the playing row gains a left-border stripe matching operator mental model.
    // Delivery chose to add border-left to .schedule-row--playing (impl-report attests).
    // This test PASSes against post-fix code and FAILs against pre-fix code (no border-left on playing row).
    const styleMatch = rowSource.match(/<style[^>]*>([\s\S]*?)<\/style>/);
    expect(styleMatch).toBeTruthy();
    const styleContent = styleMatch![1];
    // The .schedule-row--playing rule must contain a border-left declaration
    const playingRuleMatch = styleContent.match(/\.schedule-row--playing\s*\{([^}]*)\}/);
    expect(playingRuleMatch).toBeTruthy();
    expect(playingRuleMatch![1]).toMatch(/border-left/);
  });
});
