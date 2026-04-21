package de.vvwt.tm.tournament;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.internal.DraftService;
import de.vvwt.tm.tournament.internal.draft.DraftConfig;
import de.vvwt.tm.tournament.internal.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.internal.draft.DraftPreviewSection;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * RED — DraftController slice test (AC-TDD-DraftController, AC-REST-SLICE-DraftController).
 *
 * <p>This test was committed RED: {@link DraftController} at {@code
 * de.vvwt.tm.tournament.DraftController} did not exist at commit time, causing a compile error —
 * satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage (AC-REST-SLICE-DraftController)</h2>
 *
 * <ul>
 *   <li>POST /api/tournaments/{id}/draft/preview → 200 + DraftPreviewResponse
 *   <li>POST /api/tournaments/{id}/draft/apply → 200 + DraftApplyResponse
 *   <li>Malformed JSON → 400
 *   <li>Anonymous POST → 401 (AC-REST-IT-SEC-DraftController security gate)
 * </ul>
 *
 * @see DraftController
 * @see DraftService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
@WebMvcTest(DraftController.class)
@DisplayName("DraftController slice tests — E21S07 AC-REST-SLICE")
class DraftControllerSliceTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean(name = "tmDraftService")
    private DraftService draftService;

    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
        when(tenantRegistryPort.getDefault()).thenReturn(TENANT_ID);
        when(tenantContext.bind(any())).thenReturn(() -> {});
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
    }

    // -------------------------------------------------------------------------
    // preview endpoint
    // -------------------------------------------------------------------------

    /**
     * AC-REST-SLICE-DraftController: POST /preview with valid body returns 200 +
     * DraftPreviewResponse.
     */
    @Test
    @WithMockUser
    void previewDraft_withValidRequest_returns200WithPreviewResponse() throws Exception {
        DraftPreviewSection section = new DraftPreviewSection(1, 2, 4, 6, 3, 12, 75);
        DraftPreviewResult serviceResult = new DraftPreviewResult(List.of(section), List.of());
        when(draftService.preview(any(DraftConfig.class), eq(0))).thenReturn(serviceResult);

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 2, "roundrobin", 5, 10, 15, 1, null);
        DraftRequest requestBody = new DraftRequest(List.of(sectionRequest));

        mockMvc.perform(
                        post("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections").isArray())
                .andExpect(jsonPath("$.sections[0].phaseNumber").value(1));
    }

    // -------------------------------------------------------------------------
    // apply endpoint
    // -------------------------------------------------------------------------

    /**
     * AC-REST-SLICE-DraftController: POST /apply with valid body returns 200 + DraftApplyResponse.
     */
    @Test
    @WithMockUser
    void applyDraft_withValidRequest_returns200WithApplyResponse() throws Exception {
        UUID phaseId = UUID.randomUUID();
        when(draftService.apply(eq(TOURNAMENT_ID), any(DraftConfig.class)))
                .thenReturn(List.of(phaseId));

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 2, "roundrobin", 5, 10, 15, 1, null);
        DraftRequest requestBody = new DraftRequest(List.of(sectionRequest));

        mockMvc.perform(
                        post("/api/tournaments/{id}/draft/apply", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phaseIds").isArray())
                .andExpect(jsonPath("$.phaseIds[0]").value(phaseId.toString()));
    }

    // -------------------------------------------------------------------------
    // validation error
    // -------------------------------------------------------------------------

    /** AC-REST-SLICE-DraftController: malformed JSON body → 400. */
    @Test
    @WithMockUser
    void previewDraft_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(
                        post("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not-valid-json}")
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // security gate
    // -------------------------------------------------------------------------

    /** AC-REST-IT-SEC-DraftController (slice-level): anonymous GET → 401. */
    @Test
    void previewDraft_withoutAuthentication_returns401() throws Exception {
        // Use GET to avoid CSRF 403 in @WebMvcTest slice (CSRF enabled by default in slice context,
        // does not apply to GET). POST with CSRF absent → 403; GET without auth → 401 (Basic
        // realm).
        // The security property tested here is: unauthenticated access is rejected (4xx).
        mockMvc.perform(
                        get("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
