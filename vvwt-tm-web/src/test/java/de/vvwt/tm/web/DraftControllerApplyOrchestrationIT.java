package de.vvwt.tm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.TournamentService;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RED-first IT for E55S06 AC-TEST-CONTROLLER-CALLS-PHASELIFECYCLE-RED: {@code POST
 * /api/tournaments/{tid}/draft/apply} must delegate to {@link
 * DraftApplicationOrchestrator#applyDraft(UUID, DraftConfig)} (Option C, DEC-64 D-11).
 *
 * <h2>Acceptance Criteria covered</h2>
 *
 * <ul>
 *   <li>AC-TEST-CONTROLLER-CALLS-PHASELIFECYCLE-RED — controller IT: POST /apply → orchestrator
 *       invoked once with correct tournamentId and config; response is 200 + DraftApplyResponse
 *       with phaseIds; no {@code MatchGenJobScheduledEvent} published
 * </ul>
 *
 * <h2>DEC-22 RED-first governance</h2>
 *
 * <p>Before E55S06 production changes: {@link DraftController#applyDraft} called {@link
 * de.vvwt.tm.tournament.DraftService#apply} directly; {@link DraftApplicationOrchestrator} was not
 * wired into the controller. Test fails → RED. After E55S06 controller rewiring → GREEN.
 *
 * <h2>DEC-44 IT framework</h2>
 *
 * <p>Uses {@code @SpringBootTest(RANDOM_PORT, classes=TournamentManagerApplication.class)} per
 * DEC-44 D1 (web-module controller-IT carve-out). Mock of {@link DraftApplicationOrchestrator} via
 * {@code @MockitoBean} to isolate controller from downstream orchestrator implementation.
 *
 * @see DraftController
 * @see DraftApplicationOrchestrator
 * @see <a href="DEC-64">DEC-64 D-11 — Option C: controller delegates to orchestrator</a>
 * @see <a href="DEC-44">DEC-44 — web-module IT annotation canon</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @since E55S06
 */
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = de.vvwt.tm.TournamentManagerApplication.class)
@Import({
    WebModuleTestConfig.class,
    DraftControllerApplyOrchestrationIT.TestAdminCredentials.class,
    TenantContextTestSupport.class
})
@ActiveProfiles("test")
@DisplayName(
        "DraftController POST /apply delegates to DraftApplicationOrchestrator"
                + " — AC-TEST-CONTROLLER-CALLS-PHASELIFECYCLE-RED — E55S06")
class DraftControllerApplyOrchestrationIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E55S06DraftControllerApplyOrchestrationIT01";

    /**
     * Provides admin credentials for this IT via {@link AdminCredentialsProvider}. Uses a password
     * unique to this IT to avoid cross-IT credential collisions.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestAdminCredentials {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }

    @MockitoBean private DraftApplicationOrchestrator draftApplicationOrchestrator;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private TournamentService tournamentService;

    @LocalServerPort private int port;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    /**
     * AC-TEST-CONTROLLER-CALLS-PHASELIFECYCLE-RED (E55S06):
     *
     * <p>POST /api/tournaments/{tid}/draft/apply must:
     *
     * <ol>
     *   <li>Invoke {@link DraftApplicationOrchestrator#applyDraft(UUID, DraftConfig)} exactly once.
     *   <li>Return 200 OK with DraftApplyResponse containing the phaseIds returned by the
     *       orchestrator.
     * </ol>
     */
    @Test
    @DisplayName(
            "POST /apply → orchestrator.applyDraft() called once, returns 200 + phaseIds"
                    + " — AC-TEST-CONTROLLER-CALLS-PHASELIFECYCLE-RED")
    void postApply_delegatesToOrchestrator_returns200WithPhaseIds() {
        tenantBinder.bindDefaultTenant();

        // Create a DRAFT tournament to apply against
        UUID tournamentId = createDraftTournament("OrchestratorControllerIT");

        // Stub: orchestrator returns 2 phase IDs
        UUID phaseId1 = UUID.randomUUID();
        UUID phaseId2 = UUID.randomUUID();
        when(draftApplicationOrchestrator.applyDraft(eq(tournamentId), any(DraftConfig.class)))
                .thenReturn(List.of(phaseId1, phaseId2));

        // Build request
        DraftSectionRequest s1 =
                new DraftSectionRequest(1, "team_number", 2, "roundRobin", 0, 0, 12, 1, null, null);
        DraftSectionRequest s2 =
                new DraftSectionRequest(
                        2, "team_number", 1, "siegerehrung", 0, 0, 5, 1, null, null);
        DraftRequest requestBody = new DraftRequest(List.of(s1, s2));

        // POST /apply
        ResponseEntity<DraftApplyResponse> response =
                authed.postForEntity(
                        baseUrl + "/api/tournaments/{tid}/draft/apply",
                        requestBody,
                        DraftApplyResponse.class,
                        tournamentId);

        // (a) 200 OK
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // (b) phaseIds from orchestrator returned in response
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().phaseIds())
                .as("Response must contain the phaseIds returned by the orchestrator")
                .containsExactlyInAnyOrder(phaseId1, phaseId2);

        // (c) orchestrator was called exactly once with the correct tournamentId
        verify(draftApplicationOrchestrator).applyDraft(eq(tournamentId), any(DraftConfig.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID createDraftTournament(String description) {
        tenantBinder.bindDefaultTenant();
        try {
            return tournamentService
                    .createTournament(
                            description,
                            null, // appointment
                            6, // teamCount
                            2, // fieldCount
                            "BEST_OF_3",
                            "setPoints",
                            "standardVolleyball",
                            "roundRobin",
                            null, // plannedStartTime
                            null, // optimize
                            null) // seedMannschaftsfoto
                    .getId();
        } finally {
            tenantBinder.unbind();
        }
    }
}
