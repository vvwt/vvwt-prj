package de.vvwt.tm.web;

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
import de.vvwt.tm.tournament.DraftService;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftPreviewSection;
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
 * Slice test for {@link DraftController} — relocated to {@code de.vvwt.tm.web} in E22S08 (DEC-40
 * Clause A Q-1b).
 *
 * <p>Uses {@code @WebMvcTest(DraftController.class)} targeting the new {@code web} package location
 * per DEC-38 erratum (slice tests retain {@code @WebMvcTest}). {@link DraftService} is mocked via
 * {@code @MockitoBean} (Q-1b relocation — no logic change; service interface unchanged).
 *
 * @see DraftController
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, Q-1b refactor-clause</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest canon; @WebMvcTest for slice tests</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S08">E22S08 — relocate to de.vvwt.tm.web</a>
 */
@WebMvcTest(DraftController.class)
@DisplayName("DraftController slice tests — E22S08 web-module")
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

    @Test
    void previewDraft_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(
                        get("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
