package de.vvwt.tm.infrastructure.web.audio;

import de.vvwt.tm.domain.audio.AudioCategory;
import de.vvwt.tm.domain.audio.AudioFileMetadata;

import java.time.Instant;

/**
 * REST response DTO for audio file metadata (E11S01 AC1, AC3).
 *
 * <p>Returned by the upload endpoint (AC1 — 201 Created body) and the list endpoint
 * (AC3 — 200 OK array element).
 *
 * @param category   the audio category (START, END, PAUSE)
 * @param filename   the original uploaded filename (as provided by the client at upload time)
 * @param sizeBytes  the size of the stored file in bytes
 * @param uploadedAt the instant the file was stored
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story E11S01</a>
 */
public record AudioMetadataResponse(
        AudioCategory category,
        String filename,
        long sizeBytes,
        Instant uploadedAt
) {
    /**
     * Factory method — converts domain {@link AudioFileMetadata} to the REST response record.
     *
     * @param metadata domain value object
     * @return REST DTO
     */
    public static AudioMetadataResponse from(AudioFileMetadata metadata) {
        return new AudioMetadataResponse(
                metadata.category(),
                metadata.filename(),
                metadata.sizeBytes(),
                metadata.uploadedAt()
        );
    }
}
