// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// E70S02 — jscpd duplicate-detection gate script for the admin SPA.
// Invoked via `npm run check-jscpd` (package.json) and bound to Maven `verify`
// phase via frontend-maven-plugin. Implements DEC-78 Layer-C hard-gated
// DRY-lexical check per DEC-54 (mvn verify canonical target).
//
// jscpd v4.2.4 always exits 0 regardless of found clones; this script
// parses the output and exits 1 when any unignored clone is detected.
// See impl-report E70S02 §H-2 (Svelte GO) and §H-3 (Mustache formatsExts GO).

import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
const rootDir = join(__dirname, '..');

let output;
try {
  output = execSync('npx jscpd --config .jscpd.json src/', {
    cwd: rootDir,
    encoding: 'utf8',
    stdio: ['inherit', 'pipe', 'pipe'],
  });
  // Merge stderr into output (jscpd writes clone details to stdout)
} catch (err) {
  // execSync throws on non-zero exit; jscpd always exits 0, so this is
  // for genuine errors (npx fails, jscpd not found, etc.)
  process.stderr.write(String(err.stderr ?? err.message) + '\n');
  process.exit(1);
}

process.stdout.write(output);

// Parse the "Found N clones." line to determine build status.
// "Found 0 clones." → PASS (build succeeds)
// "Found N clones." (N > 0) → FAIL (build fails; new duplication detected)
const match = output.match(/Found (\d+) clones?\./);
if (!match) {
  // No summary line found — treat as error (unexpected output format)
  process.stderr.write('ERROR: jscpd output did not contain expected "Found N clones." summary.\n');
  process.exit(1);
}

const cloneCount = parseInt(match[1], 10);
if (cloneCount > 0) {
  process.stderr.write(
    `\nBUILD FAILURE: jscpd found ${cloneCount} clone(s) above the configured baseline.\n` +
    'Add entries to .jscpd.json "ignore" to suppress pre-existing findings,\n' +
    'or refactor the duplicated code. See DEC-78 §Clause E (suppress-with-tracking).\n'
  );
  process.exit(1);
}

process.stdout.write('jscpd: no new duplicates detected. DRY-lexical gate PASS.\n');
process.exit(0);
