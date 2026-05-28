// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const COUNTDOWN_SVELTE = resolve(
  new URL(import.meta.url).pathname,
  '..',
  'components',
  'Countdown.svelte',
);

const source = readFileSync(COUNTDOWN_SVELTE, 'utf-8');

describe('Countdown.svelte — AC6 visible countdown label', () => {
  it('POSITIVE: renders a visible text element with the countdown target label (AC6)', () => {
    // AC6: a visible text element near the large countdown numerals identifies
    // what the countdown counts toward. The element must NOT be aria-only —
    // it must be present in the rendered HTML template (not inside aria-label= only).
    // We verify a <p> or <span> or <div> element with the i18n key for the label.
    // Acceptable: reuse timer.countdown.label or a sibling key per AC6.
    expect(source).toMatch(
      /\$_\(\s*['"]timer\.countdown\.label['"]\s*\)/,
    );
  });

  it('POSITIVE: the label element is inside the HTML template (not only in aria-label attribute)', () => {
    // The element must appear as visible body content, not solely as an aria-label value.
    // Check that there is at least one occurrence of the key outside an aria-label="..." attribute.
    // Strategy: find the key in a context that is not an aria-label= value.
    // A simple check: verify the source contains the pattern as text content (inside >{…}< or similar).
    const ariaOnlyPattern = /aria-label=\{[^}]*timer\.countdown\.label[^}]*\}/g;
    const allOccurrences = (source.match(/\$_\(\s*['"]timer\.countdown\.label['"]\s*\)/g) ?? []).length;
    const ariaOnlyOccurrences = (source.match(ariaOnlyPattern) ?? []).length;
    // There must be MORE occurrences than aria-only ones (i.e., at least one visible usage)
    expect(allOccurrences).toBeGreaterThan(ariaOnlyOccurrences);
  });
});
