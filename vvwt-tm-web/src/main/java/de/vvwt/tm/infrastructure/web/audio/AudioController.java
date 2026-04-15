package de.vvwt.tm.infrastructure.web.audio;

import de.vvwt.tm.domain.audio.AudioCategory;
import de.vvwt.tm.domain.audio.AudioFileMetadata;
import de.vvwt.tm.domain.audio.AudioStorageService;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for tournament audio file management (E11S01).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>POST   /api/tournaments/{tournamentId}/audio/{category}        — upload (AC1)</li>
 *   <li>GET    /api/tournaments/{tournamentId}/audio/{category}/stream  — stream (AC2, public)</li>
 *   <li>GET    /api/tournaments/{tournamentId}/audio                   — list (AC3)</li>
 *   <li>DELETE /api/tournaments/{tournamentId}/audio/{category}        — delete (AC4)</li>
 * </ul>
 *
 * <h2>Authentication (AC5a)</h2>
 * <p>The streaming endpoint ({@code /stream}) is accessible without authentication — the timer
 * page has no auth and must preload audio files. Upload, list, and delete require admin HTTP Basic
 * auth via {@link de.vvwt.tm.auth.SecurityConfig} (all other {@code /api/**} endpoints are
 * already protected). The streaming path is added to the permitAll list in {@code SecurityConfig}.
 *
 * <h2>Tenant scoping (AC5, DEC-5)</h2>
 * <p>All endpoints delegate to {@link AudioStorageService}, which validates tournament ownership
 * against the active {@link de.vvwt.tm.domain.repo.TenantContext} before any I/O.
 * A missing/cross-tenant tournament results in {@link NoSuchElementException} → 404 (no tenant
 * enumeration per AC5).
 *
 * <h2>Error handling (AC7)</h2>
 * <p>{@link de.vvwt.tm.infrastructure.web.GlobalExceptionHandler} maps domain exceptions:
 * <ul>
 *   <li>{@link de.vvwt.tm.domain.audio.AudioFormatException} → 415</li>
 *   <li>{@link de.vvwt.tm.domain.audio.AudioSizeLimitException} → 413</li>
 *   <li>{@link de.vvwt.tm.domain.audio.AudioStorageException} → 500</li>
 *   <li>{@link java.util.NoSuchElementException} → 404</li>
 *   <li>{@link org.springframework.web.multipart.MaxUploadSizeExceededException} → 413</li>
 * </ul>
 *
 * @see AudioStorageService
 * @see de.vvwt.tm.auth.SecurityConfig
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story E11S01</a>
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/audio")
public class AudioController {

    private static final MediaType AUDIO_MPEG = new MediaType("audio", "mpeg");

    private final AudioStorageService audioStorageService;

    public AudioController(AudioStorageService audioStorageService) {
        this.audioStorageService = audioStorageService;
    }

    // -------------------------------------------------------------------------
    // AC1 — POST /api/tournaments/{tournamentId}/audio/{category}
    // -------------------------------------------------------------------------

    /**
     * Uploads (or replaces) the audio file for a given tournament and category.
     *
     * <p>AC1: Accepts multipart/form-data with a single {@code file} part.
     * Only {@code .mp3} files are accepted. Returns 201 with audio metadata.
     * Uploading to a category that already has a file replaces it.
     *
     * <p>Requires admin authentication (AC5a — upload is admin-only).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param category     the audio category — START, END, or PAUSE (path variable, case-insensitive)
     * @param file         the multipart file to upload
     * @return 201 Created with {@link AudioMetadataResponse} body and Location header
     */
    @PostMapping(value = "/{category}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AudioMetadataResponse> upload(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("category") String category,
            @RequestParam("file") MultipartFile file) throws IOException {

        AudioCategory audioCategory = parseCategory(category);

        try (InputStream inputStream = file.getInputStream()) {
            AudioFileMetadata metadata = audioStorageService.upload(
                    tournamentId,
                    audioCategory,
                    file.getOriginalFilename() != null ? file.getOriginalFilename() : audioCategory.toFileName(),
                    inputStream,
                    file.getSize()
            );

            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .replacePath("/api/tournaments/{tournamentId}/audio/{category}/stream")
                    .buildAndExpand(tournamentId, category.toUpperCase())
                    .toUri();

            return ResponseEntity.created(location).body(AudioMetadataResponse.from(metadata));
        }
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/tournaments/{tournamentId}/audio/{category}/stream
    // -------------------------------------------------------------------------

    /**
     * Streams the audio file for a given tournament and category.
     *
     * <p>AC2: Returns the file content with {@code Content-Type: audio/mpeg}.
     * Returns 404 if no file has been uploaded for that category.
     *
     * <p>AC5a: This endpoint is accessible without authentication — the timer page has
     * no auth and must preload audio files. The path pattern
     * {@code /api/tournaments/*\/audio/*\/stream} is listed in {@link de.vvwt.tm.auth.SecurityConfig}
     * as {@code permitAll()}.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param category     the audio category (path variable, case-insensitive)
     * @return 200 with audio/mpeg content, or 404 if no file exists
     */
    @GetMapping("/{category}/stream")
    public ResponseEntity<InputStreamResource> stream(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("category") String category) {

        AudioCategory audioCategory = parseCategory(category);
        Optional<InputStream> maybeStream = audioStorageService.stream(tournamentId, audioCategory);

        if (maybeStream.isEmpty()) {
            throw new NoSuchElementException(
                    "No audio file found for tournament=" + tournamentId
                    + " category=" + audioCategory);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(AUDIO_MPEG);
        headers.setContentDisposition(ContentDisposition.inline()
                .filename(audioCategory.toFileName())
                .build());

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(maybeStream.get()));
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/tournaments/{tournamentId}/audio
    // -------------------------------------------------------------------------

    /**
     * Returns metadata for all uploaded audio files of a tournament.
     *
     * <p>AC3: Categories without a file are omitted. Returns empty array if no files uploaded.
     * Requires admin authentication (AC5a — list is admin-only).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with list of {@link AudioMetadataResponse}
     */
    @GetMapping
    public ResponseEntity<List<AudioMetadataResponse>> list(
            @PathVariable("tournamentId") UUID tournamentId) {

        List<AudioMetadataResponse> responses = audioStorageService.list(tournamentId).stream()
                .map(AudioMetadataResponse::from)
                .toList();

        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // AC4 — DELETE /api/tournaments/{tournamentId}/audio/{category}
    // -------------------------------------------------------------------------

    /**
     * Removes the audio file for a given tournament and category.
     *
     * <p>AC4: Returns 204 on success, 404 if no file exists for that category.
     * Requires admin authentication (AC5a — delete is admin-only).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param category     the audio category (path variable, case-insensitive)
     * @return 204 No Content on success
     */
    @DeleteMapping("/{category}")
    public ResponseEntity<Void> delete(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("category") String category) {

        AudioCategory audioCategory = parseCategory(category);
        boolean deleted = audioStorageService.delete(tournamentId, audioCategory);

        if (!deleted) {
            throw new NoSuchElementException(
                    "No audio file found for tournament=" + tournamentId
                    + " category=" + audioCategory);
        }

        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a path variable string into an {@link AudioCategory} enum value.
     * Case-insensitive — {@code "start"}, {@code "START"}, and {@code "Start"} are all valid.
     *
     * @param value the path variable value
     * @return the corresponding {@link AudioCategory}
     * @throws NoSuchElementException if the value is not a valid category name (→ 404 via handler)
     */
    private AudioCategory parseCategory(String value) {
        try {
            return AudioCategory.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new NoSuchElementException(
                    "Unknown audio category: '" + value
                    + "'. Valid values: START, END, PAUSE");
        }
    }
}
