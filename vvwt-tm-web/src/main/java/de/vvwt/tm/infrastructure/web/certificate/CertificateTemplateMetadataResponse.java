package de.vvwt.tm.infrastructure.web.certificate;

import de.vvwt.tm.domain.certificate.CertificateTemplateMetadata;

import java.time.Instant;
import java.util.UUID;

/**
 * REST response DTO for certificate template metadata (E12S04 AC1, AC3).
 *
 * <p>Returned by the upload endpoint (AC1) and the metadata endpoint (AC3).
 *
 * @param tournamentId  the tournament this template belongs to
 * @param filename      original client-provided filename
 * @param format        detected file format: {@code "html"} or {@code "svg"}
 * @param uploadedAt    timestamp of last upload
 * @param fileSizeBytes file size in bytes
 *
 * @see CertificateTemplateController
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
public record CertificateTemplateMetadataResponse(
        UUID tournamentId,
        String filename,
        String format,
        Instant uploadedAt,
        long fileSizeBytes
) {

    /**
     * Factory method: maps domain metadata to a response DTO.
     *
     * @param metadata the domain metadata
     * @return the corresponding response
     */
    public static CertificateTemplateMetadataResponse from(CertificateTemplateMetadata metadata) {
        return new CertificateTemplateMetadataResponse(
                metadata.tournamentId(),
                metadata.filename(),
                metadata.format(),
                metadata.uploadedAt(),
                metadata.fileSizeBytes()
        );
    }
}
