package de.vvwt.tm.certificate;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for tournament-scoped certificate template management (E12S04).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). Interface contract is
 * byte-equivalent to the deleted legacy interface per AC-CONSUMER-IMPORTS-UNCHANGED and Brief C-2
 * (Option γ same-FQN guarantee).
 *
 * <h2>Template formats (AC7, E12S01)</h2>
 *
 * <p>Accepted: HTML ({@code .html}) and SVG ({@code .svg}) per E12S01 spike findings.
 *
 * <h2>Tenant scoping (AC8, DEC-5, DEC-17)</h2>
 *
 * <p>All methods validate tournament existence and tenant membership before touching the filesystem
 * or H2. A missing or wrong-tenant tournament yields {@link java.util.NoSuchElementException}.
 *
 * <h2>One template per tournament (AC8)</h2>
 *
 * <p>The system enforces exactly one certificate template per tournament. Uploading a new template
 * replaces the existing one (AC4) — both the file and the H2 metadata row.
 *
 * @see de.vvwt.tm.certificate.internal.DefaultCertificateTemplateService
 * @see DEC-35
 * @see DEC-21
 * @see E36S04
 */
public interface CertificateTemplateService {

    /**
     * Stores a certificate template for the given tournament (AC1, AC4).
     *
     * <p>If a template already exists, the file is replaced on the filesystem and the metadata row
     * in H2 is upserted (AC4). Returns the metadata of the stored template.
     *
     * @param tournamentId tournament UUID (tenant-scoped per DEC-5)
     * @param filename original client-provided filename (used for format detection)
     * @param inputStream template file content
     * @param sizeBytes declared file size in bytes (validated against configured limit — AC7)
     * @return the stored template metadata
     * @throws java.util.NoSuchElementException if tournament not found / wrong tenant (AC9)
     * @throws CertificateTemplateFormatException if format is not .html or .svg, or file is
     *     malformed (AC7)
     * @throws CertificateTemplateSizeException if sizeBytes exceeds the configured limit (AC7)
     * @throws CertificateTemplateStorageException on filesystem I/O failure (AC9)
     */
    CertificateTemplateMetadata upload(
            UUID tournamentId, String filename, InputStream inputStream, long sizeBytes);

    /**
     * Returns the stored template file for the given tournament (AC2).
     *
     * <p>The caller is responsible for closing the returned {@link InputStream}.
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @return the file content and content-type, or {@link Optional#empty()} if no template
     *     uploaded
     * @throws java.util.NoSuchElementException if tournament not found / wrong tenant
     * @throws CertificateTemplateStorageException on filesystem I/O failure
     */
    Optional<TemplateFile> retrieveFile(UUID tournamentId);

    /**
     * Returns the template metadata (filename, format, upload timestamp, file size) for the given
     * tournament (AC3).
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @return the template metadata, or {@link Optional#empty()} if no template uploaded
     * @throws java.util.NoSuchElementException if tournament not found / wrong tenant
     */
    Optional<CertificateTemplateMetadata> retrieveMetadata(UUID tournamentId);

    /**
     * Removes the certificate template file and metadata for the given tournament (AC5).
     *
     * @param tournamentId tournament UUID (tenant-scoped)
     * @return {@code true} if a template was deleted; {@code false} if none existed
     * @throws java.util.NoSuchElementException if tournament not found / wrong tenant
     * @throws CertificateTemplateStorageException on filesystem I/O failure during delete
     */
    boolean delete(UUID tournamentId);

    /**
     * Returns the list of available Mustache template variables (AC6).
     *
     * <p>This call is stateless and does not require a tournament context. It documents the
     * system's template contract — the set of Mustache placeholders ({@code {{name}}}) that the
     * rendering engine will substitute during generation.
     *
     * @return the fixed list of available template variables
     */
    List<CertificateTemplateVariable> listVariables();

    // -------------------------------------------------------------------------
    // Nested result type
    // -------------------------------------------------------------------------

    /**
     * Result container for a template file retrieval (AC2).
     *
     * @param inputStream the template file content (caller must close)
     * @param contentType the MIME type ({@code text/html} or {@code image/svg+xml})
     * @param metadata the template metadata (filename, format, size, timestamp)
     */
    record TemplateFile(
            InputStream inputStream, String contentType, CertificateTemplateMetadata metadata) {}
}
