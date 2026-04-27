/**
 * AC16 (governance): inline-literal enforcement for Svelte template blocks.
 * Scans all .svelte files for inline string literals in template blocks (not inside {…} expressions
 * or in <script> / <style> blocks) and fails the build if any are found.
 *
 * Fallback path per AC16: grep-via-npm-script that fails the build on inline-literal pattern.
 * Any text outside {…} expressions in the <template> portion of a .svelte file that is not
 * whitespace-only is flagged as a potential inline literal violation.
 *
 * Story: E38S08.
 */

import { readFileSync, readdirSync, statSync } from 'fs';
import { join } from 'path';

const SRC_DIR = new URL('../src', import.meta.url).pathname;
const violations = [];

function walkDir(dir) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walkDir(full);
    } else if (entry.endsWith('.svelte')) {
      checkFile(full);
    }
  }
}

function checkFile(filePath) {
  const content = readFileSync(filePath, 'utf-8');

  // Extract the template portion (between </script> and <style> or end of file)
  // Strip <script> blocks
  let template = content.replace(/<script[\s\S]*?<\/script>/g, '');
  // Strip <style> blocks
  template = template.replace(/<style[\s\S]*?<\/style>/g, '');
  // Strip Svelte expression blocks {…}
  template = template.replace(/\{[^}]*\}/g, '');
  // Strip HTML attribute values (class="...", data-testid="...")
  template = template.replace(/\w+="[^"]*"/g, '');
  template = template.replace(/\w+='[^']*'/g, '');
  // Strip HTML tags
  template = template.replace(/<[^>]*>/g, '');

  // What remains should be only whitespace
  const remaining = template.replace(/\s+/g, '').trim();
  if (remaining.length > 0) {
    violations.push({
      file: filePath.replace(SRC_DIR + '/', ''),
      remaining: remaining.slice(0, 80),
    });
  }
}

walkDir(SRC_DIR);

if (violations.length > 0) {
  console.error('AC16 FAIL: Inline string literals detected in Svelte template blocks:');
  for (const v of violations) {
    console.error(`  ${v.file}: "${v.remaining}"`);
  }
  process.exit(1);
} else {
  console.log('AC16 OK: No inline literals in Svelte template blocks.');
}
