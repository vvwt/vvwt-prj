// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentService;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import java.time.LocalDateTime;
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
 * Slice test for {@link TournamentController} (E21S02, AC-REST-SLICE-TournamentController).
 *
 * <h2>Relocation note (E22S07)</h2>
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament} to {@code de.vvwt.tm.web} per DEC-40
 * Clause D (Q-1b whole-class relocation, DEC-22 §refactor-clause). {@code @WebMvcTest} annotation
 * retained per DEC-38 erratum — slice tests do not load service beans and are unaffected by module
 * boundaries. Package line is the only change.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Happy-path GET /api/tournaments returns 200 + JSON array
 *   <li>POST with valid body returns 201 + Location header
 *   <li>POST with invalid body returns 400 (validation error)
 *   <li>Anonymous GET returns 401 (security gate — AC-REST-IT-SEC-TournamentController)
 * </ul>
 *
 * @see TournamentController
 * @see TournamentService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S07">E22S07 — Relocate TournamentController to de.vvwt.tm.web</a>
 */
@WebMvcTest(TournamentController.class)
@DisplayName("TournamentController slice tests — E21S02 AC-REST-SLICE")
class TournamentControllerSliceTest {

    @Autowired private WebApplicationContext context;

    @Autowired private ObjectMapper objectMapper;

    @MockitoBean(name = "tmTournamentService")
    private TournamentService tournamentService;

    @MockitoBean private TenantContext tenantContext;

    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.randomUUID();

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
    @DisplayName("Anonymous GET /api/tournaments returns 401")
    void anonymousGet_returns401() throws Exception {
        mockMvc.perform(get("/api/tournaments").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Anonymous POST /api/tournaments returns 401")
    void anonymousPost_returns401() throws Exception {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "Test",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null); // E53S05: seedMannschaftsfoto = null
        mockMvc.perform(
                        post("/api/tournaments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // GET /api/tournaments — list
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("GET /api/tournaments returns 200 with JSON array")
    void getList_authenticated_returns200() throws Exception {
        Tournament t1 = buildDraftTournament("Tournament A");
        Tournament t2 = buildDraftTournament("Tournament B");
        when(tournamentService.listTournaments()).thenReturn(List.of(t1, t2));

        mockMvc.perform(get("/api/tournaments").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/tournaments returns 200 with empty array when no tournaments")
    void getList_empty_returns200EmptyArray() throws Exception {
        when(tournamentService.listTournaments()).thenReturn(List.of());

        mockMvc.perform(get("/api/tournaments").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // =========================================================================
    // POST /api/tournaments — create
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("POST /api/tournaments with valid body returns 201 with id in body")
    void create_validRequest_returns201() throws Exception {
        UUID newId = UUID.randomUUID();
        Tournament created = buildDraftTournament("New Tournament");
        created.setId(newId);
        when(tournamentService.createTournament(
                        eq("New Tournament"),
                        any(),
                        eq(4),
                        eq(2),
                        eq("BEST_OF_3"),
                        eq("setPoints"),
                        eq("standardVolleyball"),
                        eq("roundRobin"),
                        isNull(),
                        isNull(),
                        isNull())) // E53S05: seedMannschaftsfoto = null
                .thenReturn(created);

        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "New Tournament",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null); // E53S05: seedMannschaftsfoto = null

        mockMvc.perform(
                        post("/api/tournaments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(newId.toString()));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/tournaments with blank description returns 400")
    void create_blankDescription_returns400() throws Exception {
        TournamentCreateRequest req =
                new TournamentCreateRequest(
                        "",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null); // E53S05: seedMannschaftsfoto = null

        mockMvc.perform(
                        post("/api/tournaments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(req))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament buildDraftTournament(String description) {
        Tournament t = new Tournament();
        t.setId(UUID.randomUUID());
        t.setDescription(description);
        t.setMatchFormat("BEST_OF_3");
        t.setScoringRuleId("setPoints");
        t.setSetValidationRuleId("standardVolleyball");
        t.setMatchGeneratorId("roundRobin");
        t.setStatus("DRAFT");
        t.setCreatedAt(LocalDateTime.now());
        t.setFieldCount(2);
        t.setTeamCount(4);
        return t;
    }
}
