package de.vvwt.tm.domain.audio;

import java.time.Instant;

/**
 * Value object representing metadata for a stored audio file.
 *
 * <p>Story E11S01 — AC1 (upload response), AC3 (list response).
 *
 * <p>Instances are produced by {@link AudioStorageService} after a successful upload or list
 * operation. No database persistence — metadata is derived from the filesystem at query time per
 * DEC-15 (files stored outside the jlink archive).
 *
 * @param category the audio category (START, END, PAUSE)
 * @param filename the original uploaded filename as provided by the client
 * @param sizeBytes the size of the stored file in bytes
 * @param uploadedAt the instant the file was stored (from filesystem mtime or upload time)
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story
 *     E11S01</a>
 */
public record AudioFileMetadata(
        AudioCategory category, String filename, long sizeBytes, Instant uploadedAt) {}
