package de.vvwt.tm.infrastructure.web.certificate;

import de.vvwt.tm.domain.certificate.CertificateTemplateMetadata;
import de.vvwt.tm.domain.certificate.CertificateTemplateService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for tournament-scoped certificate template management (E12S04).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST   /api/tournaments/{tournamentId}/certificate-template         — upload (AC1)</li>
 *   <li>GET    /api/tournaments/{tournamentId}/certificate-template         — retrieve file (AC2)</li>
 *   <li>GET    /api/tournaments/{tournamentId}/certificate-template/info    — retrieve metadata (AC3)</li>
 *   <li>DELETE /api/tournaments/{tournamentId}/certificate-template         — delete (AC5)</li>
 *   <li>GET    /api/certificate-template/variables                          — list variables (AC6)</li>
 * </ul>
 *
 * <h2>Authentication (AC11)</h2>
 * <p>All endpoints fall under {@code /api/**} which requires admin authentication per
 * {@link de.vvwt.tm.auth.SecurityConfig}. No separate permit-all rules are needed.
 *
 * <h2>Tenant scoping (AC8, DEC-5)</h2>
 * <p>All tournament-scoped endpoints delegate to {@link CertificateTemplateService}, which
 * validates tournament ownership before any filesystem or DB operation. A missing or wrong-tenant
 * tournament results in {@link NoSuchElementException} → HTTP 404.
 *
 * <h2>Error handling (AC9)</h2>
 * <p>{@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} maps domain exceptions:
 * <ul>
 *   <li>{@link de.vvwt.tm.domain.certificate.CertificateTemplateFormatException} → 400</li>
 *   <li>{@link de.vvwt.tm.domain.certificate.CertificateTemplateSizeException} → 400</li>
 *   <li>{@link de.vvwt.tm.domain.certificate.CertificateTemplateStorageException} → 500</li>
 *   <li>{@link NoSuchElementException} → 404 (tournament not found or wrong tenant)</li>
 * </ul>
 *
 * @see CertificateTemplateService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
@RestController
public class CertificateTemplateController {

    private final CertificateTemplateService certificateTemplateService;

    public CertificateTemplateController(CertificateTemplateService certificateTemplateService) {
        this.certificateTemplateService = certificateTemplateService;
    }

    // -------------------------------------------------------------------------
    // AC1 — POST /api/tournaments/{tournamentId}/certificate-template
    // -------------------------------------------------------------------------

    /**
     * Uploads (or replaces) the certificate template for the given tournament (AC1, AC4).
     *
     * <p>Accepts multipart/form-data with a single {@code file} part. The file must have a
     * {@code .html} or {@code .svg} extension and must not exceed 2 MB (AC7). Returns 200
     * with the template metadata on success.
     *
     * <p>Uploading when a template already exists replaces the previous template (AC4).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param file         the multipart file to upload
     * @return 200 with {@link CertificateTemplateMetadataResponse} body
     */
    @PostMapping(
            value = "/api/tournaments/{tournamentId}/certificate-template",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CertificateTemplateMetadataResponse> upload(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestParam("file") MultipartFile file) throws IOException {

        try (InputStream inputStream = file.getInputStream()) {
            CertificateTemplateMetadata metadata = certificateTemplateService.upload(
                    tournamentId,
                    file.getOriginalFilename() != null
                            ? file.getOriginalFilename()
                            : "certificate-template",
                    inputStream,
                    file.getSize()
            );
            return ResponseEntity.ok(CertificateTemplateMetadataResponse.from(metadata));
        }
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/tournaments/{tournamentId}/certificate-template
    // -------------------------------------------------------------------------

    /**
     * Returns the stored certificate template file for the given tournament (AC2).
     *
     * <p>Returns the file content with the appropriate Content-Type ({@code text/html} or
     * {@code image/svg+xml}). Returns 404 if no template has been uploaded.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with file content, or 404 if no template uploaded
     */
    @GetMapping("/api/tournaments/{tournamentId}/certificate-template")
    public ResponseEntity<InputStreamResource> retrieveFile(
            @PathVariable("tournamentId") UUID tournamentId) {

        Optional<CertificateTemplateService.TemplateFile> maybeFile =
                certificateTemplateService.retrieveFile(tournamentId);

        if (maybeFile.isEmpty()) {
            throw new NoSuchElementException(
                    "No certificate template found for tournament=" + tournamentId);
        }

        CertificateTemplateService.TemplateFile templateFile = maybeFile.get();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(templateFile.contentType()));
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(templateFile.metadata().filename())
                .build());

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(templateFile.inputStream()));
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/tournaments/{tournamentId}/certificate-template/info
    // -------------------------------------------------------------------------

    /**
     * Returns the certificate template metadata (AC3).
     *
     * <p>Returns filename, format, upload timestamp, and file size. Returns 404 if no template
     * has been uploaded.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with {@link CertificateTemplateMetadataResponse}, or 404 if no template uploaded
     */
    @GetMapping("/api/tournaments/{tournamentId}/certificate-template/info")
    public ResponseEntity<CertificateTemplateMetadataResponse> retrieveMetadata(
            @PathVariable("tournamentId") UUID tournamentId) {

        return certificateTemplateService.retrieveMetadata(tournamentId)
                .map(CertificateTemplateMetadataResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new NoSuchElementException(
                        "No certificate template found for tournament=" + tournamentId));
    }

    // -------------------------------------------------------------------------
    // AC5 — DELETE /api/tournaments/{tournamentId}/certificate-template
    // -------------------------------------------------------------------------

    /**
     * Removes the certificate template file and metadata for the given tournament (AC5).
     *
     * <p>Returns 204 on success; 404 if no template exists.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 204 No Content
     */
    @DeleteMapping("/api/tournaments/{tournamentId}/certificate-template")
    public ResponseEntity<Void> delete(
            @PathVariable("tournamentId") UUID tournamentId) {

        boolean deleted = certificateTemplateService.delete(tournamentId);

        if (!deleted) {
            throw new NoSuchElementException(
                    "No certificate template found for tournament=" + tournamentId);
        }

        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // AC6 — GET /api/certificate-template/variables
    // -------------------------------------------------------------------------

    /**
     * Returns the list of available Mustache template variables (AC6).
     *
     * <p>This endpoint is not tournament-scoped — it documents the system's template contract.
     * Template authors use this to know which Mustache placeholders ({@code {{name}}}) are
     * available when designing a certificate template.
     *
     * <p>Requires admin authentication (falls under {@code /api/**} per SecurityConfig).
     *
     * @return 200 with list of {@link CertificateTemplateVariableResponse}
     */
    @GetMapping("/api/certificate-template/variables")
    public ResponseEntity<List<CertificateTemplateVariableResponse>> listVariables() {
        List<CertificateTemplateVariableResponse> variables = certificateTemplateService
                .listVariables()
                .stream()
                .map(CertificateTemplateVariableResponse::from)
                .toList();
        return ResponseEntity.ok(variables);
    }
}
