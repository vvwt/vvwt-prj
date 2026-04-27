package de.vvwt.tm.web.photo;

import de.vvwt.tm.photo.PhotoStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

/**
 * REST controller for tournament-scoped team photo management (E36S02 Q-1a TDD rebuild, DEC-40
 * Clause D).
 *
 * <p>Rebuilt from deleted Q-1b artefact at the same canonical FQN ({@code de.vvwt.tm.web.photo})
 * per Brief D-7 Option γ and DEC-22 Iron Law Q-1a RED-first TDD. HTTP wire contract preserved
 * verbatim per AC-MOCKMVC-CONTRACT-PRESERVED: URL paths and JSON wire shapes are identical to the
 * deleted legacy controller (E23S04/E23S05).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST {@code /api/photo/tournaments/{tournamentId}/teams/{teamId}} — upload (AC1)
 *   <li>GET {@code /api/photo/tournaments/{tournamentId}/teams/{teamId}} — retrieve (AC2)
 *   <li>DELETE {@code /api/photo/tournaments/{tournamentId}/teams/{teamId}} — delete (AC3)
 * </ul>
 *
 * <h2>Authentication (AC-SECURITY-SUBSTANTIVE)</h2>
 *
 * <p>All endpoints are under {@code /api/**} which requires admin HTTP Basic auth via {@link
 * de.vvwt.tm.auth.internal.SecurityConfig}. No public access — photo uploads and deletes are
 * admin-only; photo retrieve is also under {@code /api/**} and therefore requires auth per legacy
 * behavior.
 *
 * <h2>Tenant scoping (DEC-5, DEC-20)</h2>
 *
 * <p>All endpoints delegate to {@link PhotoStorageService}, which validates tournament and team
 * ownership against the active tenant context. Cross-tenant access yields {@link
 * NoSuchElementException} → 404 (no tenant enumeration).
 *
 * <h2>Error handling</h2>
 *
 * <p>{@link de.vvwt.tm.web.GlobalExceptionHandler} covers {@code de.vvwt.tm.web} as a base package.
 * Photo domain exceptions from {@code de.vvwt.tm.photo.*} are absorbed directly by {@code
 * GlobalExceptionHandler} (E36S08 Phase 3 — {@code PhotoExceptionAdvice} deleted in Phase 2):
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.photo.PhotoFormatException} → 400
 *   <li>{@link de.vvwt.tm.photo.PhotoSizeException} → 400
 *   <li>{@link de.vvwt.tm.photo.PhotoStorageException} → 500
 *   <li>{@link java.util.NoSuchElementException} → 404 (via GlobalExceptionHandler)
 *   <li>{@link org.springframework.web.multipart.MaxUploadSizeExceededException} → 413 (via
 *       GlobalExceptionHandler)
 * </ul>
 *
 * <h2>DTO disposition (AC-DTO-DISPOSITION-CLAUSE-B)</h2>
 *
 * <p>{@link PhotoMetadataResponse} is preserved as web-internal DTO on DEC-40 Clause B(a)
 * field-omission grounds: {@link de.vvwt.tm.photo.PhotoFileMetadata} is the domain VO; the DTO
 * exposes only filename, sizeBytes, and uploadedAt (tenant-scoped or internal fields omitted).
 *
 * <h2>Trigger-β check</h2>
 *
 * <p>This controller imports from 2 bounded contexts: {@code photo} (PhotoStorageService, result
 * types, exceptions) and indirectly {@code tenant} (via PhotoStorageService's tenant-scoped
 * implementation). No method individually imports from more than 2 bounded contexts — Trigger β
 * does NOT fire (≤2 bounded contexts per method, L2 remains appropriate per DEC-40 Clause A).
 *
 * @see PhotoStorageService
 * @see de.vvwt.tm.web.GlobalExceptionHandler
 * @see PhotoMetadataResponse
 * @see de.vvwt.tm.auth.internal.SecurityConfig
 * @see DEC-40
 * @see E36S02
 */
@RestController
@RequestMapping("/api/photo/tournaments/{tournamentId}/teams/{teamId}")
public class TeamPhotoController {

    private final PhotoStorageService photoStorageService;

    public TeamPhotoController(PhotoStorageService photoStorageService) {
        this.photoStorageService = photoStorageService;
    }

    // -------------------------------------------------------------------------
    // AC1 — POST upload
    // -------------------------------------------------------------------------

    /**
     * Uploads (or replaces) the photo for a given team in a given tournament.
     *
     * <p>AC1: Accepts {@code multipart/form-data} with a single {@code file} part. Only JPEG and
     * PNG images are accepted. Returns 200 on success with photo metadata. Replaces any existing
     * photo for the team.
     *
     * <p>Requires admin authentication (AC-SECURITY-SUBSTANTIVE).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param teamId the team UUID (path variable)
     * @param file the multipart file to upload
     * @return 200 OK with {@link PhotoMetadataResponse} body
     * @throws IOException if reading the multipart stream fails
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoMetadataResponse> upload(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("teamId") UUID teamId,
            @RequestParam("file") MultipartFile file)
            throws IOException {

        try (InputStream inputStream = file.getInputStream()) {
            var metadata =
                    photoStorageService.upload(
                            tournamentId,
                            teamId,
                            file.getOriginalFilename() != null
                                    ? file.getOriginalFilename()
                                    : "photo",
                            inputStream,
                            file.getSize());
            return ResponseEntity.ok(PhotoMetadataResponse.from(metadata));
        }
    }

    // -------------------------------------------------------------------------
    // AC2 — GET retrieve
    // -------------------------------------------------------------------------

    /**
     * Returns the photo for a given team in a given tournament.
     *
     * <p>AC2: Returns the photo file with appropriate {@code Content-Type} ({@code image/jpeg} or
     * {@code image/png}). Returns 404 if no photo has been uploaded for this team.
     *
     * <p>Requires admin authentication (AC-SECURITY-SUBSTANTIVE).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param teamId the team UUID (path variable)
     * @return 200 with image content, or 404 if no photo exists
     */
    @GetMapping
    public ResponseEntity<InputStreamResource> retrieve(
            @PathVariable("tournamentId") UUID tournamentId, @PathVariable("teamId") UUID teamId) {

        Optional<PhotoStorageService.PhotoResult> maybeResult =
                photoStorageService.retrieve(tournamentId, teamId);

        if (maybeResult.isEmpty()) {
            throw new NoSuchElementException(
                    "No photo found for team=" + teamId + " in tournament=" + tournamentId);
        }

        PhotoStorageService.PhotoResult result = maybeResult.get();
        MediaType mediaType = MediaType.parseMediaType(result.contentType());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        headers.setContentDisposition(
                ContentDisposition.inline().filename(result.metadata().filename()).build());

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(result.inputStream()));
    }

    // -------------------------------------------------------------------------
    // AC3 — DELETE
    // -------------------------------------------------------------------------

    /**
     * Removes the photo for a given team in a given tournament.
     *
     * <p>AC3: Returns 204 on success, 404 if no photo exists for this team.
     *
     * <p>Requires admin authentication (AC-SECURITY-SUBSTANTIVE).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param teamId the team UUID (path variable)
     * @return 204 No Content on success
     */
    @DeleteMapping
    public ResponseEntity<Void> delete(
            @PathVariable("tournamentId") UUID tournamentId, @PathVariable("teamId") UUID teamId) {

        boolean deleted = photoStorageService.delete(tournamentId, teamId);

        if (!deleted) {
            throw new NoSuchElementException(
                    "No photo found for team=" + teamId + " in tournament=" + tournamentId);
        }

        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
