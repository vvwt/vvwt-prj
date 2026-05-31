// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// E70S02 — jscpd duplicate-detection gate script for Mustache templates.
// Invoked via `npm run check-jscpd-mustache` in vvwt-tm-web/src/main/ui/package.json
// and bound to Maven `verify` phase via frontend-maven-plugin.
// Implements DEC-78 Layer-C hard-gated DRY-lexical check per DEC-54.
//
// H-3 (DEC-78): jscpd v4.2.4 does not map .mustache extension by default.
// Using `formatsExts: { "handlebars": ["mustache"] }` in the .jscpd.json config
// maps the handlebars tokenizer to .mustache files. Empirically verified GO
// (2026-05-31 E70S02 delivery). See impl-report E70S02 §H-3.
//
// jscpd v4.2.4 always exits 0 regardless of found clones; this script
// parses the output and exits 1 when any unignored clone is detected.

import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname = dirname(fileURLToPath(import.meta.url));
// templates/ directory (parent of scripts/)
const rootDir = join(__dirname, '..');

let output;
try {
  // Scan all mustache templates. Config .jscpd.json is in templates/ root.
  output = execSync('npx jscpd --config .jscpd.json .', {
    cwd: rootDir,
    encoding: 'utf8',
    stdio: ['inherit', 'pipe', 'pipe'],
  });
} catch (err) {
  process.stderr.write(String(err.stderr ?? err.message) + '\n');
  process.exit(1);
}

process.stdout.write(output);

const match = output.match(/Found (\d+) clones?\./);
if (!match) {
  process.stderr.write('ERROR: jscpd output did not contain expected "Found N clones." summary.\n');
  process.exit(1);
}

const cloneCount = parseInt(match[1], 10);
if (cloneCount > 0) {
  process.stderr.write(
    `\nBUILD FAILURE: jscpd found ${cloneCount} Mustache clone(s) above the configured baseline.\n` +
    'Add entries to templates/.jscpd.json "ignore" to suppress pre-existing findings,\n' +
    'or refactor the duplicated Mustache templates. See DEC-78 §Clause E.\n'
  );
  process.exit(1);
}

process.stdout.write('jscpd: no new Mustache duplicates detected. DRY-lexical gate PASS.\n');
process.exit(0);
