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
 * Integration tests for {@link CertificateTemplateController} (E12S04).
 *
 * <p>Tests the full HTTP stack (Spring MVC, Security, Flyway, H2, filesystem) to verify:
 * <ul>
 *   <li>AC1: Upload HTML/SVG returns 200 with metadata; replaces existing template</li>
 *   <li>AC2: GET returns file with correct Content-Type; 404 if no template</li>
 *   <li>AC3: GET /info returns metadata; 404 if no template</li>
 *   <li>AC5: DELETE returns 204; 404 if no template</li>
 *   <li>AC6: GET /api/certificate-template/variables returns 6 variables</li>
 *   <li>AC7: Unsupported format → 400; Oversized file → 400</li>
 *   <li>AC8: Unknown tournament → 404</li>
 *   <li>AC9: Error messages are descriptive (not generic 500)</li>
 *   <li>AC11: All endpoints require admin auth; unauthenticated → 401</li>
 * </ul>
 *
 * @see CertificateTemplateController
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S04.story.md">Story E12S04</a>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
                de.vvwt.tm.TournamentManagerApplication.class,
                CertificateTemplateControllerIT.TestAdminCredentials.class
        },
        properties = {
                "spring.datasource.url=jdbc:h2:mem:e12s04certdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "tm.certificate-templates.data-dir=${java.io.tmpdir}/tm-cert-templates-it-e12s04",
                "tm.certificate-templates.max-size-bytes=1048576"   // 1 MB for faster tests
        })
@ActiveProfiles("test")
@DisplayName("CertificateTemplateController IT — E12S04: certificate template REST API")
class CertificateTemplateControllerIT {

    private static final String TEST_PASSWORD = "CertTemplateTest12S04";

    private static final byte[] SAMPLE_HTML =
            "<html><body><h1>{{tournamentName}}</h1><p>{{teamName}} — Platz {{placement}}</p></body></html>"
                    .getBytes();
    private static final byte[] SAMPLE_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg'><text>{{placement}} — {{teamName}}</text></svg>"
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
    // AC1 — Upload HTML template
    // =========================================================================

    @Test
    @DisplayName("AC1: POST with HTML file returns 200 with metadata")
    void uploadHtmlReturns200WithMetadata() throws Exception {
        UUID tournamentId = createTournament("HTML Template Upload Test");

        ResponseEntity<TemplateMetadataResponse> response = uploadTemplate(
                authed, tournamentId, "my-certificate.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC1: HTML upload must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().filename()).isEqualTo("my-certificate.html");
        assertThat(response.getBody().format()).isEqualTo("html");
        assertThat(response.getBody().fileSizeBytes()).isEqualTo(SAMPLE_HTML.length);
        assertThat(response.getBody().uploadedAt()).isNotNull();
        assertThat(response.getBody().tournamentId()).isEqualTo(tournamentId);
    }

    // =========================================================================
    // AC1 — Upload SVG template
    // =========================================================================

    @Test
    @DisplayName("AC1: POST with SVG file returns 200 with metadata")
    void uploadSvgReturns200WithMetadata() throws Exception {
        UUID tournamentId = createTournament("SVG Template Upload Test");

        ResponseEntity<TemplateMetadataResponse> response = uploadTemplate(
                authed, tournamentId, "cert.svg", SAMPLE_SVG, MediaType.parseMediaType("image/svg+xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().format()).isEqualTo("svg");
        assertThat(response.getBody().filename()).isEqualTo("cert.svg");
    }

    // =========================================================================
    // AC4 — Replace existing template
    // =========================================================================

    @Test
    @DisplayName("AC4: Second upload replaces the existing template (same tournament)")
    void uploadReplacesExistingTemplate() throws Exception {
        UUID tournamentId = createTournament("Replace Template Test");

        uploadTemplate(authed, tournamentId, "v1.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        byte[] updated = "<html><body>Updated {{teamName}}</body></html>".getBytes();
        ResponseEntity<TemplateMetadataResponse> second = uploadTemplate(
                authed, tournamentId, "v2.html", updated, MediaType.TEXT_HTML);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().filename()).isEqualTo("v2.html");
        assertThat(second.getBody().fileSizeBytes()).isEqualTo(updated.length);
    }

    // =========================================================================
    // AC7 — Format validation
    // =========================================================================

    @Test
    @DisplayName("AC7: POST with .pdf file returns 400 with descriptive error")
    void uploadPdfReturns400() throws Exception {
        UUID tournamentId = createTournament("Format Reject Test");

        ResponseEntity<String> response = uploadTemplateAsString(
                authed, tournamentId, "document.pdf", SAMPLE_HTML, MediaType.APPLICATION_OCTET_STREAM);

        assertThat(response.getStatusCode())
                .as("AC7: non-HTML/SVG format must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("AC9: error message should mention accepted formats")
                .containsAnyOf("html", "svg", "HTML", "SVG");
    }

    @Test
    @DisplayName("AC7: POST with .png file returns 400")
    void uploadPngReturns400() throws Exception {
        UUID tournamentId = createTournament("Image Format Reject Test");

        ResponseEntity<String> response = uploadTemplateAsString(
                authed, tournamentId, "cert.png", SAMPLE_HTML, MediaType.IMAGE_PNG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC7: POST with file exceeding 1 MB limit returns 400")
    void uploadOversizedFileReturns400() throws Exception {
        UUID tournamentId = createTournament("Size Reject Test");

        // 1 MB + 1 byte — exceeds the test-configured 1 MB limit
        byte[] oversized = new byte[1024 * 1024 + 1];
        ResponseEntity<String> response = uploadTemplateAsString(
                authed, tournamentId, "huge.html", oversized, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC7: oversized template must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("AC7: POST with invalid SVG content (not starting with <svg or <?xml) returns 400")
    void uploadInvalidSvgReturns400() throws Exception {
        UUID tournamentId = createTournament("Invalid SVG Test");

        byte[] invalidSvg = "This is not an SVG".getBytes();
        ResponseEntity<String> response = uploadTemplateAsString(
                authed, tournamentId, "invalid.svg", invalidSvg, MediaType.parseMediaType("image/svg+xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC11 — Authentication
    // =========================================================================

    @Test
    @DisplayName("AC11: POST without credentials returns 401")
    void uploadRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Upload Test");

        ResponseEntity<String> response = uploadTemplateAsString(
                restTemplate, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC11: upload without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC8 — Tournament scope
    // =========================================================================

    @Test
    @DisplayName("AC8: POST for unknown tournament returns 404")
    void uploadForUnknownTournamentReturns404() throws Exception {
        UUID unknownTournament = UUID.randomUUID();

        ResponseEntity<String> response = uploadTemplateAsString(
                authed, unknownTournament, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC8: unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC2 — Retrieve file
    // =========================================================================

    @Test
    @DisplayName("AC2: GET after HTML upload returns 200 with text/html Content-Type")
    void retrieveHtmlFileReturns200WithHtmlContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve HTML Test");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        ResponseEntity<byte[]> response = authed.getForEntity(
                new URI(templateFileUrl(tournamentId)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .as("AC2: HTML template must have text/html Content-Type")
                .startsWith("text/html");
    }

    @Test
    @DisplayName("AC2: GET after SVG upload returns 200 with image/svg+xml Content-Type")
    void retrieveSvgFileReturns200WithSvgContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve SVG Test");
        uploadTemplate(authed, tournamentId, "cert.svg", SAMPLE_SVG, MediaType.parseMediaType("image/svg+xml"));

        ResponseEntity<byte[]> response = authed.getForEntity(
                new URI(templateFileUrl(tournamentId)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString())
                .as("AC2: SVG template must have image/svg+xml Content-Type")
                .startsWith("image/svg+xml");
    }

    @Test
    @DisplayName("AC2: GET when no template uploaded returns 404")
    void retrieveFileWhenNoTemplateReturns404() throws Exception {
        UUID tournamentId = createTournament("No Template Retrieve Test");

        ResponseEntity<String> response = authed.getForEntity(
                new URI(templateFileUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC2: retrieve without prior upload must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC11: GET file without credentials returns 401")
    void retrieveFileRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Retrieve File Test");

        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(templateFileUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC3 — Retrieve metadata
    // =========================================================================

    @Test
    @DisplayName("AC3: GET /info after upload returns 200 with metadata")
    void retrieveMetadataAfterUploadReturns200() throws Exception {
        UUID tournamentId = createTournament("Retrieve Metadata Test");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        ResponseEntity<TemplateMetadataResponse> response = authed.getForEntity(
                new URI(templateInfoUrl(tournamentId)), TemplateMetadataResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().filename()).isEqualTo("cert.html");
        assertThat(response.getBody().format()).isEqualTo("html");
        assertThat(response.getBody().fileSizeBytes()).isEqualTo(SAMPLE_HTML.length);
        assertThat(response.getBody().uploadedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC3: GET /info when no template returns 404")
    void retrieveMetadataWhenNoTemplateReturns404() throws Exception {
        UUID tournamentId = createTournament("No Template Metadata Test");

        ResponseEntity<String> response = authed.getForEntity(
                new URI(templateInfoUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC11: GET /info without credentials returns 401")
    void retrieveMetadataRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Retrieve Meta Test");

        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(templateInfoUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC5 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC5: DELETE after upload returns 204")
    void deleteAfterUploadReturns204() throws Exception {
        UUID tournamentId = createTournament("Delete Template Test");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        ResponseEntity<Void> response = authed.exchange(
                new URI(templateBaseUrl(tournamentId)),
                HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode())
                .as("AC5: delete existing template must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("AC5: DELETE when no template returns 404")
    void deleteWhenNoTemplateReturns404() throws Exception {
        UUID tournamentId = createTournament("Delete Missing Template Test");

        ResponseEntity<String> response = authed.exchange(
                new URI(templateBaseUrl(tournamentId)),
                HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode())
                .as("AC5: delete non-existent template must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC5: GET file after DELETE returns 404 (file is gone)")
    void getAfterDeleteReturns404() throws Exception {
        UUID tournamentId = createTournament("Get After Delete Test");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        authed.exchange(new URI(templateBaseUrl(tournamentId)), HttpMethod.DELETE, null, Void.class);

        ResponseEntity<String> getResponse = authed.getForEntity(
                new URI(templateFileUrl(tournamentId)), String.class);

        assertThat(getResponse.getStatusCode())
                .as("AC5: file must not be accessible after delete")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC11: DELETE without credentials returns 401")
    void deleteRequiresAuth() throws Exception {
        UUID tournamentId = createTournament("Auth Delete Test");

        ResponseEntity<String> response = restTemplate.exchange(
                new URI(templateBaseUrl(tournamentId)),
                HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC6 — Variables endpoint
    // =========================================================================

    @Test
    @DisplayName("AC6: GET /variables returns 200 with 6 variables")
    void variablesEndpointReturns6Variables() throws Exception {
        ResponseEntity<VariableResponse[]> response = authed.getForEntity(
                new URI(baseUrl + "/api/certificate-template/variables"),
                VariableResponse[].class);

        assertThat(response.getStatusCode())
                .as("AC6: variables endpoint must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC6: must return exactly 6 variables")
                .hasSize(6);
    }

    @Test
    @DisplayName("AC6: Variables include placement, teamName, teamPhoto, tournamentName, date, location")
    void variablesEndpointContainsExpectedVariableNames() throws Exception {
        ResponseEntity<VariableResponse[]> response = authed.getForEntity(
                new URI(baseUrl + "/api/certificate-template/variables"),
                VariableResponse[].class);

        assertThat(response.getBody()).isNotNull();
        String[] names = java.util.Arrays.stream(response.getBody())
                .map(VariableResponse::name).toArray(String[]::new);

        assertThat(names).containsExactly(
                "placement", "teamName", "teamPhoto",
                "tournamentName", "date", "location");
    }

    @Test
    @DisplayName("AC11: GET /variables without credentials returns 401")
    void variablesRequiresAuth() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                new URI(baseUrl + "/api/certificate-template/variables"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String templateBaseUrl(UUID tournamentId) {
        return baseUrl + "/api/tournaments/" + tournamentId + "/certificate-template";
    }

    private String templateFileUrl(UUID tournamentId) {
        return templateBaseUrl(tournamentId);
    }

    private String templateInfoUrl(UUID tournamentId) {
        return templateBaseUrl(tournamentId) + "/info";
    }

    private ResponseEntity<TemplateMetadataResponse> uploadTemplate(
            TestRestTemplate template, UUID tournamentId,
            String filename, byte[] content, MediaType fileMediaType) throws Exception {

        return template.postForEntity(
                new URI(templateBaseUrl(tournamentId)),
                buildMultipartRequest(filename, content, fileMediaType),
                TemplateMetadataResponse.class);
    }

    private ResponseEntity<String> uploadTemplateAsString(
            TestRestTemplate template, UUID tournamentId,
            String filename, byte[] content, MediaType fileMediaType) throws Exception {

        return template.postForEntity(
                new URI(templateBaseUrl(tournamentId)),
                buildMultipartRequest(filename, content, fileMediaType),
                String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(
            String filename, byte[] content, MediaType fileMediaType) {

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(fileMediaType);

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

    // =========================================================================
    // Local response DTOs (mirrors of the controller response types)
    // =========================================================================

    record TemplateMetadataResponse(
            UUID tournamentId,
            String filename,
            String format,
            Instant uploadedAt,
            long fileSizeBytes
    ) {}

    record VariableResponse(
            String name,
            String type,
            String example
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
