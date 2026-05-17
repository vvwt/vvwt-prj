// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.MatchGeneratorInfo;
import de.vvwt.tm.tournament.MatchGeneratorRegistry;
import java.util.List;
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
 * Slice test for {@link MatchGeneratorInfoController} (E58S05 AC1 + AC8 TDD RED-first).
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>AC-REST-SLICE-MatchGeneratorInfoController: authenticated GET {@code /api/match-generators}
 *       returns 200 with a JSON array of {@code [{keyId, isLastPhaseGenerator}]} records, one per
 *       registered generator.
 *   <li>AC-REST-SEC-MatchGeneratorInfoController: anonymous GET returns 401.
 * </ul>
 *
 * <p>The test fixture intentionally includes a generator whose {@code isLastPhaseGenerator} flag is
 * {@code true} but whose key does NOT contain the string "award" or "ceremony" — this verifies AC4
 * at the unit level: filtering must use the flag, not key-string matching.
 *
 * @see MatchGeneratorInfoController
 * @see de.vvwt.tm.tournament.MatchGeneratorInfo
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (AC8)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-73">DEC-73 — D-5 isLastPhaseGenerator + D-8 REST endpoint</a>
 * @see <a href="E58S05">E58S05 — AC1, AC8</a>
 */
@WebMvcTest(MatchGeneratorInfoController.class)
@DisplayName("MatchGeneratorInfoController slice tests — E58S05 AC1+AC8 TDD")
class MatchGeneratorInfoControllerSliceTest {

    @Autowired private WebApplicationContext context;

    @MockitoBean(name = "tmMatchGeneratorRegistry")
    private MatchGeneratorRegistry matchGeneratorRegistry;

    @MockitoBean private TenantContext tenantContext;

    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID TENANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, TENANT_ID);
        when(tenantRegistryPort.getDefault()).thenReturn(TENANT_ID);
        when(tenantContext.bind(TENANT_ID)).thenReturn(() -> {});

        // Fixture: two generators.
        // "trophy" key has isLastPhaseGenerator==true but does NOT contain "award"/"ceremony" —
        // ensures AC4 flag-based filtering is tested (not key-string matching).
        when(matchGeneratorRegistry.getGeneratorInfoList())
                .thenReturn(
                        List.of(
                                new MatchGeneratorInfo("roundRobin", false),
                                new MatchGeneratorInfo("trophy", true)));

        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
    }

    // =========================================================================
    // Security: unauthenticated requests return 401
    // =========================================================================

    @Test
    @DisplayName("AC-REST-SEC-MatchGeneratorInfoController: anonymous GET returns 401")
    void anonymousGet_returns401() throws Exception {
        mockMvc.perform(get("/api/match-generators")).andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Happy path: returns list of MatchGeneratorInfo records
    // =========================================================================

    @Test
    @DisplayName(
            "AC-REST-SLICE-MatchGeneratorInfoController: authenticated GET /api/match-generators"
                    + " returns 200 with generator list")
    @org.springframework.security.test.context.support.WithMockUser
    void authenticatedGet_returnsGeneratorInfoList() throws Exception {
        mockMvc.perform(get("/api/match-generators"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].keyId").value("roundRobin"))
                .andExpect(jsonPath("$[0].isLastPhaseGenerator").value(false))
                .andExpect(jsonPath("$[1].keyId").value("trophy"))
                .andExpect(jsonPath("$[1].isLastPhaseGenerator").value(true));
    }
}
