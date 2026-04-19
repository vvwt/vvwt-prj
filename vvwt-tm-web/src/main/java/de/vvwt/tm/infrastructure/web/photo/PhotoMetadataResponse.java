package de.vvwt.tm.infrastructure.web.photo;

import de.vvwt.tm.domain.photo.PhotoFileMetadata;
import java.time.Instant;

/**
 * REST response DTO for photo upload metadata (E12S02 AC1).
 *
 * <p>Returned by {@code POST /api/tournaments/{tournamentId}/teams/{teamId}/photo} on success (HTTP
 * 200).
 *
 * @param filename original client filename
 * @param sizeBytes stored file size in bytes
 * @param uploadedAt timestamp when the photo was stored
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02</a>
 */
public record PhotoMetadataResponse(String filename, long sizeBytes, Instant uploadedAt) {

    /**
     * Maps a {@link PhotoFileMetadata} domain value to a response DTO.
     *
     * @param metadata the domain metadata (must not be {@code null})
     * @return the corresponding response DTO
     */
    public static PhotoMetadataResponse from(PhotoFileMetadata metadata) {
        return new PhotoMetadataResponse(
                metadata.filename(), metadata.sizeBytes(), metadata.uploadedAt());
    }
}
