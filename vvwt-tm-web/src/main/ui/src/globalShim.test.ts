// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Absolute path of this file's directory is used to resolve vite.config.ts
 * in the same SPA root. Works in any checkout, no hardcoded absolute paths.
 *
 * __dirname equivalent in ESM: import.meta.url → URL → pathname.
 */
const SPA_ROOT = resolve(new URL(import.meta.url).pathname, '../..');

function readViteConfig(spaRoot: string): string {
  return readFileSync(resolve(spaRoot, 'vite.config.ts'), 'utf-8');
}

describe('vite.config.ts — global→globalThis shim (E18S03 / AC2 / AC4 / AC5)', () => {
  const config = readViteConfig(SPA_ROOT);

  it('AC2: contains a define block mapping global to globalThis', () => {
    // The value may be quoted as 'globalThis' or "globalThis" — both are valid.
    // The key must be exactly `global` (case-sensitive).
    expect(config).toMatch(/define\s*:\s*\{[^}]*\bglobal\b\s*:\s*['"]globalThis['"]/s);
  });

  it('AC4: contains an explanatory comment about sockjs-client and Vite not auto-shimming global', () => {
    // Comment must mention both sockjs-client and the reason (Vite, auto-shim, or equivalent).
    expect(config).toMatch(/sockjs-client/i);
    expect(config).toMatch(/global/i);
    // The comment must convey that removing the entry will re-break the SPAs.
    expect(config).toMatch(/shim|re-break|re.break|removing|remove/i);
  });

  it('AC5: the define entry is present (regression guard — absence causes ReferenceError: global is not defined)', () => {
    // This is the direct regression guard. If this assertion fails, the bundle
    // produced by `vite build` will contain unguarded `global.*` references
    // (e.g. global.WebSocket, global.XMLHttpRequest from sockjs-client@1.6.1)
    // that throw ReferenceError in any browser environment.
    expect(config).toContain("global: 'globalThis'");
  });
});
