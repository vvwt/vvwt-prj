/**
 * Unit tests for teamStore (E05S05 AC12 — i18n coverage).
 *
 * Verifies:
 * - AC12: de.json contains all team translation keys (i18n DoR)
 * - teamStore exports the expected API functions
 */

import { describe, expect, it } from 'vitest';
import deMessages from '../locales/de.json';

// ─────────────────────────────────────────────────────────────────────────────
// i18n coverage — AC12
// ─────────────────────────────────────────────────────────────────────────────

describe('de.json — team translations (AC12 — E05S05)', () => {
  it('should contain the teams namespace', () => {
    expect(deMessages).toHaveProperty('teams');
  });

  it('should contain all required team column headers', () => {
    // Narrow type access
    const cols = (deMessages as Record<string, Record<string, Record<string, string>>>).teams.columns;
    expect(cols).toHaveProperty('number');
    expect(cols).toHaveProperty('description');
    expect(cols).toHaveProperty('participate');
    expect(cols).toHaveProperty('refereeAssignment');
    expect(cols).toHaveProperty('withoutAssessment');
  });

  it('should contain count indicator keys', () => {
    const teams = (deMessages as Record<string, Record<string, string>>).teams;
    expect(teams).toHaveProperty('totalCount');
    expect(teams).toHaveProperty('participatingCount');
  });

  it('should contain add/save/cancel/delete action labels', () => {
    const teams = (deMessages as Record<string, Record<string, string>>).teams;
    expect(teams).toHaveProperty('addButton');
    expect(teams).toHaveProperty('saveButton');
    expect(teams).toHaveProperty('cancelButton');
    expect(teams).toHaveProperty('deleteButton');
    expect(teams).toHaveProperty('deleteConfirm');
    expect(teams).toHaveProperty('unsavedHint');
  });

  it('should contain error and empty state messages', () => {
    const teams = (deMessages as Record<string, Record<string, string>>).teams;
    expect(teams).toHaveProperty('loadError');
    expect(teams).toHaveProperty('empty');
    expect(teams).toHaveProperty('deleteError');
    expect(deMessages).toHaveProperty('teams.error.noTournament');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// Store — API function exports
// ─────────────────────────────────────────────────────────────────────────────

describe('teamStore — exported API functions (E05S05)', () => {
  it('should export all required API functions', async () => {
    const module = await import('./teamStore.ts');
    expect(typeof module.listTeams).toBe('function');
    expect(typeof module.createTeam).toBe('function');
    expect(typeof module.updateTeam).toBe('function');
    expect(typeof module.deleteTeam).toBe('function');
    expect(typeof module.bulkCreateTeams).toBe('function');
  });
});
