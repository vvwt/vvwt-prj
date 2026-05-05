/**
 * Tests for TeamPhotos route — Story E47S01.
 *
 * AC3: No per-page .team-photos__header; title registered via pageHeader store.
 * AC5: Back-arrow registered.
 * AC6: tournamentId registered for tournament name.
 * AC12: No pop()-based back-button in template.
 *
 * RED-first per DEC-22: tests fail before migration.
 */

import { describe, it, expect } from 'vitest';

describe('TeamPhotos.svelte — AC3: per-page header removed (E47S01)', () => {
  it('TeamPhotos.svelte source does NOT contain .team-photos__header class', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TeamPhotos.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toContain('team-photos__header');
  });

  it('TeamPhotos.svelte source registers title via pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TeamPhotos.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
    expect(source).toContain('photos.title');
  });
});

describe('TeamPhotos.svelte — AC5: backTo registered (E47S01)', () => {
  it('TeamPhotos.svelte source registers backTo in pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TeamPhotos.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('backTo');
  });
});

describe('TeamPhotos.svelte — AC6: tournamentId registered (E47S01)', () => {
  it('TeamPhotos.svelte source passes tournamentId to pageHeader', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TeamPhotos.svelte');
    const source = fs.readFileSync(src, 'utf8');
    const pageHeaderCall = source.match(/pageHeader\.set\(\{([^}]*)\}/s)?.[1] ?? '';
    expect(pageHeaderCall).toContain('tournamentId');
  });
});

describe('TeamPhotos.svelte — AC12: pop() back-button removed (E47S01)', () => {
  it('TeamPhotos.svelte source has NO button template with photos.backButton', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './TeamPhotos.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).not.toMatch(/<button[^>]*>\s*\{[^}]*photos\.backButton/);
  });
});
