package de.vvwt.tm.web.photo;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.tournament.internal.dto.TeamCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TeamResponse;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
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
 * Integration tests for {@link TeamPhotoController} — E23S04: Q-1b relocation to {@code
 * de.vvwt.tm.web.photo}.
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.infrastructure.web.photo.TeamPhotoControllerIT}
 * to {@code de.vvwt.tm.web.photo} per DEC-40 Clause D (Primary-Adapter-Isolation) and DEC-22
 * §refactor-clause (Q-1b whole-class relocation). Analog to E22S07 tournament controller IT
 * relocation. Existing assertions remain GREEN (regression gate per DEC-22 §refactor-clause,
 * AC-QB-REGRESSION-GATE).
 *
 * <h2>Annotation change (AC-TEST-RELOCATION, DEC-38 + DEC-40 Clause E)</h2>
 *
 * <p>Annotation changed from {@code @SpringBootTest(RANDOM_PORT)} (pre-DEC-38, legacy pattern) to
 * {@code @ApplicationModuleTest(webEnvironment = RANDOM_PORT)} targeting the {@code web} module per
 * DEC-38 Clause A + DEC-40 2026-04-22 Amendment. Boots {@code web} + all declared {@code
 * allowedDependencies} (tenant, tournament, tournament::exceptions, tournament::dto, scoring,
 * photo). {@link WebModuleTestConfig} provides the test infrastructure beans.
 *
 * <h2>@MockitoBean reverse-case (AC-MOCKITOBEAN-REVERSE-CASE, DEC-38 Amendment)</h2>
 *
 * <p>The legacy {@code TeamPhotoControllerIT} used a {@code @MockitoBean} for {@code
 * PhotoStorageService} indirectly via {@link WebModuleTestConfig}. After this relocation, the
 * {@code web} module's {@code allowedDependencies} includes {@code "photo"}, so the REAL {@code
 * DefaultPhotoStorageService} bean is present in the {@code @ApplicationModuleTest(web)} context.
 * No {@code @MockitoBean} for {@code PhotoStorageService} is declared here (per DEC-38 Amendment
 * reverse-case: mock REMOVED because real bean is in scope). Slice tests retain
 * {@code @MockitoBean} per DEC-38 erratum.
 *
 * <h2>Exception handling (AC-ERROR-HANDLING)</h2>
 *
 * <p>The new {@link TeamPhotoController} invokes {@code
 * de.vvwt.tm.photo.DefaultPhotoStorageService} which throws exceptions from {@code
 * de.vvwt.tm.photo.*}. These are handled by {@link PhotoExceptionAdvice} (E23S04) in the {@code
 * web} module, which maps them to the same HTTP status codes as the legacy handler.
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC1: Upload accepts JPEG/PNG, returns 200 with metadata; rejects invalid formats → 400;
 *       rejects oversized files → 400; replaces existing photo
 *   <li>AC2: Retrieve returns 200 + correct Content-Type; 404 if no photo
 *   <li>AC3: Delete returns 204; 404 if no photo
 *   <li>AC4: Team listing includes {@code hasPhoto} boolean per team
 *   <li>AC8: Unknown team/tournament → 404
 *   <li>AC-SECURITY-SUBSTANTIVE: All endpoints require admin auth; unauthenticated → 401
 * </ul>
 *
 * @see TeamPhotoController
 * @see PhotoExceptionAdvice
 * @see WebModuleTestConfig
 * @see de.vvwt.tm.photo.PhotoStorageService
 * @see DEC-38
 * @see DEC-40
 * @see E23S04
 */
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(WebModuleTestConfig.class)
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(
        properties = {
            "tm.photos.data-dir=${java.io.tmpdir}/tm-photos-it-e23s04",
            "tm.photos.max-size-bytes=1048576"
        })
@DisplayName("TeamPhotoController IT — E23S04: relocated to de.vvwt.tm.web.photo")
class TeamPhotoControllerIT {

    private static final String TEST_PASSWORD = "PhotoTestPass12S02";

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
        authed = restTemplate.withBasicAuth(AdminCredentialsProvider.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC1 — Upload
    // =========================================================================

    @Test
    @DisplayName("AC1: POST with JPEG returns 200 with photo metadata")
    void uploadJpegReturns200WithMetadata() throws Exception {
        UUID tournamentId = createTournament("Photo Upload Test");
        UUID teamId = createTeam(tournamentId, "Team 1");

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
        UUID tournamentId = createTournament("PNG Upload Test");
        UUID teamId = createTeam(tournamentId, "Team PNG");

        ResponseEntity<PhotoMetadataResponse> response =
                uploadPhoto(
                        authed, tournamentId, teamId, "team.png", SAMPLE_PNG, MediaType.IMAGE_PNG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("AC1: upload replaces existing photo (second upload returns 200)")
    void uploadReplacesExistingPhoto() throws Exception {
        UUID tournamentId = createTournament("Replace Test");
        UUID teamId = createTeam(tournamentId, "Team R");

        uploadPhoto(authed, tournamentId, teamId, "old.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);
        ResponseEntity<PhotoMetadataResponse> second =
                uploadPhoto(
                        authed, tournamentId, teamId, "new.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().filename()).isEqualTo("new.jpg");
    }

    @Test
    @DisplayName("AC7: POST with non-image file (.pdf) returns 400")
    void uploadPdfReturns400() throws Exception {
        UUID tournamentId = createTournament("Format Reject Test");
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
    @DisplayName("AC7: POST with file exceeding 1 MB limit returns 400")
    void uploadOversizedFileReturns400() throws Exception {
        UUID tournamentId = createTournament("Size Reject Test");
        UUID teamId = createTeam(tournamentId, "Team SIZE");

        // 1 MB + 1 byte — exceeds the test-configured 1 MB limit
        byte[] oversized = new byte[1024 * 1024 + 1];
        ResponseEntity<String> response =
                uploadPhotoAsString(
                        authed, tournamentId, teamId, "big.jpg", oversized, MediaType.IMAGE_JPEG);

        assertThat(response.getStatusCode())
                .as("AC7: oversized photo must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC-SECURITY-SUBSTANTIVE: POST without credentials returns 401")
    void uploadRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Test Upload");
        UUID teamId = createTeam(tournamentId, "Team AUTH");

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
        UUID tournamentId = createTournament("Unknown Team Test");
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
    // AC2 — Retrieve
    // =========================================================================

    @Test
    @DisplayName("AC2: GET after upload returns 200 with image/jpeg content type")
    void retrieveJpegReturns200() throws Exception {
        UUID tournamentId = createTournament("Retrieve JPEG Test");
        UUID teamId = createTeam(tournamentId, "Team RJ");
        uploadPhoto(authed, tournamentId, teamId, "t.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

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
    @DisplayName("AC2: GET after PNG upload returns 200 with image/png content type")
    void retrievePngReturns200WithPngContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve PNG Test");
        UUID teamId = createTeam(tournamentId, "Team RP");
        uploadPhoto(authed, tournamentId, teamId, "t.png", SAMPLE_PNG, MediaType.IMAGE_PNG);

        ResponseEntity<byte[]> response =
                authed.getForEntity(new URI(photoUrl(tournamentId, teamId)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("image/png");
    }

    @Test
    @DisplayName("AC2: GET when no photo uploaded returns 404")
    void retrieveWhenNoPhotoReturns404() throws Exception {
        UUID tournamentId = createTournament("No Photo Retrieve Test");
        UUID teamId = createTeam(tournamentId, "Team NP");

        ResponseEntity<String> response =
                authed.getForEntity(new URI(photoUrl(tournamentId, teamId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC2: retrieve without prior upload must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC-SECURITY-SUBSTANTIVE: GET without credentials returns 401")
    void retrieveRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Retrieve Test");
        UUID teamId = createTeam(tournamentId, "Team AR");

        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(photoUrl(tournamentId, teamId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC-SECURITY-SUBSTANTIVE: retrieve without auth must return 401")
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
    @DisplayName("AC3: DELETE when no photo returns 404")
    void deleteWhenNoPhotoReturns404() throws Exception {
        UUID tournamentId = createTournament("Delete Missing Photo Test");
        UUID teamId = createTeam(tournamentId, "Team DMP");

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

    @Test
    @DisplayName("AC-SECURITY-SUBSTANTIVE: DELETE without credentials returns 401")
    void deleteRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Delete Test");
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
    // AC4 — hasPhoto in team listing
    // =========================================================================

    @Test
    @DisplayName("AC4: GET /teams returns hasPhoto=false before upload, true after upload")
    void teamListingIncludesHasPhoto() throws Exception {
        UUID tournamentId = createTournament("HasPhoto Test Tournament");
        UUID teamId = createTeam(tournamentId, "Team HP");

        // Before upload: hasPhoto should be false
        TeamResponse[] beforeUpload =
                authed.getForObject(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        TeamResponse[].class);

        assertThat(beforeUpload).hasSize(1);
        assertThat(beforeUpload[0].hasPhoto())
                .as("AC4: hasPhoto must be false before upload")
                .isFalse();

        // Upload a photo
        uploadPhoto(authed, tournamentId, teamId, "photo.jpg", SAMPLE_JPEG, MediaType.IMAGE_JPEG);

        // After upload: hasPhoto should be true
        TeamResponse[] afterUpload =
                authed.getForObject(
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
        authed.exchange(
                new URI(photoUrl(tournamentId, teamId)), HttpMethod.DELETE, null, Void.class);

        TeamResponse[] teams =
                authed.getForObject(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        TeamResponse[].class);

        assertThat(teams[0].hasPhoto()).as("AC4: hasPhoto must be false after delete").isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String photoUrl(UUID tournamentId, UUID teamId) {
        return baseUrl + "/api/tournaments/" + tournamentId + "/teams/" + teamId + "/photo";
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
    // Test configuration — known test admin password + legacy PhotoStorageService bridge
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return () -> hash;
        }

        /**
         * Bridges the legacy {@code de.vvwt.tm.domain.photo.PhotoStorageService} interface (used by
         * {@code TeamController} for the {@code hasPhoto} AC4 check) to the real {@code
         * de.vvwt.tm.photo.PhotoStorageService} bean loaded by {@code @ApplicationModuleTest(web)}.
         *
         * <p>During the parallel phase, {@code TeamController} still injects the legacy type. The
         * {@code web} module's test context provides the real {@code DefaultPhotoStorageService}
         * bean (of type {@code de.vvwt.tm.photo.PhotoStorageService}) because {@code "photo"} is in
         * {@code web.allowedDependencies}. This {@code @Primary} bridge overrides the Mockito mock
         * in {@link WebModuleTestConfig}, allowing AC4 assertions to verify real {@code hasPhoto}
         * state. Removed at E23S05 Cutover-1 when {@code TeamController} is updated to use the
         * module service directly.
         *
         * @param modulePhotoService the real {@code de.vvwt.tm.photo.PhotoStorageService} bean
         * @return a {@code de.vvwt.tm.domain.photo.PhotoStorageService} that delegates {@code
         *     hasPhoto} to the module service
         */
        @Bean
        @Primary
        de.vvwt.tm.domain.photo.PhotoStorageService legacyPhotoStorageServiceBridge(
                PhotoStorageService modulePhotoService) {
            return new de.vvwt.tm.domain.photo.PhotoStorageService() {
                @Override
                public de.vvwt.tm.domain.photo.PhotoFileMetadata upload(
                        UUID tournamentId,
                        UUID teamId,
                        String filename,
                        InputStream inputStream,
                        long sizeBytes) {
                    throw new UnsupportedOperationException(
                            "Legacy bridge: upload not delegated in E23S04 IT");
                }

                @Override
                public Optional<PhotoResult> retrieve(UUID tournamentId, UUID teamId) {
                    throw new UnsupportedOperationException(
                            "Legacy bridge: retrieve not delegated in E23S04 IT");
                }

                @Override
                public boolean delete(UUID tournamentId, UUID teamId) {
                    throw new UnsupportedOperationException(
                            "Legacy bridge: delete not delegated in E23S04 IT");
                }

                @Override
                public boolean hasPhoto(UUID tournamentId, UUID teamId) {
                    return modulePhotoService.hasPhoto(tournamentId, teamId);
                }
            };
        }
    }
}
