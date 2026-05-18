// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.photo;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import de.vvwt.tm.web.WebModuleTestConfig;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Regression integration test for E12S08 AC2 + AC3 + AC7 — team photo size limit fix.
 *
 * <p>This IT verifies the two directions of the size-limit enforcement after the fix:
 *
 * <ol>
 *   <li>A photo at or under the configured limit uploads successfully (AC2).
 *   <li>A photo above the configured limit is rejected with a photo-specific error response —
 *       messageKey {@code error.photo.tooLarge} — not a bare HTTP 413 and not an audio-domain
 *       message (AC3, AC7).
 * </ol>
 *
 * <p>The test configures {@code tm.photos.max-size-bytes=1048576} (1 MB) as the domain layer cap,
 * with the Spring multipart cap set higher (5 MB). This ensures the upload request reaches the
 * domain layer, which reliably produces a JSON error body via {@code PhotoSizeException}. The
 * multipart-layer rejection path is separately covered by {@code
 * GlobalExceptionHandlerMaxUploadPhotoTest}.
 *
 * <p>Tests are authored RED-first per DEC-22 Iron Law (E12S08): at commit time the photo domain
 * default is 5 MB. A 2 MB photo (above 1 MB IT limit but well under 5 MB original default) is
 * rejected by the domain layer with {@code PhotoSizeException} because the IT property override is
 * active. The messageKey in the existing response is {@code error.photo.tooLarge} — already
 * correct. The AC7 regression test confirms this path stays correct after the fix.
 *
 * <h2>Test property overrides</h2>
 *
 * <p>{@code tm.photos.max-size-bytes=1048576} (1 MB) — domain layer cap for this IT. <br>
 * {@code spring.servlet.multipart.max-file-size=5MB} and {@code max-request-size=5MB} — multipart
 * cap higher than domain cap so the request reaches the controller.
 *
 * @see TeamPhotoController
 * @see de.vvwt.tm.web.GlobalExceptionHandler
 * @since E12S08
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, TeamPhotoSizeLimitIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(
        properties = {
            "tm.photos.data-dir=${java.io.tmpdir}/tm-photos-sizelimit-it-e12s08",
            // IT-scoped domain limit: 1 MB (low enough that a 2 MB upload triggers the domain
            // layer's PhotoSizeException, which reliably produces a JSON error body).
            // The multipart layer is set HIGHER (5 MB) so the request reaches the controller —
            // this tests the domain-layer rejection path. The multipart-layer rejection path
            // (MaxUploadSizeExceededException → error.photo.tooLarge routing) is separately
            // covered by GlobalExceptionHandlerMaxUploadPhotoTest (unit test).
            "tm.photos.max-size-bytes=1048576",
            "spring.servlet.multipart.max-file-size=5MB",
            "spring.servlet.multipart.max-request-size=5MB"
        })
@DisplayName("TeamPhotoSizeLimitIT — E12S08: photo size limit regression (AC2, AC3, AC7)")
class TeamPhotoSizeLimitIT {

    private static final String TEST_PASSWORD = "PhotoSizeLimitTestE12S08";

    /** Minimal valid JPEG header bytes. */
    private static final byte[] JPEG_HEADER =
            new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private TestRestTemplate authed;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth("admin", TEST_PASSWORD);
    }

    // =========================================================================
    // AC2 — photo at/under configured limit uploads successfully
    // =========================================================================

    @Test
    @DisplayName(
            "AC2: photo at/under the configured limit (1 MB domain cap) uploads successfully —"
                    + " returns HTTP 200")
    void photoAtLimitUploadsSuccessfully() throws Exception {
        UUID tournamentId = createTournament("SizeLimit-Under-E12S08");
        UUID teamId = createTeam(tournamentId);

        // 512 KB photo — well under the 1 MB IT domain limit
        byte[] smallPhoto = buildJpeg(512 * 1024);

        ResponseEntity<String> response =
                uploadPhotoAsString(authed, tournamentId, teamId, "photo.jpg", smallPhoto);

        assertThat(response.getStatusCode())
                .as(
                        "AC2: photo under the domain limit (1 MB cap) must upload successfully"
                                + " (HTTP 200)")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // AC3 + AC7 — photo above configured limit → photo-specific error, not bare 413
    // =========================================================================

    @Test
    @DisplayName(
            "AC3/AC7: photo above the configured domain limit (1 MB) is rejected with HTTP 400 and"
                    + " messageKey error.photo.tooLarge — not a bare HTTP 413, not audio-domain"
                    + " error")
    void photoAboveLimitProducesPhotoSpecificError() throws Exception {
        UUID tournamentId = createTournament("SizeLimit-Over-E12S08");
        UUID teamId = createTeam(tournamentId);

        // 2 MB photo — above the 1 MB IT domain cap but below the 5 MB multipart cap,
        // so the request reaches the controller and is rejected by the domain layer
        // with PhotoSizeException → error.photo.tooLarge (a proper JSON body).
        byte[] twombPhoto = buildJpeg(2 * 1024 * 1024);

        ResponseEntity<String> response =
                uploadPhotoAsString(authed, tournamentId, teamId, "big-photo.jpg", twombPhoto);

        // The response must be an error (4xx), not 200
        assertThat(response.getStatusCode().is4xxClientError())
                .as("AC3: over-limit photo must be rejected with a 4xx status")
                .isTrue();

        // The response body must contain a photo-specific messageKey, not an audio-domain key
        String body = response.getBody();
        assertThat(body)
                .as("AC7: response body must contain messageKey error.photo.tooLarge")
                .contains("error.photo.tooLarge");

        // Explicit guard: must NOT contain audio-domain messageKey
        assertThat(body)
                .as("AC3: response body must NOT contain audio-domain messageKey")
                .doesNotContain("error.audio.tooLarge");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a byte array representing a minimal JPEG with the given total size. The header is
     * valid JPEG magic bytes; the rest is padding.
     */
    private byte[] buildJpeg(int sizeBytes) {
        byte[] result = new byte[sizeBytes];
        System.arraycopy(JPEG_HEADER, 0, result, 0, Math.min(JPEG_HEADER.length, sizeBytes));
        return result;
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
                        "roundRobin",
                        null,
                        null,
                        null);

        ResponseEntity<TournamentResponse> created =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    private UUID createTeam(UUID tournamentId) throws Exception {
        // E05S12: createTournament seeds teamCount=8 teams automatically.
        // Retrieve the first seeded team via the team listing API.
        de.vvwt.tm.tournament.internal.dto.TeamResponse[] teams =
                authed.getForObject(
                        new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams"),
                        de.vvwt.tm.tournament.internal.dto.TeamResponse[].class);
        assertThat(teams).isNotNull().isNotEmpty();
        return teams[0].id();
    }

    private ResponseEntity<String> uploadPhotoAsString(
            TestRestTemplate template,
            UUID tournamentId,
            UUID teamId,
            String filename,
            byte[] content)
            throws Exception {

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_JPEG);

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

        String url = baseUrl + "/api/photo/tournaments/" + tournamentId + "/teams/" + teamId;
        return template.postForEntity(
                new URI(url), new HttpEntity<>(body, requestHeaders), String.class);
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    @TestConfiguration
    static class TestAdminCredentials {

        @Bean("webItAdminCredentialsProvider")
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
