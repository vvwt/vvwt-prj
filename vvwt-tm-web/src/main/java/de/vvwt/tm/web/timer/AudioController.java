package de.vvwt.tm.web.timer;

import de.vvwt.tm.timer.audio.AudioCategory;
import de.vvwt.tm.timer.audio.AudioFileMetadata;
import de.vvwt.tm.timer.audio.AudioStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST controller for tournament audio file management (E26S03 Q-1a TDD reconstruction).
 *
 * <h2>Endpoints (Wave-2-aligned URLs per Brief v1.1 D-10)</h2>
 *
 * <ul>
 *   <li>POST /api/audio/tournaments/{tournamentId}/{category} — upload (admin auth required)
 *   <li>GET /api/audio/tournaments/{tournamentId}/{category}/stream — stream (permitAll)
 *   <li>GET /api/audio/tournaments/{tournamentId} — list (admin auth required)
 *   <li>DELETE /api/audio/tournaments/{tournamentId}/{category} — delete (admin auth required)
 * </ul>
 *
 * <h2>Pattern A (DEC-40 §2026-04-27 Clarification)</h2>
 *
 * <p>The return type {@link AudioFileMetadata} is a bounded-context-owned Pattern A record at
 * {@code de.vvwt.tm.timer.audio.*} (per E26S02 placement). No web-tier DTO mapping is needed
 * (projection == wire shape; Clause B (a)/(c)/(d) conditions do NOT fire). The legacy {@code
 * AudioMetadataResponse} 1:1 wrapper was consolidated into {@link AudioFileMetadata} by E26S02 —
 * it is NOT re-authored here. {@code AudioController.list} returns
 * {@code List<AudioFileMetadata>} directly; no factory-method mapping invoked
 * (AC-NO-LEGACY-AUDIO-METADATA-RESPONSE-RE-AUTHORING).
 *
 * <h2>Authentication (AC-AUTHENTICATION-FLOW-PRESERVED)</h2>
 *
 * <p>The streaming endpoint ({@code /stream}) is accessible without authentication (timer page
 * has no auth and must preload audio files). Upload, list, and delete require admin HTTP Basic
 * auth via {@link de.vvwt.tm.auth.internal.SecurityConfig} (all other {@code /api/**} endpoints
 * are already protected by the catch-all rule). The streaming path
 * {@code /api/audio/tournaments/{id}/{cat}/stream} is in the {@code permitAll()} list in
 * {@code SecurityConfig} (AC-SECURITY-CONFIG-URL-3-AUDIO-STREAM).
 *
 * <h2>Tenant scoping</h2>
 *
 * <p>All endpoints delegate to {@link AudioStorageService}, which validates tournament ownership
 * against the active tenant context. A missing/cross-tenant tournament results in
 * {@link NoSuchElementException} → 404 (no tenant enumeration).
 *
 * <h2>Error handling</h2>
 *
 * <p>{@link de.vvwt.tm.web.GlobalExceptionHandler} maps domain exceptions:
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.timer.audio.AudioFormatException} → 415
 *   <li>{@link de.vvwt.tm.timer.audio.AudioSizeLimitException} → 413
 *   <li>{@link de.vvwt.tm.timer.audio.AudioStorageException} → 500
 *   <li>{@link NoSuchElementException} → 404
 *   <li>{@link org.springframework.web.multipart.MaxUploadSizeExceededException} → 413
 * </ul>
 *
 * @see AudioStorageService
 * @see de.vvwt.tm.timer.audio.AudioFileMetadata
 * @see de.vvwt.tm.auth.internal.SecurityConfig
 * @since E26S03
 */
@RestController
@RequestMapping("/api/audio/tournaments/{tournamentId}")
public class AudioController {

    private static final MediaType AUDIO_MPEG = new MediaType("audio", "mpeg");

    private final AudioStorageService audioStorageService;

    public AudioController(AudioStorageService audioStorageService) {
        this.audioStorageService = audioStorageService;
    }

    // -------------------------------------------------------------------------
    // POST /api/audio/tournaments/{tournamentId}/{category}
    // -------------------------------------------------------------------------

    /**
     * Uploads (or replaces) the audio file for a given tournament and category.
     *
     * <p>Accepts multipart/form-data with a single {@code file} part. Only {@code .mp3} files are
     * accepted. Returns 201 with {@link AudioFileMetadata} body. Uploading to a category that
     * already has a file replaces it.
     *
     * <p>Requires admin authentication.
     *
     * <p>Pattern A (DEC-40 §2026-04-27 Clarification): return type {@link AudioFileMetadata} is
     * bounded-context-owned at {@code de.vvwt.tm.timer.audio.*}. No web-tier DTO mapping needed.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param category the audio category — START, END, or PAUSE (path variable, case-insensitive)
     * @param file the multipart file to upload
     * @return 201 Created with {@link AudioFileMetadata} body and Location header
     */
    @PostMapping(value = "/{category}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AudioFileMetadata> upload(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("category") String category,
            @RequestParam("file") MultipartFile file)
            throws IOException {

        AudioCategory audioCategory = parseCategory(category);

        try (InputStream inputStream = file.getInputStream()) {
            AudioFileMetadata metadata =
                    audioStorageService.upload(
                            tournamentId,
                            audioCategory,
                            file.getOriginalFilename() != null
                                    ? file.getOriginalFilename()
                                    : audioCategory.toFileName(),
                            inputStream,
                            file.getSize());

            URI location =
                    ServletUriComponentsBuilder.fromCurrentRequest()
                            .replacePath(
                                    "/api/audio/tournaments/{tournamentId}/{category}/stream")
                            .buildAndExpand(tournamentId, category.toUpperCase())
                            .toUri();

            return ResponseEntity.created(location).body(metadata);
        }
    }

    // -------------------------------------------------------------------------
    // GET /api/audio/tournaments/{tournamentId}/{category}/stream
    // -------------------------------------------------------------------------

    /**
     * Streams the audio file for a given tournament and category.
     *
     * <p>Returns the file content with {@code Content-Type: audio/mpeg}. Returns 404 if no file
     * has been uploaded for that category.
     *
     * <p>This endpoint is accessible without authentication — the timer page has no auth and must
     * preload audio files. The path pattern {@code /api/audio/tournaments/{id}/{cat}/stream} is
     * listed in {@link de.vvwt.tm.auth.internal.SecurityConfig} as {@code permitAll()}
     * (AC-SECURITY-CONFIG-URL-3-AUDIO-STREAM).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param category the audio category (path variable, case-insensitive)
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
                    "No audio file found for tournament="
                            + tournamentId
                            + " category="
                            + audioCategory);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(AUDIO_MPEG);
        headers.setContentDisposition(
                ContentDisposition.inline().filename(audioCategory.toFileName()).build());

        return ResponseEntity.ok().headers(headers).body(new InputStreamResource(maybeStream.get()));
    }

    // -------------------------------------------------------------------------
    // GET /api/audio/tournaments/{tournamentId}
    // -------------------------------------------------------------------------

    /**
     * Returns metadata for all uploaded audio files of a tournament.
     *
     * <p>Categories without a file are omitted. Returns empty array if no files uploaded. Requires
     * admin authentication.
     *
     * <p>Pattern A (DEC-40 §2026-04-27 Clarification): return type {@code List<AudioFileMetadata>}
     * is bounded-context-owned. No factory-method mapping (AC-NO-LEGACY-AUDIO-METADATA-RESPONSE-RE-AUTHORING).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 with list of {@link AudioFileMetadata}
     */
    @GetMapping
    public ResponseEntity<List<AudioFileMetadata>> list(
            @PathVariable("tournamentId") UUID tournamentId) {

        return ResponseEntity.ok(audioStorageService.list(tournamentId));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/audio/tournaments/{tournamentId}/{category}
    // -------------------------------------------------------------------------

    /**
     * Removes the audio file for a given tournament and category.
     *
     * <p>Returns 204 on success, 404 if no file exists for that category. Requires admin
     * authentication.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param category the audio category (path variable, case-insensitive)
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
                    "No audio file found for tournament="
                            + tournamentId
                            + " category="
                            + audioCategory);
        }

        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Parses a path variable string into an {@link AudioCategory} enum value. Case-insensitive —
     * {@code "start"}, {@code "START"}, and {@code "Start"} are all valid.
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
                    "Unknown audio category: '" + value + "'. Valid values: START, END, PAUSE");
        }
    }
}
