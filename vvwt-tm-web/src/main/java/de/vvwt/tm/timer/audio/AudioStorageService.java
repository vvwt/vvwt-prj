// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.audio;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Public service port for audio file storage within the {@code timer.audio} sub-package.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.AudioStorageService} per DEC-21 + DEC-35 module
 * layout. The {@code timer.audio} package is a public sub-package of the {@code timer} Modulith
 * module (not a separate Modulith module) per user decision option A (2026-04-27). Accessible to
 * external consumers via the {@code "timer"} entry in their {@code allowedDependencies}.
 *
 * <p>4-method interface preserved verbatim per C-3 signature-preservation. Implementation: {@link
 * de.vvwt.tm.timer.audio.internal.DefaultAudioStorageService} in {@code timer.audio.internal} per
 * DEC-35 naming canon (no {@code Impl} suffix — {@code Default*} canonical name).
 *
 * <p>All operations are scoped to a tournament belonging to the active tenant (DEC-5, DEC-17). No
 * database table is used — persistence is purely filesystem-based per DEC-15 + E11S01 AC6.
 *
 * <p><strong>Pattern A (DEC-40 §2026-04-27 Clarification):</strong> {@link AudioFileMetadata}
 * serves both as the service-method projection and as the HTTP wire shape. The legacy {@code
 * AudioMetadataResponse} 1:1 wrapper is DELETED (E26S02) — projection == wire shape.
 *
 * @see de.vvwt.tm.timer.audio.internal.DefaultAudioStorageService
 * @see de.vvwt.tm.timer.internal.DefaultTimerDataService
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02</a>
 */
public interface AudioStorageService {

    /**
     * Stores (or replaces) the audio file for the given tournament and category.
     *
     * <p>Uploading to a category that already has a file replaces it. Returns metadata for the
     * stored file (also the HTTP wire shape per Pattern A).
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @param category the audio category (START, END, PAUSE)
     * @param filename the original filename as supplied by the client (for metadata only)
     * @param inputStream the binary content of the .mp3 file
     * @param sizeBytes declared content size in bytes (used for size limit validation)
     * @return metadata describing the stored audio file
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (→ 404)
     * @throws AudioFormatException if the file is not a .mp3 (→ 415)
     * @throws AudioSizeLimitException if the file exceeds the configured size limit (→ 413)
     * @throws AudioStorageException if a disk I/O error occurs (→ 500)
     */
    AudioFileMetadata upload(
            UUID tournamentId,
            AudioCategory category,
            String filename,
            InputStream inputStream,
            long sizeBytes);

    /**
     * Opens a stream for reading the audio file for the given tournament and category.
     *
     * <p>Returns an empty {@link Optional} if no file exists for the category. The caller is
     * responsible for closing the returned stream.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @param category the audio category (START, END, PAUSE)
     * @return the file input stream, or empty if no file is stored for that category
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (→ 404)
     * @throws AudioStorageException if a disk I/O error occurs (→ 500)
     */
    Optional<InputStream> stream(UUID tournamentId, AudioCategory category);

    /**
     * Returns metadata for all uploaded audio files of the given tournament.
     *
     * <p>Categories without a file are omitted from the result. Return type is also the HTTP wire
     * shape per Pattern A (DEC-40 §2026-04-27 Clarification).
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @return list of metadata; empty if no files have been uploaded
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (→ 404)
     * @throws AudioStorageException if a disk I/O error occurs (→ 500)
     */
    List<AudioFileMetadata> list(UUID tournamentId);

    /**
     * Removes the audio file for the given tournament and category.
     *
     * <p>Returns {@code true} if the file was deleted, {@code false} if no file existed for that
     * category.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @param category the audio category (START, END, PAUSE)
     * @return {@code true} if the file was deleted; {@code false} if no file existed
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (→ 404)
     * @throws AudioStorageException if a disk I/O error occurs (→ 500)
     */
    boolean delete(UUID tournamentId, AudioCategory category);
}
