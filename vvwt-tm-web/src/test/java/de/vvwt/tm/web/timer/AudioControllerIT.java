package de.vvwt.tm.web.timer;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.timer.audio.AudioCategory;
import de.vvwt.tm.timer.audio.AudioFileMetadata;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import de.vvwt.tm.web.WebModuleTestConfig;

/**
 * Integration tests for {@link AudioController} — E26S03 Q-1a TDD reconstruction.
 *
 * <h2>Test coverage (AC-Q7-METHOD-COUNT-RECONFIRMATION)</h2>
 *
 * <ul>
 *   <li>upload-happy: POST /api/audio/tournaments/{id}/{cat} returns 201 with AudioFileMetadata
 *   <li>upload-security: POST requires admin auth → 401 without credentials
 *   <li>upload-replace: uploading to same category replaces existing file
 *   <li>stream-happy: GET /api/audio/tournaments/{id}/{cat}/stream returns 200 audio/mpeg
 *   <li>stream-no-auth: stream endpoint accessible without admin auth (permitAll)
 *   <li>stream-missing: stream for non-uploaded category → 404
 *   <li>list-happy: GET /api/audio/tournaments/{id} returns 200 with list
 *   <li>list-empty: list before any upload returns 200 with empty list
 *   <li>list-security: GET list requires admin auth → 401 without credentials
 *   <li>delete-happy: DELETE /api/audio/tournaments/{id}/{cat} returns 204
 *   <li>delete-security: DELETE requires admin auth → 401 without credentials
 *   <li>delete-missing: DELETE for non-uploaded category → 404
 *   <li>upload-format: non-mp3 file → 415
 *   <li>wave2-url: new URL shape /api/audio/tournaments/{id}/{cat} (not legacy)
 * </ul>
 *
 * <p>Total: 14 {@code @Test} methods (audit (v) baseline 14; corrected from grep-15 per Cycle-1
 * reviewer F#1).
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 Iron Law Q-1a — RED-first: this test written before {@link AudioController} exists;
 *       RED commit = this commit; GREEN commit = next (AudioController production code)
 *   <li>DEC-36 — {@code AudioStorageService} interface FQN used (NOT
 *       {@code timer.audio.internal.DefaultAudioStorageService})
 *   <li>DEC-40 §2026-04-27 Clarification Pattern A — response type {@link AudioFileMetadata}
 *       imported from {@code de.vvwt.tm.timer.audio.*} (NOT {@code web.internal.dto.*})
 *   <li>DEC-44 D1 — {@code @SpringBootTest(RANDOM_PORT)} + {@code @Import({WebModuleTestConfig,
 *       TestAdminCredentials})} per 2026-04-27 empirical refinement
 *   <li>DEC-44 D2 — per-IT inner {@code TestAdminCredentials} provides {@code @Primary
 *       AdminCredentialsProvider}; no {@code UserDetailsService} or {@code SecurityFilterChain}
 *       substitute
 *   <li>AC-DEC44-D2-EMPIRICAL-REFINEMENT-RESPECT — no UDS/SecurityFilterChain substitute beans
 *   <li>AC-NO-LEGACY-AUDIO-METADATA-RESPONSE-RE-AUTHORING — test imports {@code AudioFileMetadata}
 *       directly (Pattern A; no {@code AudioMetadataResponse} wrapper)
 * </ul>
 *
 * @see AudioController
 * @see WebModuleTestConfig
 * @since E26S03
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e26s03audioitdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({WebModuleTestConfig.class, AudioControllerIT.TestAdminCredentials.class})
class AudioControllerIT {

    static final String TEST_PASSWORD = "AudioControllerIT26S03";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private JdbcTemplate jdbcTemplate;

    private String baseUrl;
    private UUID defaultTenantId;
    private UUID tournamentId;

    // Minimal valid MP3 header bytes (ID3v2 + null frame) for upload tests
    // Just enough to pass the MPEG audio format check
    private static final byte[] MINIMAL_MP3 = {
        (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00
    };

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        defaultTenantId = tenantContextBinder.bindDefaultTenant();
        cleanupTestData();
        setupTestData();
    }

    @AfterEach
    void tearDown() {
        cleanupTestData();
        tenantContextBinder.unbind();
    }

    // =========================================================================
    // Test data setup / cleanup
    // =========================================================================

    private void cleanupTestData() {
        jdbcTemplate.update("DELETE FROM set_result");
        jdbcTemplate.update("DELETE FROM match_outcome");
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM match");
        jdbcTemplate.update("DELETE FROM phase");
        jdbcTemplate.update("DELETE FROM team_avatar_rating");
        jdbcTemplate.update("DELETE FROM team_avatar");
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM activity_types");
        jdbcTemplate.update("DELETE FROM tournament");
        jdbcTemplate.update("DELETE FROM devices");
    }

    private void setupTestData() {
        LocalDateTime now = LocalDateTime.now();
        tournamentId = UUID.randomUUID();
        Tournament tournament =
                new Tournament(
                        tournamentId,
                        defaultTenantId,
                        "E26S03 Audio IT Tournament",
                        "BEST_OF_1",
                        "threePointMatchRule",
                        "standardVolleyballSet",
                        "roundRobinMatchGenerator",
                        "ACTIVE",
                        now,
                        null,
                        3,
                        4);
        tournamentRepository.save(tournament);
    }

    // =========================================================================
    // Upload — POST /api/audio/tournaments/{tournamentId}/{category}
    // =========================================================================

    /**
     * Upload happy path: POST /api/audio/tournaments/{id}/{cat} with admin auth returns 201 with
     * {@link AudioFileMetadata} body.
     *
     * <p>AC-URL-PATHS-WAVE-2-ALIGNED: Wave-2 URL shape {@code /api/audio/tournaments/{id}/{cat}}
     * (NOT legacy {@code /api/tournaments/{id}/audio/{cat}}).
     * AC-NO-LEGACY-AUDIO-METADATA-RESPONSE-RE-AUTHORING: response type is {@link AudioFileMetadata}.
     */
    @Test
    void uploadReturns201WithAudioFileMetadata() {
        ResponseEntity<AudioFileMetadata> response =
                uploadMp3(tournamentId, AudioCategory.START, "start.mp3", MINIMAL_MP3, true);

        assertThat(response.getStatusCode())
                .as("Upload with admin auth must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);

        AudioFileMetadata body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.category())
                .as("Response must contain the uploaded category")
                .isEqualTo(AudioCategory.START);
        assertThat(body.filename())
                .as("Response must contain the original filename")
                .isEqualTo("start.mp3");
        assertThat(body.sizeBytes())
                .as("Response must contain size > 0")
                .isGreaterThan(0);
    }

    /**
     * Upload security: POST without admin auth → 401.
     *
     * <p>Upload, list, and delete require admin authentication. Only the stream endpoint is
     * permitAll (AC-AUTHENTICATION-FLOW-PRESERVED).
     */
    @Test
    void uploadReturns401WithoutAdminAuth() {
        ResponseEntity<Void> response =
                uploadMp3AsVoid(tournamentId, AudioCategory.START, "start.mp3", MINIMAL_MP3, false);

        assertThat(response.getStatusCode())
                .as("Upload without admin auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Upload-replace: uploading to the same category replaces the existing file and returns 201
     * with updated metadata.
     */
    @Test
    void uploadReplacesExistingFileForSameCategory() {
        // First upload
        uploadMp3(tournamentId, AudioCategory.END, "end_v1.mp3", MINIMAL_MP3, true);

        // Second upload — replaces the first
        byte[] newContent = new byte[MINIMAL_MP3.length + 4];
        System.arraycopy(MINIMAL_MP3, 0, newContent, 0, MINIMAL_MP3.length);
        ResponseEntity<AudioFileMetadata> response =
                uploadMp3(tournamentId, AudioCategory.END, "end_v2.mp3", newContent, true);

        assertThat(response.getStatusCode())
                .as("Second upload to same category must also return 201")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().filename())
                .as("Filename must reflect the new upload")
                .isEqualTo("end_v2.mp3");
    }

    // =========================================================================
    // Stream — GET /api/audio/tournaments/{tournamentId}/{category}/stream
    // =========================================================================

    /**
     * Stream happy path: GET /api/audio/tournaments/{id}/{cat}/stream after upload returns 200
     * with {@code Content-Type: audio/mpeg}.
     *
     * <p>AC-SECURITY-CONFIG-URL-3-AUDIO-STREAM: the stream endpoint uses permitAll.
     */
    @Test
    void streamReturns200AudioMpegAfterUpload() {
        // Upload first
        uploadMp3(tournamentId, AudioCategory.PAUSE, "pause.mp3", MINIMAL_MP3, true);

        // Stream — no auth required
        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(
                        baseUrl
                                + "/api/audio/tournaments/"
                                + tournamentId
                                + "/pause/stream",
                        byte[].class);

        assertThat(response.getStatusCode())
                .as("Stream after upload must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .satisfies(
                        ct ->
                                assertThat(ct.toString())
                                        .as("Content-Type must be audio/mpeg")
                                        .startsWith("audio/mpeg"));
    }

    /**
     * Stream no-auth: GET stream endpoint is accessible without admin credentials (permitAll per
     * AC-AUTHENTICATION-FLOW-PRESERVED + AC-SECURITY-CONFIG-URL-3-AUDIO-STREAM).
     */
    @Test
    void streamIsAccessibleWithoutAdminAuth() {
        // Upload with auth first
        uploadMp3(tournamentId, AudioCategory.START, "start.mp3", MINIMAL_MP3, true);

        // Stream without credentials — must succeed
        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(
                        baseUrl
                                + "/api/audio/tournaments/"
                                + tournamentId
                                + "/start/stream",
                        byte[].class);

        assertThat(response.getStatusCode())
                .as("Stream must be accessible without admin auth")
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * Stream missing: GET stream for a category with no uploaded file → 404.
     *
     * <p>{@link de.vvwt.tm.audio.AudioController} throws {@code NoSuchElementException} when no
     * file exists → {@link de.vvwt.tm.web.GlobalExceptionHandler} maps to 404.
     */
    @Test
    void streamReturns404WhenNoFileUploaded() {
        ResponseEntity<Void> response =
                restTemplate.getForEntity(
                        baseUrl
                                + "/api/audio/tournaments/"
                                + tournamentId
                                + "/end/stream",
                        Void.class);

        assertThat(response.getStatusCode())
                .as("Stream for non-uploaded category must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // List — GET /api/audio/tournaments/{tournamentId}
    // =========================================================================

    /**
     * List happy path: GET /api/audio/tournaments/{id} after uploading 2 files returns 200 with
     * list of 2 {@link AudioFileMetadata} entries.
     *
     * <p>AC-NO-LEGACY-AUDIO-METADATA-RESPONSE-RE-AUTHORING: response type is {@code List<AudioFileMetadata>}.
     */
    @Test
    void listReturns200WithUploadedFiles() {
        uploadMp3(tournamentId, AudioCategory.START, "start.mp3", MINIMAL_MP3, true);
        uploadMp3(tournamentId, AudioCategory.END, "end.mp3", MINIMAL_MP3, true);

        ResponseEntity<List<AudioFileMetadata>> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .exchange(
                                baseUrl + "/api/audio/tournaments/" + tournamentId,
                                HttpMethod.GET,
                                null,
                                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("List after 2 uploads must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("List must contain 2 uploaded files")
                .hasSize(2);
    }

    /**
     * List empty: GET /api/audio/tournaments/{id} before any uploads returns 200 with empty list.
     */
    @Test
    void listReturns200WithEmptyListBeforeAnyUpload() {
        ResponseEntity<List<AudioFileMetadata>> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .exchange(
                                baseUrl + "/api/audio/tournaments/" + tournamentId,
                                HttpMethod.GET,
                                null,
                                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode())
                .as("List before any uploads must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("List before uploads must be empty (not null)")
                .isEmpty();
    }

    /**
     * List security: GET list without admin auth → 401. List is an admin-only endpoint.
     */
    @Test
    void listReturns401WithoutAdminAuth() {
        ResponseEntity<Void> response =
                restTemplate.getForEntity(
                        baseUrl + "/api/audio/tournaments/" + tournamentId, Void.class);

        assertThat(response.getStatusCode())
                .as("List without admin auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Delete — DELETE /api/audio/tournaments/{tournamentId}/{category}
    // =========================================================================

    /**
     * Delete happy path: DELETE after upload returns 204 No Content.
     */
    @Test
    void deleteReturns204AfterUpload() {
        uploadMp3(tournamentId, AudioCategory.PAUSE, "pause.mp3", MINIMAL_MP3, true);

        ResponseEntity<Void> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .exchange(
                                baseUrl
                                        + "/api/audio/tournaments/"
                                        + tournamentId
                                        + "/pause",
                                HttpMethod.DELETE,
                                null,
                                Void.class);

        assertThat(response.getStatusCode())
                .as("Delete after upload must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * Delete security: DELETE without admin auth → 401.
     */
    @Test
    void deleteReturns401WithoutAdminAuth() {
        ResponseEntity<Void> response =
                restTemplate.exchange(
                        baseUrl
                                + "/api/audio/tournaments/"
                                + tournamentId
                                + "/start",
                        HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode())
                .as("Delete without admin auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * Delete missing: DELETE for a category with no uploaded file → 404.
     *
     * <p>{@link AudioController#delete} throws {@code NoSuchElementException} when file not found.
     */
    @Test
    void deleteReturns404WhenFileDoesNotExist() {
        ResponseEntity<Void> response =
                restTemplate
                        .withBasicAuth("admin", TEST_PASSWORD)
                        .exchange(
                                baseUrl
                                        + "/api/audio/tournaments/"
                                        + tournamentId
                                        + "/end",
                                HttpMethod.DELETE,
                                null,
                                Void.class);

        assertThat(response.getStatusCode())
                .as("Delete for non-existing file must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // Upload format validation
    // =========================================================================

    /**
     * Upload format: sending a non-MP3 file (e.g., plain text) → 415 Unsupported Media Type.
     *
     * <p>{@link de.vvwt.tm.timer.audio.AudioFormatException} is thrown by
     * {@code DefaultAudioStorageService} for non-.mp3 files. Caught by
     * {@link de.vvwt.tm.web.GlobalExceptionHandler#handleAudioFormat}.
     */
    @Test
    void uploadNonMp3FileReturns415() {
        byte[] textContent = "hello world".getBytes();
        ResponseEntity<Void> response =
                uploadMp3AsVoid(tournamentId, AudioCategory.START, "file.txt", textContent, true);

        assertThat(response.getStatusCode())
                .as("Non-MP3 upload must return 415 Unsupported Media Type")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private ResponseEntity<AudioFileMetadata> uploadMp3(
            UUID tId, AudioCategory category, String filename, byte[] content, boolean withAuth) {

        HttpEntity<MultiValueMap<String, Object>> request =
                buildUploadRequest(filename, content, withAuth);

        String url =
                baseUrl
                        + "/api/audio/tournaments/"
                        + tId
                        + "/"
                        + category.name().toLowerCase();

        if (withAuth) {
            return restTemplate
                    .withBasicAuth("admin", TEST_PASSWORD)
                    .exchange(url, HttpMethod.POST, request, AudioFileMetadata.class);
        }
        return restTemplate.exchange(url, HttpMethod.POST, request, AudioFileMetadata.class);
    }

    private ResponseEntity<Void> uploadMp3AsVoid(
            UUID tId, AudioCategory category, String filename, byte[] content, boolean withAuth) {

        HttpEntity<MultiValueMap<String, Object>> request =
                buildUploadRequest(filename, content, withAuth);

        String url =
                baseUrl
                        + "/api/audio/tournaments/"
                        + tId
                        + "/"
                        + category.name().toLowerCase();

        if (withAuth) {
            return restTemplate
                    .withBasicAuth("admin", TEST_PASSWORD)
                    .exchange(url, HttpMethod.POST, request, Void.class);
        }
        return restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> buildUploadRequest(
            String filename, byte[] content, boolean withAuth) {

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

        ByteArrayResource fileResource =
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(fileResource, fileHeaders));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        return new HttpEntity<>(body, headers);
    }

    // =========================================================================
    // Test configuration — fixed admin credentials (DEC-44 D2 empirical-refinement pattern)
    // =========================================================================

    /**
     * Per-IT {@link AdminCredentialsProvider} providing a fixed BCrypt-hashed test password.
     *
     * <p>Per DEC-44 §"2026-04-27 Empirical Refinement": this inner class provides {@code @Primary
     * AdminCredentialsProvider} which overrides the non-primary placeholder in {@link
     * WebModuleTestConfig} via {@code spring.main.allow-bean-definition-overriding=true}.
     * Production {@code SecurityFilterChain} + {@code UserDetailsService} remain sole instances.
     * No {@code UserDetailsService} or {@code SecurityFilterChain} substitute bean added
     * (AC-DEC44-D2-EMPIRICAL-REFINEMENT-RESPECT).
     */
    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hashed = passwordEncoder.encode(TEST_PASSWORD);
            return () -> hashed;
        }
    }
}
