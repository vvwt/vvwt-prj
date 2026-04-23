package de.vvwt.tm.web.certificate;

import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import java.time.Instant;
import java.util.UUID;

/**
 * REST response DTO for certificate template metadata (E23S09, DEC-40 Clause B(a)).
 *
 * <p>Relocated from {@code
 * de.vvwt.tm.infrastructure.web.certificate.CertificateTemplateMetadataResponse} to {@code
 * de.vvwt.tm.web.certificate.*} per DEC-40 Clause D + DEC-22 §refactor-clause (Q-1b).
 *
 * <p>DEC-40 Clause B(a) — field-omission justification: this DTO is preserved as a web-internal DTO
 * rather than serializing {@link CertificateTemplateMetadata} directly, because the entity may
 * contain internal fields (e.g., storage-location path) that must not be exposed in JSON responses.
 *
 * <p>Returned by the upload endpoint (AC1) and the metadata endpoint (AC3).
 *
 * @param tournamentId the tournament this template belongs to
 * @param filename original client-provided filename
 * @param format detected file format: {@code "html"} or {@code "svg"}
 * @param uploadedAt timestamp of last upload
 * @param fileSizeBytes file size in bytes
 * @see CertificateTemplateController
 * @see DEC-40
 * @see E23S09
 */
public record CertificateTemplateMetadataResponse(
        UUID tournamentId, String filename, String format, Instant uploadedAt, long fileSizeBytes) {

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
                metadata.fileSizeBytes());
    }
}
