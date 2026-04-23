package de.vvwt.tm.web.certificate;

import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import de.vvwt.tm.certificate.CertificateTemplateService;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST controller for tournament-scoped certificate template management (E23S09, DEC-40 Clause D).
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.infrastructure.web.certificate.*} to {@code
 * de.vvwt.tm.web.certificate.*} per DEC-40 Clause D + DEC-22 §refactor-clause (Q-1b). URL mappings
 * are preserved verbatim during the parallel phase; renamed atomically at E23S10 Cutover-2.
 *
 * <h2>Endpoints (E23S10 Cutover-2 — new URL patterns per AC-URL-RENAME-CERTIFICATE-ENDPOINTS)</h2>
 *
 * <ul>
 *   <li>POST /api/certificate/tournaments/{tournamentId}/template — upload (AC1)
 *   <li>GET /api/certificate/tournaments/{tournamentId}/template — retrieve file (AC2)
 *   <li>GET /api/certificate/tournaments/{tournamentId}/template/info — retrieve metadata (AC3)
 *   <li>DELETE /api/certificate/tournaments/{tournamentId}/template — delete (AC5)
 *   <li>GET /api/certificate/variables — list variables (AC6)
 * </ul>
 *
 * <h2>DEC-40 Clause A — Trigger β check per method (AC-TRIGGER-BETA-CHECK)</h2>
 *
 * <ul>
 *   <li>{@code upload}: imports from {@code certificate} module only (CertificateTemplateService,
 *       CertificateTemplateMetadata; web-internal DTOs CertificateTemplateMetadataResponse).
 *       Cross-bounded-context count = 1. Trigger β does NOT fire.
 *   <li>{@code retrieveFile}: imports from {@code certificate} module only
 *       (CertificateTemplateService). Cross-bounded-context count = 1. Trigger β does NOT fire.
 *   <li>{@code retrieveMetadata}: imports from {@code certificate} module only. Count = 1. Trigger
 *       β does NOT fire.
 *   <li>{@code delete}: imports from {@code certificate} module only. Count = 1. Trigger β does NOT
 *       fire.
 *   <li>{@code listVariables}: imports from {@code certificate} module only
 *       (CertificateTemplateVariableResponse). Count = 1. Trigger β does NOT fire.
 * </ul>
 *
 * <p>Post-story result: NO method imports from >2 bounded contexts. Trigger β does NOT fire. No
 * L2.5 Clause C evaluation required.
 *
 * <h2>DEC-40 Clause B — DTO-vs-entity disposition per endpoint
 * (AC-DTO-DISPOSITION-CLAUSE-B-PER-ENDPOINT)</h2>
 *
 * <ul>
 *   <li>Upload (POST): request body is {@code MultipartFile}, no DTO. Response: {@link
 *       CertificateTemplateMetadataResponse} — preserved as web-internal DTO on Clause B(a)
 *       field-omission grounds ({@link CertificateTemplateMetadata} contains internal fields like
 *       storage-location path not exposed in JSON). Clause B(a) applies.
 *   <li>Retrieve-file (GET): response is file {@code byte[]} stream via {@link
 *       InputStreamResource}, no JSON DTO. Clause B non-applicable.
 *   <li>Info (GET /info): response is {@link CertificateTemplateMetadataResponse} — Clause B(a)
 *       field-omission (entity may contain internal fields). Clause B(a) applies.
 *   <li>Delete (DELETE): response 204 No Content — no DTO. Clause B non-applicable.
 *   <li>Variables (GET /variables): response is {@link List} of {@link
 *       CertificateTemplateVariableResponse} — preserved as web-internal DTO on Clause B(d)
 *       field-aliasing grounds (JSON shape differs from domain enum shape; ensures stable wire
 *       contract per Clause B(b)). Clause B(d)+B(b) apply.
 * </ul>
 *
 * <h2>Authentication (AC-SECURITY-SUBSTANTIVE)</h2>
 *
 * <p>All endpoints fall under {@code /api/**} which requires admin authentication per {@link
 * de.vvwt.tm.auth.internal.SecurityConfig}. No separate permit-all rules are needed. Security
 * semantics preserved byte-equivalent from the relocated source.
 *
 * <h2>Tenant scoping (DEC-5)</h2>
 *
 * <p>All tournament-scoped endpoints delegate to {@link CertificateTemplateService}, which
 * validates tournament ownership before any filesystem or DB operation. A missing or wrong-tenant
 * tournament results in {@link NoSuchElementException} → HTTP 404.
 *
 * <h2>Error handling (AC-ERROR-HANDLING-UNCHANGED)</h2>
 *
 * <p>{@link CertificateExceptionAdvice} (co-located in {@code de.vvwt.tm.web.certificate}) maps the
 * new {@code de.vvwt.tm.certificate.*} module exceptions (analog to {@code PhotoExceptionAdvice}
 * from E23S04). The {@code GlobalExceptionHandler} covers {@link NoSuchElementException}:
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.certificate.CertificateTemplateFormatException} → 400 (via {@link
 *       CertificateExceptionAdvice})
 *   <li>{@link de.vvwt.tm.certificate.CertificateTemplateSizeException} → 400 (via {@link
 *       CertificateExceptionAdvice})
 *   <li>{@link de.vvwt.tm.certificate.CertificateTemplateStorageException} → 500 (via {@link
 *       CertificateExceptionAdvice})
 *   <li>{@link NoSuchElementException} → 404 (tournament not found or wrong tenant; via {@code
 *       GlobalExceptionHandler})
 * </ul>
 *
 * @see CertificateTemplateService
 * @see DEC-40
 * @see DEC-22
 * @see E23S09
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
     * <p>Accepts multipart/form-data with a single {@code file} part. The file must have a {@code
     * .html} or {@code .svg} extension and must not exceed 2 MB (AC7). Returns 200 with the
     * template metadata on success.
     *
     * <p>Uploading when a template already exists replaces the previous template (AC4).
     *
     * <p>DEC-40 Clause B(a): response uses {@link CertificateTemplateMetadataResponse} DTO
     * (field-omission — entity may contain internal storage-path fields not exposed in JSON).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param file the multipart file to upload
     * @return 200 with {@link CertificateTemplateMetadataResponse} body
     */
    @PostMapping(
            value = "/api/certificate/tournaments/{tournamentId}/template",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CertificateTemplateMetadataResponse> upload(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestParam("file") MultipartFile file)
            throws IOException {

        try (InputStream inputStream = file.getInputStream()) {
            CertificateTemplateMetadata metadata =
                    certificateTemplateService.upload(
                            tournamentId,
                            file.getOriginalFilename() != null
                                    ? file.getOriginalFilename()
                                    : "certificate-template",
                            inputStream,
                            file.getSize());
            return ResponseEntity.ok(CertificateTemplateMetadataResponse.from(metadata));
        }
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/tournaments/{tournamentId}/certificate-template
    // -------------------------------------------------------------------------

    /**
     * Returns the stored certificate template file for the given tournament (AC2).
     *
     * <p>Returns the file content with the appropriate Content-Type ({@code text/html} or {@code
     * image/svg+xml}). Returns 404 if no template has been uploaded.
     *
     * <p>DEC-40 Clause B: non-applicable — response is file {@code byte[]} stream, no JSON DTO.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with file content, or 404 if no template uploaded
     */
    @GetMapping("/api/certificate/tournaments/{tournamentId}/template")
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
        headers.setContentDisposition(
                ContentDisposition.inline().filename(templateFile.metadata().filename()).build());

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
     * <p>Returns filename, format, upload timestamp, and file size. Returns 404 if no template has
     * been uploaded.
     *
     * <p>DEC-40 Clause B(a): response uses {@link CertificateTemplateMetadataResponse} DTO
     * (field-omission grounds).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with {@link CertificateTemplateMetadataResponse}, or 404 if no template uploaded
     */
    @GetMapping("/api/certificate/tournaments/{tournamentId}/template/info")
    public ResponseEntity<CertificateTemplateMetadataResponse> retrieveMetadata(
            @PathVariable("tournamentId") UUID tournamentId) {

        return certificateTemplateService
                .retrieveMetadata(tournamentId)
                .map(CertificateTemplateMetadataResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(
                        () ->
                                new NoSuchElementException(
                                        "No certificate template found for tournament="
                                                + tournamentId));
    }

    // -------------------------------------------------------------------------
    // AC5 — DELETE /api/tournaments/{tournamentId}/certificate-template
    // -------------------------------------------------------------------------

    /**
     * Removes the certificate template file and metadata for the given tournament (AC5).
     *
     * <p>Returns 204 on success; 404 if no template exists.
     *
     * <p>DEC-40 Clause B: non-applicable — response 204 No Content, no DTO.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 204 No Content
     */
    @DeleteMapping("/api/certificate/tournaments/{tournamentId}/template")
    public ResponseEntity<Void> delete(@PathVariable("tournamentId") UUID tournamentId) {

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
     * <p>DEC-40 Clause B(d)+B(b): response uses {@link CertificateTemplateVariableResponse} DTO
     * (field-aliasing grounds + contract-stability: JSON shape differs from domain enum shape).
     *
     * @return 200 with list of {@link CertificateTemplateVariableResponse}
     */
    @GetMapping("/api/certificate/variables")
    public ResponseEntity<List<CertificateTemplateVariableResponse>> listVariables() {
        List<CertificateTemplateVariableResponse> variables =
                certificateTemplateService.listVariables().stream()
                        .map(CertificateTemplateVariableResponse::from)
                        .toList();
        return ResponseEntity.ok(variables);
    }
}
