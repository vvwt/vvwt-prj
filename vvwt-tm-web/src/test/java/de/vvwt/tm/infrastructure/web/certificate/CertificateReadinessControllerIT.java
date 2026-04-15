package de.vvwt.tm.infrastructure.web.certificate;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.infrastructure.web.dto.TournamentCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TournamentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link CertificateReadinessController} (E12S07 AC1, AC8).
 *
 * <p>Tests the full HTTP stack for the readiness endpoint:
 * <ul>
 *   <li>AC1: Empty tournament returns all-false/zero readiness</li>
 *   <li>AC1: After template upload, templateUploaded becomes true</li>
 *   <li>AC8: Unknown tournament → 404 (tenant-scoped guard)</li>
 *   <li>AC8: Unauthenticated request → 401</li>
 * </ul>
 *
 * @see CertificateReadinessController
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                CertificateReadinessControllerIT.TestAdminCredentials.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e12s07readinessdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "tm.certificate-templates.data-dir=${java.io.tmpdir}/tm-cert-templates-it-e12s07-readiness",
                "tm.certificate-templates.max-size-bytes=1048576"
        })
@ActiveProfiles("test")
@DisplayName("CertificateReadinessController IT — E12S07: certificate readiness endpoint")
class CertificateReadinessControllerIT {

    private static final String TEST_PASSWORD = "ReadinessTest12S07";

    private static final byte[] SAMPLE_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg'><text>{{placement}} {{teamName}}</text></svg>"
                    .getBytes();

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
    // AC1 — Empty tournament returns all-false readiness
    // =========================================================================

    @Test
    @DisplayName("AC1: empty tournament returns templateUploaded=false, standingsAvailable=false, teams=0")
    void emptyTournamentReturnsAllFalse() throws Exception {
        UUID tournamentId = createTournament("Readiness Test — Empty");

        ResponseEntity<ReadinessResponse> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/certificate-readiness"),
                ReadinessResponse.class);

        assertThat(response.getStatusCode())
                .as("AC1: readiness endpoint must return 200 for a valid tournament")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().templateUploaded())
                .as("AC1: no template uploaded → templateUploaded must be false")
                .isFalse();
        assertThat(response.getBody().standingsAvailable())
                .as("AC1: no phases → standingsAvailable must be false")
                .isFalse();
        assertThat(response.getBody().totalTeams())
                .as("AC1: no teams created yet → totalTeams must be 0")
                .isEqualTo(0);
        assertThat(response.getBody().teamsWithPhoto())
                .as("AC1: no photos uploaded → teamsWithPhoto must be 0")
                .isEqualTo(0);
    }

    // =========================================================================
    // AC1 — Template upload flips templateUploaded
    // =========================================================================

    @Test
    @DisplayName("AC1: after template upload, templateUploaded becomes true")
    void afterTemplateUploadReadinessReflectsTemplate() throws Exception {
        UUID tournamentId = createTournament("Readiness Test — After Upload");

        // Verify initial state
        ResponseEntity<ReadinessResponse> before = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/certificate-readiness"),
                ReadinessResponse.class);
        assertThat(before.getBody()).isNotNull();
        assertThat(before.getBody().templateUploaded()).isFalse();

        // Upload a template
        uploadSvgTemplate(tournamentId, "cert.svg");

        // Verify readiness reflects the upload
        ResponseEntity<ReadinessResponse> after = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/certificate-readiness"),
                ReadinessResponse.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(after.getBody()).isNotNull();
        assertThat(after.getBody().templateUploaded())
                .as("AC1: after upload, templateUploaded must be true")
                .isTrue();
    }

    // =========================================================================
    // AC8 — Unknown tournament returns 404
    // =========================================================================

    @Test
    @DisplayName("AC8: unknown tournament UUID returns 404")
    void unknownTournamentReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response = authed.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + unknownId + "/certificate-readiness"),
                String.class);

        assertThat(response.getStatusCode())
                .as("AC8: unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC8 — Unauthenticated request returns 401
    // =========================================================================

    @Test
    @DisplayName("AC8: unauthenticated request returns 401")
    void unauthenticatedReturns401() throws Exception {
        UUID tournamentId = createTournament("Readiness Test — Auth");

        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/certificate-readiness"),
                String.class);

        assertThat(response.getStatusCode())
                .as("AC8: unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private void uploadSvgTemplate(UUID tournamentId, String filename) throws Exception {
        org.springframework.util.LinkedMultiValueMap<String, Object> body =
                new org.springframework.util.LinkedMultiValueMap<>();
        org.springframework.core.io.ByteArrayResource resource =
                new org.springframework.core.io.ByteArrayResource(SAMPLE_SVG) {
                    @Override
                    public String getFilename() { return filename; }
                };
        body.add("file", resource);

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, Object>> request =
                new org.springframework.http.HttpEntity<>(body, headers);

        authed.postForEntity(
                new URI(baseUrl + "/api/tournaments/" + tournamentId + "/certificate-template"),
                request, String.class);
    }

    // =========================================================================
    // Local response DTO
    // =========================================================================

    record ReadinessResponse(
            boolean templateUploaded,
            boolean standingsAvailable,
            int totalTeams,
            int teamsWithPhoto
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
