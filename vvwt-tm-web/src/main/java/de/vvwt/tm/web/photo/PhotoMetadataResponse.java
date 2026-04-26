package de.vvwt.tm.web.photo;

import de.vvwt.tm.photo.PhotoFileMetadata;
import java.time.Instant;

/**
 * REST response DTO for photo upload metadata (E36S02 Q-1a TDD rebuild, DEC-40 Clause B).
 *
 * <p>Rebuilt from deleted Q-1b artefact at the same canonical FQN ({@code de.vvwt.tm.web.photo})
 * per Brief D-7 Option γ and DEC-22 Iron Law Q-1a RED-first TDD. Wire format preserved verbatim per
 * AC-C3-SIGNATURE-PRESERVATION and AC-MOCKMVC-CONTRACT-PRESERVED: fields {@code filename}, {@code
 * sizeBytes}, and {@code uploadedAt} are identical to the legacy DTO shape.
 *
 * <p>Preserved as web-internal DTO on DEC-40 Clause B(a) field-omission grounds: {@link
 * PhotoFileMetadata} (the domain VO) contains tenant-scoped or internal fields not exposed in the
 * JSON response.
 *
 * <p>Returned by {@code POST /api/photo/tournaments/{tournamentId}/teams/{teamId}} on success (HTTP
 * 200).
 *
 * @param filename original client filename
 * @param sizeBytes stored file size in bytes
 * @param uploadedAt timestamp when the photo was stored
 * @see TeamPhotoController
 * @see PhotoFileMetadata
 * @see DEC-40
 * @see E36S02
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
