package de.vvwt.tm.photo;

import java.time.Instant;

/**
 * Immutable value object describing a stored team photo (E12S02 AC1, E23S01).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.photo.PhotoFileMetadata} to the canonical {@code
 * de.vvwt.tm.photo} Modulith module per DEC-21, DEC-35 (VO in public package), and E23S01. The
 * legacy package {@code de.vvwt.tm.domain.photo} remains on the classpath until E23S05 Cutover-1.
 *
 * <p>Returned by {@link PhotoStorageService#upload} and {@link PhotoStorageService#retrieve} when
 * metadata is needed without the file content itself.
 *
 * @param filename the original client filename (e.g. {@code photo.jpg})
 * @param sizeBytes the stored file size in bytes
 * @param uploadedAt the timestamp when the photo was stored (last-modified time of the file)
 * @see PhotoStorageService
 * @since E12S02
 */
public record PhotoFileMetadata(String filename, long sizeBytes, Instant uploadedAt) {}
