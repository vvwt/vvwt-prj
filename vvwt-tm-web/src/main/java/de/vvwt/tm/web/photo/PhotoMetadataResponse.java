package de.vvwt.tm.web.photo;

import de.vvwt.tm.photo.PhotoFileMetadata;
import java.time.Instant;

/**
 * REST response DTO for photo upload metadata (E23S04, DEC-40 Clause B).
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.infrastructure.web.photo.PhotoMetadataResponse}
 * to {@code de.vvwt.tm.web.photo} per DEC-40 Clause D (Primary-Adapter-Isolation) and DEC-22
 * §refactor-clause (Q-1b whole-class relocation, byte-identical content). Preserved as web-internal
 * DTO on DEC-40 Clause B(a) field-omission grounds: {@link PhotoFileMetadata} (the domain VO)
 * contains tenant-scoped or internal fields not exposed in the JSON response.
 *
 * <p>Returned by {@code POST /api/tournaments/{tournamentId}/teams/{teamId}/photo} on success (HTTP
 * 200).
 *
 * @param filename original client filename
 * @param sizeBytes stored file size in bytes
 * @param uploadedAt timestamp when the photo was stored
 * @see TeamPhotoController
 * @see PhotoFileMetadata
 * @see DEC-40
 * @see E23S04
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
