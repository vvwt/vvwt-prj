package de.vvwt.tm.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamService;
import de.vvwt.tm.tournament.internal.dto.TeamBulkCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TeamCreateRequest;
import java.util.List;
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
 * Slice test for {@link TeamController} (E21S04, AC-REST-SLICE-TeamController).
 *
 * <h2>Relocation note (E22S07)</h2>
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament} to {@code de.vvwt.tm.web} per DEC-40
 * Clause D (Q-1b whole-class relocation, DEC-22 §refactor-clause). {@code @WebMvcTest} annotation
 * retained per DEC-38 erratum — slice tests do not load service beans and are unaffected by module
 * boundaries. Package line is the only change.
 *
 * <h2>Coverage (C-13 methodology)</h2>
 *
 * <ul>
 *   <li>Happy-path GET returns 200 + JSON array
 *   <li>Happy-path POST (create) returns 201 + Location header
 *   <li>Happy-path POST (bulk create) returns 200
 *   <li>Happy-path PUT (update) returns 200
 *   <li>Happy-path DELETE returns 204
 *   <li>POST with blank description returns 400 (validation error)
 *   <li>Unauthenticated GET returns 401 (security gate)
 * </ul>
 *
 * @see TeamController
 * @see TeamService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate TeamController to de.vvwt.tm.web</a>
 */
@WebMvcTest(TeamController.class)
@DisplayName("TeamController slice tests — E21S04 AC-REST-SLICE")
class TeamControllerSliceTest {

    @Autowired private WebApplicationContext context;

    @Autowired private ObjectMapper objectMapper;

    @MockitoBean(name = "tmTeamService")
    private TeamService teamService;

    @MockitoBean private PhotoStorageService photoStorageService;

    @MockitoBean private TenantContext tenantContext;

    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TOURNAMENT_ID = UUID.randomUUID();
    private static final String BASE_URL = "/api/tournaments/" + TOURNAMENT_ID + "/teams";

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
    // Security: unauthenticated requests return 401
    // =========================================================================

    @Test
    @DisplayName("Anonymous GET returns 401")
    void anonymousGet_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Anonymous POST returns 401")
    void anonymousPost_returns401() throws Exception {
        TeamCreateRequest req = new TeamCreateRequest("A", null, null, null, null);
        mockMvc.perform(
                        post(BASE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET /api/tournaments/{tournamentId}/teams
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET returns 200 with JSON array of teams")
    void getList_authenticated_returns200() throws Exception {
        Team team = buildTeam(1, "Alpha");
        when(teamService.listTeams(TOURNAMENT_ID)).thenReturn(List.of(team));

        mockMvc.perform(get(BASE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].teamNumber").value(1))
                .andExpect(jsonPath("$[0].description").value("Alpha"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET returns 200 with empty array when no teams")
    void getList_empty_returns200EmptyArray() throws Exception {
        when(teamService.listTeams(TOURNAMENT_ID)).thenReturn(List.of());

        mockMvc.perform(get(BASE_URL).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // =========================================================================
    // POST /api/tournaments/{tournamentId}/teams — create
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("POST with valid body returns 201 with id in body")
    void create_validRequest_returns201() throws Exception {
        UUID newId = UUID.randomUUID();
        Team created = buildTeam(1, "Alpha");
        created.setId(newId);
        when(teamService.createTeam(
                        eq(TOURNAMENT_ID), eq("Alpha"), eq(0), eq(true), eq(false), eq(false)))
                .thenReturn(created);

        TeamCreateRequest req = new TeamCreateRequest("Alpha", null, null, null, null);
        mockMvc.perform(
                        post(BASE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newId.toString()))
                .andExpect(jsonPath("$.description").value("Alpha"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST with blank description returns 400")
    void create_blankDescription_returns400() throws Exception {
        TeamCreateRequest req = new TeamCreateRequest("", null, null, null, null);
        mockMvc.perform(
                        post(BASE_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // POST /api/tournaments/{tournamentId}/teams/bulk — bulk create
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("POST /bulk with valid body returns 201")
    void bulkCreate_validRequest_returns201() throws Exception {
        Team created = buildTeam(1, "Alpha");
        TeamService.BulkCreateResult result = TeamService.BulkCreateResult.success(created);
        when(teamService.bulkCreateTeams(eq(TOURNAMENT_ID), any())).thenReturn(List.of(result));

        TeamCreateRequest item = new TeamCreateRequest("Alpha", null, null, null, null);
        TeamBulkCreateRequest req = new TeamBulkCreateRequest(List.of(item));
        mockMvc.perform(
                        post(BASE_URL + "/bulk")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].success").value(true));
    }

    // =========================================================================
    // PUT /api/tournaments/{tournamentId}/teams/{id} — update
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("PUT with valid body returns 200 with updated team")
    void update_validRequest_returns200() throws Exception {
        UUID teamId = UUID.randomUUID();
        Team updated = buildTeam(1, "Updated");
        updated.setId(teamId);
        when(teamService.updateTeam(
                        eq(TOURNAMENT_ID),
                        eq(teamId),
                        any(),
                        eq(0),
                        eq(true),
                        eq(false),
                        eq(false)))
                .thenReturn(updated);

        String body = "{\"description\":\"Updated\"}";
        mockMvc.perform(
                        put(BASE_URL + "/" + teamId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Updated"));
    }

    // =========================================================================
    // DELETE /api/tournaments/{tournamentId}/teams/{id} — delete
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("DELETE returns 204 on success")
    void delete_returns204() throws Exception {
        UUID teamId = UUID.randomUUID();

        mockMvc.perform(delete(BASE_URL + "/" + teamId).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Team buildTeam(int num, String desc) {
        Team t = new Team();
        t.setId(UUID.randomUUID());
        t.setTournamentId(TOURNAMENT_ID);
        t.setTeamNumber(num);
        t.setDescription(desc);
        t.setParticipate(true);
        return t;
    }
}
