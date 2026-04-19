package de.vvwt.tm.domain.photo;

import java.time.Instant;

/**
 * Immutable value object describing a stored team photo (E12S02 AC1).
 *
 * <p>Returned by {@link PhotoStorageService#upload} and {@link PhotoStorageService#retrieve} when
 * metadata is needed without the file content itself.
 *
 * @param filename the original client filename (e.g. {@code photo.jpg})
 * @param sizeBytes the stored file size in bytes
 * @param uploadedAt the timestamp when the photo was stored (last-modified time of the file)
 * @see PhotoStorageService
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02</a>
 */
public record PhotoFileMetadata(String filename, long sizeBytes, Instant uploadedAt) {}
