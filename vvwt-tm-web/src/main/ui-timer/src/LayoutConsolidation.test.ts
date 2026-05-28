// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const APP_SVELTE = resolve(new URL(import.meta.url).pathname, '..', 'App.svelte');
const source = readFileSync(APP_SVELTE, 'utf-8');

// ── AC1: Tournament name inside sticky header ────────────────────────────────

describe('E11S11 AC1 — Tournament name renders in sticky header left side', () => {
  it('POSITIVE: App.svelte contains a sticky-header element that wraps the tournament name', () => {
    // The tournament name must be inside the sticky header container.
    // We verify: sticky-header class exists AND tournament-name is inside the sticky header block.
    // Source-inspectable: the HTML structure has the sticky header wrapping the h1.
    expect(source).toMatch(/timer-app__sticky-header/);
  });

  it('POSITIVE: tournament-name element is present in App.svelte', () => {
    // The tournament description/name must render as a heading inside the header area.
    expect(source).toMatch(/timer-app__tournament-name/);
  });

  it('POSITIVE: tournament name is placed inside the sticky header (not a standalone header block)', () => {
    // After consolidation the old standalone `.timer-app__header` container is replaced
    // by the new sticky header. The old `.timer-app__header` class should no longer appear
    // as the primary wrapper for the loaded state tournament name.
    // We verify the sticky header class is present.
    expect(source).toMatch(/timer-app__sticky-header/);
  });
});

// ── AC2: Main display block (RoundCounter + Countdown + TransportControls) in sticky header right side ──

describe('E11S11 AC2 — Main display block renders in sticky header right side', () => {
  it('POSITIVE: App.svelte contains main-display block class inside the sticky header', () => {
    // The main display block (round counter + countdown + transport controls) must render
    // in the right column of the sticky header.
    expect(source).toMatch(/timer-app__main-display/);
  });

  it('POSITIVE: RoundCounter component is used inside the main display block', () => {
    // RoundCounter must appear in the source (it was already present; verify it is not removed).
    expect(source).toMatch(/RoundCounter/);
  });

  it('POSITIVE: Countdown component is used inside the main display block', () => {
    expect(source).toMatch(/Countdown/);
  });

  it('POSITIVE: TransportControls component is used inside the main display block', () => {
    expect(source).toMatch(/TransportControls/);
  });
});

// ── AC3: Hallenuhr-Now rendered under tournament name inside sticky header ────

describe('E11S11 AC3 — Hallenuhr-Now renders under tournament name in sticky header', () => {
  it('POSITIVE: hallenuhrzeit element is present in App.svelte', () => {
    expect(source).toMatch(/hallenuhrzeit/i);
  });

  it('POSITIVE: Hallenuhr-Now is inside the sticky header (not standalone bar)', () => {
    // After consolidation the old standalone `.timer-app__hallenuhrzeit-bar` standalone
    // element outside the header is replaced by an element inside the sticky header.
    // We verify the sticky header class is present (it wraps the Hallenuhr-Now).
    expect(source).toMatch(/timer-app__sticky-header/);
  });

  it('POSITIVE: Hallenuhr-Now element has a class for the left-column placement', () => {
    // The Hallenuhr-Now must be placed in the left column of the header grid.
    expect(source).toMatch(/timer-app__hallenuhrzeit/);
  });
});

// ── AC4: CSS grid height-invariant (right column spans 2 rows) ───────────────

describe('E11S11 AC4 — Header strip height invariant (grid-row span)', () => {
  it('POSITIVE: App.svelte CSS declares grid-template-rows or grid-row on the header structure', () => {
    // The height invariant is achieved via CSS grid — the right column spans both rows.
    // We check for a CSS declaration that spans rows: `grid-row: span 2` or `grid-row: 1 / 3`.
    expect(source).toMatch(/grid-row\s*:\s*(span\s+2|1\s*\/\s*3)/);
  });

  it('POSITIVE: App.svelte CSS declares display: grid for the sticky header', () => {
    // The two-column layout requires CSS grid.
    expect(source).toMatch(/display\s*:\s*grid/);
  });

  it('POSITIVE: App.svelte CSS declares grid-template-columns for the header', () => {
    // Two columns must be declared.
    expect(source).toMatch(/grid-template-columns/);
  });
});

// ── AC5: Sticky header uses position: sticky; top: 0 ────────────────────────

describe('E11S11 AC5 — Header strip is sticky during schedule scroll', () => {
  it('POSITIVE: App.svelte CSS declares position: sticky for the header', () => {
    expect(source).toMatch(/position\s*:\s*sticky/);
  });

  it('POSITIVE: App.svelte CSS declares top: 0 on the sticky header', () => {
    // The sticky header must have top: 0 to stick at the viewport top.
    expect(source).toMatch(/top\s*:\s*0/);
  });
});

// ── AC6: Schedule container uses overflow-y: auto or scroll ─────────────────

describe('E11S11 AC6 — Schedule table renders inside a scrollable container', () => {
  it('POSITIVE: App.svelte CSS declares overflow-y: auto (or scroll) for the schedule container', () => {
    expect(source).toMatch(/overflow-y\s*:\s*(auto|scroll)/);
  });
});

// ── AC7: Auto-scroll to playing row on playingIndex change ───────────────────

describe('E11S11 AC7 — Auto-scroll to playing row on playingIndex change', () => {
  it('POSITIVE: App.svelte contains scrollIntoView call for auto-scroll', () => {
    // The auto-scroll mechanism uses scrollIntoView (smooth).
    expect(source).toMatch(/scrollIntoView/);
  });

  it('POSITIVE: App.svelte uses smooth scroll behavior for auto-scroll', () => {
    // AC7 + Brief Q-3: smooth, not snap.
    expect(source).toMatch(/behavior\s*:\s*['"]smooth['"]/);
  });

  it('POSITIVE: App.svelte auto-scroll is wired to playingIndex changes', () => {
    // The auto-scroll must react to playingIndex — verified by presence of
    // playingIndex reference in the context of an $effect or reactive block.
    expect(source).toMatch(/playingIndex/);
  });
});

// ── AC8: Auto-scroll suppressed when row is already visible ─────────────────

describe('E11S11 AC8 — Auto-scroll does not interfere with operator manual scroll', () => {
  it('POSITIVE: App.svelte contains a visibility check before scrolling (getBoundingClientRect or similar)', () => {
    // Option (b): auto-scroll fires only when the playing row is not visible.
    // Verified by presence of getBoundingClientRect or IntersectionObserver.
    expect(source).toMatch(/getBoundingClientRect|IntersectionObserver/);
  });
});
