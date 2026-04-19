package de.vvwt.tm.domain.audio;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Domain service interface for managing tournament audio files.
 *
 * <p>Story E11S01 — AC1–AC7. All operations are scoped to a tournament that must belong to the
 * active tenant (DEC-5, DEC-17). Callers are responsible for ensuring that a valid {@link
 * de.vvwt.tm.domain.repo.TenantContext} is active before invoking any method.
 *
 * <p>Files are persisted on disk at {@code {dataDir}/audio/{tournamentId}/{category}.mp3} per AC6 /
 * DEC-15. No database table is used — persistence is purely filesystem-based.
 *
 * @see AudioStorageServiceImpl
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story
 *     E11S01</a>
 */
public interface AudioStorageService {

    /**
     * Stores (or replaces) the audio file for the given tournament and category.
     *
     * <p>AC1: Accepts a multipart {@code .mp3} upload. Uploading to a category that already has a
     * file replaces it. Returns metadata for the stored file.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @param category the audio category (START, END, PAUSE)
     * @param filename the original filename as supplied by the client (for metadata only)
     * @param inputStream the binary content of the .mp3 file
     * @param sizeBytes declared content size in bytes (used for validation against limit)
     * @return metadata describing the stored audio file
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (AC5 → 404)
     * @throws AudioFormatException if the file is not a .mp3 (AC7 → 415)
     * @throws AudioSizeLimitException if the file exceeds the configured size limit (AC7 → 413)
     * @throws AudioStorageException if a disk I/O error occurs (AC7 → 500)
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
     * <p>AC2: Returns the raw byte stream. Callers must close the stream after use. Returns an
     * empty {@link java.util.Optional} if no file exists for the category (→ 404).
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @param category the audio category (START, END, PAUSE)
     * @return the file input stream, or empty if no file is stored for that category
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (AC5 → 404)
     * @throws AudioStorageException if a disk I/O error occurs (AC7 → 500)
     */
    java.util.Optional<InputStream> stream(UUID tournamentId, AudioCategory category);

    /**
     * Returns metadata for all uploaded audio files of the given tournament.
     *
     * <p>AC3: Categories without a file are omitted from the result.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @return list of metadata; empty if no files have been uploaded
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (AC5 → 404)
     * @throws AudioStorageException if a disk I/O error occurs (AC7 → 500)
     */
    List<AudioFileMetadata> list(UUID tournamentId);

    /**
     * Removes the audio file for the given tournament and category.
     *
     * <p>AC4: Returns {@code true} if the file was deleted, {@code false} if no file existed for
     * that category (→ 404 from the controller).
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @param category the audio category (START, END, PAUSE)
     * @return {@code true} if the file was deleted; {@code false} if no file existed
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant (AC5 → 404)
     * @throws AudioStorageException if a disk I/O error occurs (AC7 → 500)
     */
    boolean delete(UUID tournamentId, AudioCategory category);
}
