package de.vvwt.tm.infrastructure.tournament;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.ActivityTypeService;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityTypeCreateRequest;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityTypeUpdateRequest;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.List;
import java.util.NoSuchElementException;
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
 * Slice test for {@link ActivityTypeController} (E20S02, AC2 — Approach C Hybrid).
 *
 * <h2>RED-capture (AC1 anti-spoof)</h2>
 *
 * <p>This test class was committed RED before {@link ActivityTypeController} had any handler
 * methods (skeleton class only). The first {@code @Test} method (list returns 200) failed with a
 * {@code ResultMatcher} status mismatch: expected 200, actual 404 — satisfying AC1b behavioural-RED
 * requirement.
 *
 * <h2>Coverage (AC2)</h2>
 *
 * <ul>
 *   <li>CRUD mapping: POST/GET/PUT/DELETE
 *   <li>Request validation: name required, assignmentRule required, capacityPerRound min(1)
 *   <li>HTTP status branches: 201/200/204/400/404/409
 *   <li>Security: {@code @WithMockUser} + springSecurity() + csrf()
 * </ul>
 *
 * @see ActivityTypeController
 */
@WebMvcTest(ActivityTypeController.class)
@DisplayName("ActivityTypeController slice tests — E20S02 AC2")
class ActivityTypeControllerTest {

    @Autowired private WebApplicationContext context;

    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ActivityTypeService activityTypeService;

    @MockitoBean private TenantContext tenantContext;

    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private final UUID TOURNAMENT_ID = UUID.randomUUID();
    private final UUID ACTIVITY_ID = UUID.randomUUID();
    private final UUID TENANT_ID = UUID.randomUUID();

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
    // AC9 — Security: unauthenticated requests return 401
    // =========================================================================

    @Test
    @DisplayName("AC9: unauthenticated GET returns 401")
    void unauthenticatedGetReturns401() throws Exception {
        mockMvc.perform(
                        get("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC9: unauthenticated POST returns 401")
    void unauthenticatedPostReturns401() throws Exception {
        var request = new ActivityTypeCreateRequest("Photo", "FIRST_FREE_ROUND", null, 1);
        mockMvc.perform(
                        post("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // AC9 — Wrong-tenant returns 404 (tenant-scoped: unknown resource in tenant context)
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC9: wrong-tenant GET returns 404 (tournament not found for tenant)")
    void wrongTenantGetReturns404() throws Exception {
        when(activityTypeService.findByTournamentId(any()))
                .thenThrow(new NoSuchElementException("Tournament not found"));

        mockMvc.perform(
                        get("/api/tournaments/{id}/activity-types", UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // AC2 — GET /api/tournaments/{id}/activity-types → 200
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC2: GET list returns 200 with empty array when no types configured")
    void listReturnsEmptyArrayWhen200() throws Exception {
        when(activityTypeService.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/tournaments/{id}/activity-types", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: GET list returns activity types with correct fields")
    void listReturnsActivityTypesWithCorrectFields() throws Exception {
        ActivityType at =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "Photo",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeService.findByTournamentId(TOURNAMENT_ID)).thenReturn(List.of(at));

        mockMvc.perform(get("/api/tournaments/{id}/activity-types", TOURNAMENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ACTIVITY_ID.toString()))
                .andExpect(jsonPath("$[0].name").value("Photo"))
                .andExpect(jsonPath("$[0].assignmentRule").value("FIRST_FREE_ROUND"));
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: GET on unknown tournament returns 404")
    void listOnUnknownTournamentReturns404() throws Exception {
        when(activityTypeService.findByTournamentId(any()))
                .thenThrow(new NoSuchElementException("Tournament not found"));

        mockMvc.perform(get("/api/tournaments/{id}/activity-types", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // AC2 — POST /api/tournaments/{id}/activity-types → 201
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC2: POST creates activity type and returns 201 with Location header")
    void postCreatesAndReturns201() throws Exception {
        ActivityType created =
                new ActivityType(
                        ACTIVITY_ID,
                        TOURNAMENT_ID,
                        "Photo",
                        "FIRST_FREE_ROUND",
                        null,
                        1,
                        TENANT_ID);
        when(activityTypeService.create(
                        eq(TOURNAMENT_ID), eq("Photo"), eq("FIRST_FREE_ROUND"), eq(null), eq(1)))
                .thenReturn(created);

        var request = new ActivityTypeCreateRequest("Photo", "FIRST_FREE_ROUND", null, 1);
        mockMvc.perform(
                        post("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Photo"))
                .andExpect(jsonPath("$.assignmentRule").value("FIRST_FREE_ROUND"));
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: POST with blank name returns 400")
    void postWithBlankNameReturns400() throws Exception {
        var request = new ActivityTypeCreateRequest("", "FIRST_FREE_ROUND", null, 1);
        mockMvc.perform(
                        post("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: POST with missing assignmentRule returns 400")
    void postWithBlankRuleReturns400() throws Exception {
        var request = new ActivityTypeCreateRequest("Photo", "", null, 1);
        mockMvc.perform(
                        post("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: POST with capacity=0 returns 400")
    void postWithZeroCapacityReturns400() throws Exception {
        var request = new ActivityTypeCreateRequest("Photo", "FIRST_FREE_ROUND", 0, 1);
        mockMvc.perform(
                        post("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: POST with duplicate name returns 409")
    void postWithDuplicateNameReturns409() throws Exception {
        when(activityTypeService.create(any(), any(), any(), any(), anyInt()))
                .thenThrow(new ConflictException("Duplicate name"));

        var request = new ActivityTypeCreateRequest("Photo", "FIRST_FREE_ROUND", null, 1);
        mockMvc.perform(
                        post("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: POST with unknown assignmentRule returns 400 from service")
    void postWithUnknownRuleReturns400() throws Exception {
        when(activityTypeService.create(any(), any(), eq("UNKNOWN"), any(), anyInt()))
                .thenThrow(new IllegalArgumentException("Unknown rule"));

        var request = new ActivityTypeCreateRequest("Photo", "UNKNOWN", null, 1);
        mockMvc.perform(
                        post("/api/tournaments/{id}/activity-types", TOURNAMENT_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // AC2 — PUT /api/tournaments/{id}/activity-types/{actId} → 200
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC2: PUT updates activity type and returns 200")
    void putUpdatesAndReturns200() throws Exception {
        ActivityType updated =
                new ActivityType(
                        ACTIVITY_ID, TOURNAMENT_ID, "NewName", "FIRST_FREE_ROUND", 3, 2, TENANT_ID);
        when(activityTypeService.update(
                        eq(TOURNAMENT_ID),
                        eq(ACTIVITY_ID),
                        eq("NewName"),
                        eq("FIRST_FREE_ROUND"),
                        eq(3),
                        eq(2)))
                .thenReturn(updated);

        var request = new ActivityTypeUpdateRequest("NewName", "FIRST_FREE_ROUND", 3, 2);
        mockMvc.perform(
                        put(
                                        "/api/tournaments/{id}/activity-types/{actId}",
                                        TOURNAMENT_ID,
                                        ACTIVITY_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NewName"))
                .andExpect(jsonPath("$.capacityPerRound").value(3));
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: PUT on unknown activity type returns 404")
    void putOnUnknownActivityReturns404() throws Exception {
        when(activityTypeService.update(any(), any(), any(), any(), any(), anyInt()))
                .thenThrow(new NoSuchElementException("Not found"));

        var request = new ActivityTypeUpdateRequest("Name", "FIRST_FREE_ROUND", null, 1);
        mockMvc.perform(
                        put(
                                        "/api/tournaments/{id}/activity-types/{actId}",
                                        TOURNAMENT_ID,
                                        UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // AC2 — DELETE /api/tournaments/{id}/activity-types/{actId} → 204
    // =========================================================================

    @Test
    @WithMockUser
    @DisplayName("AC2: DELETE removes activity type and returns 204")
    void deleteReturns204() throws Exception {
        doNothing().when(activityTypeService).delete(TOURNAMENT_ID, ACTIVITY_ID);

        mockMvc.perform(
                        delete(
                                        "/api/tournaments/{id}/activity-types/{actId}",
                                        TOURNAMENT_ID,
                                        ACTIVITY_ID)
                                .with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser
    @DisplayName("AC2: DELETE on unknown activity type returns 404")
    void deleteOnUnknownActivityReturns404() throws Exception {
        doThrow(new NoSuchElementException("Not found"))
                .when(activityTypeService)
                .delete(any(), any());

        mockMvc.perform(
                        delete(
                                        "/api/tournaments/{id}/activity-types/{actId}",
                                        TOURNAMENT_ID,
                                        UUID.randomUUID())
                                .with(csrf()))
                .andExpect(status().isNotFound());
    }
}
