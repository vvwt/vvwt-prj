package de.vvwt.tm.photo;

import java.time.Instant;

/**
 * Immutable value object describing a stored team photo (E36S01 Q-1a TDD rebuild).
 *
 * <p>Rebuilt from deleted Q-1b artefact at the same canonical FQN ({@code de.vvwt.tm.photo})
 * per Brief D-7 Option γ. Signature preserved verbatim per AC-RECORD-FIELDS-PRESERVED:
 * {@code (String filename, long sizeBytes, Instant uploadedAt)}.
 *
 * <p>Returned by {@link PhotoStorageService#upload} and included in
 * {@link PhotoStorageService.PhotoResult} from {@link PhotoStorageService#retrieve}.
 *
 * <p>Historical provenance: originally E12S02; relocated to this module by E23S01 (Q-1b);
 * rebuilt Q-1a RED-first by E36S01 per DEC-22 Iron Law + DEC-41 §3 hierarchy clause (1).
 *
 * @param filename the original client filename (e.g. {@code photo.jpg})
 * @param sizeBytes the stored file size in bytes
 * @param uploadedAt the timestamp when the photo was stored (last-modified time of the file)
 * @see PhotoStorageService
 * @since E36S01
 */
public record PhotoFileMetadata(String filename, long sizeBytes, Instant uploadedAt) {}
