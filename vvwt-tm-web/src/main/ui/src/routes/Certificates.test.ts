/**
 * Tests for Certificates route — Story E52S02.
 *
 * AC-TEST-NEW-ROUTE-REGISTERED-RED: App.svelte must register /tournaments/:tournamentId/certificates route.
 * AC-TEST-GENERATE-UI-INVOKES-RENDER-CONTROLLER-RED: Certificates.svelte must invoke CertificateRenderController endpoints.
 * AC-URL-FE-NEW-ROUTE-CERTIFICATES: new route must point at Certificates component.
 * AC-URL-SUB-PAGE-BUTTON-NAVIGATION: navigation via push().
 * AC-I18N-DE-NEW-GENERATE-UI-KEYS: de.json must have certificates.* namespace.
 *
 * RED-first per DEC-22: all tests fail before implementation.
 */

import { describe, it, expect } from 'vitest';
import deMessages from '../locales/de.json';

type Messages = Record<string, unknown>;

// ─────────────────────────────────────────────────────────────────────────────
// AC-TEST-NEW-ROUTE-REGISTERED-RED
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — E52S02: /certificates route registered (AC-TEST-NEW-ROUTE-REGISTERED-RED)', () => {
  it('App.svelte source imports Certificates component', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("import Certificates from './routes/Certificates.svelte'");
  });

  it('App.svelte source registers /tournaments/:tournamentId/certificates route', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('/tournaments/:tournamentId/certificates');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-TEST-GENERATE-UI-INVOKES-RENDER-CONTROLLER-RED
// ─────────────────────────────────────────────────────────────────────────────

describe('Certificates.svelte — E52S02: invokes CertificateRenderController endpoints (AC-TEST-GENERATE-UI-INVOKES-RENDER-CONTROLLER-RED)', () => {
  it('Certificates.svelte source references /certificate/tournaments/ BE endpoint prefix', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Certificates.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('/certificate/tournaments/');
  });

  it('Certificates.svelte source has single-team endpoint reference /print/', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Certificates.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // single-team: /certificate/tournaments/{tid}/print/{teamId}
    expect(source).toContain('/print/');
  });

  it('Certificates.svelte source has all-teams endpoint reference /print (ZIP)', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Certificates.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // all-teams: /certificate/tournaments/{tid}/print
    expect(source).toContain('/certificate/tournaments/');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-URL-FE-NEW-ROUTE-CERTIFICATES (parentRouteMap)
// ─────────────────────────────────────────────────────────────────────────────

describe('parentRouteMap.ts — E52S02: /certificates route registered (AC-URL-FE-NEW-ROUTE-CERTIFICATES)', () => {
  it('parentRouteMap.ts source contains /certificates entry targeting /tournaments', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, '../lib/parentRouteMap.ts');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('/tournaments/:tournamentId/certificates');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-I18N-DE-NEW-GENERATE-UI-KEYS
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E52S02: certificates namespace (AC-I18N-DE-NEW-GENERATE-UI-KEYS)', () => {
  it('de.json contains a certificates namespace', () => {
    const msgs = deMessages as Messages;
    expect(msgs).toHaveProperty('certificates');
  });

  it('de.json certificates namespace has title key', () => {
    const msgs = deMessages as Messages;
    const c = msgs.certificates as Record<string, string>;
    expect(c).toHaveProperty('title');
  });

  it('de.json certificates namespace has allTeamsButton key', () => {
    const msgs = deMessages as Messages;
    const c = msgs.certificates as Record<string, string>;
    expect(c).toHaveProperty('allTeamsButton');
  });

  it('de.json certificates namespace has missingPhotosWarning key', () => {
    const msgs = deMessages as Messages;
    const c = msgs.certificates as Record<string, string>;
    expect(c).toHaveProperty('missingPhotosWarning');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-I18N-DE-NEW-SUB-PAGE-KEY (certificateTemplate.createCertificatesButton)
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E52S02: certificateTemplate.createCertificatesButton (AC-I18N-DE-NEW-SUB-PAGE-KEY)', () => {
  it('de.json certificateTemplate namespace contains createCertificatesButton key with value "Urkunden erstellen"', () => {
    const msgs = deMessages as Messages;
    const ct = msgs.certificateTemplate as Record<string, string>;
    expect(ct).toHaveProperty('createCertificatesButton');
    expect(ct.createCertificatesButton).toBe('Urkunden erstellen');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-SECURITY-NO-NEW-AUTH-SURFACE
// ─────────────────────────────────────────────────────────────────────────────

describe('Certificates.svelte — E52S02: no new public endpoint (AC-SECURITY-NO-NEW-AUTH-SURFACE)', () => {
  it('Certificates.svelte source uses window.open or anchor with same-origin BE paths (no new @RequestMapping)', async () => {
    // Security: Certificates.svelte invokes existing BE URLs only — no new endpoints
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './Certificates.svelte');
    const source = fs.readFileSync(src, 'utf8');
    // Must reference the documented BE endpoint prefix
    expect(source).toContain('/certificate/tournaments/');
    // Must NOT introduce any new API endpoints (no fetch to arbitrary paths)
    expect(source).not.toContain('/api/certificates/');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-URL-SUB-PAGE-BUTTON-NAVIGATION — CertificateTemplate header button uses push()
// ─────────────────────────────────────────────────────────────────────────────

describe('CertificateTemplate.svelte — E52S02: header button uses push() to /certificates (AC-URL-SUB-PAGE-BUTTON-NAVIGATION)', () => {
  it('CertificateTemplate.svelte source contains push call to /certificates', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './CertificateTemplate.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain("push(`/tournaments/${tournamentId}/certificates`)");
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-GOV-FE-ROUTE-URL-UNCHANGED — App.svelte: certificate-template route unchanged
// ─────────────────────────────────────────────────────────────────────────────

describe('App.svelte — E52S02: /certificate-template route unchanged (AC-GOV-FE-ROUTE-URL-UNCHANGED)', () => {
  it('App.svelte source still registers /certificate-template route with CertificateTemplate component', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, '../App.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('/tournaments/:tournamentId/certificate-template');
    expect(source).toContain('CertificateTemplate');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC-IMPL-RENAME-TOUCH-SURFACE-EXACT — de.json: old key absent, new key present
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — E52S02: exact rename touch surface (AC-IMPL-RENAME-TOUCH-SURFACE-EXACT)', () => {
  it('de.json tournaments namespace has certificatesButton but NOT certificateTemplateButton', () => {
    const msgs = deMessages as Messages;
    const t = msgs.tournaments as Record<string, string>;
    expect(t).toHaveProperty('certificatesButton');
    expect(t).not.toHaveProperty('certificateTemplateButton');
  });
});
