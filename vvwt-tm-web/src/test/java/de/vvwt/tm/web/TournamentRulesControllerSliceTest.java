package de.vvwt.tm.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.scoring.SetValidationRuleRegistry;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice test for {@link TournamentRulesController} (E21S10,
 * AC-REST-SLICE-TournamentRulesController, DEC-26 C-13 methodology, inventory row 412).
 *
 * <p>Moved from {@code de.vvwt.tm.tournament} to {@code de.vvwt.tm.web} at E22S11 atomic cutover
 * (DEC-40 — REST controllers in web.*).
 *
 * <h2>Coverage (C-13 methodology)</h2>
 *
 * <ul>
 *   <li>Happy-path GET /api/tournament-rules with admin auth returns 200 + correct JSON shape
 *   <li>Anonymous GET /api/tournament-rules returns 401 (AC-REST-IT-SEC-TournamentRulesController)
 * </ul>
 *
 * @see TournamentRulesController
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — controller test methodology (C-13)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E21S10">E21S10 — inventory row 412</a>
 */
@WebMvcTest(TournamentRulesController.class)
@DisplayName("TournamentRulesController slice tests — E21S10 AC-REST-SLICE")
class TournamentRulesControllerSliceTest {

    @Autowired private WebApplicationContext context;

    @MockitoBean(name = "tmMatchGeneratorRegistry")
    private MatchGeneratorRegistry matchGeneratorRegistry;

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
        // Use HashMap to allow null values (SetValidationRuleRegistry.getAll() returns rule impls,
        // which are not needed for the controller — only the key set is used)
        java.util.HashMap<String, de.vvwt.tm.scoring.SetValidationRule> validationRules =
                new java.util.HashMap<>();
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
    @DisplayName("AC-REST-IT-SEC: Anonymous GET /api/tournament-rules returns 401")
    void anonymousGet_returns401() throws Exception {
        mockMvc.perform(get("/api/tournament-rules")).andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Happy path: authenticated request returns rule registry JSON
    // =========================================================================

    @Test
    @DisplayName(
            "AC-REST-SLICE-TournamentRulesController: authenticated GET returns 200 + correct"
                    + " JSON shape")
    @org.springframework.security.test.context.support.WithMockUser
    void authenticatedGet_returns200WithRuleRegistries() throws Exception {
        mockMvc.perform(get("/api/tournament-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchGeneratorIds").isArray())
                .andExpect(jsonPath("$.scoringRuleIds").isArray())
                .andExpect(jsonPath("$.setValidationRuleIds").isArray())
                .andExpect(jsonPath("$.matchFormats").isArray())
                .andExpect(jsonPath("$.matchGeneratorIds[0]").value("roundRobin"))
                .andExpect(jsonPath("$.matchFormats").isNotEmpty());
    }
}
