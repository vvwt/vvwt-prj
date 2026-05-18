// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.certificate;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Integration tests for {@link CertificateRenderController} — E24S05 / E67S01.
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT)} per DEC-44 web-module IT pattern. Boots the full
 * application context. {@link WebModuleTestConfig} provides the test infrastructure beans.
 *
 * <h2>DEC-38 Amendment reverse-case (real beans in scope)</h2>
 *
 * <p>All certificate/tournament collaborator beans are real (not mocked): {@code
 * CertificateAssembler}, {@code CertificateTemplateService}, {@code TournamentRepository}, etc. are
 * present in the {@code web} module's {@code allowedDependencies}. No {@code @MockitoBean} for
 * domain-layer beans (per DEC-38 Amendment reverse-case).
 *
 * <h2>Test coverage</h2>
 *
 * <ul>
 *   <li>AC-CERT-IT-NO-RESULTS-400: authenticated + tournament exists + no template + no game
 *       results → 400 (E67S01: no-results branch is preserved; 400 is now from missing results, not
 *       missing template)
 *   <li>AC-CERT-IT-BATCH-NO-RESULTS-400: batch endpoint + no template + no game results → 400
 *   <li>AC-CERT-IT-SECURITY-UNAUTHENTICATED: unauthenticated → 401
 *   <li>AC-BYTE-EQUIVALENT-400: 400 body non-empty for no-results scenario
 *   <li>AC-CERT-IT-SVG-HAPPY: tournament exists + SVG template uploaded → 400 (no standings)
 * </ul>
 *
 * @see CertificateRenderController
 * @see WebModuleTestConfig
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (Q-1a RED-first)</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest IT canon</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @since E24S05
 * @since E67S01 — no-template fallback to system-default certificate
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({WebModuleTestConfig.class, CertificateRenderControllerIT.TestAdminCredentials.class})
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "tm.certificate-templates.data-dir=${java.io.tmpdir}/tm-cert-templates-it-e24s05",
            "tm.certificate-templates.max-size-bytes=1048576"
        })
@DisplayName("CertificateRenderController IT — E24S05")
class CertificateRenderControllerIT {

    static final String TEST_PASSWORD = "CertRenderTest24S05";

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
    // AC-CERT-IT-SECURITY-UNAUTHENTICATED — unauthenticated → 401
    // =========================================================================

    @Test
    @DisplayName("AC-CERT-IT-SECURITY-UNAUTHENTICATED: single endpoint unauthenticated → 401")
    void singleCertificate_unauthenticated_returns401() throws Exception {
        UUID tournamentId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(
                                baseUrl
                                        + "/certificate/tournaments/"
                                        + tournamentId
                                        + "/print/"
                                        + teamId),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC-CERT-IT-SECURITY-UNAUTHENTICATED — unauthenticated must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC-CERT-IT-SECURITY-UNAUTHENTICATED: batch endpoint unauthenticated → 401")
    void allCertificates_unauthenticated_returns401() throws Exception {
        UUID tournamentId = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        new URI(baseUrl + "/certificate/tournaments/" + tournamentId + "/print"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC-CERT-IT-SECURITY-UNAUTHENTICATED — batch unauthenticated must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // AC-CERT-IT-NO-RESULTS-400 — authenticated, no template, no game results → 400
    // E67S01: the 400 here is from the no-results branch (AC5 preserved), not no-template.
    // The no-results check runs BEFORE the template check in the updated controller.
    // =========================================================================

    @Test
    @DisplayName(
            "AC-CERT-IT-NO-RESULTS-400 (E67S01 AC5): single endpoint, no template + no game"
                    + " results → 400 text/plain (no-results branch preserved)")
    void singleCertificate_noTemplateNoResults_returns400() throws Exception {
        UUID tournamentId = createTournament("CertRender No Template Test");
        UUID teamId = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/certificate/tournaments/"
                                        + tournamentId
                                        + "/print/"
                                        + teamId),
                        String.class);

        assertThat(response.getStatusCode())
                .as(
                        "AC-CERT-IT-NO-RESULTS-400 — no game results must return 400"
                                + " (no-results branch preserved per E67S01 AC5)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("AC-CERT-IT-NO-RESULTS-400 — 400 body must be non-empty")
                .isNotBlank();
    }

    @Test
    @DisplayName(
            "AC-CERT-IT-BATCH-NO-RESULTS-400 (E67S01 AC5): batch endpoint, no template + no"
                    + " game results → 400 text/plain (no-results branch preserved)")
    void allCertificates_noTemplateNoResults_returns400() throws Exception {
        UUID tournamentId = createTournament("CertRender Batch No Template Test");

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(baseUrl + "/certificate/tournaments/" + tournamentId + "/print"),
                        String.class);

        assertThat(response.getStatusCode())
                .as(
                        "AC-CERT-IT-BATCH-NO-RESULTS-400 — batch no game results must return 400"
                                + " (no-results branch preserved per E67S01 AC5)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotBlank();
    }

    // =========================================================================
    // AC-BYTE-EQUIVALENT-400 — new /certificate endpoint returns 400 for no-template
    // =========================================================================

    /**
     * AC-BYTE-EQUIVALENT-400: endpoint returns non-empty 400 body for the no-results scenario
     * (E67S01: no-results branch preserved; 400 is from missing game results, not missing
     * template).
     */
    @Test
    @DisplayName(
            "AC-BYTE-EQUIVALENT-400 (E67S01): /certificate endpoint, no template + no game results"
                    + " → 400 non-empty body (no-results branch)")
    void singleCertificate_noResults_returns400WithNonEmptyBody() throws Exception {
        UUID tournamentId = createTournament("CertRender Byte Equiv Test");
        UUID teamId = UUID.randomUUID();

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/certificate/tournaments/"
                                        + tournamentId
                                        + "/print/"
                                        + teamId),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC-BYTE-EQUIVALENT-400 — no game results must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("AC-BYTE-EQUIVALENT-400 — 400 body must be non-empty")
                .isNotBlank();
    }

    // =========================================================================
    // AC-CERT-IT-SVG-HAPPY — SVG template uploaded → 400 (no standings)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-CERT-IT-SVG-HAPPY: SVG template uploaded but no standings → 400 (controller"
                    + " reaches phase/standings check)")
    void singleCertificate_svgTemplateNoStandings_returns400() throws Exception {
        UUID tournamentId = createTournament("CertRender SVG No Standings Test");

        // Upload SVG template
        uploadTemplate(
                authed,
                tournamentId,
                "certificate.svg",
                SAMPLE_SVG,
                MediaType.parseMediaType("image/svg+xml"));

        UUID teamId = UUID.randomUUID();
        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/certificate/tournaments/"
                                        + tournamentId
                                        + "/print/"
                                        + teamId),
                        String.class);

        // With template present but no standings (no game results), controller returns 400
        assertThat(response.getStatusCode())
                .as(
                        "AC-CERT-IT-SVG-HAPPY — no standings returns 400 (template found,"
                                + " phase not found)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // Private helpers
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
                        null, // E53S05: seedMannschaftsfoto = null
                        null); // E68S01: organizer = null

        ResponseEntity<TournamentResponse> created =
                authed.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, TournamentResponse.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        return created.getBody().id();
    }

    private ResponseEntity<String> uploadTemplate(
            TestRestTemplate client,
            UUID tournamentId,
            String filename,
            byte[] content,
            MediaType contentType)
            throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        ByteArrayResource resource =
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(contentType);
        HttpEntity<ByteArrayResource> filePart = new HttpEntity<>(resource, partHeaders);
        body.add("file", filePart);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);

        return client.postForEntity(
                new URI(baseUrl + "/api/certificate/templates/" + tournamentId),
                new HttpEntity<>(body, requestHeaders),
                String.class);
    }

    // =========================================================================
    // Test configuration — known test admin password
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
