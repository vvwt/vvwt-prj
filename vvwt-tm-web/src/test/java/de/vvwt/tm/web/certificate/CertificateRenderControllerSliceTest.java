package de.vvwt.tm.web.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.samskivert.mustache.MustacheException;
import de.vvwt.tm.certificate.CertificateAssembler;
import de.vvwt.tm.certificate.CertificatePlacementRow;
import de.vvwt.tm.certificate.CertificateTemplateMetadata;
import de.vvwt.tm.certificate.CertificateTemplateService;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.LocationDisplayResolver;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * @WebMvcTest slice test for {@link CertificateRenderController} (AC-REDFIRST-SLICE-TEST, E24S05).
 *
 * <p>This test was committed RED before the controller existed — satisfying the DEC-22 Iron Law
 * Q-1a RED-first requirement (AC-RED-SEMANTICS-ALPHA: compile-error-as-RED; controller absent at
 * RED commit).
 *
 * <p>All 9 collaborators mocked via {@code @MockitoBean} per DEC-36 (cross-package test types
 * reference public interfaces, not implementation classes).
 *
 * <p>5 branches covered per AC-REDFIRST-SLICE-TEST:
 *
 * <ul>
 *   <li>SVG byte-response (AC-SLICE-SVG-BRANCH)
 *   <li>HTML Mustache view (AC-SLICE-HTML-BRANCH)
 *   <li>ZIP archive (AC-SLICE-ZIP-BRANCH)
 *   <li>400 plaintext error (AC-SLICE-ERROR-400-BRANCH)
 *   <li>MustacheException 500 (AC-SLICE-MUSTACHE-500-BRANCH)
 * </ul>
 *
 * <p>Security: without {@code @WithMockUser}, all endpoints return 401
 * (AC-SLICE-SECURITY-UNAUTHENTICATED).
 *
 * @see CertificateRenderController
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing (interface mock)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation (controller in web.certificate)</a>
 * @since E24S05
 */
@WebMvcTest(CertificateRenderController.class)
@DisplayName("CertificateRenderController @WebMvcTest slice — E24S05")
class CertificateRenderControllerSliceTest {

    private static final UUID TOURNAMENT_ID =
            UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TEAM_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID TENANT_ID = UUID.randomUUID();

    @Autowired private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean private CertificateAssembler certificateAssembler;
    @MockitoBean private CertificateTemplateService certificateTemplateService;
    @MockitoBean private LocationDisplayResolver locationDisplayResolver;
    @MockitoBean private TournamentRepository tournamentRepository;
    @MockitoBean private TeamRepository teamRepository;
    @MockitoBean private TeamAvatarRepository teamAvatarRepository;
    @MockitoBean private PhaseRepository phaseRepository;
    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;
    @MockitoBean private MessageSource messageSource;

    private Tournament tournament;
    private Phase phase;
    private CertificateAssembler.AvatarPlacement teamPlacement;
    private CertificatePlacementRow svgRow;
    private CertificatePlacementRow htmlRow;
    private CertificateTemplateMetadata svgMetadata;
    private CertificateTemplateMetadata htmlMetadata;

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
        when(tenantRegistryPort.getDefault()).thenReturn(TENANT_ID);
        when(tenantContext.bind(any())).thenReturn(() -> {});
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();

        tournament = new Tournament();
        tournament.setId(TOURNAMENT_ID);
        tournament.setDescription("Test Tournament");

        phase = new Phase();
        phase.setId(UUID.randomUUID());

        teamPlacement = new CertificateAssembler.AvatarPlacement(1, TEAM_ID, TEAM_ID);
        svgRow =
                new CertificatePlacementRow(
                        1,
                        TEAM_ID,
                        "Test Team",
                        "data:image/png;base64,abc",
                        "Test Tournament",
                        "2026-04-26",
                        "Test Location");
        htmlRow =
                new CertificatePlacementRow(
                        1,
                        TEAM_ID,
                        "Test Team",
                        "/api/photo/teams/" + TEAM_ID,
                        "Test Tournament",
                        "2026-04-26",
                        "Test Location");
        svgMetadata =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID, "certificate.svg", "svg", Instant.now(), 1024L);
        htmlMetadata =
                new CertificateTemplateMetadata(
                        TOURNAMENT_ID, "certificate.html", "html", Instant.now(), 1024L);
    }

    // =========================================================================
    // AC-SLICE-SECURITY-UNAUTHENTICATED — no @WithMockUser → 401
    // =========================================================================

    @Test
    @DisplayName("AC-SLICE-SECURITY-UNAUTHENTICATED: unauthenticated single → 401")
    void singleCertificate_unauthenticated_returns401() throws Exception {
        mockMvc.perform(
                        get(
                                "/certificate/tournaments/{tid}/print/{teamId}",
                                TOURNAMENT_ID,
                                TEAM_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-SLICE-SECURITY-UNAUTHENTICATED: unauthenticated batch → 401")
    void allCertificates_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/certificate/tournaments/{tid}/print", TOURNAMENT_ID))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // AC-SLICE-SVG-BRANCH — SVG single certificate
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC-SLICE-SVG-BRANCH: SVG format → 200 image/svg+xml byte body")
    void singleCertificate_svgFormat_returns200SvgBytes() throws Exception {
        byte[] svgContent = "<svg><text>Test</text></svg>".getBytes(StandardCharsets.UTF_8);
        CertificateTemplateService.TemplateFile templateFile =
                new CertificateTemplateService.TemplateFile(
                        new ByteArrayInputStream(svgContent), "image/svg+xml", svgMetadata);

        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(certificateTemplateService.retrieveFile(TOURNAMENT_ID))
                .thenReturn(Optional.of(templateFile));
        when(certificateAssembler.getFinalPhase(TOURNAMENT_ID)).thenReturn(Optional.of(phase));
        when(certificateAssembler.computePlacementOrder(eq(TOURNAMENT_ID), any()))
                .thenReturn(List.of(teamPlacement));
        when(locationDisplayResolver.resolveLocationDisplayName(any())).thenReturn("Test Location");
        when(certificateAssembler.buildSvgRows(any(), any(), any())).thenReturn(List.of(svgRow));
        when(certificateAssembler.renderSvgTemplate(any(), any()))
                .thenReturn("<svg><text>Test</text></svg>");
        when(tenantContext.current()).thenReturn(UUID.randomUUID());

        mockMvc.perform(
                        get(
                                "/certificate/tournaments/{tid}/print/{teamId}",
                                TOURNAMENT_ID,
                                TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/svg+xml"));
    }

    // =========================================================================
    // AC-SLICE-HTML-BRANCH — HTML single certificate Mustache view
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-SLICE-HTML-BRANCH: HTML format → 200 view-name certificate/print + model"
                    + " singleCertificate")
    void singleCertificate_htmlFormat_returnsMustacheView() throws Exception {
        byte[] htmlContent = "<html></html>".getBytes(StandardCharsets.UTF_8);
        CertificateTemplateService.TemplateFile templateFile =
                new CertificateTemplateService.TemplateFile(
                        new ByteArrayInputStream(htmlContent), "text/html", htmlMetadata);

        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(certificateTemplateService.retrieveFile(TOURNAMENT_ID))
                .thenReturn(Optional.of(templateFile));
        when(certificateAssembler.getFinalPhase(TOURNAMENT_ID)).thenReturn(Optional.of(phase));
        when(certificateAssembler.computePlacementOrder(eq(TOURNAMENT_ID), any()))
                .thenReturn(List.of(teamPlacement));
        when(locationDisplayResolver.resolveLocationDisplayName(any())).thenReturn("Test Location");
        when(certificateAssembler.buildHtmlRows(any(), any(), any())).thenReturn(List.of(htmlRow));
        when(certificateAssembler.toMustacheMap(any()))
                .thenReturn(
                        Map.of(
                                "placement", 1,
                                "teamName", "Test Team",
                                "teamPhoto", "",
                                "tournamentName", "Test Tournament",
                                "date", "2026-04-26",
                                "location", "Test Location",
                                "hasPhoto", false));
        when(tenantContext.current()).thenReturn(UUID.randomUUID());

        mockMvc.perform(
                        get("/certificate/tournaments/{tid}/print/{teamId}", TOURNAMENT_ID, TEAM_ID)
                                .accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(view().name("certificate/print"))
                .andExpect(model().attributeExists("singleCertificate"));
    }

    // =========================================================================
    // AC-SLICE-ZIP-BRANCH — ZIP batch certificates (SVG format)
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC-SLICE-ZIP-BRANCH: SVG batch → 200 application/zip")
    void allCertificates_svgFormat_returnsZip() throws Exception {
        byte[] svgContent = "<svg></svg>".getBytes(StandardCharsets.UTF_8);
        CertificateTemplateService.TemplateFile templateFile =
                new CertificateTemplateService.TemplateFile(
                        new ByteArrayInputStream(svgContent), "image/svg+xml", svgMetadata);

        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(certificateTemplateService.retrieveFile(TOURNAMENT_ID))
                .thenReturn(Optional.of(templateFile));
        when(certificateAssembler.getFinalPhase(TOURNAMENT_ID)).thenReturn(Optional.of(phase));
        when(certificateAssembler.computePlacementOrder(eq(TOURNAMENT_ID), any()))
                .thenReturn(List.of(teamPlacement));
        when(locationDisplayResolver.resolveLocationDisplayName(any())).thenReturn("Test Location");
        when(certificateAssembler.buildSvgRows(any(), any(), any())).thenReturn(List.of(svgRow));
        when(certificateAssembler.renderSvgTemplate(any(), any())).thenReturn("<svg></svg>");
        when(tenantContext.current()).thenReturn(UUID.randomUUID());

        mockMvc.perform(get("/certificate/tournaments/{tid}/print", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"));
    }

    // =========================================================================
    // AC-SLICE-ERROR-400-BRANCH — 400 plaintext when template absent
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC-SLICE-ERROR-400-BRANCH: no template → 400 text/plain")
    void singleCertificate_noTemplate_returns400Plaintext() throws Exception {
        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(certificateTemplateService.retrieveFile(TOURNAMENT_ID)).thenReturn(Optional.empty());
        when(tenantContext.current()).thenReturn(UUID.randomUUID());

        String body =
                mockMvc.perform(
                                get(
                                        "/certificate/tournaments/{tid}/print/{teamId}",
                                        TOURNAMENT_ID,
                                        TEAM_ID))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).isNotEmpty();
    }

    // =========================================================================
    // AC-SLICE-MUSTACHE-500-BRANCH — 500 plaintext on MustacheException
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName(
            "AC-SLICE-MUSTACHE-500-BRANCH: MustacheException during SVG render → 500 text/plain"
                    + " body 'Mustache rendering error: '")
    void singleCertificate_mustacheException_returns500Plaintext() throws Exception {
        byte[] svgContent = "<svg></svg>".getBytes(StandardCharsets.UTF_8);
        CertificateTemplateService.TemplateFile templateFile =
                new CertificateTemplateService.TemplateFile(
                        new ByteArrayInputStream(svgContent), "image/svg+xml", svgMetadata);

        when(tournamentRepository.findById(TOURNAMENT_ID)).thenReturn(Optional.of(tournament));
        when(certificateTemplateService.retrieveFile(TOURNAMENT_ID))
                .thenReturn(Optional.of(templateFile));
        when(certificateAssembler.getFinalPhase(TOURNAMENT_ID)).thenReturn(Optional.of(phase));
        when(certificateAssembler.computePlacementOrder(eq(TOURNAMENT_ID), any()))
                .thenReturn(List.of(teamPlacement));
        when(locationDisplayResolver.resolveLocationDisplayName(any())).thenReturn("Test Location");
        when(certificateAssembler.buildSvgRows(any(), any(), any())).thenReturn(List.of(svgRow));
        when(certificateAssembler.renderSvgTemplate(any(), any()))
                .thenThrow(new MustacheException("template variable missing: {{teamName}}"));
        when(tenantContext.current()).thenReturn(UUID.randomUUID());

        String body =
                mockMvc.perform(
                                get(
                                        "/certificate/tournaments/{tid}/print/{teamId}",
                                        TOURNAMENT_ID,
                                        TEAM_ID))
                        .andExpect(status().isInternalServerError())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).startsWith("Mustache rendering error: ");
    }
}
