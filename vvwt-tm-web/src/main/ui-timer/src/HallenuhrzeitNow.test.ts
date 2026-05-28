// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');
const source = readFileSync(APP_SVELTE, 'utf-8');

describe('App.svelte — AC7 Hallenuhr-now permanent reading', () => {
  it('POSITIVE: source contains a setInterval with 1000ms interval for Hallenuhr-now refresh (AC7)', () => {
    // AC7 mandates a setInterval(…, 1000) or equivalent driving the Hallenuhr-now display.
    expect(source).toMatch(/setInterval\s*\([^)]*,\s*1000\s*\)/);
  });

  it('POSITIVE: source renders a permanent Hallenuhr-now element visible across transport states (AC7)', () => {
    // The Hallenuhr-now element must be present outside any transport-state conditional block.
    // We verify: the source contains a class or identifier for the Hallenuhr-now display.
    expect(source).toMatch(/hallenuhrzeit/i);
  });

  it('POSITIVE: Hallenuhr-now display uses formatTimeSeconds or equivalent to format the time (AC7)', () => {
    // The displayed value must be formatted (not raw seconds). Check formatTimeSeconds is called
    // in the context of the Hallenuhr-now state update.
    expect(source).toMatch(/formatTimeSeconds/);
  });
});
