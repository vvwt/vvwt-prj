// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

const COMPONENT_PATH = path.resolve(__dirname, './InfoPortalOptIn.svelte');

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — Component structure
// ─────────────────────────────────────────────────────────────────────────────

describe('InfoPortalOptIn.svelte — E62S02 AC3: opt-in control structure', () => {

  it('InfoPortalOptIn.svelte exists as a route component', () => {
    expect(fs.existsSync(COMPONENT_PATH)).toBe(true);
  });

  it('AC3: component fetches current opt-in state from API', () => {
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    // Component must call the API to get current status
    expect(source).toContain('/info-portal');
    expect(source).toContain('apiFetch');
  });

  it('AC3: component renders disabled state when status is DISABLED', () => {
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    // Must handle DISABLED state (AC8: clear disabled/unavailable state)
    expect(source).toContain('DISABLED');
  });

  it('AC3: component renders registered state when status is REGISTERED', () => {
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    // Must handle REGISTERED state
    expect(source).toContain('REGISTERED');
  });

  it('AC3: component renders opt-in button when status is NOT_REGISTERED', () => {
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    // Must handle NOT_REGISTERED state with an opt-in action
    expect(source).toContain('NOT_REGISTERED');
    // Must have a POST action for opt-in
    expect(source).toMatch(/POST|post/);
  });

  it('AC3: component handles ERROR state', () => {
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    // Must handle ERROR state
    expect(source).toContain('ERROR');
  });

  it('AC3: component uses TypeScript lang attribute (DEC-2)', () => {
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    // DEC-2: Svelte + TypeScript
    expect(source).toContain("lang=\"ts\"");
  });

  it('AC3: component imports apiFetch from api.js (standard pattern)', () => {
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).toContain("from '../lib/api.js'");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// App.svelte route registration
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — E62S02: InfoPortalOptIn route registered', () => {
  const APP_PATH = path.resolve(__dirname, '../App.svelte');

  it('App.svelte imports InfoPortalOptIn component', () => {
    const source = fs.readFileSync(APP_PATH, 'utf8');
    expect(source).toContain('InfoPortalOptIn');
  });

  it('App.svelte registers /info-portal route for tournament context', () => {
    const source = fs.readFileSync(APP_PATH, 'utf8');
    expect(source).toContain('/info-portal');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Tournaments.svelte navigation
// ─────────────────────────────────────────────────────────────────────────────

describe('Tournaments.svelte — E62S02: Info-Portal navigation button', () => {
  const TOURNAMENTS_PATH = path.resolve(__dirname, './Tournaments.svelte');

  it('Tournaments.svelte has Info-Portal navigation button', () => {
    const source = fs.readFileSync(TOURNAMENTS_PATH, 'utf8');
    expect(source).toContain('/info-portal');
  });
});
