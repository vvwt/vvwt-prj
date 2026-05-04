/**
 * Regression test for E18S03 — Vite global→globalThis shim.
 *
 * Root cause: sockjs-client@1.6.1 references the Node.js built-in `global`
 * identifier at module-evaluation time. Vite ≥ 5 does NOT auto-shim `global`,
 * so loading any SPA entry bundle that transitively imports sockjs-client
 * throws `ReferenceError: global is not defined` in browsers.
 *
 * Fix: add `define: { global: 'globalThis' }` to each of the three
 * vite.config.ts files. Vite substitutes every bare `global` reference in the
 * bundle with `globalThis`, which is a valid browser global.
 *
 * DEC-22 / AC5 compliance: this test encodes the failure mode
 * "loading the entry bundle in a browser-like environment throws
 * ReferenceError: global is not defined". It uses a build-config assertion
 * approach (config file content check) rather than a full bundle evaluation,
 * because:
 *   1. Building the bundle in a test context requires a full `vite build` run
 *      (expensive, not hermetic in unit-test scope).
 *   2. The `define` entry in vite.config.ts IS the preventive mechanism;
 *      its presence directly controls whether the shim is emitted into the
 *      bundle at build time.
 *   3. AC3 (symmetric form) and AC4 (explanatory comment) are also verifiable
 *      from the config file content, keeping all regression checks co-located.
 *
 * RED-first evidence: run this test BEFORE adding the `define` block to
 * vite.config.ts — all three assertions fail. Add the block → GREEN.
 * The impl-report documents the RED→GREEN transcript.
 */

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
