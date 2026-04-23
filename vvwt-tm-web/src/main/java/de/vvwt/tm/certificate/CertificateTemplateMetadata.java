package de.vvwt.tm.certificate;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable value object representing certificate template metadata stored in H2 (E12S04 AC1, AC3 —
 * filename, format, upload timestamp, file size).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.certificate.CertificateTemplateMetadata} to the new
 * {@code de.vvwt.tm.certificate} Modulith module as part of E23S06 (Q-1b whole-class relocation per
 * DEC-22 §refactor-clause). The record structure is byte-equivalent to the legacy record.
 *
 * @param tournamentId the tournament this template belongs to (AC8 — one per tournament)
 * @param filename original client-provided filename (for display purposes)
 * @param format detected file format: {@code "html"} or {@code "svg"}
 * @param uploadedAt timestamp when the template was last uploaded / replaced
 * @param fileSizeBytes file size in bytes (as stored on disk)
 * @see CertificateTemplateService
 */
public record CertificateTemplateMetadata(
        UUID tournamentId,
        String filename,
        String format,
        Instant uploadedAt,
        long fileSizeBytes) {}
