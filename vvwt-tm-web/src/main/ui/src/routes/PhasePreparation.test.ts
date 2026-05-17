// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
import { describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

const __dirname_local = path.dirname(new URL(import.meta.url).pathname);

describe('PhasePreparation.svelte — E51S06 rollback (AC-TEST-FRONTEND-PREPARE-ROUTE-LANDS-DRAG-AND-DROP-GREEN)', () => {
    const svelteSource = fs.readFileSync(
        path.resolve(__dirname_local, 'PhasePreparation.svelte'),
        'utf-8'
    );

    it('does NOT override commitEndpoint (no /prepare endpoint wired)', () => {
        // E51S06 rollback: commitEndpoint prop must NOT be passed from PhasePreparation.svelte
        // The default endpoint in PhaseTransition.svelte (/api/phases/{id}/transition-commit) applies.
        expect(svelteSource).not.toContain('commitEndpoint=');
    });

    it('still sets pageTitleKey to phases.prepareTitle', () => {
        expect(svelteSource).toContain('pageTitleKey="phases.prepareTitle"');
    });

    it('does not reference /prepare endpoint', () => {
        // The /prepare endpoint should no longer be referenced in the component
        expect(svelteSource).not.toContain('/prepare');
    });
});
