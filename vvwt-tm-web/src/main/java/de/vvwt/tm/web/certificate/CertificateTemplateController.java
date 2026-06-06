// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.certificate;

import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import de.vvwt.tm.certificate.CertificateTemplateService;
import de.vvwt.tm.tenant.LocationDisplayResolver;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
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
 * REST controller for tournament-scoped certificate template management (E36S06, DEC-40 Clause D).
 *
 * <p>Q-1a TDD rebuild under DEC-22 Iron Law RED-first discipline (E36S06). Replaces the Q-1b
 * relocated version from E23S09 at the same canonical FQN per D-7 Option γ. Implementation was
 * authored after the failing {@link CertificateTemplateControllerIT} was committed (RED state
 * commit hash: {@code a2a41b1}).
 *
 * <h2>Endpoints (AC-MOCKMVC-CONTRACT-PRESERVED)</h2>
 *
 * <p>URL paths preserved verbatim from the Q-1b source per DEC-40 boundary preservation:
 *
 * <ul>
 *   <li>POST {@code /api/certificate/tournaments/{tournamentId}/template} — upload (AC1)
 *   <li>GET {@code /api/certificate/tournaments/{tournamentId}/template} — retrieve file (AC2)
 *   <li>GET {@code /api/certificate/tournaments/{tournamentId}/template/info} — retrieve metadata
 *       (AC3)
 *   <li>DELETE {@code /api/certificate/tournaments/{tournamentId}/template} — delete (AC5)
 *   <li>GET {@code /api/certificate/variables} — list variables (AC6)
 * </ul>
 *
 * <h2>DEC-40 Clause A — Trigger β check per method (AC-TRIGGER-BETA-CHECK)</h2>
 *
 * <ul>
 *   <li>{@code upload}: imports from {@code certificate} module only. Cross-bounded-context count =
 *       1. Trigger β does NOT fire.
 *   <li>{@code retrieveFile}: imports from {@code certificate} module only. Count = 1. Trigger β
 *       does NOT fire.
 *   <li>{@code retrieveMetadata}: imports from {@code certificate} module only. Count = 1. Trigger
 *       β does NOT fire.
 *   <li>{@code delete}: imports from {@code certificate} module only. Count = 1. Trigger β does NOT
 *       fire.
 *   <li>{@code listVariables}: imports from {@code certificate} module only. Count = 1. Trigger β
 *       does NOT fire.
 * </ul>
 *
 * <p>Post-story result: NO method imports from >2 bounded contexts. Trigger β does NOT fire.
 *
 * <h2>DEC-40 Clause B — DTO-vs-entity disposition per endpoint (AC-DTO-DISPOSITION-CLAUSE-B)</h2>
 *
 * <ul>
 *   <li>Upload (POST): request body is {@code MultipartFile}. Response: {@link
 *       CertificateTemplateMetadataResponse} DTO — Clause B(a) applies (field-omission grounds).
 *   <li>Retrieve-file (GET): response is file stream via {@link InputStreamResource}. Clause B
 *       non-applicable (no JSON DTO).
 *   <li>Info (GET /info): response is {@link CertificateTemplateMetadataResponse} DTO — Clause B(a)
 *       applies.
 *   <li>Delete (DELETE): 204 No Content. Clause B non-applicable.
 *   <li>Variables (GET /variables): response is {@link List} of {@link
 *       CertificateTemplateVariableResponse} — Clause B(d)+B(b) apply (field-aliasing + contract
 *       stability).
 * </ul>
 *
 * <h2>Authentication (AC11)</h2>
 *
 * <p>All endpoints fall under {@code /api/**} which requires admin authentication per {@link
 * de.vvwt.tm.auth.internal.SecurityConfig}. No additional permit-all rules needed.
 *
 * <h2>Error handling (AC-ERROR-HANDLING-UNCHANGED)</h2>
 *
 * <p>{@link de.vvwt.tm.web.GlobalExceptionHandler} handles all {@code de.vvwt.tm.certificate.*}
 * exception types (absorbed from deleted {@code CertificateExceptionAdvice} in E36S08 Phase 3).
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.certificate.CertificateTemplateFormatException} → 400 (via
 *       GlobalExceptionHandler)
 *   <li>{@link de.vvwt.tm.certificate.CertificateTemplateSizeException} → 400 (via
 *       GlobalExceptionHandler)
 *   <li>{@link de.vvwt.tm.certificate.CertificateTemplateStorageException} → 500 (via
 *       GlobalExceptionHandler)
 *   <li>{@link NoSuchElementException} → 404 (tournament not found / wrong tenant)
 * </ul>
 *
 * @see CertificateTemplateService
 * @see DEC-40
 * @see DEC-22
 * @see E36S06
 */
@RestController
public class CertificateTemplateController {

    private final CertificateTemplateService certificateTemplateService;
    private final LocationDisplayResolver locationDisplayResolver;
    private final TournamentRepository tournamentRepository;

    /**
     * Constructor injection per DEC-35 Spring DI canon (interface type, not implementation).
     *
     * @param certificateTemplateService the certificate template service port
     * @param locationDisplayResolver the location display resolver (tenant module)
     * @param tournamentRepository the tournament repository (for organizer pre-fill, E68S02 AC1)
     */
    public CertificateTemplateController(
            CertificateTemplateService certificateTemplateService,
            LocationDisplayResolver locationDisplayResolver,
            TournamentRepository tournamentRepository) {
        this.certificateTemplateService = certificateTemplateService;
        this.locationDisplayResolver = locationDisplayResolver;
        this.tournamentRepository = tournamentRepository;
    }

    // -------------------------------------------------------------------------
    // AC1 — POST /api/certificate/tournaments/{tournamentId}/template
    // -------------------------------------------------------------------------

    /**
     * Uploads (or replaces) the certificate template for the given tournament (AC1, AC4, E71S02).
     *
     * <p>Accepts {@code multipart/form-data} with a single {@code file} part and optional {@code
     * photoAspectRatioWidth} / {@code photoAspectRatioHeight} integer parameters for the
     * per-template crop aspect ratio override (E71S02 AC1). The file must have a {@code .html} or
     * {@code .svg} extension and must not exceed the configured size limit (AC7). Returns 200 with
     * the template metadata on success.
     *
     * <p>If a template already exists for the tournament, the previous template is replaced (AC4).
     *
     * <p>DEC-40 Clause B(a): response uses {@link CertificateTemplateMetadataResponse} DTO
     * (field-omission grounds).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param file the multipart file to upload
     * @param photoAspectRatioWidth optional width component of the per-template crop ratio
     *     override; omit or pass null to leave unset (falls back to global default at render time)
     * @param photoAspectRatioHeight optional height component of the per-template crop ratio
     *     override
     * @return 200 with {@link CertificateTemplateMetadataResponse} body
     * @throws NoSuchElementException if tournament not found / wrong tenant (→ 404)
     * @throws de.vvwt.tm.certificate.CertificateTemplateFormatException on invalid format or
     *     invalid ratio (→ 400)
     * @throws de.vvwt.tm.certificate.CertificateTemplateSizeException on file too large (→ 400)
     * @throws IOException on stream read failure
     */
    @PostMapping(
            value = "/api/certificate/tournaments/{tournamentId}/template",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CertificateTemplateMetadataResponse> upload(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "photoAspectRatioWidth", required = false)
                    Integer photoAspectRatioWidth,
            @RequestParam(value = "photoAspectRatioHeight", required = false)
                    Integer photoAspectRatioHeight)
            throws IOException {

        try (InputStream inputStream = file.getInputStream()) {
            CertificateTemplateMetadata metadata =
                    certificateTemplateService.upload(
                            tournamentId,
                            file.getOriginalFilename() != null
                                    ? file.getOriginalFilename()
                                    : "certificate-template",
                            inputStream,
                            file.getSize(),
                            photoAspectRatioWidth,
                            photoAspectRatioHeight);
            return ResponseEntity.ok(CertificateTemplateMetadataResponse.from(metadata));
        }
    }

    // -------------------------------------------------------------------------
    // E71S02 AC2 — GET /api/certificate/tournaments/{tournamentId}/effective-ratio
    // -------------------------------------------------------------------------

    /**
     * Returns the effective crop aspect ratio for team photos for this tournament's certificate
     * template (E71S02 AC2).
     *
     * <p>Returns the per-template override when set, otherwise the global default from {@code
     * tm.photos}. This endpoint is consumed by {@code TeamPhotos.svelte} to resolve the crop ratio
     * before uploading a photo for a tournament that has a custom certificate template.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with {@link EffectiveAspectRatioResponse}
     * @throws NoSuchElementException if tournament not found / wrong tenant (→ 404)
     */
    @GetMapping("/api/certificate/tournaments/{tournamentId}/effective-ratio")
    public ResponseEntity<EffectiveAspectRatioResponse> effectiveAspectRatio(
            @PathVariable("tournamentId") UUID tournamentId) {

        de.vvwt.tm.certificate.AspectRatio ratio =
                certificateTemplateService.retrieveEffectiveAspectRatio(tournamentId);
        return ResponseEntity.ok(new EffectiveAspectRatioResponse(ratio.width(), ratio.height()));
    }

    /**
     * Response DTO for the effective crop aspect ratio endpoint (E71S02 AC2).
     *
     * @param width width component of the effective crop ratio
     * @param height height component of the effective crop ratio
     */
    public record EffectiveAspectRatioResponse(int width, int height) {}

    // -------------------------------------------------------------------------
    // AC2 — GET /api/certificate/tournaments/{tournamentId}/template
    // -------------------------------------------------------------------------

    /**
     * Returns the stored certificate template file for the given tournament (AC2).
     *
     * <p>Returns the file content with the appropriate {@code Content-Type} ({@code text/html} or
     * {@code image/svg+xml}). Returns 404 if no template has been uploaded.
     *
     * <p>DEC-40 Clause B: non-applicable — response is file stream, no JSON DTO.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with file content, or 404 if no template uploaded
     * @throws NoSuchElementException if tournament not found / wrong tenant (→ 404)
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
    // AC3 — GET /api/certificate/tournaments/{tournamentId}/template/info
    // -------------------------------------------------------------------------

    /**
     * Returns the certificate template metadata for the given tournament (AC3).
     *
     * <p>Returns filename, format, upload timestamp, and file size. Returns 404 if no template has
     * been uploaded.
     *
     * <p>DEC-40 Clause B(a): response uses {@link CertificateTemplateMetadataResponse} DTO
     * (field-omission grounds).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with {@link CertificateTemplateMetadataResponse}, or 404 if no template uploaded
     * @throws NoSuchElementException if tournament not found / wrong tenant (→ 404)
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
    // AC5 — DELETE /api/certificate/tournaments/{tournamentId}/template
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
     * @throws NoSuchElementException if no template exists (→ 404)
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
    // E68S02 AC1 — GET /api/certificate/print-defaults/{tournamentId}
    // -------------------------------------------------------------------------

    /**
     * Returns the pre-fill defaults for the certificate print override form (E68S02 AC1).
     *
     * <p>The response contains the current organizer and venue values that the UI pre-populates
     * into the override input fields before the operator opens the print screen:
     *
     * <ul>
     *   <li>{@code organizer} — the tournament's stored organizer (null → empty string)
     *   <li>{@code venue} — the current live location display name from {@link
     *       LocationDisplayResolver}
     * </ul>
     *
     * <p>Falls under {@code /api/**} — requires admin authentication per {@code SecurityConfig}. No
     * new security rule needed (AC10).
     *
     * <p>DEC-40 Clause A — cross-bounded-context import count: {@code certificate} (template
     * service), {@code tournament} (repository), {@code tenant} (resolver) = 2 bounded contexts.
     * Trigger β does NOT fire.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with {@link CertificatePrintDefaultsResponse}, or 404 if tournament not found
     * @throws NoSuchElementException if tournament not found (→ 404 via GlobalExceptionHandler)
     * @since E68S02
     */
    @GetMapping("/api/certificate/print-defaults/{tournamentId}")
    public ResponseEntity<CertificatePrintDefaultsResponse> printDefaults(
            @PathVariable("tournamentId") UUID tournamentId) {

        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(
                                () ->
                                        new NoSuchElementException(
                                                "Tournament not found: " + tournamentId));

        String organizer = tournament.getOrganizer() != null ? tournament.getOrganizer() : "";
        String venue = locationDisplayResolver.resolveLocationDisplayName();

        return ResponseEntity.ok(new CertificatePrintDefaultsResponse(organizer, venue));
    }

    // -------------------------------------------------------------------------
    // AC6 — GET /api/certificate/variables
    // -------------------------------------------------------------------------

    /**
     * Returns the list of available Mustache template variables (AC6).
     *
     * <p>This endpoint is not tournament-scoped — it documents the system's template contract.
     * Template authors use this to know which Mustache placeholders ({@code {{name}}}) are
     * available when designing a certificate template.
     *
     * <p>Requires admin authentication (falls under {@code /api/**} per {@code SecurityConfig}).
     *
     * <p>DEC-40 Clause B(d)+B(b): response uses {@link CertificateTemplateVariableResponse} DTO
     * (field-aliasing + contract-stability grounds).
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
