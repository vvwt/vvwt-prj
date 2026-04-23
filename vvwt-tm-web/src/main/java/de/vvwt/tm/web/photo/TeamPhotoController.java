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
 * REST controller for tournament-scoped team photo management (E23S04, DEC-40 Clause D).
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.infrastructure.web.photo.TeamPhotoController} to
 * the canonical {@code de.vvwt.tm.web.photo} package per DEC-40 Clause D
 * (Primary-Adapter-Isolation) and DEC-22 §refactor-clause (Q-1b whole-class relocation,
 * byte-identical body). Analog to E22S07 tournament controller relocation.
 *
 * <h2>Endpoints (E23S05 Cutover-1 — new URL)</h2>
 *
 * <ul>
 *   <li>POST /api/photo/tournaments/{tournamentId}/teams/{teamId} — upload (AC1)
 *   <li>GET /api/photo/tournaments/{tournamentId}/teams/{teamId} — retrieve (AC2)
 *   <li>DELETE /api/photo/tournaments/{tournamentId}/teams/{teamId} — delete (AC3)
 * </ul>
 *
 * <h2>URL rename (E23S05 Cutover-1)</h2>
 *
 * <p>URL renamed atomically at E23S05 Cutover-1 from {@code
 * /api/tournaments/{tid}/teams/{teamId}/photo} to {@code
 * /api/photo/tournaments/{tid}/teams/{teamId}} per DEC-21 per-context atomic cutover, DEC-40
 * precedent (E22S08 URL-rename pattern), and DEC-22 Q-1b §refactor-clause.
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
 * <h2>Error handling (AC-ERROR-HANDLING)</h2>
 *
 * <p>{@link de.vvwt.tm.tournament.internal.web.GlobalExceptionHandler} covers {@code
 * de.vvwt.tm.web} as a base package (E22S07). Photo domain exceptions from {@code
 * de.vvwt.tm.photo.*} are handled by {@link PhotoExceptionAdvice} (E23S04):
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
 * <h2>Post-Cutover-1 (E23S05)</h2>
 *
 * <p>The legacy {@code de.vvwt.tm.infrastructure.web.photo.TeamPhotoController} has been deleted at
 * E23S05 Cutover-1. The {@code @ComponentScan(excludeFilters)} in {@link
 * de.vvwt.tm.TournamentManagerApplication} has been removed atomically.
 *
 * <h2>Trigger-β check (AC-TRIGGER-BETA-CHECK)</h2>
 *
 * <p>This controller imports from 2 bounded contexts: {@code photo} (PhotoStorageService, result
 * types, exceptions) and indirectly {@code tenant} (via PhotoStorageService's tenant-scoped
 * implementation). No method individually imports from more than 2 bounded contexts — Trigger β
 * does NOT fire (≤2 bounded contexts per method, L2 remains appropriate).
 *
 * <h2>DTO disposition (AC-DTO-DISPOSITION-CLAUSE-B)</h2>
 *
 * <p>{@link PhotoMetadataResponse} is preserved as web-internal DTO on DEC-40 Clause B(a)
 * field-omission grounds: {@link de.vvwt.tm.photo.PhotoFileMetadata} is the domain VO; the DTO
 * exposes only filename, sizeBytes, and uploadedAt (tenant-scoped or internal fields omitted).
 *
 * @see PhotoStorageService
 * @see PhotoExceptionAdvice
 * @see PhotoMetadataResponse
 * @see de.vvwt.tm.auth.internal.SecurityConfig
 * @see DEC-40
 * @see E23S04
 * @see E23S05
 */
@RestController
@RequestMapping("/api/photo/tournaments/{tournamentId}/teams/{teamId}")
public class TeamPhotoController {

    private final PhotoStorageService photoStorageService;

    public TeamPhotoController(PhotoStorageService photoStorageService) {
        this.photoStorageService = photoStorageService;
    }

    // -------------------------------------------------------------------------
    // AC1 — POST /api/tournaments/{tournamentId}/teams/{teamId}/photo
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
    // AC2 — GET /api/tournaments/{tournamentId}/teams/{teamId}/photo
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
    // AC3 — DELETE /api/tournaments/{tournamentId}/teams/{teamId}/photo
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
