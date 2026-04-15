package de.vvwt.tm.domain.photo;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for tournament-scoped team photo storage (E12S02).
 *
 * <h2>Tenant scoping (AC10, DEC-5, DEC-17)</h2>
 * <p>Every method validates that the given tournament belongs to the active tenant
 * via the tenant-scoped {@link de.vvwt.tm.domain.repo.TournamentRepository}. Methods that
 * also reference a team validate team membership in the tournament via the tenant-scoped
 * {@link de.vvwt.tm.domain.repo.TeamRepository}.
 *
 * <h2>Filesystem persistence (AC5, DEC-14, DEC-15)</h2>
 * <p>Photos are stored as files at
 * {@code {dataDir}/{tournamentId}/{teamId}.{ext}} per AC5. No H2 table is introduced.
 * The data directory is configurable via {@link PhotoStorageConfig} per DEC-15.
 *
 * @see PhotoStorageServiceImpl
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story E12S02</a>
 */
public interface PhotoStorageService {

    /**
     * Stores a photo for the given team in the given tournament.
     *
     * <p>AC1: Replaces any existing photo. Returns photo metadata on success.
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @param teamId       team UUID (must belong to the tournament)
     * @param filename     original client filename (used for extension detection)
     * @param inputStream  photo file content
     * @param sizeBytes    declared file size (validated against configured limit)
     * @return metadata for the stored photo
     * @throws java.util.NoSuchElementException if tournament or team not found / wrong tenant
     * @throws PhotoSizeException               if {@code sizeBytes} exceeds the configured limit (AC7)
     * @throws PhotoFormatException             if the filename does not end with .jpg, .jpeg, or .png (AC7)
     * @throws PhotoStorageException            on filesystem I/O failure (AC8)
     */
    PhotoFileMetadata upload(UUID tournamentId, UUID teamId,
                             String filename, InputStream inputStream, long sizeBytes);

    /**
     * Returns the stored photo for the given team if one exists.
     *
     * <p>AC2: The caller is responsible for closing the returned {@link InputStream}.
     * Returns empty if no photo has been uploaded for this team.
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @param teamId       team UUID (must belong to the tournament)
     * @return the photo file content and content-type, or empty if no photo exists
     * @throws java.util.NoSuchElementException if tournament or team not found / wrong tenant
     * @throws PhotoStorageException            on filesystem I/O failure
     */
    Optional<PhotoResult> retrieve(UUID tournamentId, UUID teamId);

    /**
     * Deletes the photo for the given team.
     *
     * <p>AC3: Returns {@code true} if a photo was deleted; {@code false} if no photo existed.
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @param teamId       team UUID (must belong to the tournament)
     * @return {@code true} if a photo was deleted; {@code false} if none existed
     * @throws java.util.NoSuchElementException if tournament or team not found / wrong tenant
     * @throws PhotoStorageException            on filesystem I/O failure
     */
    boolean delete(UUID tournamentId, UUID teamId);

    /**
     * Returns whether a photo exists for the given team in the given tournament.
     *
     * <p>AC4: Used by the team-listing endpoint to populate {@code hasPhoto} without
     * fetching each photo individually. Does NOT validate tournament/team existence —
     * callers that already have validated teams call this method directly for efficiency.
     * Returns {@code false} if no file exists on disk.
     *
     * @param tournamentId tournament UUID
     * @param teamId       team UUID
     * @return {@code true} if a photo file exists for this team
     */
    boolean hasPhoto(UUID tournamentId, UUID teamId);

    /**
     * Result container for a photo retrieval — file content and detected content type.
     *
     * @param inputStream the photo file content (caller must close)
     * @param contentType the MIME type (e.g. {@code image/jpeg} or {@code image/png})
     * @param metadata    photo metadata (filename, size, uploadedAt)
     */
    record PhotoResult(InputStream inputStream, String contentType, PhotoFileMetadata metadata) {}
}
