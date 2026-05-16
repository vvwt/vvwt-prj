// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.auth.AdminCredentialsProvider;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Integration tests for {@link CertificateTemplateController} — E36S06 Q-1a TDD RED-first rebuild.
 *
 * <p>Authored RED-first per DEC-22 Iron Law: this IT was committed BEFORE the implementation
 * classes ({@link CertificateTemplateController}, {@link CertificateTemplateMetadataResponse},
 * {@link CertificateTemplateVariableResponse}) were written. The RED commit hash is the parent of
 * the GREEN commit that adds the implementation.
 *
 * <h2>DEC-38 + DEC-40 — @ApplicationModuleTest (AC-DEC38-IT-PATTERN)</h2>
 *
 * <p>Uses {@code @ApplicationModuleTest(ALL_DEPENDENCIES, RANDOM_PORT)} targeting the {@code web}
 * module per DEC-38 Clause A + DEC-40 2026-04-22 Amendment. Boots {@code web} + all declared {@code
 * allowedDependencies} (tenant, tournament, tournament::exceptions, scoring, photo, certificate).
 *
 * <h2>DEC-36 — Cross-package test typing (AC-TESTING-DEC36)</h2>
 *
 * <p>This test class is in package {@code de.vvwt.tm.web.certificate} (different from {@code
 * de.vvwt.tm.certificate.internal}). It references {@link
 * de.vvwt.tm.certificate.CertificateTemplateService} (public interface) only, never the
 * implementation class.
 *
 * <h2>DEC-41 — Fresh TDD corpus (AC-DEC41-FRESH-RED-FIRST-TESTS)</h2>
 *
 * <p>The legacy 23-method {@code CertificateTemplateControllerIT} was classified as 100%
 * Snapshot-Driven per DEC-41 §1 and deleted per AC-DELETE-LEGACY-FIRST. This IT is a fully fresh
 * TDD corpus covering the same AC surface with new test methods authored RED-first.
 *
 * <h2>HTTP contract preserved verbatim (AC-MOCKMVC-CONTRACT-PRESERVED,
 * AC-C3-SIGNATURE-PRESERVATION)</h2>
 *
 * <p>URL paths and JSON wire shapes from the Q-1b relocated {@code CertificateTemplateController}
 * (E23S09) are preserved verbatim per DEC-40 boundary preservation.
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC1: Upload HTML returns 200 with metadata DTO (tournamentId, filename, format, uploadedAt,
 *       fileSizeBytes)
 *   <li>AC1: Upload SVG returns 200 with correct format
 *   <li>AC4: Second upload replaces existing template
 *   <li>AC2: GET file after HTML upload returns 200 with text/html Content-Type
 *   <li>AC2: GET file after SVG upload returns 200 with image/svg+xml Content-Type
 *   <li>AC2: GET file when no template returns 404
 *   <li>AC3: GET /info after upload returns 200 with metadata
 *   <li>AC3: GET /info when no template returns 404
 *   <li>AC5: DELETE after upload returns 204
 *   <li>AC5: DELETE when no template returns 404
 *   <li>AC5: GET file after DELETE returns 404
 *   <li>AC6: GET /variables returns 200 with exactly 6 variables
 *   <li>AC6: Variables contain expected names (placement, teamName, teamPhoto, tournamentName,
 *       date, location)
 *   <li>AC7: POST with .pdf format returns 400
 *   <li>AC7: POST with .png format returns 400
 *   <li>AC7: POST with oversized file returns 400
 *   <li>AC7: POST with invalid SVG content returns 400
 *   <li>AC8: POST for unknown tournament returns 404
 *   <li>AC11: POST without auth returns 401
 *   <li>AC11: GET file without auth returns 401
 *   <li>AC11: GET /info without auth returns 401
 *   <li>AC11: DELETE without auth returns 401
 *   <li>AC11: GET /variables without auth returns 401
 * </ul>
 *
 * @see CertificateTemplateController
 * @see WebModuleTestConfig
 * @see de.vvwt.tm.certificate.CertificateTemplateService
 * @see DEC-38
 * @see DEC-40
 * @see DEC-41
 * @see E36S06
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, CertificateTemplateControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "tm.certificate-templates.data-dir=${java.io.tmpdir}/tm-cert-templates-it-e36s06",
            "tm.certificate-templates.max-size-bytes=1048576" // 1 MB for faster tests
        })
@DisplayName("CertificateTemplateController IT — E36S06 Q-1a TDD rebuild")
class CertificateTemplateControllerIT {

    private static final String TEST_PASSWORD = "CertTemplateTest36S06";

    private static final byte[] SAMPLE_HTML =
            "<html><body><h1>{{tournamentName}}</h1><p>{{teamName}} — Platz {{placement}}</p></body></html>"
                    .getBytes();
    private static final byte[] SAMPLE_SVG =
            "<svg xmlns='http://www.w3.org/2000/svg'><text>{{placement}} — {{teamName}}</text></svg>"
                    .getBytes();

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
    // AC1 — Upload HTML template
    // =========================================================================

    @Test
    @DisplayName("AC1: POST with HTML file returns 200 with full metadata DTO")
    void uploadHtmlReturns200WithMetadata() throws Exception {
        UUID tournamentId = createTournament("HTML Upload IT E36S06");

        ResponseEntity<TemplateMetadataResponse> response =
                uploadTemplate(
                        authed, tournamentId, "certificate.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC1: HTML upload must return 200 OK")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().tournamentId())
                .as("AC1: response.tournamentId must match path variable")
                .isEqualTo(tournamentId);
        assertThat(response.getBody().filename())
                .as("AC1: response.filename must match uploaded file name")
                .isEqualTo("certificate.html");
        assertThat(response.getBody().format())
                .as("AC1: response.format must be 'html' for .html file")
                .isEqualTo("html");
        assertThat(response.getBody().fileSizeBytes())
                .as("AC1: response.fileSizeBytes must match uploaded file size")
                .isEqualTo(SAMPLE_HTML.length);
        assertThat(response.getBody().uploadedAt())
                .as("AC1: response.uploadedAt must not be null")
                .isNotNull();
    }

    // =========================================================================
    // AC1 — Upload SVG template
    // =========================================================================

    @Test
    @DisplayName("AC1: POST with SVG file returns 200 with format=svg")
    void uploadSvgReturns200WithSvgFormat() throws Exception {
        UUID tournamentId = createTournament("SVG Upload IT E36S06");

        ResponseEntity<TemplateMetadataResponse> response =
                uploadTemplate(
                        authed,
                        tournamentId,
                        "cert.svg",
                        SAMPLE_SVG,
                        MediaType.parseMediaType("image/svg+xml"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().format())
                .as("AC1: format must be 'svg' for .svg file")
                .isEqualTo("svg");
        assertThat(response.getBody().filename()).isEqualTo("cert.svg");
    }

    // =========================================================================
    // AC4 — Replace existing template
    // =========================================================================

    @Test
    @DisplayName("AC4: Second upload replaces existing template; metadata reflects new file")
    void secondUploadReplacesExistingTemplate() throws Exception {
        UUID tournamentId = createTournament("Replace Template IT E36S06");

        uploadTemplate(authed, tournamentId, "v1.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        byte[] updated =
                "<html><body>Updated {{teamName}} — {{placement}}</body></html>".getBytes();
        ResponseEntity<TemplateMetadataResponse> second =
                uploadTemplate(authed, tournamentId, "v2.html", updated, MediaType.TEXT_HTML);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().filename())
                .as("AC4: after replace, filename reflects new upload")
                .isEqualTo("v2.html");
        assertThat(second.getBody().fileSizeBytes())
                .as("AC4: after replace, fileSizeBytes reflects new file size")
                .isEqualTo(updated.length);
    }

    // =========================================================================
    // AC2 — Retrieve file (HTML)
    // =========================================================================

    @Test
    @DisplayName("AC2: GET template file after HTML upload returns 200 with text/html Content-Type")
    void retrieveHtmlFileReturns200WithHtmlContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve HTML IT E36S06");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        ResponseEntity<byte[]> response =
                authed.getForEntity(new URI(templateFileUrl(tournamentId)), byte[].class);

        assertThat(response.getStatusCode())
                .as("AC2: GET file after upload must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("AC2: Content-Type must not be null")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .as("AC2: HTML template must have text/html Content-Type")
                .startsWith("text/html");
    }

    // =========================================================================
    // AC2 — Retrieve file (SVG)
    // =========================================================================

    @Test
    @DisplayName(
            "AC2: GET template file after SVG upload returns 200 with image/svg+xml Content-Type")
    void retrieveSvgFileReturns200WithSvgContentType() throws Exception {
        UUID tournamentId = createTournament("Retrieve SVG IT E36S06");
        uploadTemplate(
                authed,
                tournamentId,
                "cert.svg",
                SAMPLE_SVG,
                MediaType.parseMediaType("image/svg+xml"));

        ResponseEntity<byte[]> response =
                authed.getForEntity(new URI(templateFileUrl(tournamentId)), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString())
                .as("AC2: SVG template must have image/svg+xml Content-Type")
                .startsWith("image/svg+xml");
    }

    // =========================================================================
    // AC2 — Retrieve file when no template uploaded
    // =========================================================================

    @Test
    @DisplayName("AC2: GET template file when no template uploaded returns 404")
    void retrieveFileWhenNoTemplateReturns404() throws Exception {
        UUID tournamentId = createTournament("No Template Retrieve IT E36S06");

        ResponseEntity<String> response =
                authed.getForEntity(new URI(templateFileUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC2: GET file without prior upload must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC3 — Retrieve metadata
    // =========================================================================

    @Test
    @DisplayName("AC3: GET /info after upload returns 200 with full metadata")
    void retrieveMetadataAfterUploadReturns200WithFullMetadata() throws Exception {
        UUID tournamentId = createTournament("Metadata Retrieve IT E36S06");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        ResponseEntity<TemplateMetadataResponse> response =
                authed.getForEntity(
                        new URI(templateInfoUrl(tournamentId)), TemplateMetadataResponse.class);

        assertThat(response.getStatusCode())
                .as("AC3: GET /info after upload must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().filename()).isEqualTo("cert.html");
        assertThat(response.getBody().format()).isEqualTo("html");
        assertThat(response.getBody().fileSizeBytes()).isEqualTo(SAMPLE_HTML.length);
        assertThat(response.getBody().uploadedAt()).isNotNull();
    }

    // =========================================================================
    // AC3 — Retrieve metadata when no template uploaded
    // =========================================================================

    @Test
    @DisplayName("AC3: GET /info when no template uploaded returns 404")
    void retrieveMetadataWhenNoTemplateReturns404() throws Exception {
        UUID tournamentId = createTournament("No Template Metadata IT E36S06");

        ResponseEntity<String> response =
                authed.getForEntity(new URI(templateInfoUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC3: GET /info without prior upload must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC5 — Delete
    // =========================================================================

    @Test
    @DisplayName("AC5: DELETE after upload returns 204 No Content")
    void deleteAfterUploadReturns204() throws Exception {
        UUID tournamentId = createTournament("Delete Template IT E36S06");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        ResponseEntity<Void> response =
                authed.exchange(
                        new URI(templateBaseUrl(tournamentId)),
                        HttpMethod.DELETE,
                        null,
                        Void.class);

        assertThat(response.getStatusCode())
                .as("AC5: DELETE of existing template must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    // =========================================================================
    // AC5 — Delete when no template
    // =========================================================================

    @Test
    @DisplayName("AC5: DELETE when no template uploaded returns 404")
    void deleteWhenNoTemplateReturns404() throws Exception {
        UUID tournamentId = createTournament("Delete Missing Template IT E36S06");

        ResponseEntity<String> response =
                authed.exchange(
                        new URI(templateBaseUrl(tournamentId)),
                        HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC5: DELETE of non-existent template must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC5 — GET file after DELETE
    // =========================================================================

    @Test
    @DisplayName("AC5: GET file after DELETE returns 404 (template is gone)")
    void getFileAfterDeleteReturns404() throws Exception {
        UUID tournamentId = createTournament("Get After Delete IT E36S06");
        uploadTemplate(authed, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        authed.exchange(
                new URI(templateBaseUrl(tournamentId)), HttpMethod.DELETE, null, Void.class);

        ResponseEntity<String> getResponse =
                authed.getForEntity(new URI(templateFileUrl(tournamentId)), String.class);

        assertThat(getResponse.getStatusCode())
                .as("AC5: file must not be accessible after deletion")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC6 — Variables endpoint: count
    // =========================================================================

    @Test
    @DisplayName(
            "AC6 (E46S03): GET /variables returns 200 with exactly 13 tom_-prefixed variables"
                    + " (AC-VARIABLES-LIST-COUNT-EXACTLY-13)")
    void variablesEndpointReturns6Variables() throws Exception {
        ResponseEntity<VariableResponse[]> response =
                authed.getForEntity(
                        new URI(baseUrl + "/api/certificate/variables"), VariableResponse[].class);

        assertThat(response.getStatusCode())
                .as("AC6: variables endpoint must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("AC6 (E46S03): must return exactly 13 variables")
                .hasSize(13);
    }

    // =========================================================================
    // AC6 — Variables endpoint: names in order
    // =========================================================================

    @Test
    @DisplayName(
            "AC6 (E46S03): Variables contain 13 tom_-prefixed names"
                    + " (AC-VARIABLES-LIST-13-PREFIXED)")
    void variablesEndpointContainsExpectedNames() throws Exception {
        ResponseEntity<VariableResponse[]> response =
                authed.getForEntity(
                        new URI(baseUrl + "/api/certificate/variables"), VariableResponse[].class);

        assertThat(response.getBody()).isNotNull();
        String[] names =
                java.util.Arrays.stream(response.getBody())
                        .map(VariableResponse::name)
                        .toArray(String[]::new);

        assertThat(names)
                .as("AC6 (E46S03): variable names must match tom_-prefixed contract in order")
                .containsExactly(
                        "tom_placement",
                        "tom_team_name",
                        "tom_team_photo",
                        "tom_tournament_name",
                        "tom_date",
                        "tom_location",
                        "tom_organizer",
                        "tom_label_certificate",
                        "tom_label_place",
                        "tom_label_achieved_by",
                        "tom_label_team_photo",
                        "tom_label_generated_by",
                        "tom_label_on");
    }

    // =========================================================================
    // AC7 — Format validation: .pdf rejected
    // =========================================================================

    @Test
    @DisplayName("AC7: POST with .pdf filename returns 400 with descriptive message")
    void uploadPdfReturns400() throws Exception {
        UUID tournamentId = createTournament("PDF Format Reject IT E36S06");

        ResponseEntity<String> response =
                uploadTemplateAsString(
                        authed,
                        tournamentId,
                        "document.pdf",
                        SAMPLE_HTML,
                        MediaType.APPLICATION_OCTET_STREAM);

        assertThat(response.getStatusCode())
                .as("AC7: non-HTML/SVG file must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("AC9: error message should mention accepted formats")
                .containsAnyOf("html", "svg", "HTML", "SVG");
    }

    // =========================================================================
    // AC7 — Format validation: .png rejected
    // =========================================================================

    @Test
    @DisplayName("AC7: POST with .png filename returns 400")
    void uploadPngReturns400() throws Exception {
        UUID tournamentId = createTournament("PNG Format Reject IT E36S06");

        ResponseEntity<String> response =
                uploadTemplateAsString(
                        authed, tournamentId, "cert.png", SAMPLE_HTML, MediaType.IMAGE_PNG);

        assertThat(response.getStatusCode())
                .as("AC7: image/png file must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC7 — Size validation: oversized file rejected
    // =========================================================================

    @Test
    @DisplayName("AC7: POST with file exceeding configured 1 MB limit returns 400")
    void uploadOversizedFileReturns400() throws Exception {
        UUID tournamentId = createTournament("Oversized File IT E36S06");

        // 1 MB + 1 byte — exceeds the test-configured 1 MB limit
        byte[] oversized = new byte[1024 * 1024 + 1];
        ResponseEntity<String> response =
                uploadTemplateAsString(
                        authed, tournamentId, "huge.html", oversized, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC7: file exceeding max-size-bytes must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC7 — Content validation: invalid SVG content rejected
    // =========================================================================

    @Test
    @DisplayName("AC7: POST with .svg extension but non-SVG content returns 400")
    void uploadInvalidSvgContentReturns400() throws Exception {
        UUID tournamentId = createTournament("Invalid SVG Content IT E36S06");

        byte[] notAnSvg = "This is not an SVG file".getBytes();
        ResponseEntity<String> response =
                uploadTemplateAsString(
                        authed,
                        tournamentId,
                        "invalid.svg",
                        notAnSvg,
                        MediaType.parseMediaType("image/svg+xml"));

        assertThat(response.getStatusCode())
                .as("AC7: SVG file with invalid content must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // AC8 — Unknown tournament
    // =========================================================================

    @Test
    @DisplayName("AC8: POST for unknown tournament returns 404")
    void uploadForUnknownTournamentReturns404() throws Exception {
        UUID unknownId = UUID.randomUUID();

        ResponseEntity<String> response =
                uploadTemplateAsString(
                        authed, unknownId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC8: upload for unknown tournament must return 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // AC11 — Authentication: POST requires auth
    // =========================================================================

    @Test
    @DisplayName("AC11: POST without credentials returns 401")
    void uploadRequiresAuthentication() throws Exception {
        UUID tournamentId = createTournament("Auth Upload IT E36S06");

        ResponseEntity<String> response =
                uploadTemplateAsString(
                        restTemplate, tournamentId, "cert.html", SAMPLE_HTML, MediaType.TEXT_HTML);

        assertThat(response.getStatusCode())
                .as("AC11: upload without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC11 — Authentication: GET file requires auth
    // =========================================================================

    @Test
    @DisplayName("AC11: GET template file without credentials returns 401")
    void retrieveFileRequiresAuthentication() throws Exception {
        UUID tournamentId = createTournament("Auth Retrieve File IT E36S06");

        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(templateFileUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC11: GET file without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC11 — Authentication: GET /info requires auth
    // =========================================================================

    @Test
    @DisplayName("AC11: GET /info without credentials returns 401")
    void retrieveMetadataRequiresAuthentication() throws Exception {
        UUID tournamentId = createTournament("Auth Retrieve Info IT E36S06");

        ResponseEntity<String> response =
                restTemplate.getForEntity(new URI(templateInfoUrl(tournamentId)), String.class);

        assertThat(response.getStatusCode())
                .as("AC11: GET /info without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC11 — Authentication: DELETE requires auth
    // =========================================================================

    @Test
    @DisplayName("AC11: DELETE without credentials returns 401")
    void deleteRequiresAuthentication() throws Exception {
        UUID tournamentId = createTournament("Auth Delete IT E36S06");

        ResponseEntity<String> response =
                restTemplate.exchange(
                        new URI(templateBaseUrl(tournamentId)),
                        HttpMethod.DELETE,
                        null,
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC11: DELETE without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC11 — Authentication: GET /variables requires auth
    // =========================================================================

    @Test
    @DisplayName("AC11: GET /variables without credentials returns 401")
    void variablesRequiresAuthentication() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/api/certificate/variables"), String.class);

        assertThat(response.getStatusCode())
                .as("AC11: GET /variables without auth must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Helpers — URL builders
    // =========================================================================

    private String templateBaseUrl(UUID tournamentId) {
        return baseUrl + "/api/certificate/tournaments/" + tournamentId + "/template";
    }

    private String templateFileUrl(UUID tournamentId) {
        return templateBaseUrl(tournamentId);
    }

    private String templateInfoUrl(UUID tournamentId) {
        return templateBaseUrl(tournamentId) + "/info";
    }

    // =========================================================================
    // Helpers — HTTP request builders
    // =========================================================================

    private ResponseEntity<TemplateMetadataResponse> uploadTemplate(
            TestRestTemplate template,
            UUID tournamentId,
            String filename,
            byte[] content,
            MediaType fileMediaType)
            throws Exception {

        return template.postForEntity(
                new URI(templateBaseUrl(tournamentId)),
                buildMultipartRequest(filename, content, fileMediaType),
                TemplateMetadataResponse.class);
    }

    private ResponseEntity<String> uploadTemplateAsString(
            TestRestTemplate template,
            UUID tournamentId,
            String filename,
            byte[] content,
            MediaType fileMediaType)
            throws Exception {

        return template.postForEntity(
                new URI(templateBaseUrl(tournamentId)),
                buildMultipartRequest(filename, content, fileMediaType),
                String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipartRequest(
            String filename, byte[] content, MediaType fileMediaType) {

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(fileMediaType);

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
    // Helpers — Tournament factory
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
                        "roundRobin",
                        null,
                        null,
                        null); // E53S05: seedMannschaftsfoto = null

        ResponseEntity<TournamentResponse> created =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    // =========================================================================
    // Local response DTOs (test-local records — do NOT reference implementation classes)
    // DEC-36: test in different package from de.vvwt.tm.certificate.internal
    // =========================================================================

    /**
     * Test-local mirror of {@link CertificateTemplateMetadataResponse} wire shape.
     *
     * <p>DEC-36: cross-package test must not import the implementation DTO directly when
     * deserializing JSON — using a test-local record with the same field names achieves Jackson
     * deserialization without violating cross-package type isolation.
     */
    record TemplateMetadataResponse(
            UUID tournamentId,
            String filename,
            String format,
            Instant uploadedAt,
            long fileSizeBytes) {}

    /** Test-local mirror of {@link CertificateTemplateVariableResponse} wire shape. */
    record VariableResponse(String name, String type, String example) {}

    // =========================================================================
    // Test configuration — override admin credentials with known test password
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
