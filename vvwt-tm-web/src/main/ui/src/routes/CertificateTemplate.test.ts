/**
 * Tests for CertificateTemplate route — Story E47S01.
 *
 * AC3: No per-page .cert-template__header; title registered via pageHeader store.
 * AC5: Back-arrow registered.
 * AC6: tournamentId registered for tournament name.
 * AC12: No pop()-based back-button in template.
 *
 * RED-first per DEC-22: tests fail before migration.
 */

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
