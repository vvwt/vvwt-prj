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

// ─────────────────────────────────────────────────────────────────────────────
// E64S02 — AC2: infoPortal.title key in de.json + infoPortalButton key
// ─────────────────────────────────────────────────────────────────────────────
import deMessages from '../locales/de.json';

describe('de.json — E64S02 AC2: infoPortal.title key present', () => {
  it('de.json infoPortal.title is a non-empty German string', () => {
    // AC2: infoPortal.title key must exist in the infoPortal namespace
    const ip = (deMessages as unknown as Record<string, Record<string, string>>).infoPortal;
    expect(ip).toHaveProperty('title');
    expect(typeof ip.title).toBe('string');
    expect(ip.title.length).toBeGreaterThan(0);
  });
});

describe('de.json — E64S02 AC2: tournaments.infoPortalButton key present', () => {
  it('de.json tournaments.infoPortalButton is a non-empty German string', () => {
    // AC2: tournaments.infoPortalButton key must exist in the tournaments namespace
    const t = (deMessages as unknown as Record<string, Record<string, string>>).tournaments;
    expect(t).toHaveProperty('infoPortalButton');
    expect(typeof t.infoPortalButton).toBe('string');
    expect(t.infoPortalButton.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E64S02 — AC3: no hardcoded German display strings in InfoPortalOptIn.svelte
// ─────────────────────────────────────────────────────────────────────────────

describe('InfoPortalOptIn.svelte — E64S02 AC3: no hardcoded German display strings', () => {
  it('component does not contain hardcoded German string "Laden…"', () => {
    // AC3: loading text must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain("'Laden…'");
    expect(source).not.toContain('"Laden…"');
    expect(source).not.toContain('>Laden…<');
  });

  it('component does not contain hardcoded German string "Fehler:"', () => {
    // AC3: load error text must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain('>Fehler:');
    expect(source).not.toContain("'Fehler'");
  });

  it('component does not contain hardcoded German string "Info-Portal nicht konfiguriert"', () => {
    // AC3: DISABLED state text must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain('Info-Portal nicht konfiguriert');
  });

  it('component does not contain hardcoded German string "Bereits veröffentlicht"', () => {
    // AC3: REGISTERED state text must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain('Bereits veröffentlicht');
  });

  it('component does not contain hardcoded German string "Dieses Turnier ist noch nicht"', () => {
    // AC3: NOT_REGISTERED state description must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain('Dieses Turnier ist noch nicht im Info-Portal veröffentlicht');
  });

  it('component does not contain hardcoded German string "Opt-in: Im Info-Portal veröffentlichen"', () => {
    // AC3: opt-in button label must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain('Opt-in: Im Info-Portal veröffentlichen');
  });

  it('component does not contain hardcoded German string "Wird veröffentlicht…"', () => {
    // AC3: submitting state for opt-in button must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain("'Wird veröffentlicht…'");
    expect(source).not.toContain('"Wird veröffentlicht…"');
  });

  it('component does not contain hardcoded German string "Veröffentlichung fehlgeschlagen"', () => {
    // AC3: ERROR state text must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain('Veröffentlichung fehlgeschlagen');
  });

  it('component does not contain hardcoded German string "Erneut versuchen"', () => {
    // AC3: retry button label must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain("'Erneut versuchen'");
    expect(source).not.toContain('"Erneut versuchen"');
    expect(source).not.toContain('>Erneut versuchen<');
  });

  it('component does not contain hardcoded German string "Wird erneut versucht…"', () => {
    // AC3: retry-submitting label must be i18n-externalized
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).not.toContain("'Wird erneut versucht…'");
    expect(source).not.toContain('"Wird erneut versucht…"');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E64S02 — AC3: de.json contains all i18n keys for InfoPortalOptIn strings
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E64S02 AC3: infoPortal i18n keys for all opt-in page strings', () => {
  const ip = (deMessages as unknown as Record<string, Record<string, string>>).infoPortal;

  it('de.json infoPortal.loading is present', () => {
    expect(ip).toHaveProperty('loading');
    expect(ip.loading.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.loadError is present', () => {
    expect(ip).toHaveProperty('loadError');
    expect(ip.loadError.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.statusDisabled is present', () => {
    expect(ip).toHaveProperty('statusDisabled');
    expect(ip.statusDisabled.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.statusRegistered is present', () => {
    expect(ip).toHaveProperty('statusRegistered');
    expect(ip.statusRegistered.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.statusNotRegistered is present', () => {
    expect(ip).toHaveProperty('statusNotRegistered');
    expect(ip.statusNotRegistered.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.optInButton is present', () => {
    expect(ip).toHaveProperty('optInButton');
    expect(ip.optInButton.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.submittingOptIn is present', () => {
    expect(ip).toHaveProperty('submittingOptIn');
    expect(ip.submittingOptIn.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.statusError is present', () => {
    expect(ip).toHaveProperty('statusError');
    expect(ip.statusError.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.retryButton is present', () => {
    expect(ip).toHaveProperty('retryButton');
    expect(ip.retryButton.length).toBeGreaterThan(0);
  });

  it('de.json infoPortal.submittingRetry is present', () => {
    expect(ip).toHaveProperty('submittingRetry');
    expect(ip.submittingRetry.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E64S02 — AC4: DISABLED state shows configuration guidance hint
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E64S02 AC4: DISABLED state configuration guidance hint', () => {
  it('de.json infoPortal.configHint mentions info-portal: configuration block', () => {
    // AC4: guidance hint must name the application.yml info-portal: block
    const ip = (deMessages as unknown as Record<string, Record<string, string>>).infoPortal;
    expect(ip).toHaveProperty('configHint');
    expect(ip.configHint.length).toBeGreaterThan(0);
    // Must mention the configuration block name or properties
    expect(ip.configHint).toMatch(/info-portal/i);
  });

  it('de.json infoPortal.configHint mentions url property', () => {
    // AC4: hint must name the url property
    const ip = (deMessages as unknown as Record<string, Record<string, string>>).infoPortal;
    expect(ip.configHint).toContain('url');
  });

  it('de.json infoPortal.configHint mentions tenant-id property', () => {
    // AC4: hint must name the tenant-id property
    const ip = (deMessages as unknown as Record<string, Record<string, string>>).infoPortal;
    expect(ip.configHint).toContain('tenant-id');
  });

  it('de.json infoPortal.configHint mentions location-id property', () => {
    // AC4: hint must name the location-id property
    const ip = (deMessages as unknown as Record<string, Record<string, string>>).infoPortal;
    expect(ip.configHint).toContain('location-id');
  });

  it('InfoPortalOptIn.svelte renders configHint key in DISABLED state', () => {
    // AC4: the component must render the config hint for DISABLED status
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).toContain("infoPortal.configHint");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E64S02 — AC5: no bare $_(key) ?? 'literal' anti-pattern; canonical form used
// ─────────────────────────────────────────────────────────────────────────────

describe('InfoPortalOptIn.svelte — E64S02 AC5: canonical i18n pattern, no dead ?? literal', () => {
  it('component does not use dead ?? literal fallback pattern', () => {
    // AC5: bare $_(key) ?? 'literal' is the anti-pattern; must not be present
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    // The anti-pattern is: $_(key) ?? 'somestring'
    expect(source).not.toMatch(/\$_\([^)]+\)\s*\?\?\s*'[^']+'/);
    expect(source).not.toMatch(/\$_\([^)]+\)\s*\?\?\s*"[^"]+"/);
  });

  it('component uses $_ for i18n lookups (not hardcoded fallbacks)', () => {
    // AC5: i18n lookups must use $_ function
    const source = fs.readFileSync(COMPONENT_PATH, 'utf8');
    expect(source).toContain('$_');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E64S02 — AC6: only de.json modified (not en.json)
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E64S02 AC6: de.json only scope', () => {
  it('de.json does not contain "null" as any value for E64S02 keys', () => {
    // AC6: all new keys must have real German string values (not null/empty)
    const ip = (deMessages as unknown as Record<string, Record<string, string>>).infoPortal;
    const newKeys = ['title', 'loading', 'loadError', 'statusDisabled', 'statusRegistered',
      'statusNotRegistered', 'optInButton', 'submittingOptIn', 'statusError',
      'retryButton', 'submittingRetry', 'configHint'];
    for (const key of newKeys) {
      if (ip[key] !== undefined) {
        expect(ip[key]).not.toBe('');
        expect(ip[key]).not.toBeNull();
      }
    }
  });
});
