package de.vvwt.tm.certificate.internal;

import de.vvwt.tm.certificate.CertificateTemplateFormatException;
import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import de.vvwt.tm.certificate.CertificateTemplateRepository;
import de.vvwt.tm.certificate.CertificateTemplateService;
import de.vvwt.tm.certificate.CertificateTemplateSizeException;
import de.vvwt.tm.certificate.CertificateTemplateStorageConfig;
import de.vvwt.tm.certificate.CertificateTemplateStorageException;
import de.vvwt.tm.certificate.CertificateTemplateVariable;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link CertificateTemplateService} — filesystem + H2 storage (E12S04).
 *
 * <p>Rebuilt under DEC-22 Iron Law Q-1a RED-first TDD discipline (E36S04). Full behavioral parity
 * with the deleted legacy implementation preserved per AC-CONSUMER-IMPORTS-UNCHANGED and Brief C-11
 * (behavioral-divergence stop-and-escalate trigger).
 *
 * <p>This class is package-private to the {@code certificate} module. External consumers MUST
 * reference the public interface {@link CertificateTemplateService} — not this class (DEC-35,
 * DEC-36).
 *
 * <h2>Storage layout (DEC-15)</h2>
 *
 * <p>Template files are stored at {@code {dataDir}/{tournamentId}/certificate-template.{ext}},
 * where {@code ext} is {@code html} or {@code svg}. The filename on disk is always {@code
 * certificate-template.{ext}} — the original client filename is stored in H2 only.
 *
 * <h2>H2 metadata (AC1, AC3, DEC-14)</h2>
 *
 * <p>Template metadata (filename, format, upload_timestamp, file_size_bytes) is persisted in the
 * {@code certificate_template} table via {@link CertificateTemplateRepository}.
 *
 * <h2>Tenant scoping (AC8, DEC-5, DEC-17)</h2>
 *
 * <p>Every method validates tournament ownership via {@link TournamentRepository#findById}, which
 * returns empty if the tournament belongs to a different tenant.
 *
 * @see CertificateTemplateService
 * @see de.vvwt.tm.certificate.CertificateTemplateRepository
 * @see CertificateTemplateStorageConfig
 * @see DefaultCertificateTemplateRepository
 * @see DEC-35
 * @see E36S04
 */
@Service
public class DefaultCertificateTemplateService implements CertificateTemplateService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultCertificateTemplateService.class);

    /** Filesystem name for the stored template file (extension is appended). */
    private static final String TEMPLATE_FILE_STEM = "certificate-template";

    /** Accepted file extensions (lowercase). */
    private static final List<String> ACCEPTED_EXTENSIONS = List.of(".html", ".svg");

    /** Content-Type for HTML templates. */
    static final String CONTENT_TYPE_HTML = "text/html";

    /** Content-Type for SVG templates. */
    static final String CONTENT_TYPE_SVG = "image/svg+xml";

    /** Fixed set of Mustache variables available in certificate templates (AC6). */
    private static final List<CertificateTemplateVariable> VARIABLES =
            List.of(
                    new CertificateTemplateVariable("placement", "String", "1"),
                    new CertificateTemplateVariable("teamName", "String", "Team A"),
                    new CertificateTemplateVariable(
                            "teamPhoto", "String", "data:image/jpeg;base64,/9j/..."),
                    new CertificateTemplateVariable(
                            "tournamentName", "String", "Stadtmeisterschaft 2026"),
                    new CertificateTemplateVariable("date", "String", "15. April 2026"),
                    new CertificateTemplateVariable(
                            "location", "String", "Sporthalle Musterstadt"));

    private final CertificateTemplateStorageConfig config;
    private final TournamentRepository tournamentRepository;

    /** DEC-35: inject the public interface port, not the concrete implementation. */
    private final CertificateTemplateRepository templateRepository;

    public DefaultCertificateTemplateService(
            CertificateTemplateStorageConfig config,
            TournamentRepository tournamentRepository,
            CertificateTemplateRepository templateRepository) {
        this.config = config;
        this.tournamentRepository = tournamentRepository;
        this.templateRepository = templateRepository;
    }

    // -------------------------------------------------------------------------
    // AC1 + AC4 — Upload (and replace)
    // -------------------------------------------------------------------------

    @Override
    public CertificateTemplateMetadata upload(
            UUID tournamentId, String filename, InputStream inputStream, long sizeBytes) {
        requireTournamentInTenant(tournamentId);
        String ext = validateAndResolveExtension(filename);
        validateSize(sizeBytes, filename);

        // AC4: delete the existing template file (if any) before writing the new one.
        // This handles the case where the extension changes (e.g. .html → .svg).
        deleteExistingTemplateFile(tournamentId);

        Path targetFile = templateFilePath(tournamentId, ext);
        ensureParentDirectory(targetFile);

        byte[] content = readAndValidateContent(inputStream, filename, ext, sizeBytes);

        try {
            Files.write(targetFile, content);
        } catch (IOException ex) {
            throw new CertificateTemplateStorageException(
                    "Failed to store certificate template for tournament="
                            + tournamentId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }

        long actualSize = content.length;
        Instant uploadedAt = Instant.now();
        String format = ext.substring(1); // strip leading dot: ".html" → "html"

        CertificateTemplateMetadata metadata =
                new CertificateTemplateMetadata(
                        tournamentId, filename, format, uploadedAt, actualSize);

        templateRepository.upsert(metadata);

        log.info(
                "[tm-cert] Stored certificate template: tournament={} filename={} format={}"
                        + " size={}",
                tournamentId,
                filename,
                format,
                actualSize);

        return metadata;
    }

    // -------------------------------------------------------------------------
    // AC2 — Retrieve file
    // -------------------------------------------------------------------------

    @Override
    public Optional<TemplateFile> retrieveFile(UUID tournamentId) {
        requireTournamentInTenant(tournamentId);

        Optional<CertificateTemplateMetadata> maybeMetadata =
                templateRepository.findByTournamentId(tournamentId);
        if (maybeMetadata.isEmpty()) {
            return Optional.empty();
        }

        CertificateTemplateMetadata metadata = maybeMetadata.get();
        Path file = templateFilePath(tournamentId, "." + metadata.format());

        if (!Files.exists(file)) {
            // Metadata exists but file is gone — log and treat as not found
            log.warn(
                    "[tm-cert] Template metadata found but file missing: tournament={} path={}",
                    tournamentId,
                    file);
            return Optional.empty();
        }

        InputStream inputStream = openFile(file, tournamentId);
        String contentType = "svg".equals(metadata.format()) ? CONTENT_TYPE_SVG : CONTENT_TYPE_HTML;

        return Optional.of(new TemplateFile(inputStream, contentType, metadata));
    }

    // -------------------------------------------------------------------------
    // AC3 — Retrieve metadata
    // -------------------------------------------------------------------------

    @Override
    public Optional<CertificateTemplateMetadata> retrieveMetadata(UUID tournamentId) {
        requireTournamentInTenant(tournamentId);
        return templateRepository.findByTournamentId(tournamentId);
    }

    // -------------------------------------------------------------------------
    // AC5 — Delete
    // -------------------------------------------------------------------------

    @Override
    public boolean delete(UUID tournamentId) {
        requireTournamentInTenant(tournamentId);

        Optional<CertificateTemplateMetadata> maybeMetadata =
                templateRepository.findByTournamentId(tournamentId);
        if (maybeMetadata.isEmpty()) {
            return false;
        }

        String format = maybeMetadata.get().format();
        deleteExistingTemplateFile(tournamentId, "." + format);
        boolean dbDeleted = templateRepository.deleteByTournamentId(tournamentId);

        log.info("[tm-cert] Deleted certificate template: tournament={}", tournamentId);
        return dbDeleted;
    }

    // -------------------------------------------------------------------------
    // AC6 — Variables
    // -------------------------------------------------------------------------

    @Override
    public List<CertificateTemplateVariable> listVariables() {
        return VARIABLES;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that the tournament exists and belongs to the active tenant.
     *
     * <p>DEC-5, DEC-17: uses the tenant-scoped {@link TournamentRepository#findById(Object)}.
     * Returns empty if not found OR wrong tenant → produces HTTP 404 (no tenant enumeration).
     *
     * @param tournamentId the tournament to validate
     * @throws NoSuchElementException if tournament not found or wrong tenant
     */
    private void requireTournamentInTenant(UUID tournamentId) {
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));
    }

    /**
     * Validates the filename extension (must be .html or .svg, case-insensitive) and returns the
     * normalized lowercase extension (e.g. {@code ".html"}).
     *
     * <p>AC7: unsupported format → HTTP 400.
     *
     * @param filename the original client filename
     * @return the lowercase extension including the leading dot
     * @throws CertificateTemplateFormatException if extension is not accepted
     */
    private String validateAndResolveExtension(String filename) {
        if (filename == null) {
            throw new CertificateTemplateFormatException(
                    "Filename must not be null. Accepted formats: HTML (.html), SVG (.svg).");
        }
        String lower = filename.toLowerCase();
        for (String ext : ACCEPTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        throw new CertificateTemplateFormatException(
                "Unsupported template format. Only HTML (.html) and SVG (.svg) templates are"
                        + " accepted. Received: "
                        + filename);
    }

    /**
     * Validates the declared file size against the configured limit (AC7).
     *
     * @param sizeBytes declared size
     * @param filename original filename for the error message
     * @throws CertificateTemplateSizeException if size exceeds the limit
     */
    private void validateSize(long sizeBytes, String filename) {
        long maxBytes = config.getMaxSizeBytes();
        if (sizeBytes > maxBytes) {
            throw new CertificateTemplateSizeException(
                    "Certificate template '"
                            + filename
                            + "' is too large ("
                            + sizeBytes
                            + " bytes). Maximum allowed size is "
                            + maxBytes
                            + " bytes.");
        }
    }

    /**
     * Reads the input stream into a byte array and performs a lightweight well-formedness check
     * (AC7 — file is parseable as the accepted format).
     *
     * <p>For HTML: content must be non-empty (any HTML fragment is accepted; no full parse). For
     * SVG: content must start with {@code <svg} or {@code <?xml} after trimming whitespace.
     *
     * @param inputStream the file input stream (will be fully consumed)
     * @param filename original filename (for error messages)
     * @param ext the resolved extension (.html or .svg)
     * @param sizeBytes declared size (used as initial buffer hint)
     * @return the file content as a byte array
     * @throws CertificateTemplateFormatException if well-formedness check fails
     * @throws CertificateTemplateStorageException if reading fails
     */
    private byte[] readAndValidateContent(
            InputStream inputStream, String filename, String ext, long sizeBytes) {
        byte[] content;
        try {
            content = inputStream.readAllBytes();
        } catch (IOException ex) {
            throw new CertificateTemplateStorageException(
                    "Failed to read uploaded certificate template '"
                            + filename
                            + "': "
                            + ex.getMessage(),
                    ex);
        }

        if (content.length == 0) {
            throw new CertificateTemplateFormatException(
                    "Uploaded certificate template '" + filename + "' is empty.");
        }

        if (".svg".equals(ext)) {
            String start =
                    new String(content, 0, Math.min(content.length, 200))
                            .stripLeading()
                            .toLowerCase();
            if (!start.startsWith("<svg") && !start.startsWith("<?xml")) {
                throw new CertificateTemplateFormatException(
                        "The uploaded SVG file '"
                                + filename
                                + "' does not appear to be a valid SVG document. "
                                + "SVG files must start with '<svg' or '<?xml'.");
            }
        }
        // For HTML: any non-empty content is accepted (templates are HTML fragments or full
        // documents)

        return content;
    }

    /**
     * Deletes any existing template file for the tournament — all supported extensions. Used before
     * uploading a new template (AC4 replace).
     *
     * @param tournamentId the tournament UUID
     */
    private void deleteExistingTemplateFile(UUID tournamentId) {
        for (String ext : ACCEPTED_EXTENSIONS) {
            deleteExistingTemplateFile(tournamentId, ext);
        }
    }

    /**
     * Deletes the template file with the given extension if it exists.
     *
     * @param tournamentId the tournament UUID
     * @param ext extension including the leading dot
     */
    private void deleteExistingTemplateFile(UUID tournamentId, String ext) {
        Path file = templateFilePath(tournamentId, ext);
        if (Files.exists(file)) {
            try {
                Files.delete(file);
                log.debug("[tm-cert] Deleted existing template file: {}", file);
            } catch (IOException ex) {
                throw new CertificateTemplateStorageException(
                        "Failed to delete existing certificate template for tournament="
                                + tournamentId
                                + ": "
                                + ex.getMessage(),
                        ex);
            }
        }
    }

    /**
     * Resolves the filesystem path for a certificate template file.
     *
     * <p>Structure: {@code {dataDir}/{tournamentId}/certificate-template.{ext}}. Both path
     * components are safe (UUID and fixed stem + validated extension) — no path traversal.
     *
     * @param tournamentId the tournament UUID
     * @param ext the file extension including the leading dot (e.g. {@code ".html"})
     * @return the absolute path to the template file
     */
    private Path templateFilePath(UUID tournamentId, String ext) {
        return Path.of(config.getDataDir())
                .resolve(tournamentId.toString())
                .resolve(TEMPLATE_FILE_STEM + ext);
    }

    /**
     * Creates the parent directory of the target file if it does not already exist.
     *
     * @param file the target file path
     * @throws CertificateTemplateStorageException if directory creation fails
     */
    private void ensureParentDirectory(Path file) {
        Path parent = file.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException ex) {
                throw new CertificateTemplateStorageException(
                        "Failed to create certificate template storage directory '"
                                + parent
                                + "': "
                                + ex.getMessage(),
                        ex);
            }
        }
    }

    /**
     * Opens the template file for reading.
     *
     * @param file the file path
     * @param tournamentId for error message
     * @return an open InputStream (caller must close)
     * @throws CertificateTemplateStorageException if opening fails
     */
    private InputStream openFile(Path file, UUID tournamentId) {
        try {
            return Files.newInputStream(file);
        } catch (IOException ex) {
            throw new CertificateTemplateStorageException(
                    "Failed to open certificate template for tournament="
                            + tournamentId
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }
}
