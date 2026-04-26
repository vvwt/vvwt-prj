package de.vvwt.tm.web.photo;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tournament.internal.dto.TeamCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TeamResponse;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.net.URI;
import java.time.Instant;
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
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Integration tests for {@link TeamPhotoController} — E36S02 Q-1a TDD rebuild.
 *
 * <p>Replaces the deleted Snapshot-Driven {@code TeamPhotoControllerIT} (E23S04/E23S05, 17 methods,
 * 100% Snapshot-Driven per Discovery audit (ii) {@code
 * E36-discovery-dec41-test-classification.md}). All methods are freshly authored RED-first per
 * DEC-22 Iron Law + DEC-41 §3 clause (1). RED state: deletion commit {@code 0814232} removed all
 * three legacy artefacts; this IT compiled immediately in the next commit against absent production
 * code, then turned GREEN when the production code was added.
 *
 * <h2>Annotation (DEC-38, DEC-40)</h2>
 *
 * <p>{@code @ApplicationModuleTest(ALL_DEPENDENCIES, RANDOM_PORT)} per DEC-38 Clause A + DEC-40
 * Amendment — the {@code web} module boots with all its declared {@code allowedDependencies}
 * (tenant, tournament, tournament::exceptions, tournament::dto, scoring, photo, certificate,
 * print). The real {@code DefaultPhotoStorageService} bean is present (no {@code @MockitoBean} for
 * {@code PhotoStorageService} per DEC-38 Amendment reverse-case).
 *
 * <h2>Wire contract (AC-MOCKMVC-CONTRACT-PRESERVED)</h2>
 *
 * <ul>
 *   <li>POST {@code /api/photo/tournaments/{tournamentId}/teams/{teamId}} — upload
 *   <li>GET {@code /api/photo/tournaments/{tournamentId}/teams/{teamId}} — retrieve
 *   <li>DELETE {@code /api/photo/tournaments/{tournamentId}/teams/{teamId}} — delete
 * </ul>
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC1 — upload JPEG/PNG, replace existing
 *   <li>AC2 — retrieve with correct Content-Type, 404 when absent
 *   <li>AC3 — delete 204, 404 when absent
 *   <li>AC4 — hasPhoto flag in team listing
 *   <li>AC7 — format + size rejection (400)
 *   <li>AC8 — unknown tournament/team → 404
 *   <li>AC-SECURITY-SUBSTANTIVE — all endpoints require admin auth; unauthenticated → 401
 * </ul>
 *
 * @see TeamPhotoController
 * @see PhotoExceptionAdvice
 * @see WebModuleTestConfig
 * @see de.vvwt.tm.photo.PhotoStorageService
 * @see DEC-22
 * @see DEC-38
 * @see DEC-40
 * @see DEC-41
 * @see E36S02
 */
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WebModuleTestConfig.class)
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(
        properties = {
            "tm.photos.data-dir=${java.io.tmpdir}/tm-photos-it-e36s02",
            "tm.photos.max-size-bytes=1048576"
        })
@DisplayName("TeamPhotoController IT — E36S02: Q-1a TDD rebuild at de.vvwt.tm.web.photo")
class TeamPhotoControllerIT {

    private static final String TEST_PASSWORD = "PhotoTestPass36S02";

    /** Minimal valid JPEG header bytes. */
    private static final byte[] SAMPLE_JPEG =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};

    /** Minimal valid PNG header bytes. */
    private static final byte[] SAMPLE_PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth("admin", TEST_PASSWORD);
    }

    // =========================================================================
    // AC1 — Upload
    // =========================================================================

    @Test
    @DisplayName("AC1: POST with JPEG returns 200 with photo metadata")
    void uploadJpegReturns200WithMetadata() throws Exception {
        UUID tournamentId = createTournament("Photo Upload Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team JPEG");

        ResponseEntity<PhotoMetadataResponse> response =
                uploadPhoto(
                        authed,
                        tournamentId,
                        teamId,
                        "team1.jpg",
                        SAMPLE_JPEG,
                        MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC1: JPEG upload must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().filename()).isEqualTo("team1.jpg");
        assertThat(response.getBody().sizeBytes()).isEqualTo(SAMPLE_JPEG.length);
        assertThat(response.getBody().uploadedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC1: POST with PNG returns 200")
    void uploadPngReturns200() throws Exception {
        UUID tournamentId = createTournament("PNG Upload Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team PNG");

        ResponseEntity<PhotoMetadataResponse> response =
                uploadPhoto(
                        authed, tournamentId, teamId, "team.png", SAMPLE_PNG, MediaType.IMAGE_PNG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName(
            "AC1: upload replaces existing photo (second upload returns 200 with new filename)")
    void uploadReplacesExistingPhoto() throws Exception {
        UUID tournamentId = createTournament("Replace Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team Replace");

        uploadPhoto(authed, tournamentId, teamId, "old.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);
        ResponseEntity<PhotoMetadataResponse> second =
                uploadPhoto(
                        authed, tournamentId, teamId, "new.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().filename()).isEqualTo("new.jpg");
    }

    // =========================================================================
    // AC7 — Format + size rejection
    // =========================================================================

    @Test
    @DisplayName("AC7: POST with non-image content type returns 400 (PhotoFormatException)")
    void uploadNonImageReturns400() throws Exception {
        UUID tournamentId = createTournament("Format Reject Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team FMT");

        ResponseEntity<String> response =
                uploadPhotoAsString(
                        authed,
                        tournamentId,
                        teamId,
                        "doc.pdf",
                        SAMPLE_JPEG,
                        MediaType.APPLICATION_OCTET_STREAM);

        assertThat(response.getStatusCode())
                .as("AC7: non-image format must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC7: POST with file exceeding 1 MB limit returns 400 (PhotoSizeException)")
    void uploadOversizedFileReturns400() throws Exception {
        UUID tournamentId = createTournament("Size Reject Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team SIZE");

        byte[] oversized = new byte[1024 * 1024 + 1];
        ResponseEntity<String> response =
                uploadPhotoAsString(
                        authed, tournamentId, teamId, "big.jpg", oversized, MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC7: oversized photo must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC2 — Retrieve
    // =========================================================================

    @Test
    @DisplayName("AC2: GET after JPEG upload returns 200 with image/jpeg Content-Type")
    void retrieveJpegReturns200WithJpegContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve JPEG Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team RJ");
        uploadPhoto(authed, tournamentId, teamId, "photo.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        ResponseEntity<byte[]> response =
                authed.getForEntity(new URI(photoUrl(tournamentId, teamId)), byte[].class);

        assertThat(response.getStatusCode())
                .as("AC2: retrieve must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("AC2: Content-Type must be image/jpeg")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("image/jpeg");
    }

    @Test
    @DisplayName("AC2: GET after PNG upload returns 200 with image/png Content-Type")
    void retrievePngReturns200WithPngContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve PNG Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team RP");
        uploadPhoto(authed, tournamentId, teamId, "photo.png", SAMPLE_PNG, MediaType.IMAGE_PNG);

        ResponseEntity<byte[]> response =
                authed.getForEntity(new URI(photoUrl(tournamentId, teamId)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("image/png");
    }

    @Test
    @DisplayName("AC2: GET when no photo uploaded returns 404")
    void retrieveWhenNoPhotoReturns404() throws Exception {
        UUID tournamentId = createTournament("No Photo Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team NP");

        ResponseEntity<String> response =
                authed.getForEntity(new URI(photoUrl(tournamentId, teamId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC2: retrieve without prior upload must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC3 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC3: DELETE after upload returns 204 No Content")
    void deleteAfterUploadReturns204() throws Exception {
        UUID tournamentId = createTournament("Delete Photo Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team DEL");
        uploadPhoto(authed, tournamentId, teamId, "photo.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        ResponseEntity<Void> response =
                authed.exchange(
                        new URI(photoUrl(tournamentId, teamId)),
                        HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode())
                .as("AC3: delete existing photo must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("AC3: DELETE when no photo exists returns 404")
    void deleteWhenNoPhotoReturns404() throws Exception {
        UUID tournamentId = createTournament("Delete Missing Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team DM");

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(photoUrl(tournamentId, teamId)),
                        HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC3: delete non-existent photo must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC4 — hasPhoto in team listing
    // =========================================================================

    @Test
    @DisplayName("AC4: GET /teams returns hasPhoto=false before upload, true after upload")
    void teamListingHasPhotoFalseBeforeUploadTrueAfter() throws Exception {
        UUID tournamentId = createTournament("HasPhoto Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team HP");

        TeamResponse[] before =
                authed.getForObject(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        TeamResponse[].class);

        assertThat(before).hasSize(1);
        assertThat(before[0].hasPhoto()).as("AC4: hasPhoto must be false before upload").isFalse();

        uploadPhoto(authed, tournamentId, teamId, "photo.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        TeamResponse[] after =
                authed.getForObject(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        TeamResponse[].class);

        assertThat(after).hasSize(1);
        assertThat(after[0].hasPhoto()).as("AC4: hasPhoto must be true after upload").isTrue();
    }

    @Test
    @DisplayName("AC4: GET /teams returns hasPhoto=false after delete")
    void teamListingHasPhotoFalseAfterDelete() throws Exception {
        UUID tournamentId = createTournament("HasPhoto Delete Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team HPD");

        uploadPhoto(authed, tournamentId, teamId, "photo.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);
        authed.exchange(
                new URI(photoUrl(tournamentId, teamId)), HttpMethod.DELETE, null, Void.class);

        TeamResponse[] teams =
                authed.getForObject(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        TeamResponse[].class);

        assertThat(teams[0].hasPhoto()).as("AC4: hasPhoto must be false after delete").isFalse();
    }

    // =========================================================================
    // AC8 — Unknown tournament/team → 404
    // =========================================================================

    @Test
    @DisplayName("AC8: POST for unknown tournament returns 404")
    void uploadForUnknownTournamentReturns404() throws Exception {
        UUID unknownTournament = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();

        ResponseEntity<String> response =
                uploadPhotoAsString(
                        authed,
                        unknownTournament,
                        teamId,
                        "t.jpg",
                        SAMPLE_JPEG,
                        MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC8: unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC8: POST for unknown team returns 404")
    void uploadForUnknownTeamReturns404() throws Exception {
        UUID tournamentId = createTournament("Unknown Team Test E36S02");
        UUID unknownTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                uploadPhotoAsString(
                        authed,
                        tournamentId,
                        unknownTeam,
                        "t.jpg",
                        SAMPLE_JPEG,
                        MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC8: unknown team must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC-SECURITY-SUBSTANTIVE — all endpoints require admin auth
    // =========================================================================

    @Test
    @DisplayName("AC-SECURITY-SUBSTANTIVE: POST without credentials returns 401")
    void uploadRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Upload Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team AU");

        ResponseEntity<String> response =
                uploadPhotoAsString(
                        restTemplate,
                        tournamentId,
                        teamId,
                        "t.jpg",
                        SAMPLE_JPEG,
                        MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC-SECURITY-SUBSTANTIVE: upload without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC-SECURITY-SUBSTANTIVE: GET without credentials returns 401")
    void retrieveRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Retrieve Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team AR");

        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(photoUrl(tournamentId, teamId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC-SECURITY-SUBSTANTIVE: retrieve without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC-SECURITY-SUBSTANTIVE: DELETE without credentials returns 401")
    void deleteRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Delete Test E36S02");
        UUID teamId = createTeam(tournamentId, "Team AD");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(photoUrl(tournamentId, teamId)),
                        HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC-SECURITY-SUBSTANTIVE: delete without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String photoUrl(UUID tournamentId, UUID teamId) {
        return baseUrl + "/api/photo/tournaments/" + tournamentId + "/teams/" + teamId;
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

    private UUID createTeam(UUID tournamentId, String description) throws Exception {
        TeamCreateRequest request = new TeamCreateRequest(description, null, null, null, null);

        ResponseEntity<TeamResponse> created =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        request,
                        TeamResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    private ResponseEntity<PhotoMetadataResponse> uploadPhoto(
            TestRestTemplate template,
            UUID tournamentId,
            UUID teamId,
            String filename,
            byte[] content,
            MediaType imageMediaType)
            throws Exception {

        return template.postForEntity(
                new URI(photoUrl(tournamentId, teamId)),
                buildMultipartRequest(filename, content, imageMediaType),
                PhotoMetadataResponse.class);
    }

    private ResponseEntity<String> uploadPhotoAsString(
            TestRestTemplate template,
            UUID tournamentId,
            UUID teamId,
            String filename,
            byte[] content,
            MediaType imageMediaType)
            throws Exception {

        return template.postForEntity(
                new URI(photoUrl(tournamentId, teamId)),
                buildMultipartRequest(filename, content, imageMediaType),
                String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(
            String filename, byte[] content, MediaType imageMediaType) {

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(imageMediaType);

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
    // Response DTO (mirror of PhotoMetadataResponse for deserialization)
    // =========================================================================

    record PhotoMetadataResponse(String filename, long sizeBytes, Instant uploadedAt) {}

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
