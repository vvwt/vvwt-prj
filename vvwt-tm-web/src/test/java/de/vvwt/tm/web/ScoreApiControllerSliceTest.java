package de.vvwt.tm.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.infrastructure.testsupport.TenantContextSliceTestSupport;
import de.vvwt.tm.scoring.PartialScoreInput;
import de.vvwt.tm.scoring.ScoreEntryService;
import de.vvwt.tm.scoring.SetSubmitInput;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.tm.tournament.exceptions.ForbiddenException;
import de.vvwt.tm.tournament.exceptions.UnauthorizedException;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Slice tests for {@link ScoreApiController} — validation, error-response, and security-negative
 * paths (E22S09, DEC-38 erratum, DEC-36, DEC-40 Clause E).
 *
 * <h2>TDD RED-first (DEC-22)</h2>
 *
 * <p>This file is committed BEFORE {@link ScoreApiController} is authored. The reference to
 * {@code de.vvwt.tm.web.ScoreApiController} in {@code @WebMvcTest} causes compile-fail, proving
 * the RED state.
 *
 * <h2>Coverage (AC-SLICE-TEST-WEBMVC)</h2>
 *
 * <ul>
 *   <li>(a) Jakarta-validation error on {@code POST /partial} missing {@code matchId} → 400
 *   <li>(b) {@link UnauthorizedException} → 401
 *   <li>(c) {@link ForbiddenException} → 403
 *   <li>(d) {@link ValidationException} → 400 with rule-context message
 *   <li>(e) Missing required {@code token} param on {@code GET /match} → 400
 * </ul>
 *
 * <h2>DEC-36 compliance</h2>
 *
 * <p>{@link ScoreEntryService} is mocked via {@code @MockitoBean} using the PUBLIC interface, NOT
 * the implementation class {@code DefaultScoreEntryService}.
 *
 * @see ScoreApiController
 * @see ScoreEntryService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package test typing rule</a>
 * @see <a href="DEC-38">DEC-38 — {@code @WebMvcTest} retained for slice tests (erratum)</a>
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="E22S09">E22S09 — TDD-reconstruct ScoreApiController</a>
 */
@WebMvcTest(ScoreApiController.class)
@DisplayName("ScoreApiController slice tests — E22S09 AC-SLICE-TEST-WEBMVC")
class ScoreApiControllerSliceTest {

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper objectMapper;

    /** DEC-36: mock the PUBLIC interface, not DefaultScoreEntryService. */
    @MockitoBean private ScoreEntryService scoreEntryService;

    @MockitoBean private TenantContext tenantContext;
    @MockitoBean private TenantRegistryPort tenantRegistryPort;

    private MockMvc mockMvc;

    private static final UUID MATCH_ID = UUID.randomUUID();
    private static final String VALID_DEVICE_TOKEN = "test-device-token-valid";

    @BeforeEach
    void setUp() {
        TenantContextSliceTestSupport.configureMock(tenantContext, UUID.randomUUID());
        when(tenantContext.bind(any())).thenReturn(() -> {});
        mockMvc =
                MockMvcBuilders.webAppContextSetup(context)
                        .apply(SecurityMockMvcConfigurers.springSecurity())
                        .build();
    }

    // =========================================================================
    // (a) Jakarta-validation error path → 400 (AC-SLICE-TEST-WEBMVC clause a)
    // =========================================================================

    @Test
    @DisplayName(
            "(a) POST /partial with null matchId fails @Valid → 400 with ApiErrorResponse")
    void postPartial_nullMatchId_returns400() throws Exception {
        // Missing matchId — jakarta validation fires (PartialScoreInput has @NotNull matchId)
        String body =
                """
                {
                  "setIndex": 0,
                  "team1Points": 5,
                  "team2Points": 3,
                  "deviceToken": "some-token"
                }
                """;

        mockMvc.perform(
                        post("/api/score/partial")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // =========================================================================
    // (b) UnauthorizedException → 401 (AC-SLICE-TEST-WEBMVC clause b)
    // =========================================================================

    @Test
    @DisplayName(
            "(b) POST /partial with invalid deviceToken → UnauthorizedException → 401")
    void postPartial_invalidToken_returns401() throws Exception {
        doThrow(new UnauthorizedException("Device token invalid"))
                .when(scoreEntryService)
                .handlePartialScore(any(PartialScoreInput.class));

        PartialScoreInput request =
                new PartialScoreInput(MATCH_ID, 0, 5, 3, "unknown-token");
        String body = objectMapper.writeValueAsString(request);

        mockMvc.perform(
                        post("/api/score/partial")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    // =========================================================================
    // (c) ForbiddenException → 403 (AC-SLICE-TEST-WEBMVC clause c)
    // =========================================================================

    @Test
    @DisplayName(
            "(c) GET /match with valid token but wrong field → ForbiddenException → 403")
    void getMatch_wrongField_returns403() throws Exception {
        doThrow(new ForbiddenException("Device assigned to different field"))
                .when(scoreEntryService)
                .getMatchForField(anyInt(), anyString());

        mockMvc.perform(
                        get("/api/score/match")
                                .param("field", "2")
                                .param("token", VALID_DEVICE_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    // =========================================================================
    // (d) ValidationException → 400 with rule-context message
    //     (AC-SLICE-TEST-WEBMVC clause d)
    // =========================================================================

    @Test
    @DisplayName(
            "(d) POST /submit where score fails set-validation → ValidationException → 400 with"
                    + " message")
    void postSubmit_validationFails_returns400WithMessage() throws Exception {
        String ruleMessage = "Set score invalid: points out of range for format";
        doThrow(new ValidationException(ruleMessage))
                .when(scoreEntryService)
                .submitSetResult(any(SetSubmitInput.class));

        SetSubmitInput request = new SetSubmitInput(MATCH_ID, 0, 99, 0, VALID_DEVICE_TOKEN);
        String body = objectMapper.writeValueAsString(request);

        mockMvc.perform(
                        post("/api/score/submit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(ruleMessage));
    }

    // =========================================================================
    // (e) Missing required query param → 400 (AC-URL-MAP-PARITY: token is required)
    // =========================================================================

    @Test
    @DisplayName("(e) GET /match without token param returns 400 (MissingServletRequestParameterException)")
    void getMatch_missingToken_returns400() throws Exception {
        mockMvc.perform(get("/api/score/match").param("field", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }
}
