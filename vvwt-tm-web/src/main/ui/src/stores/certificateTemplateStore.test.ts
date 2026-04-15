/**
 * Unit tests for certificateTemplateStore (E12S05).
 *
 * Verifies:
 * - AC8: de.json contains all required certificateTemplate translation keys (i18n DoR)
 * - certificateTemplateStore exports the expected API functions
 * - tournaments namespace contains the certificateTemplateButton key
 */

import { describe, expect, it } from 'vitest';
import deMessages from '../locales/de.json';
import {
  getTemplateMetadata,
  uploadTemplate,
  deleteTemplate,
  listVariables,
} from './certificateTemplateStore.js';

// ─────────────────────────────────────────────────────────────────────────────
// i18n coverage — AC8
// ─────────────────────────────────────────────────────────────────────────────

type Messages = Record<string, unknown>;
type CertMessages = Record<string, string | Record<string, string>>;

describe('de.json — certificateTemplate translations (AC8 — E12S05)', () => {
  const msgs = deMessages as Messages;

  it('should contain the certificateTemplate namespace', () => {
    expect(msgs).toHaveProperty('certificateTemplate');
  });

  it('should contain core template action labels', () => {
    const ct = msgs.certificateTemplate as CertMessages;
    expect(ct).toHaveProperty('title');
    expect(ct).toHaveProperty('backButton');
    expect(ct).toHaveProperty('uploadButton');
    expect(ct).toHaveProperty('replaceButton');
    expect(ct).toHaveProperty('uploadingButton');
    expect(ct).toHaveProperty('deleteButton');
    expect(ct).toHaveProperty('deleteConfirm');
    expect(ct).toHaveProperty('replaceConfirm');
  });

  it('should contain status and metadata labels (AC2)', () => {
    const ct = msgs.certificateTemplate as CertMessages;
    expect(ct).toHaveProperty('noTemplate');
    expect(ct).toHaveProperty('metaFilename');
    expect(ct).toHaveProperty('metaFormat');
    expect(ct).toHaveProperty('metaUploadedAt');
    expect(ct).toHaveProperty('metaFileSize');
    expect(ct).toHaveProperty('sizeLabel');
  });

  it('should contain preview labels (AC6)', () => {
    const ct = msgs.certificateTemplate as CertMessages;
    expect(ct).toHaveProperty('previewTitle');
    expect(ct).toHaveProperty('previewError');
  });

  it('should contain variable reference labels (AC5)', () => {
    const ct = msgs.certificateTemplate as CertMessages;
    expect(ct).toHaveProperty('variablesTitle');
    expect(ct).toHaveProperty('variablesLoadError');
    const cols = ct.columns as Record<string, string>;
    expect(cols).toHaveProperty('variable');
    expect(cols).toHaveProperty('type');
    expect(cols).toHaveProperty('example');
  });

  it('should contain error messages (AC7, AC8)', () => {
    const ct = msgs.certificateTemplate as CertMessages;
    expect(ct).toHaveProperty('uploadError');
    expect(ct).toHaveProperty('deleteError');
    expect(ct).toHaveProperty('loadError');
    const errorNs = ct.error as Record<string, string>;
    expect(errorNs).toHaveProperty('noTournament');
  });

  it('should contain the certificateTemplateButton key in tournaments namespace (AC8 — nav)', () => {
    const tournaments = msgs.tournaments as CertMessages;
    expect(tournaments).toHaveProperty('certificateTemplateButton');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// certificateTemplateStore — exported API function shapes
// ─────────────────────────────────────────────────────────────────────────────

describe('certificateTemplateStore — exported API functions (E12S05)', () => {
  it('should export getTemplateMetadata as a function', () => {
    expect(typeof getTemplateMetadata).toBe('function');
  });

  it('should export uploadTemplate as a function', () => {
    expect(typeof uploadTemplate).toBe('function');
  });

  it('should export deleteTemplate as a function', () => {
    expect(typeof deleteTemplate).toBe('function');
  });

  it('should export listVariables as a function', () => {
    expect(typeof listVariables).toBe('function');
  });
});
