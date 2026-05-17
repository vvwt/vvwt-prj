// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, it, expect } from 'vitest';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// AC1 — de.json has embeddedWorker.title
// ─────────────────────────────────────────────────────────────────────────────

describe('i18n — AC1: embeddedWorker.title key in de.json (E63S05)', () => {
  it('de.json has "embeddedWorker" top-level namespace', () => {
    const d = deMessages as unknown as Record<string, unknown>;
    expect(d).toHaveProperty('embeddedWorker');
  });

  it('de.json embeddedWorker.title is defined and non-empty', () => {
    const d = deMessages as unknown as Record<string, Record<string, string>>;
    expect(d.embeddedWorker).toHaveProperty('title');
    expect(d.embeddedWorker.title.length).toBeGreaterThan(0);
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC2 — EmbeddedWorkerControl.svelte registers page title via pageHeader
// ─────────────────────────────────────────────────────────────────────────────

describe('EmbeddedWorkerControl.svelte — AC2: pageHeader registration (E63S05)', () => {
  it('EmbeddedWorkerControl.svelte source imports pageHeader store', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './EmbeddedWorkerControl.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pageHeader');
  });

  it('EmbeddedWorkerControl.svelte source uses embeddedWorker.title key', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './EmbeddedWorkerControl.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('embeddedWorker.title');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC3 — Control endpoints referenced in component
// ─────────────────────────────────────────────────────────────────────────────

describe('EmbeddedWorkerControl.svelte — AC3: control endpoints (E63S05)', () => {
  it('EmbeddedWorkerControl.svelte references /pause endpoint', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './EmbeddedWorkerControl.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('pause');
  });

  it('EmbeddedWorkerControl.svelte references /resume endpoint', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './EmbeddedWorkerControl.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('resume');
  });

  it('EmbeddedWorkerControl.svelte references /disable endpoint', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './EmbeddedWorkerControl.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('disable');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC4 — stateCode displayed in route body
// ─────────────────────────────────────────────────────────────────────────────

describe('EmbeddedWorkerControl.svelte — AC4: stateCode display (E63S05)', () => {
  it('EmbeddedWorkerControl.svelte source references stateCode field', async () => {
    const fs = await import('fs');
    const path = await import('path');
    const src = path.resolve(__dirname, './EmbeddedWorkerControl.svelte');
    const source = fs.readFileSync(src, 'utf8');
    expect(source).toContain('stateCode');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// AC5 — parentRouteMap has /embedded-worker entry
// ─────────────────────────────────────────────────────────────────────────────

describe('parentRouteMap — AC5: /embedded-worker entry (E63S05)', () => {
  it('PARENT_ROUTE_MAP has /embedded-worker key', async () => {
    const { PARENT_ROUTE_MAP } = await import('../lib/parentRouteMap.js');
    expect(PARENT_ROUTE_MAP).toHaveProperty('/embedded-worker');
  });

  it('resolveParent for /embedded-worker returns /tournaments', async () => {
    const { resolveParent } = await import('../lib/parentRouteMap.js');
    const result = resolveParent('/embedded-worker', '');
    expect(result).toBe('/tournaments');
  });
});
