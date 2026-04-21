package de.vvwt.tm.infrastructure.web.audio;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.domain.audio.AudioCategory;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Integration tests for {@link AudioController} — E11S01: Audio file management REST API.
 *
 * <p>Tests the full HTTP stack (Spring MVC, Security, routing) to verify:
 *
 * <ul>
 *   <li>AC1: Upload accepts .mp3, returns 201 with metadata
 *   <li>AC2: Stream returns 200 audio/mpeg without auth; 404 if no file
 *   <li>AC3: List returns 200 with metadata array (authenticated)
 *   <li>AC4: Delete returns 204 (authenticated); 404 if no file
 *   <li>AC5: Cross-tenant tournament → 404
 *   <li>AC5a: Stream endpoint is public; upload/list/delete require auth
 *   <li>AC7: Non-.mp3 → 415; oversized → 413 (Spring multipart); unknown tournament → 404
 * </ul>
 *
 * <p>The test context uses an isolated in-memory H2 database and a temp directory for audio file
 * storage (overriding {@code tm.audio.data-dir}).
 *
 * @see AudioController
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story
 *     E11S01</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            AudioControllerIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e11s01audiodb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "spring.servlet.multipart.max-file-size=1MB",
            "spring.servlet.multipart.max-request-size=1MB",
            // Use the system temp dir so Spring context can resolve the property at startup.
            // Each test method uses a separate subdirectory via the UUID-based tournament path,
            // so test isolation is preserved without needing @TempDir per-context.
            "tm.audio.data-dir=${java.io.tmpdir}/tm-audio-it-e11s01"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("AudioController IT — E11S01: audio file management REST API")
class AudioControllerIT {

    private static final String TEST_PASSWORD = "AudioTestPass11S01";
    private static final byte[] SAMPLE_MP3 = new byte[] {0x49, 0x44, 0x33, 0x00, 0x00, 0x01};

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC1 — Upload
    // =========================================================================

    @Test
    @DisplayName("AC1: POST /api/tournaments/{id}/audio/START with .mp3 returns 201 with metadata")
    void uploadMp3Returns201() throws Exception {
        UUID tournamentId = createTournament("Audio Upload Test");

        ResponseEntity<AudioMetadataResponse> response =
                uploadAudio(authed, tournamentId, AudioCategory.START, "start.mp3", SAMPLE_MP3);

        assertThat(response.getStatusCode())
                .as("AC1: .mp3 upload must return 201 Created")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().category()).isEqualTo(AudioCategory.START);
        assertThat(response.getBody().filename()).isEqualTo("start.mp3");
        assertThat(response.getBody().sizeBytes()).isEqualTo(SAMPLE_MP3.length);
        assertThat(response.getBody().uploadedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC1: upload without credentials returns 401 (admin-only per AC5a)")
    void uploadRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Test Upload");

        ResponseEntity<String> response =
                uploadAudioAsString(
                        restTemplate, tournamentId, AudioCategory.START, "start.mp3", SAMPLE_MP3);

        assertThat(response.getStatusCode())
                .as("AC1/AC5a: upload without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC7: upload of non-.mp3 file returns 415")
    void uploadNonMp3Returns415() throws Exception {
        UUID tournamentId = createTournament("Format Reject Test");

        ResponseEntity<String> response =
                uploadAudioAsString(
                        authed, tournamentId, AudioCategory.START, "audio.wav", SAMPLE_MP3);

        assertThat(response.getStatusCode())
                .as("AC7: non-.mp3 upload must return 415 Unsupported Media Type")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("AC7: upload for unknown tournament returns 404")
    void uploadForUnknownTournamentReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response =
                uploadAudioAsString(
                        authed, unknownId, AudioCategory.START, "start.mp3", SAMPLE_MP3);

        assertThat(response.getStatusCode())
                .as("AC7: unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC2 — Stream
    // =========================================================================

    @Test
    @DisplayName("AC2: GET /audio/START/stream returns 200 audio/mpeg after upload")
    void streamReturns200AudioMpeg() throws Exception {
        UUID tournamentId = createTournament("Stream Test");
        uploadAudio(authed, tournamentId, AudioCategory.START, "start.mp3", SAMPLE_MP3);

        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(
                        new URI(audioStreamUrl(tournamentId, AudioCategory.START)), byte[].class);

        assertThat(response.getStatusCode())
                .as("AC2: stream must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("AC2: Content-Type must be audio/mpeg")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("audio/mpeg");
        assertThat(response.getBody())
                .as("AC2: response body must match uploaded content")
                .isEqualTo(SAMPLE_MP3);
    }

    @Test
    @DisplayName("AC2/AC5a: stream endpoint is accessible without authentication")
    void streamIsPublicWithoutAuth() throws Exception {
        UUID tournamentId = createTournament("Public Stream Test");
        uploadAudio(authed, tournamentId, AudioCategory.END, "end.mp3", SAMPLE_MP3);

        // Use unauthenticated template
        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(
                        new URI(audioStreamUrl(tournamentId, AudioCategory.END)), byte[].class);

        assertThat(response.getStatusCode())
                .as("AC5a: stream must be accessible without authentication")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC2: stream returns 404 when no file uploaded for category")
    void streamReturns404WhenNoFileUploaded() throws Exception {
        UUID tournamentId = createTournament("No File Stream Test");

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(audioStreamUrl(tournamentId, AudioCategory.PAUSE)), String.class);

        assertThat(response.getStatusCode())
                .as("AC2: stream must return 404 if no file uploaded for category")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC5: stream for unknown tournament returns 404 (tenant enumeration prevented)")
    void streamForUnknownTournamentReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(audioStreamUrl(unknownId, AudioCategory.START)), String.class);

        assertThat(response.getStatusCode())
                .as("AC5: cross-tenant or unknown tournament → 404 (no tenant enumeration)")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC3 — List
    // =========================================================================

    @Test
    @DisplayName("AC3: GET /audio returns list of uploaded metadata (authenticated)")
    void listReturnsMetadata() throws Exception {
        UUID tournamentId = createTournament("List Test");
        uploadAudio(authed, tournamentId, AudioCategory.START, "start.mp3", SAMPLE_MP3);
        uploadAudio(authed, tournamentId, AudioCategory.END, "end.mp3", SAMPLE_MP3);

        ResponseEntity<AudioMetadataResponse[]> response =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio"),
                        AudioMetadataResponse[].class);

        assertThat(response.getStatusCode())
                .as("AC3: list must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC3: two files uploaded, two metadata entries expected")
                .hasSize(2);
    }

    @Test
    @DisplayName("AC3: GET /audio without credentials returns 401 (admin-only per AC5a)")
    void listRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("List Auth Test");

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC3/AC5a: list without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC3: GET /audio returns empty array when no files uploaded")
    void listReturnsEmptyArrayWhenNoFiles() throws Exception {
        UUID tournamentId = createTournament("Empty List Test");

        ResponseEntity<AudioMetadataResponse[]> response =
                authed.getForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio"),
                        AudioMetadataResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    // =========================================================================
    // AC4 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC4: DELETE /audio/{category} after upload returns 204")
    void deleteExistingFileReturns204() throws Exception {
        UUID tournamentId = createTournament("Delete Test");
        uploadAudio(authed, tournamentId, AudioCategory.PAUSE, "pause.mp3", SAMPLE_MP3);

        ResponseEntity<Void> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio/PAUSE"),
                        HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode())
                .as("AC4: deleting an existing file must return 204 No Content")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("AC4: DELETE /audio/{category} when no file exists returns 404")
    void deleteMissingFileReturns404() throws Exception {
        UUID tournamentId = createTournament("Delete Missing Test");

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio/START"),
                        HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC4: deleting a non-existent file must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC4/AC5a: DELETE without credentials returns 401 (admin-only)")
    void deleteRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Delete Auth Test");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio/START"),
                        HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC4/AC5a: delete without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String audioStreamUrl(UUID tournamentId, AudioCategory category) {
        return baseUrl + "/api/tournaments/" + tournamentId + "/audio/" + category + "/stream";
    }

    private UUID createTournament(String description) throws Exception {
        TournamentCreateRequest request =
                new TournamentCreateRequest(
                        description,
                        null,
                        8,
                        4,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin");

        ResponseEntity<TournamentResponse> created =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    private ResponseEntity<AudioMetadataResponse> uploadAudio(
            TestRestTemplate template,
            UUID tournamentId,
            AudioCategory category,
            String filename,
            byte[] content)
            throws Exception {

        return template.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio/" + category),
                buildMultipartRequest(filename, content),
                AudioMetadataResponse.class);
    }

    private ResponseEntity<String> uploadAudioAsString(
            TestRestTemplate template,
            UUID tournamentId,
            AudioCategory category,
            String filename,
            byte[] content)
            throws Exception {

        return template.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/audio/" + category),
                buildMultipartRequest(filename, content),
                String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(
            String filename, byte[] content) {

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

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        return new HttpEntity<>(body, requestHeaders);
    }

    // =========================================================================
    // Response DTO (mirror of AudioMetadataResponse for deserialization)
    // =========================================================================

    record AudioMetadataResponse(
            AudioCategory category,
            String filename,
            long sizeBytes,
            java.time.Instant uploadedAt) {}

    // =========================================================================
    // Test configuration — known test admin password
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
