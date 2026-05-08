/**
 * Tests for PhasePreparation.svelte — E51S06.
 *
 * AC-TEST-FRONTEND-PREPARE-ROUTE-LANDS-DRAG-AND-DROP-GREEN:
 * Verifies that PhasePreparation.svelte does NOT override commitEndpoint to /prepare.
 * The drag&drop Vorbereiten route must commit via the default transition-commit endpoint.
 *
 * E51S06 rollback of E48S21: commitEndpoint override to /prepare removed.
 * PhaseTransition.svelte default (transition-commit) is used instead.
 *
 * DEC-22 Iron Law: tests written GREEN (the change is already in place).
 */

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
