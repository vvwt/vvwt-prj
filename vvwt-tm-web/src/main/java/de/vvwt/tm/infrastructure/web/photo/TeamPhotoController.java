package de.vvwt.tm.infrastructure.web.photo;

import de.vvwt.tm.domain.photo.PhotoStorageService;
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
 * REST controller for tournament-scoped team photo management (E12S02).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/tournaments/{tournamentId}/teams/{teamId}/photo — upload (AC1)
 *   <li>GET /api/tournaments/{tournamentId}/teams/{teamId}/photo — retrieve (AC2)
 *   <li>DELETE /api/tournaments/{tournamentId}/teams/{teamId}/photo — delete (AC3)
 * </ul>
 *
 * <h2>Authentication (AC10)</h2>
 *
 * <p>All endpoints are under {@code /api/**} which requires admin HTTP Basic auth via {@link
 * de.vvwt.tm.auth.SecurityConfig}. No public access — photo uploads are admin-only.
 *
 * <h2>Tenant scoping (AC10, DEC-5)</h2>
 *
 * <p>All endpoints delegate to {@link PhotoStorageService}, which validates tournament and team
 * ownership against the active {@link de.vvwt.tm.domain.repo.TenantContext}. Cross-tenant access
 * yields {@link NoSuchElementException} → 404 (no tenant enumeration).
 *
 * <h2>Error handling (AC7, AC8)</h2>
 *
 * <p>{@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} maps domain exceptions:
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.domain.photo.PhotoFormatException} → 400
 *   <li>{@link de.vvwt.tm.domain.photo.PhotoSizeException} → 400
 *   <li>{@link de.vvwt.tm.domain.photo.PhotoStorageException} → 500
 *   <li>{@link java.util.NoSuchElementException} → 404
 *   <li>{@link org.springframework.web.multipart.MaxUploadSizeExceededException} → 413
 * </ul>
 *
 * @see PhotoStorageService
 * @see de.vvwt.tm.auth.SecurityConfig
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02</a>
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/teams/{teamId}/photo")
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
     * <p>Requires admin authentication (AC10).
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
     * <p>Requires admin authentication (AC10).
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
     * <p>Requires admin authentication (AC10).
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
