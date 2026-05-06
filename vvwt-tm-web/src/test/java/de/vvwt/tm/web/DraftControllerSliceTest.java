package de.vvwt.tm.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.DraftService;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftPreviewSection;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice tests for {@link DraftController} (E21S19 — DraftController relocated to {@code
 * de.vvwt.tm.web}; new {@code GET} and {@code PUT} mappings added).
 *
 * <h2>E21S21 additions (DEC-22 Iron Law — RED-first)</h2>
 *
 * <p>Tests added for AC-TEST-BE-PREVIEW-WITH-N-TEAMS-RED and AC-TEST-BE-PREVIEW-ZERO-TEAMS-BOUNDARY
 * were committed RED before the production fix was applied (DEC-22 Iron Law). The pre-existing test
 * {@code previewDraft_withValidRequest_returns200WithPreviewResponse} was updated from {@code
 * eq(0)} to {@code eq(8)} — stubbing the correct post-fix behavior. Added {@code @MockitoBean} for
 * {@link TournamentRepository} with the {@code "tmTournamentRepository"} qualifier.
 *
 * <h2>RED-first discipline (DEC-22 Iron Law)</h2>
 *
 * <p>This test class was committed RED: the relocated {@link DraftController} at {@code
 * de.vvwt.tm.web.DraftController} did not exist at test-commit time, causing a compile error —
 * satisfying the DEC-22 Iron Law. The new test methods for GET/PUT also failed at runtime before
 * the controller mappings were added.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>GET /api/tournaments/{id}/draft → 200 + DraftResponse (empty sections)
 *   <li>GET /api/tournaments/{id}/draft → 200 + DraftResponse (with sections from mock)
 *   <li>PUT /api/tournaments/{id}/draft with valid body → 200 + DraftResponse
 *   <li>PUT /api/tournaments/{id}/draft with malformed JSON → 400
 *   <li>GET /api/tournaments/{id}/draft for unknown tournament → 404
 *   <li>POST /preview with valid body, teamCount=8 → 200 + teamsPerGroup=2 (E21S21
 *       AC-TEST-BE-PREVIEW-WITH-N-TEAMS-RED)
 *   <li>POST /preview with valid body, teamCount=0 → 200 + teamsPerGroup=0 (E21S21
 *       AC-TEST-BE-PREVIEW-ZERO-TEAMS-BOUNDARY)
 *   <li>POST /preview cross-tenant → 404 (E21S21 AC-TEST-BE-CROSS-TENANT-404-PRESERVED)
 *   <li>POST /preview without body → 400 (AC-TEST-BE-PREVIEW-NO-BODY-RED preserved)
 *   <li>POST /apply with valid body → 200 + DraftApplyResponse (pre-existing)
 *   <li>Anonymous request → 401 (pre-existing security gate)
 * </ul>
 *
 * @see DraftController
 * @see de.vvwt.tm.tournament.DraftService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation: web module</a>
 * @see <a href="E21S19">E21S19 — Restore GET + PUT + DraftController relocation</a>
 * @see <a href="E21S21">E21S21 — Fix Vorschau/Apply 400 + remove auto-save + load
 *     getTeamCount()</a>
 */
@WebMvcTest(DraftController.class)
@DisplayName("DraftController slice tests — E21S19 GET/PUT + relocation + E21S21 team count")
class DraftControllerSliceTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean(name = "tmDraftService")
    private DraftService draftService;

    @MockitoBean(name = "tmTournamentRepository")
    private TournamentRepository tournamentRepository;

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

    // =========================================================================
    // GET /draft → 200 + empty sections (Scenario B)
    // =========================================================================

    /**
     * GET /draft on tournament with no draft → service returns DraftConfig.empty() → 200 + empty
     * sections list.
     */
    @Test
    @WithMockUser
    @DisplayName("GET /draft when service returns empty DraftConfig → 200 + empty sections")
    void getDraft_whenServiceReturnsEmpty_returns200WithEmptySections() throws Exception {
        when(draftService.loadDraft(eq(TOURNAMENT_ID))).thenReturn(DraftConfig.empty());

        mockMvc.perform(get("/api/tournaments/{id}/draft", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections").isArray())
                .andExpect(jsonPath("$.sections").isEmpty());
    }

    // =========================================================================
    // GET /draft → 200 + sections (Scenario A)
    // =========================================================================

    /**
     * GET /draft when service returns DraftConfig with one section → 200 + sections in response.
     */
    @Test
    @WithMockUser
    @DisplayName("GET /draft when service returns DraftConfig with sections → 200 + sections")
    void getDraft_whenServiceReturnsSections_returns200WithSections() throws Exception {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));
        when(draftService.loadDraft(eq(TOURNAMENT_ID))).thenReturn(config);

        mockMvc.perform(get("/api/tournaments/{id}/draft", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections").isArray())
                .andExpect(jsonPath("$.sections[0].sectionNumber").value(1))
                .andExpect(jsonPath("$.sections[0].groupCount").value(2))
                .andExpect(jsonPath("$.sections[0].gameMode").value("roundRobin"));
    }

    // =========================================================================
    // GET /draft → 404 when tournament not found (Scenario F)
    // =========================================================================

    /** GET /draft when tournament not found → service throws TournamentNotFoundException → 404. */
    @Test
    @WithMockUser
    @DisplayName("GET /draft when tournament not found → 404")
    void getDraft_whenTournamentNotFound_returns404() throws Exception {
        when(draftService.loadDraft(eq(TOURNAMENT_ID)))
                .thenThrow(new TournamentNotFoundException(TOURNAMENT_ID));

        mockMvc.perform(get("/api/tournaments/{id}/draft", TOURNAMENT_ID))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // PUT /draft → 200 + DraftResponse (Scenario C)
    // =========================================================================

    /** PUT /draft with valid body → service saves and returns DraftConfig → 200 + DraftResponse. */
    @Test
    @WithMockUser
    @DisplayName("PUT /draft with valid body → 200 + DraftResponse echoing sections")
    void saveDraft_withValidBody_returns200WithSavedData() throws Exception {
        DraftSection savedSection =
                new DraftSection(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, List.of());
        DraftConfig savedConfig = new DraftConfig(List.of(savedSection));
        when(draftService.saveDraft(eq(TOURNAMENT_ID), any(DraftConfig.class)))
                .thenReturn(savedConfig);

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, null);
        DraftRequest requestBody = new DraftRequest(List.of(sectionRequest));

        mockMvc.perform(
                        put("/api/tournaments/{id}/draft", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections").isArray())
                .andExpect(jsonPath("$.sections[0].sectionNumber").value(1))
                .andExpect(jsonPath("$.sections[0].lapTimeMinutes").value(15));
    }

    // =========================================================================
    // PUT /draft → 400 with malformed body (Scenario E)
    // =========================================================================

    /** PUT /draft with malformed JSON → 400 Bad Request. */
    @Test
    @WithMockUser
    @DisplayName("PUT /draft with malformed body → 400 Bad Request")
    void saveDraft_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(
                        put("/api/tournaments/{id}/draft", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not-valid-json}")
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // E21S21 AC-TEST-BE-PREVIEW-WITH-N-TEAMS-RED: teamCount=8, groupCount=4 → teamsPerGroup=2
    // Anti-fooling: confirms Tournament.getTeamCount() is used, not isParticipate filter
    // =========================================================================

    /**
     * E21S21 AC-TEST-BE-PREVIEW-WITH-N-TEAMS-RED — POST /preview with teamCount=8, groupCount=4 →
     * teamsPerGroup=2.
     *
     * <p>Anti-fooling: Tournament.teamCount=8; if the implementation used a Team-row filter
     * returning 6 (isParticipate=true), teamsPerGroup would be 1 (floor(6/4)=1), not 2 (8/4=2). The
     * mock returns teamsPerGroup=2 only when draftService.preview is called with eq(8) — confirming
     * Tournament.getTeamCount() is the count source.
     */
    @Test
    @WithMockUser
    @DisplayName(
            "E21S21: POST /preview with teamCount=8, groupCount=4 → teamsPerGroup=2 (anti-fooling)")
    void previewDraft_withTeamCount8_returnsTeamsPerGroup2() throws Exception {
        // Fixture: tournament with teamCount=8 (anti-fooling: NOT isParticipate filter result)
        Tournament tournament = new Tournament();
        tournament.setTeamCount(8);
        when(tournamentRepository.findById(eq(TOURNAMENT_ID))).thenReturn(Optional.of(tournament));

        // Mock: draftService.preview called with participatingTeamCount=8 → teamsPerGroup=2
        // (E48S10 refactor: 3-arg API; anyInt() for fieldCount — this test validates teamCount
        // threading)
        DraftPreviewSection section = new DraftPreviewSection(1, 4, 2, 1, 1, 4, 60);
        DraftPreviewResult serviceResult = new DraftPreviewResult(List.of(section), List.of());
        when(draftService.preview(any(DraftConfig.class), eq(8), anyInt()))
                .thenReturn(serviceResult);

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 4, "roundRobin", 5, 10, 15, 1, null);
        DraftRequest requestBody = new DraftRequest(List.of(sectionRequest));

        mockMvc.perform(
                        post("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].teamsPerGroup").value(2));
    }

    // =========================================================================
    // E21S21 AC-TEST-BE-PREVIEW-ZERO-TEAMS-BOUNDARY: teamCount=0 → teamsPerGroup=0
    // =========================================================================

    /**
     * E21S21 AC-TEST-BE-PREVIEW-ZERO-TEAMS-BOUNDARY — Tournament.teamCount=0 → preview returns
     * teamsPerGroup=0. Formula path unchanged; only input source changed.
     */
    @Test
    @WithMockUser
    @DisplayName("E21S21: POST /preview with teamCount=0 → teamsPerGroup=0 (boundary)")
    void previewDraft_withTeamCount0_returnsZeroMath() throws Exception {
        Tournament tournament = new Tournament();
        tournament.setTeamCount(0);
        when(tournamentRepository.findById(eq(TOURNAMENT_ID))).thenReturn(Optional.of(tournament));

        DraftPreviewSection section = new DraftPreviewSection(1, 4, 0, 0, 0, 0, 0);
        DraftPreviewResult serviceResult = new DraftPreviewResult(List.of(section), List.of());
        // E48S10 refactor: 3-arg API; anyInt() for fieldCount — test validates teamCount=0 boundary
        when(draftService.preview(any(DraftConfig.class), eq(0), anyInt()))
                .thenReturn(serviceResult);

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 4, "roundRobin", 5, 10, 15, 1, null);
        DraftRequest requestBody = new DraftRequest(List.of(sectionRequest));

        mockMvc.perform(
                        post("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[0].teamsPerGroup").value(0));
    }

    // =========================================================================
    // E21S21 AC-TEST-BE-CROSS-TENANT-404-PRESERVED: cross-tenant → 404
    // =========================================================================

    /**
     * E21S21 AC-TEST-BE-CROSS-TENANT-404-PRESERVED — When tournamentRepository.findById returns
     * empty (cross-tenant / not found), DraftController throws TournamentNotFoundException → 404.
     * No draft data or team count is leaked.
     */
    @Test
    @WithMockUser
    @DisplayName("E21S21: POST /preview cross-tenant → 404 (no data leaked)")
    void previewDraft_crossTenant_returns404() throws Exception {
        when(tournamentRepository.findById(eq(TOURNAMENT_ID))).thenReturn(Optional.empty());

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 4, "roundRobin", 5, 10, 15, 1, null);
        DraftRequest requestBody = new DraftRequest(List.of(sectionRequest));

        mockMvc.perform(
                        post("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // Pre-existing: POST /preview → 200 (updated for E21S21: eq(8) for teamCount)
    // =========================================================================

    /**
     * Pre-existing: POST /preview with valid body → 200 + DraftPreviewResponse.
     *
     * <p>Updated for E21S21: tournament mock returns teamCount=8; draftService.preview stubbed with
     * eq(8) (not eq(0) — the hardcoded zero was the bug). Confirms post-fix behavior.
     */
    @Test
    @WithMockUser
    @DisplayName("POST /draft/preview with valid body returns 200 + DraftPreviewResponse")
    void previewDraft_withValidRequest_returns200WithPreviewResponse() throws Exception {
        Tournament tournament = new Tournament();
        tournament.setTeamCount(8);
        when(tournamentRepository.findById(eq(TOURNAMENT_ID))).thenReturn(Optional.of(tournament));

        DraftPreviewSection section = new DraftPreviewSection(1, 2, 4, 6, 3, 12, 75);
        DraftPreviewResult serviceResult = new DraftPreviewResult(List.of(section), List.of());
        // E48S10 refactor: 3-arg API; anyInt() for fieldCount — pre-existing 200-response test
        when(draftService.preview(any(DraftConfig.class), eq(8), anyInt()))
                .thenReturn(serviceResult);

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, null);
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

    // =========================================================================
    // Pre-existing: POST /apply → 200
    // =========================================================================

    /** Pre-existing: POST /apply with valid body → 200 + DraftApplyResponse. */
    @Test
    @WithMockUser
    @DisplayName("POST /draft/apply with valid body returns 200 + DraftApplyResponse")
    void applyDraft_withValidRequest_returns200WithApplyResponse() throws Exception {
        UUID phaseId = UUID.randomUUID();
        when(draftService.apply(eq(TOURNAMENT_ID), any(DraftConfig.class)))
                .thenReturn(List.of(phaseId));

        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(1, "team_number", 2, "roundRobin", 5, 10, 15, 1, null);
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

    // =========================================================================
    // Pre-existing: malformed JSON → 400
    // =========================================================================

    /** Pre-existing: malformed JSON body → 400. */
    @Test
    @WithMockUser
    @DisplayName("POST /draft/preview with malformed JSON → 400")
    void previewDraft_withMalformedJson_returns400() throws Exception {
        mockMvc.perform(
                        post("/api/tournaments/{id}/draft/preview", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not-valid-json}")
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Pre-existing: anonymous request → 401
    // =========================================================================

    /** Pre-existing: anonymous GET → 401. */
    @Test
    @DisplayName("Unauthenticated GET /draft returns 401")
    void getDraft_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/tournaments/{id}/draft", TOURNAMENT_ID))
                .andExpect(status().isUnauthorized());
    }
}
