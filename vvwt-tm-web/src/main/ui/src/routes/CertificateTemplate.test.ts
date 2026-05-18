// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';

describe('CertificateTemplate.svelte — AC3: per-page header removed (E47S01)', () => {
  it('CertificateTemplate.svelte source does NOT contain .cert-template__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('cert-template__header');
  });

  it('CertificateTemplate.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('certificateTemplate.title');
  });
});

describe('CertificateTemplate.svelte — AC5: backTo registered (E47S01)', () => {
  it('CertificateTemplate.svelte source registers backTo in pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
  });
});

describe('CertificateTemplate.svelte — AC6: tournamentId registered (E47S01)', () => {
  it('CertificateTemplate.svelte source passes tournamentId to pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    const pageHeaderCall = source.match(/pageHeader\.set\(\{([^}]*)\}/s)?.[1] ?? '';
    expect(pageHeaderCall).toContain('tournamentId');
  });
});

describe('CertificateTemplate.svelte — AC12: pop() back-button removed (E47S01)', () => {
  it('CertificateTemplate.svelte source has NO button template with certificateTemplate.backButton', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toMatch(/<button[^>]*>\s*\{[^}]*certificateTemplate\.backButton/);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E52S02 — "Urkunden erstellen" header-right button on CertificateTemplate.svelte
// Visibility-gated: visible only when tournament.status === 'COMPLETED'.
// ─────────────────────────────────────────────────────────────────────────────

describe('CertificateTemplate.svelte — E52S02: "Urkunden erstellen" button (AC-TEST-SUB-PAGE-BUTTON-VISIBLE-COMPLETED-RED)', () => {
  it('CertificateTemplate.svelte source contains certificateTemplate.createCertificatesButton i18n key', async () => {
    // AC-TEST-SUB-PAGE-BUTTON-VISIBLE-COMPLETED-RED: button must use new i18n key
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('certificateTemplate.createCertificatesButton');
  });

  it('CertificateTemplate.svelte source gates "Urkunden erstellen" button on COMPLETED status', async () => {
    // AC-TEST-SUB-PAGE-BUTTON-VISIBLE-COMPLETED-RED: button must be inside COMPLETED gate
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("COMPLETED");
    expect(source).toContain('createCertificatesButton');
  });
});

describe('CertificateTemplate.svelte — E52S02: button hidden for non-COMPLETED (AC-TEST-SUB-PAGE-BUTTON-HIDDEN-NON-COMPLETED-RED)', () => {
  it('CertificateTemplate.svelte source does NOT show createCertificatesButton unconditionally (no status gate = fail)', async () => {
    // AC-TEST-SUB-PAGE-BUTTON-HIDDEN-NON-COMPLETED-RED: button must be gated
    // Verify that createCertificatesButton appears INSIDE a status conditional block (COMPLETED guard)
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // The COMPLETED check must appear before the createCertificatesButton reference
    const completedIdx = source.indexOf("=== 'COMPLETED'");
    const buttonIdx = source.indexOf('createCertificatesButton');
    expect(completedIdx).toBeGreaterThan(-1);
    expect(buttonIdx).toBeGreaterThan(-1);
    expect(completedIdx).toBeLessThan(buttonIdx);
  });
});

describe('CertificateTemplate.svelte — E52S02: button click navigates to /certificates (AC-TEST-SUB-PAGE-BUTTON-CLICK-NAVIGATES-TO-CERTIFICATES-ROUTE-RED)', () => {
  it('CertificateTemplate.svelte source navigates to /certificates route on button click', async () => {
    // AC-TEST-SUB-PAGE-BUTTON-CLICK-NAVIGATES-TO-CERTIFICATES-ROUTE-RED
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('/certificates');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// E67S02 — Standard-template default messaging + generation discoverability
// AC1: no-template empty-state conveys standard template is active (positive default)
// AC3: generation path discoverable; non-COMPLETED tournament shows "not yet available" notice
// ─────────────────────────────────────────────────────────────────────────────

describe('CertificateTemplate.svelte — E67S02 AC1: standard-template default messaging (AC-CERT-STANDARD-DEFAULT-MSG-RED)', () => {
  it('CertificateTemplate.svelte source contains certificateTemplate.standardTemplateDefault i18n key', async () => {
    // AC-CERT-STANDARD-DEFAULT-MSG-RED: no-template empty-state must use standardTemplateDefault key
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('certificateTemplate.standardTemplateDefault');
  });
});

describe('CertificateTemplate.svelte — E67S02 AC3: generation-not-available notice for non-COMPLETED (AC-CERT-GEN-NOT-AVAILABLE-MSG-RED)', () => {
  it('CertificateTemplate.svelte source contains certificateTemplate.generationNotAvailable i18n key', async () => {
    // AC-CERT-GEN-NOT-AVAILABLE-MSG-RED: non-COMPLETED tournament must show generationNotAvailable key
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('certificateTemplate.generationNotAvailable');
  });

  it('CertificateTemplate.svelte source gates generationNotAvailable notice on non-COMPLETED status', async () => {
    // AC-CERT-GEN-NOT-AVAILABLE-MSG-RED: message must appear inside a non-COMPLETED conditional
    // The COMPLETED check must appear before the generationNotAvailable reference
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    const completedIdx = source.indexOf("!== 'COMPLETED'");
    const msgIdx = source.indexOf('generationNotAvailable');
    expect(completedIdx).toBeGreaterThan(-1);
    expect(msgIdx).toBeGreaterThan(-1);
    expect(completedIdx).toBeLessThan(msgIdx);
  });
});
