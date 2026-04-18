package de.vvwt.tm.infrastructure.web.photo;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.infrastructure.web.dto.TeamCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TeamResponse;
import de.vvwt.tm.infrastructure.web.dto.TournamentCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TournamentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
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

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TeamPhotoController} — E12S02: Team photo upload REST API.
 *
 * <p>Tests the full HTTP stack (Spring MVC, Security, routing) to verify:
 * <ul>
 *   <li>AC1: Upload accepts JPEG/PNG, returns 200 with metadata; rejects invalid formats/sizes → 400</li>
 *   <li>AC2: Retrieve returns 200 + correct Content-Type; 404 if no photo</li>
 *   <li>AC3: Delete returns 204; 404 if no photo</li>
 *   <li>AC4: Team listing includes {@code hasPhoto} boolean per team</li>
 *   <li>AC8: Unknown team/tournament → 404</li>
 *   <li>AC10: All endpoints require admin auth; unauthenticated → 401</li>
 * </ul>
 *
 * <p>Uses an isolated in-memory H2 database and a temp directory for photo storage.
 *
 * @see TeamPhotoController
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story E12S02</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                TeamPhotoControllerIT.TestAdminCredentials.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e12s02photodb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "tm.photos.data-dir=${java.io.tmpdir}/tm-photos-it-e12s02",
                "tm.photos.max-size-bytes=1048576"   // 1 MB for faster tests
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TeamPhotoController IT — E12S02: team photo upload REST API")
class TeamPhotoControllerIT {

    private static final String TEST_PASSWORD = "PhotoTestPass12S02";
    /** Minimal valid JPEG header bytes. */
    private static final byte[] SAMPLE_JPEG = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10
    };
    /** Minimal valid PNG header bytes. */
    private static final byte[] SAMPLE_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC1 — Upload
    // =========================================================================

    @Test
    @DisplayName("AC1: POST with JPEG returns 200 with photo metadata")
    void uploadJpegReturns200WithMetadata() throws Exception {
        UUID tournamentId = createTournament("Photo Upload Test");
        UUID teamId = createTeam(tournamentId, "Team 1");

        ResponseEntity<PhotoMetadataResponse> response = uploadPhoto(
                authed, tournamentId, teamId, "team1.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

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
        UUID tournamentId = createTournament("PNG Upload Test");
        UUID teamId = createTeam(tournamentId, "Team PNG");

        ResponseEntity<PhotoMetadataResponse> response = uploadPhoto(
                authed, tournamentId, teamId, "team.png", SAMPLE_PNG, MediaType.IMAGE_PNG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC1: upload replaces existing photo (second upload returns 200)")
    void uploadReplacesExistingPhoto() throws Exception {
        UUID tournamentId = createTournament("Replace Test");
        UUID teamId = createTeam(tournamentId, "Team R");

        uploadPhoto(authed, tournamentId, teamId, "old.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);
        ResponseEntity<PhotoMetadataResponse> second = uploadPhoto(
                authed, tournamentId, teamId, "new.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().filename()).isEqualTo("new.jpg");
    }

    @Test
    @DisplayName("AC7: POST with non-image file (.pdf) returns 400")
    void uploadPdfReturns400() throws Exception {
        UUID tournamentId = createTournament("Format Reject Test");
        UUID teamId = createTeam(tournamentId, "Team FMT");

        ResponseEntity<String> response = uploadPhotoAsString(
                authed, tournamentId, teamId, "doc.pdf", SAMPLE_JPEG, MediaType.APPLICATION_OCTET_STREAM);

        assertThat(response.getStatusCode())
                .as("AC7: non-image format must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC7: POST with file exceeding 1 MB limit returns 400")
    void uploadOversizedFileReturns400() throws Exception {
        UUID tournamentId = createTournament("Size Reject Test");
        UUID teamId = createTeam(tournamentId, "Team SIZE");

        // 1 MB + 1 byte — exceeds the test-configured 1 MB limit
        byte[] oversized = new byte[1024 * 1024 + 1];
        ResponseEntity<String> response = uploadPhotoAsString(
                authed, tournamentId, teamId, "big.jpg", oversized, MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC7: oversized photo must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC10: POST without credentials returns 401")
    void uploadRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Test Upload");
        UUID teamId = createTeam(tournamentId, "Team AUTH");

        ResponseEntity<String> response = uploadPhotoAsString(
                restTemplate, tournamentId, teamId, "t.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC10: upload without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC8: POST for unknown tournament returns 404")
    void uploadForUnknownTournamentReturns404() throws Exception {
        UUID unknownTournament = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();

        ResponseEntity<String> response = uploadPhotoAsString(
                authed, unknownTournament, teamId, "t.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC8: unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC8: POST for unknown team returns 404")
    void uploadForUnknownTeamReturns404() throws Exception {
        UUID tournamentId = createTournament("Unknown Team Test");
        UUID unknownTeam = UUID.randomUUID();

        ResponseEntity<String> response = uploadPhotoAsString(
                authed, tournamentId, unknownTeam, "t.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC8: unknown team must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC2 — Retrieve
    // =========================================================================

    @Test
    @DisplayName("AC2: GET after upload returns 200 with image/jpeg content type")
    void retrieveJpegReturns200() throws Exception {
        UUID tournamentId = createTournament("Retrieve JPEG Test");
        UUID teamId = createTeam(tournamentId, "Team RJ");
        uploadPhoto(authed, tournamentId, teamId, "t.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        ResponseEntity<byte[]> response = authed.getForEntity(
                new URI(photoUrl(tournamentId, teamId)), byte[].class);

        assertThat(response.getStatusCode())
                .as("AC2: retrieve must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("AC2: Content-Type must be image/jpeg")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith("image/jpeg");
    }

    @Test
    @DisplayName("AC2: GET after PNG upload returns 200 with image/png content type")
    void retrievePngReturns200WithPngContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve PNG Test");
        UUID teamId = createTeam(tournamentId, "Team RP");
        uploadPhoto(authed, tournamentId, teamId, "t.png", SAMPLE_PNG, MediaType.IMAGE_PNG);

        ResponseEntity<byte[]> response = authed.getForEntity(
                new URI(photoUrl(tournamentId, teamId)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("image/png");
    }

    @Test
    @DisplayName("AC2: GET when no photo uploaded returns 404")
    void retrieveWhenNoPhotoReturns404() throws Exception {
        UUID tournamentId = createTournament("No Photo Retrieve Test");
        UUID teamId = createTeam(tournamentId, "Team NP");

        ResponseEntity<String> response = authed.getForEntity(
                new URI(photoUrl(tournamentId, teamId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC2: retrieve without prior upload must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC10: GET without credentials returns 401")
    void retrieveRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Retrieve Test");
        UUID teamId = createTeam(tournamentId, "Team AR");

        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(photoUrl(tournamentId, teamId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC10: retrieve without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC3 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC3: DELETE after upload returns 204")
    void deleteAfterUploadReturns204() throws Exception {
        UUID tournamentId = createTournament("Delete Photo Test");
        UUID teamId = createTeam(tournamentId, "Team DEL");
        uploadPhoto(authed, tournamentId, teamId, "t.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        ResponseEntity<Void> response = authed.exchange(
                new URI(photoUrl(tournamentId, teamId)),
                HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode())
                .as("AC3: delete existing photo must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("AC3: DELETE when no photo returns 404")
    void deleteWhenNoPhotoReturns404() throws Exception {
        UUID tournamentId = createTournament("Delete Missing Photo Test");
        UUID teamId = createTeam(tournamentId, "Team DMP");

        ResponseEntity<String> response = authed.exchange(
                new URI(photoUrl(tournamentId, teamId)),
                HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode())
                .as("AC3: delete non-existent photo must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC10: DELETE without credentials returns 401")
    void deleteRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Delete Test");
        UUID teamId = createTeam(tournamentId, "Team AD");

        ResponseEntity<String> response = restTemplate.exchange(
                new URI(photoUrl(tournamentId, teamId)),
                HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode())
                .as("AC10: delete without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC4 — hasPhoto in team listing
    // =========================================================================

    @Test
    @DisplayName("AC4: GET /teams returns hasPhoto=false before upload, true after upload")
    void teamListingIncludesHasPhoto() throws Exception {
        UUID tournamentId = createTournament("HasPhoto Test Tournament");
        UUID teamId = createTeam(tournamentId, "Team HP");

        // Before upload: hasPhoto should be false
        TeamResponse[] beforeUpload = authed.getForObject(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                TeamResponse[].class);

        assertThat(beforeUpload).hasSize(1);
        assertThat(beforeUpload[0].hasPhoto())
                .as("AC4: hasPhoto must be false before upload")
                .isFalse();

        // Upload a photo
        uploadPhoto(authed, tournamentId, teamId, "photo.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        // After upload: hasPhoto should be true
        TeamResponse[] afterUpload = authed.getForObject(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                TeamResponse[].class);

        assertThat(afterUpload).hasSize(1);
        assertThat(afterUpload[0].hasPhoto())
                .as("AC4: hasPhoto must be true after upload")
                .isTrue();
    }

    @Test
    @DisplayName("AC4: GET /teams hasPhoto=false again after delete")
    void teamListingHasPhotoFalseAfterDelete() throws Exception {
        UUID tournamentId = createTournament("HasPhoto Delete Test");
        UUID teamId = createTeam(tournamentId, "Team HPD");

        uploadPhoto(authed, tournamentId, teamId, "photo.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);
        authed.exchange(new URI(photoUrl(tournamentId, teamId)), HttpMethod.DELETE, null, Void.class);

        TeamResponse[] teams = authed.getForObject(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                TeamResponse[].class);

        assertThat(teams[0].hasPhoto())
                .as("AC4: hasPhoto must be false after delete")
                .isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String photoUrl(UUID tournamentId, UUID teamId) {
        return baseUrl + "/api/tournaments/" + tournamentId + "/teams/" + teamId + "/photo";
    }

    private UUID createTournament(String description) throws Exception {
        TournamentCreateRequest request = new TournamentCreateRequest(
                description, null, 8, 4, "BEST_OF_3",
                "setPoints", "standardVolleyball", "roundRobin");

        ResponseEntity<TournamentResponse> created = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    private UUID createTeam(UUID tournamentId, String description) throws Exception {
        TeamCreateRequest request = new TeamCreateRequest(description, null, null, null, null);

        ResponseEntity<TeamResponse> created = authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                request, TeamResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    private ResponseEntity<PhotoMetadataResponse> uploadPhoto(
            TestRestTemplate template, UUID tournamentId, UUID teamId,
            String filename, byte[] content, MediaType imageMediaType) throws Exception {

        return template.postForEntity(
                new URI(photoUrl(tournamentId, teamId)),
                buildMultipartRequest(filename, content, imageMediaType),
                PhotoMetadataResponse.class);
    }

    private ResponseEntity<String> uploadPhotoAsString(
            TestRestTemplate template, UUID tournamentId, UUID teamId,
            String filename, byte[] content, MediaType imageMediaType) throws Exception {

        return template.postForEntity(
                new URI(photoUrl(tournamentId, teamId)),
                buildMultipartRequest(filename, content, imageMediaType),
                String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(
            String filename, byte[] content, MediaType imageMediaType) {

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(imageMediaType);

        ByteArrayResource fileResource = new ByteArrayResource(content) {
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

    record PhotoMetadataResponse(
            String filename,
            long sizeBytes,
            Instant uploadedAt
    ) {}

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
