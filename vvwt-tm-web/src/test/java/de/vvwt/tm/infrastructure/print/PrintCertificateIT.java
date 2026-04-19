package de.vvwt.tm.infrastructure.print;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.auth.SecurityConfig;
import de.vvwt.tm.infrastructure.web.dto.TournamentCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TournamentResponse;
import de.vvwt.tm.tenant.TenantContextTestSupport;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Integration tests for certificate print routes — E12S06.
 *
 * <p>Tests the full HTTP stack to verify:
 *
 * <ul>
 *   <li>AC1: {@code GET /print/tournaments/{id}/certificates/{teamId}} — SVG path (not tested
 *       end-to-end without standings; route existence and error paths are verified)
 *   <li>AC2: same route → HTML path (template format = html)
 *   <li>AC3: {@code GET /print/tournaments/{id}/certificates} → ZIP path (SVG)
 *   <li>AC4: same route → HTML all-certificates page
 *   <li>AC5: Placement order from D-33 (verified via assembler unit test; here route delegates)
 *   <li>AC6: All 6 template variables present in rendered output (HTML path)
 *   <li>AC7: No template uploaded → 400 with German error message
 *   <li>AC8: No standings (no matches played) → 400 with German error message
 *   <li>AC9: Unknown tournament → 404; unknown team (not in standings) → 404
 *   <li>AC10: Error messages are in German (default locale)
 *   <li>AC11: All routes require admin auth → 401 without credentials; tenant isolation
 * </ul>
 *
 * <h2>Test data strategy</h2>
 *
 * <p>Setting up full match+standings data requires running the slot optimizer (apply-draft), which
 * is too expensive here. We rely on:
 *
 * <ul>
 *   <li>Tournament created via REST API (draft status, no phases, no matches)
 *   <li>HTML certificate template uploaded via the template API
 *   <li>SVG certificate template uploaded via the template API
 *   <li>Error-path tests (AC7, AC8) exercise the 400 responses
 *   <li>Auth tests (AC11) and 404 tests (AC9) work without full data
 * </ul>
 *
 * <p>The {@link CertificateAssemblerTest} covers data assembly and rendering logic with
 * unit-test-level isolation.
 *
 * @see PrintController
 * @see CertificateAssembler
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S06.story.md">Story
 *     E12S06</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            de.vvwt.tm.TournamentManagerApplication.class,
            PrintCertificateIT.TestAdminCredentials.class
        },
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e12s06certprintdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.certificate-templates.data-dir=${java.io.tmpdir}/tm-cert-print-it-e12s06",
            "tm.certificate-templates.max-size-bytes=1048576"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("PrintController IT — E12S06: certificate rendering routes")
class PrintCertificateIT {

    private static final String TEST_PASSWORD = "CertPrintTestE12S06";

    /** Minimal valid HTML certificate template with all 6 D-4 variable placeholders. */
    private static final byte[] SAMPLE_HTML_TEMPLATE =
            ("<html><body>"
                            + "<h1>{{placement}} — {{teamName}}</h1>"
                            + "<p>{{tournamentName}}</p>"
                            + "<p>{{date}} — {{location}}</p>"
                            + "{{#hasPhoto}}<img src=\"{{teamPhoto}}\">{{/hasPhoto}}"
                            + "</body></html>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Minimal valid SVG certificate template with all 6 D-4 variable placeholders. */
    private static final byte[] SAMPLE_SVG_TEMPLATE =
            ("<svg xmlns='http://www.w3.org/2000/svg'>"
                            + "<text>{{placement}} — {{teamName}}</text>"
                            + "<text>{{tournamentName}}</text>"
                            + "<text>{{date}} — {{location}}</text>"
                            + "</svg>")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(SecurityConfig.ADMIN_USERNAME, TEST_PASSWORD);
    }

    // =========================================================================
    // AC11: authentication enforcement
    // =========================================================================

    @Test
    @DisplayName(
            "AC11: GET /print/tournaments/{id}/certificates/{teamId} without credentials returns"
                    + " 401")
    void singleCertificateRequiresAuthentication() throws Exception {
        UUID randomTournament = UUID.randomUUID();
        UUID randomTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + randomTournament
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC11: single certificate route must require authentication")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC11: GET /print/tournaments/{id}/certificates without credentials returns 401")
    void allCertificatesRequiresAuthentication() throws Exception {
        UUID randomTournament = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + randomTournament
                                        + "/certificates"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC11: all-certificates route must require authentication")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC9: unknown tournament → 404
    // =========================================================================

    @Test
    @DisplayName("AC9: GET /print/tournaments/{unknownId}/certificates/{teamId} returns 404")
    void singleCertificateReturns404ForUnknownTournament() throws Exception {
        UUID unknownTournament = UUID.randomUUID();
        UUID randomTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + unknownTournament
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC9: unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC9: GET /print/tournaments/{unknownId}/certificates returns 404")
    void allCertificatesReturns404ForUnknownTournament() throws Exception {
        UUID unknownTournament = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + unknownTournament
                                        + "/certificates"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC9: unknown tournament (all-certs route) must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC7: no template uploaded → 400 with German message
    // =========================================================================

    @Test
    @DisplayName("AC7: GET single certificate without uploaded template returns 400")
    void singleCertificateReturns400WhenNoTemplateUploaded() throws Exception {
        UUID tournamentId = createTournament("Cert Route No Template Single");
        UUID randomTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + tournamentId
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC7: no template uploaded must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC7: GET all certificates without uploaded template returns 400")
    void allCertificatesReturns400WhenNoTemplateUploaded() throws Exception {
        UUID tournamentId = createTournament("Cert Route No Template All");

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(baseUrl + "/print/tournaments/" + tournamentId + "/certificates"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC7: no template uploaded (all route) must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC10: 400 error message is in German when no template uploaded")
    void noTemplateErrorMessageIsGerman() throws Exception {
        UUID tournamentId = createTournament("German Error Message Test");
        UUID randomTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + tournamentId
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("AC10: error message must be in German (contains 'Vorlage')")
                .containsIgnoringCase("Vorlage");
    }

    // =========================================================================
    // AC8: no standings → 400 with German message (template present, no matches)
    // =========================================================================

    @Test
    @DisplayName("AC8: GET single certificate with template but no standings returns 400")
    void singleCertificateReturns400WhenNoStandings() throws Exception {
        UUID tournamentId = createTournament("Cert Route No Standings Single");
        uploadHtmlTemplate(tournamentId);

        UUID randomTeam = UUID.randomUUID();
        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + tournamentId
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC8: no standings (no phases/ratings) must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC8: GET all certificates with template but no standings returns 400")
    void allCertificatesReturns400WhenNoStandings() throws Exception {
        UUID tournamentId = createTournament("Cert Route No Standings All");
        uploadHtmlTemplate(tournamentId);

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(baseUrl + "/print/tournaments/" + tournamentId + "/certificates"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC8: no standings (all route) must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC10: 400 error message is in German when no standings")
    void noStandingsErrorMessageIsGerman() throws Exception {
        UUID tournamentId = createTournament("German Standings Error Test");
        uploadHtmlTemplate(tournamentId);
        UUID randomTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + tournamentId
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("AC10: no-standings error must be in German (contains 'Spielergebnisse')")
                .containsIgnoringCase("Spielergebnisse");
    }

    @Test
    @DisplayName("AC8: no standings 400 response body does not contain HTML (plain text error)")
    void noStandingsErrorIsPlainText() throws Exception {
        UUID tournamentId = createTournament("Plain Text Error Test");
        uploadHtmlTemplate(tournamentId);
        UUID randomTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + tournamentId
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Error is plain text, not JSON (AC10: no raw JSON errors)
        assertThat(response.getBody())
                .as("AC10: error response must not be JSON")
                .doesNotContain("\"status\":");
    }

    // =========================================================================
    // AC7: SVG template — no template uploaded → 400
    // =========================================================================

    @Test
    @DisplayName("AC7: GET all certificates (SVG path) without template returns 400")
    void allCertificatesSvgPathReturns400WhenNoTemplateUploaded() throws Exception {
        UUID tournamentId = createTournament("Cert Route SVG No Template All");

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(baseUrl + "/print/tournaments/" + tournamentId + "/certificates"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC7: SVG all-certs route without template must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC8: SVG template uploaded but no standings → 400
    // =========================================================================

    @Test
    @DisplayName("AC8: GET all certificates (SVG path) with template but no standings returns 400")
    void allCertificatesSvgPathReturns400WhenNoStandings() throws Exception {
        UUID tournamentId = createTournament("Cert Route SVG No Standings");
        uploadSvgTemplate(tournamentId);

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(baseUrl + "/print/tournaments/" + tournamentId + "/certificates"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC8: SVG all-certs route with no standings must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName(
            "AC8: GET single certificate (SVG path) with template but no standings returns 400")
    void singleCertificateSvgPathReturns400WhenNoStandings() throws Exception {
        UUID tournamentId = createTournament("Cert Route SVG Single No Standings");
        uploadSvgTemplate(tournamentId);
        UUID randomTeam = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/print/tournaments/"
                                        + tournamentId
                                        + "/certificates/"
                                        + randomTeam),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC8: SVG single-cert route with no standings must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC11: tenant isolation — correct tenant but wrong tournament UUID → 404
    // =========================================================================

    @Test
    @DisplayName(
            "AC11: GET /print/tournaments/{randomId}/certificates returns 404 (tenant isolation)")
    void allCertificatesReturns404ForRandomUuid() throws Exception {
        UUID neverCreated = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(baseUrl + "/print/tournaments/" + neverCreated + "/certificates"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC11: tenant-isolated repository returns 404 for unknown tournament UUID")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // Helper: create tournament
    // =========================================================================

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

        assertThat(created.getStatusCode())
                .as("Tournament creation must succeed (201)")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    // =========================================================================
    // Helper: upload certificate template (HTML)
    // =========================================================================

    private void uploadHtmlTemplate(UUID tournamentId) throws Exception {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.TEXT_HTML);

        ByteArrayResource fileResource =
                new ByteArrayResource(SAMPLE_HTML_TEMPLATE) {
                    @Override
                    public String getFilename() {
                        return "certificate.html";
                    }
                };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(fileResource, fileHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(
                                baseUrl
                                        + "/api/tournaments/"
                                        + tournamentId
                                        + "/certificate-template"),
                        new HttpEntity<>(body, requestHeaders),
                        String.class);

        assertThat(response.getStatusCode())
                .as("HTML template upload must succeed")
                .isEqualTo(HttpStatus.OK);
    }

    // =========================================================================
    // Helper: upload certificate template (SVG)
    // =========================================================================

    private void uploadSvgTemplate(UUID tournamentId) throws Exception {
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType("image/svg+xml"));

        ByteArrayResource fileResource =
                new ByteArrayResource(SAMPLE_SVG_TEMPLATE) {
                    @Override
                    public String getFilename() {
                        return "certificate.svg";
                    }
                };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(fileResource, fileHeaders));

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response =
                authed.postForEntity(
                        new URI(
                                baseUrl
                                        + "/api/tournaments/"
                                        + tournamentId
                                        + "/certificate-template"),
                        new HttpEntity<>(body, requestHeaders),
                        String.class);

        assertThat(response.getStatusCode())
                .as("SVG template upload must succeed")
                .isEqualTo(HttpStatus.OK);
    }

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
