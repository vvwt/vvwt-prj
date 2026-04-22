package de.vvwt.tm.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.SetValidationRuleRegistry;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice test for {@link ScoringRulesController} (E22S08, AC-S08-SLICE-UNCHANGED,
 * AC-S08-SLICE-MOCKITOBEAN-RETAINED).
 *
 * <p>Relocated and renamed from {@code de.vvwt.tm.tournament.TournamentRulesControllerSliceTest}
 * (E21S10) to {@code de.vvwt.tm.web.ScoringRulesControllerSliceTest} in E22S08.
 *
 * <p>Uses {@code @WebMvcTest(ScoringRulesController.class)} per DEC-38 erratum (slice tests retain
 * {@code @WebMvcTest}). {@code @MockitoBean} declarations for {@link ScoringRuleRegistry} and
 * {@link SetValidationRuleRegistry} are RETAINED (AC-S08-SLICE-MOCKITOBEAN-RETAINED): slice
 * annotation does NOT load service beans, so real scoring beans are not in scope here. Import FQNs
 * updated from {@code de.vvwt.tm.domain.rules.*} to {@code de.vvwt.tm.scoring.*}
 * (AC-S08-REGISTRY-REPOINT).
 *
 * @see ScoringRulesController
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law, Q-1b refactor-clause</a>
 * @see <a href="DEC-38">DEC-38 — @ApplicationModuleTest canon; @WebMvcTest for slice tests</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation; reverse @MockitoBean case</a>
 * @see <a href="E22S08">E22S08 — relocate + rename + re-point scoring registries</a>
 */
@WebMvcTest(ScoringRulesController.class)
@DisplayName("ScoringRulesController slice tests — E22S08 web-module")
class ScoringRulesControllerSliceTest {

    @Autowired private WebApplicationContext context;

    @MockitoBean(name = "tmMatchGeneratorRegistry")
    private MatchGeneratorRegistry matchGeneratorRegistry;

    // AC-S08-SLICE-MOCKITOBEAN-RETAINED: mocks retained for @WebMvcTest slice (real beans not in
    // scope). Import FQNs updated to de.vvwt.tm.scoring.* per AC-S08-REGISTRY-REPOINT.
    @MockitoBean private ScoringRuleRegistry scoringRuleRegistry;
    @MockitoBean private SetValidationRuleRegistry setValidationRuleRegistry;

    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
        when(tenantRegistryPort.getDefault()).thenReturn(TENANT_ID);
        when(tenantContext.bind(TENANT_ID)).thenReturn(() -> {});

        when(matchGeneratorRegistry.knownIds()).thenReturn(Set.of("roundRobin"));
        when(scoringRuleRegistry.knownIds()).thenReturn(Set.of("setPoints", "threePointMatch"));
        // HashMap to allow null values (SetValidationRuleRegistry.getAll() returns rule impls
        // which are not needed for the controller — only key set is used)
        Map<String, SetValidationRule> validationRules = new HashMap<>();
        validationRules.put("standardVolleyball", null);
        validationRules.put("timeBoundedSet", null);
        when(setValidationRuleRegistry.getAll()).thenReturn(validationRules);

        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
    }

    // =========================================================================
    // Security: unauthenticated requests return 401
    // =========================================================================

    @Test
    @DisplayName("AC-S08-SEC: Anonymous GET /api/scoring/rules returns 401")
    void anonymousGet_returns401() throws Exception {
        mockMvc.perform(get("/api/scoring/rules")).andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Happy path: authenticated request returns rule registry JSON
    // =========================================================================

    @Test
    @DisplayName(
            "AC-S08-SLICE: authenticated GET /api/scoring/rules returns 200 + correct JSON shape")
    @org.springframework.security.test.context.support.WithMockUser
    void authenticatedGet_returns200WithRuleRegistries() throws Exception {
        mockMvc.perform(get("/api/scoring/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchGeneratorIds").isArray())
                .andExpect(jsonPath("$.scoringRuleIds").isArray())
                .andExpect(jsonPath("$.setValidationRuleIds").isArray())
                .andExpect(jsonPath("$.matchFormats").isArray())
                .andExpect(jsonPath("$.matchGeneratorIds[0]").value("roundRobin"))
                .andExpect(jsonPath("$.matchFormats").isNotEmpty());
    }
}
