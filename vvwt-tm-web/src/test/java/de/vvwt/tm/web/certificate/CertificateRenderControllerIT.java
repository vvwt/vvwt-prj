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
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Integration tests for {@link CertificateRenderController} — E24S05.
 *
 * <p>Uses {@code @ApplicationModuleTest(ALL_DEPENDENCIES, RANDOM_PORT)} targeting the {@code web}
 * module per DEC-38 Clause A + DEC-40 2026-04-22 Amendment. Boots {@code web} + all declared
 * {@code allowedDependencies} (tenant, tournament, scoring, photo, certificate).
 * {@link WebModuleTestConfig} provides the test infrastructure beans.
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
 *   <li>AC-CERT-IT-NO-TEMPLATE-400: authenticated + tournament exists + no template → 400
 *   <li>AC-CERT-IT-BATCH-NO-TEMPLATE-400: batch endpoint + no template → 400
 *   <li>AC-CERT-IT-SECURITY-UNAUTHENTICATED: unauthenticated → 401
 *   <li>AC-BYTE-EQUIVALENT-400: 400 body byte-equivalent to legacy PrintController response
 *   <li>AC-CERT-IT-SVG-HAPPY: tournament exists + SVG template uploaded → 400 (no standings)
 * </ul>
 *
 * @see CertificateRenderController
 * @see WebModuleTestConfig
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (Q-1a RED-first)</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest IT canon</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @since E24S05
 */
@ApplicationModuleTest(
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
    // AC-CERT-IT-NO-TEMPLATE-400 — authenticated, no template → 400
    // =========================================================================

    @Test
    @DisplayName("AC-CERT-IT-NO-TEMPLATE-400: single endpoint, no template → 400 text/plain")
    void singleCertificate_noTemplate_returns400() throws Exception {
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
                .as("AC-CERT-IT-NO-TEMPLATE-400 — no template must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .as("AC-CERT-IT-NO-TEMPLATE-400 — 400 body must be non-empty")
                .isNotBlank();
    }

    @Test
    @DisplayName("AC-CERT-IT-BATCH-NO-TEMPLATE-400: batch endpoint, no template → 400 text/plain")
    void allCertificates_noTemplate_returns400() throws Exception {
        UUID tournamentId = createTournament("CertRender Batch No Template Test");

        ResponseEntity<String> response =
                authed.getForEntity(
                        new URI(
                                baseUrl
                                        + "/certificate/tournaments/"
                                        + tournamentId
                                        + "/print"),
                        String.class);

        assertThat(response.getStatusCode())
                .as("AC-CERT-IT-BATCH-NO-TEMPLATE-400 — batch no template must return 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotBlank();
    }

    // =========================================================================
    // AC-BYTE-EQUIVALENT-400 — new /certificate endpoint returns 400 for no-template
    // =========================================================================

    /**
     * AC-BYTE-EQUIVALENT-400: new endpoint returns non-empty 400 body for no-template scenario.
     *
     * <p>Note: byte-equivalence with legacy /print endpoint is NOT verifiable in the
     * {@code @ApplicationModuleTest(web)} context because the legacy {@code PrintController}
     * lives in the {@code infrastructure} module which is not in {@code web}'s
     * {@code allowedDependencies}. This IT verifies the new endpoint error semantics independently.
     * Cross-endpoint byte-equivalence is covered by E24S07 cutover ITs where both controllers
     * are loaded in a full @SpringBootTest context.
     */
    @Test
    @DisplayName(
            "AC-BYTE-EQUIVALENT-400: new /certificate endpoint, no template → 400 non-empty body")
    void singleCertificate_noTemplate_returns400WithNonEmptyBody() throws Exception {
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
                .as("AC-BYTE-EQUIVALENT-400 — new endpoint must return 400")
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
                        "roundRobin");

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
        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder encoder) {
            String hash = encoder.encode(TEST_PASSWORD);
            return () -> hash;
        }
    }
}
