package de.vvwt.tm.certificate;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable value object representing certificate template metadata stored in H2 (E12S04 AC1, AC3 —
 * filename, format, upload timestamp, file size).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). All field names, types,
 * and order are preserved verbatim per AC-RECORD-FIELDS-PRESERVED-METADATA and Brief C-3
 * (signature-preservation).
 *
 * <p>Consumer chain (per audit (i)):
 *
 * <ul>
 *   <li>{@link CertificateTemplateRepository} — {@code findByTournamentId} returns this record;
 *       {@code upsert} takes this record as parameter
 *   <li>{@code CertificateTemplateController} (E36S06) — reads metadata for API response
 *   <li>{@code CertificateTemplateMetadataResponse} (E36S06) — {@code from(CertificateTemplateMetadata)}
 *       factory method consumes this record
 *   <li>{@link CertificateTemplateService.TemplateFile} — carries metadata as nested field
 * </ul>
 *
 * @param tournamentId the tournament this template belongs to (AC8 — one per tournament)
 * @param filename original client-provided filename (for display purposes)
 * @param format detected file format: {@code "html"} or {@code "svg"}
 * @param uploadedAt timestamp when the template was last uploaded / replaced
 * @param fileSizeBytes file size in bytes (as stored on disk)
 * @see CertificateTemplateService
 * @see E36S04
 */
public record CertificateTemplateMetadata(
        UUID tournamentId,
        String filename,
        String format,
        Instant uploadedAt,
        long fileSizeBytes) {}
