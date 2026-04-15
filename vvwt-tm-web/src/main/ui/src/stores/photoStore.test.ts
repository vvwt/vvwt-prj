/**
 * Unit tests for photoStore (E12S03).
 *
 * Verifies:
 * - AC8: de.json contains all required photo translation keys (i18n DoR)
 * - photoStore exports the expected API functions
 * - getPhotoUrl produces the expected URL patterns
 */

import { describe, expect, it } from 'vitest';
import deMessages from '../locales/de.json';
import { getPhotoUrl, uploadPhoto, deletePhoto } from './photoStore.js';

// ─────────────────────────────────────────────────────────────────────────────
// i18n coverage — AC8
// ─────────────────────────────────────────────────────────────────────────────

type Messages = Record<string, unknown>;
type PhotoMessages = Record<string, string | Record<string, string>>;

describe('de.json — photo translations (AC8 — E12S03)', () => {
  const msgs = deMessages as Messages;

  it('should contain the photos namespace', () => {
    expect(msgs).toHaveProperty('photos');
  });

  it('should contain core photo action labels', () => {
    const photos = msgs.photos as PhotoMessages;
    expect(photos).toHaveProperty('title');
    expect(photos).toHaveProperty('backButton');
    expect(photos).toHaveProperty('uploadButton');
    expect(photos).toHaveProperty('replaceButton');
    expect(photos).toHaveProperty('uploadingButton');
    expect(photos).toHaveProperty('deleteButton');
    expect(photos).toHaveProperty('deleteConfirm');
  });

  it('should contain summary and count label', () => {
    const photos = msgs.photos as PhotoMessages;
    expect(photos).toHaveProperty('summary');
    expect(photos).toHaveProperty('empty');
  });

  it('should contain preview labels', () => {
    const photos = msgs.photos as PhotoMessages;
    expect(photos).toHaveProperty('previewHint');
    expect(photos).toHaveProperty('previewLabel');
    expect(photos).toHaveProperty('previewAlt');
    expect(photos).toHaveProperty('previewCloseButton');
    expect(photos).toHaveProperty('noPhotoLabel');
  });

  it('should contain error messages (AC7, AC8)', () => {
    const photos = msgs.photos as PhotoMessages;
    expect(photos).toHaveProperty('uploadError');
    expect(photos).toHaveProperty('uploadFormatError');
    expect(photos).toHaveProperty('deleteError');
    const errorNs = photos.error as Record<string, string>;
    expect(errorNs).toHaveProperty('noTournament');
  });

  it('should contain the teamPhotosButton key in tournaments namespace (AC8 — nav)', () => {
    const tournaments = msgs.tournaments as PhotoMessages;
    expect(tournaments).toHaveProperty('teamPhotosButton');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// photoStore — getPhotoUrl
// ─────────────────────────────────────────────────────────────────────────────

describe('photoStore — getPhotoUrl (E12S03)', () => {
  const TOURNAMENT = 'aaaa-bbbb';
  const TEAM = 'cccc-dddd';

  it('returns the base URL without cache bust when bust is omitted', () => {
    const url = getPhotoUrl(TOURNAMENT, TEAM);
    expect(url).toBe(`/api/tournaments/${TOURNAMENT}/teams/${TEAM}/photo`);
  });

  it('appends a ?t= query parameter when bust value is provided', () => {
    const url = getPhotoUrl(TOURNAMENT, TEAM, 12345);
    expect(url).toBe(`/api/tournaments/${TOURNAMENT}/teams/${TEAM}/photo?t=12345`);
  });

  it('handles different tournamentId and teamId values', () => {
    const url = getPhotoUrl('t1', 'tm2');
    expect(url).toContain('/api/tournaments/t1/teams/tm2/photo');
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// photoStore — exported API function shapes
// ─────────────────────────────────────────────────────────────────────────────

describe('photoStore — exported API functions (E12S03)', () => {
  it('should export uploadPhoto as a function', () => {
    expect(typeof uploadPhoto).toBe('function');
  });

  it('should export deletePhoto as a function', () => {
    expect(typeof deletePhoto).toBe('function');
  });

  it('should export getPhotoUrl as a function', () => {
    expect(typeof getPhotoUrl).toBe('function');
  });
});
