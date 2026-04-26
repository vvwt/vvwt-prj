package de.vvwt.tm.web.certificate;

import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import java.time.Instant;
import java.util.UUID;

/**
 * REST response DTO for certificate template metadata (E36S06, DEC-40 Clause B(a)).
 *
 * <p>Q-1a TDD rebuild under DEC-22 Iron Law RED-first discipline (E36S06). Replaces the Q-1b
 * relocated version from E23S09.
 *
 * <p>Field names, types, and order are preserved verbatim per AC-C3-SIGNATURE-PRESERVATION (Brief
 * C-3 — DTO JSON wire contract must match legacy). Wire contract: Jackson serializes all five
 * fields to/from JSON using their Java names.
 *
 * <p>DEC-40 Clause B(a) — field-omission justification: this DTO is a web-internal type (not the
 * {@link de.vvwt.tm.certificate.CertificateTemplateMetadata} domain record directly) because the
 * domain record may evolve to include internal fields (e.g. storage-path) not appropriate for HTTP
 * responses. Clause B(a) applies.
 *
 * <p>Returned by:
 *
 * <ul>
 *   <li>POST {@code /api/certificate/tournaments/{tournamentId}/template} (AC1 upload)
 *   <li>GET {@code /api/certificate/tournaments/{tournamentId}/template/info} (AC3 metadata)
 * </ul>
 *
 * @param tournamentId the tournament this template belongs to
 * @param filename original client-provided filename
 * @param format detected file format: {@code "html"} or {@code "svg"}
 * @param uploadedAt timestamp of last upload
 * @param fileSizeBytes file size in bytes
 * @see CertificateTemplateController
 * @see DEC-40
 * @see E36S06
 */
public record CertificateTemplateMetadataResponse(
        UUID tournamentId, String filename, String format, Instant uploadedAt, long fileSizeBytes) {

    /**
     * Factory method: maps domain metadata to a response DTO.
     *
     * @param metadata the domain metadata (never null)
     * @return the corresponding response DTO with matching field values
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
