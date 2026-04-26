package de.vvwt.tm.photo;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for tournament-scoped team photo storage (E36S01 Q-1a TDD rebuild).
 *
 * <p>Rebuilt from deleted Q-1b artefact at the same canonical FQN ({@code de.vvwt.tm.photo}) per
 * Brief D-7 Option γ. Interface method signatures preserved verbatim per
 * AC-C3-SIGNATURE-PRESERVATION and AC-CONSUMER-IMPORTS-UNCHANGED.
 *
 * <p>Consumers ({@code TeamPhotoController}, {@code DefaultCertificateAssembler}, {@code
 * WebModuleTestConfig}) retain their existing import statements unchanged — the FQN is identical
 * (Option γ same-FQN guarantee per Brief C-2).
 *
 * <h2>Tenant scoping (DEC-5, DEC-17)</h2>
 *
 * <p>Every method validates that the given tournament belongs to the active tenant via the
 * tenant-scoped {@link de.vvwt.tm.tournament.TournamentRepository}. Methods that also reference a
 * team validate team membership in the tournament via the tenant-scoped {@link
 * de.vvwt.tm.tournament.TeamRepository}.
 *
 * <h2>Filesystem persistence (DEC-14, DEC-15)</h2>
 *
 * <p>Photos are stored as files at {@code {dataDir}/{tournamentId}/{teamId}.{ext}}. No H2 table is
 * introduced. The data directory is configurable via {@link PhotoStorageConfig}.
 *
 * <p>Historical provenance: originally E12S02; relocated to this module by E23S01 (Q-1b); rebuilt
 * Q-1a RED-first by E36S01 per DEC-22 Iron Law + DEC-41 §3 hierarchy clause (1).
 *
 * @see de.vvwt.tm.photo.internal.DefaultPhotoStorageService
 * @see PhotoStorageConfig
 * @since E36S01
 */
public interface PhotoStorageService {

    /**
     * Stores a photo for the given team in the given tournament.
     *
     * <p>Replaces any existing photo. Returns photo metadata on success.
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @param teamId team UUID (must belong to the tournament)
     * @param filename original client filename (used for extension detection)
     * @param inputStream photo file content
     * @param sizeBytes declared file size (validated against configured limit)
     * @return metadata for the stored photo
     * @throws java.util.NoSuchElementException if tournament or team not found / wrong tenant
     * @throws PhotoSizeException if {@code sizeBytes} exceeds the configured limit
     * @throws PhotoFormatException if the filename does not end with .jpg, .jpeg, or .png
     * @throws PhotoStorageException on filesystem I/O failure
     */
    PhotoFileMetadata upload(
            UUID tournamentId,
            UUID teamId,
            String filename,
            InputStream inputStream,
            long sizeBytes);

    /**
     * Returns the stored photo for the given team if one exists.
     *
     * <p>The caller is responsible for closing the returned {@link InputStream}. Returns empty if
     * no photo has been uploaded for this team.
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @param teamId team UUID (must belong to the tournament)
     * @return the photo file content and content-type, or empty if no photo exists
     * @throws java.util.NoSuchElementException if tournament or team not found / wrong tenant
     * @throws PhotoStorageException on filesystem I/O failure
     */
    Optional<PhotoResult> retrieve(UUID tournamentId, UUID teamId);

    /**
     * Deletes the photo for the given team.
     *
     * <p>Returns {@code true} if a photo was deleted; {@code false} if no photo existed.
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @param teamId team UUID (must belong to the tournament)
     * @return {@code true} if a photo was deleted; {@code false} if none existed
     * @throws java.util.NoSuchElementException if tournament or team not found / wrong tenant
     * @throws PhotoStorageException on filesystem I/O failure
     */
    boolean delete(UUID tournamentId, UUID teamId);

    /**
     * Returns whether a photo exists for the given team in the given tournament.
     *
     * <p>Does NOT validate tournament/team existence — callers that already have validated teams
     * call this method directly for efficiency. Returns {@code false} if no file exists on disk.
     *
     * @param tournamentId tournament UUID
     * @param teamId team UUID
     * @return {@code true} if a photo file exists for this team
     */
    boolean hasPhoto(UUID tournamentId, UUID teamId);

    /**
     * Result container for a photo retrieval — file content and detected content type.
     *
     * @param inputStream the photo file content (caller must close)
     * @param contentType the MIME type (e.g. {@code image/jpeg} or {@code image/png})
     * @param metadata photo metadata (filename, size, uploadedAt)
     */
    record PhotoResult(InputStream inputStream, String contentType, PhotoFileMetadata metadata) {}
}
