// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.cutover;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.internal.dto.TeamBulkCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TeamBulkCreateResponse;
import de.vvwt.tm.tournament.internal.dto.TeamCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Within-tournament E2E smoke test verifying the E21S13 atomic cutover result.
 *
 * <p>Exercises the tournament stack — {@code de.vvwt.tm.tournament.*} — end-to-end via HTTP:
 *
 * <ol>
 *   <li>Create a {@code Tournament} via POST {@code /api/tournaments}
 *   <li>Add teams via POST {@code /api/tournaments/{id}/teams/bulk}
 *   <li>Start a {@code Phase} via POST {@code /api/tournaments/{id}/draft/apply}
 *   <li>Verify {@link TournamentRepository}, {@link TeamRepository}, and {@link PhaseRepository}
 *       are all wired from {@code de.vvwt.tm.tournament.*} (no legacy {@code domain.*} types)
 * </ol>
 *
 * <p>Zero imports from deleted legacy packages: no {@code de.vvwt.tm.domain.*}, no {@code
 * de.vvwt.tm.infrastructure.web.dto.*} (deleted). All tournament types imported from {@code
 * de.vvwt.tm.tournament.*} or {@code de.vvwt.tm.tournament.internal.*}.
 *
 * <p>This test is committed in the same cutover commit as the legacy deletion (AC-ATOMICITY). It
 * MUST be green on {@code staging} post-cutover — a compile error or test failure here means the
 * cutover commit is broken.
 *
 * @see TournamentCrossContextSmokeIT
 * @see <a href="E21S13">E21S13 — Atomic cutover commit</a>
 * @see <a href="DEC-32">DEC-32 — Mechanical FQN-rewrite carve-out</a>
 */
@Tag("smoke")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TournamentManagerApplication.class, TournamentCutoverSmokeIT.TestConfig.class})
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("E21S13 — TournamentCutoverSmokeIT (within-tournament E2E post-cutover)")
class TournamentCutoverSmokeIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S13CutoverSmokeIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    /** New tournament-context repositories — wired from {@code de.vvwt.tm.tournament.*}. */
    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private TeamRepository teamRepository;
    @Autowired private PhaseRepository phaseRepository;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // E2E happy-path: Tournament → Teams → Phase
    // =========================================================================

    /**
     * Verifies the full within-tournament creation flow post-cutover:
     *
     * <ol>
     *   <li>POST /api/tournaments → 201 Created, tournament ID returned
     *   <li>POST /api/tournaments/{id}/teams/bulk → 201 Created, teams created
     *   <li>POST /api/tournaments/{id}/draft/apply → 200 OK, phase IDs returned
     *   <li>Repositories ({@link TournamentRepository}, {@link TeamRepository}, {@link
     *       PhaseRepository}) can read the persisted entities — all from {@code
     *       de.vvwt.tm.tournament.*}, not the deleted legacy {@code de.vvwt.tm.domain.*}
     * </ol>
     *
     * <p>DEC-32 post-condition: all four steps use only types from {@code de.vvwt.tm.tournament.*}
     * or {@code de.vvwt.tm.tournament.internal.*}. Zero references to deleted legacy packages.
     */
    @Test
    @DisplayName(
            "E2E: Tournament → Teams → Phase creation succeeds; new tournament repos are wired")
    void tournamentToPhasePipelineIsGreenPostCutover() throws Exception {
        tenantBinder.bindDefaultTenant();
        try {
            // Step 1: Create tournament
            var createRequest =
                    new TournamentCreateRequest(
                            "E21S13 Cutover Smoke Hallenturnier",
                            null,
                            4,
                            2,
                            "BEST_OF_3",
                            "setPoints",
                            "standardVolleyball",
                            "roundRobin",
                            null,
                            null,
                            null, // E53S05: seedMannschaftsfoto = null
                            null); // E68S01: organizer = null
            ResponseEntity<TournamentResponse> tournamentResp =
                    authed.postForEntity(
                            new URI(baseUrl + "/api/tournaments"),
                            createRequest,
                            TournamentResponse.class);

            assertThat(tournamentResp.getStatusCode())
                    .as("POST /api/tournaments must return 201")
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(tournamentResp.getBody()).isNotNull();
            UUID tournamentId = tournamentResp.getBody().id();
            assertThat(tournamentId).as("tournament ID must not be null").isNotNull();

            // Verify via new TournamentRepository (de.vvwt.tm.tournament.TournamentRepository)
            assertThat(tournamentRepository.findById(tournamentId))
                    .as("TournamentRepository (new tournament.*) must find created tournament")
                    .isPresent();

            // Step 2: Add teams via bulk endpoint
            var teamRequests =
                    List.of(
                            new TeamCreateRequest("Alpha", null, null, null, null),
                            new TeamCreateRequest("Bravo", null, null, null, null),
                            new TeamCreateRequest("Charlie", null, null, null, null),
                            new TeamCreateRequest("Delta", null, null, null, null));
            var bulkRequest = new TeamBulkCreateRequest(teamRequests);
            ResponseEntity<TeamBulkCreateResponse> teamsResp =
                    authed.postForEntity(
                            new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams/bulk"),
                            bulkRequest,
                            TeamBulkCreateResponse.class);

            assertThat(teamsResp.getStatusCode())
                    .as("POST /teams/bulk must return 201")
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(teamsResp.getBody()).isNotNull();

            // Verify via new TeamRepository (de.vvwt.tm.tournament.TeamRepository)
            // E05S12: tournament was created with teamCount=4, so 4 teams are auto-seeded.
            // The bulk request adds 4 more. Total = 4 (seeded) + 4 (bulk) = 8.
            assertThat(teamRepository.findByTournamentId(tournamentId))
                    .as("TeamRepository (new tournament.*) must return all teams (seeded + bulk)")
                    .hasSize(teamRequests.size() + 4);

            // Step 3: Apply draft → start Phase.
            // Two-section config: section 1 = roundrobin (generates matches via
            // RoundRobinMatchGenerator),
            // section 2 = siegerehrung (last phase, satisfies D-10 invariant per
            // AC-IMPL-LAST-PHASE-INVARIANT,
            // E48S01). Match generation only runs for Phase 1, so no SiegerehrungMatchGenerator
            // (E48S02)
            // is required here.
            var section1 =
                    new DraftSectionRequest(
                            1, "team_number", 1, "roundRobin", 0, 0, 15, 1, null, null);
            var section2 =
                    new DraftSectionRequest(
                            2, "team_number", 1, "awardCeremony", 0, 0, 15, 1, null, null);
            var draftRequest = new DraftRequest(List.of(section1, section2));
            ResponseEntity<DraftApplyResponse> draftResp =
                    authed.postForEntity(
                            new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft/apply"),
                            draftRequest,
                            DraftApplyResponse.class);

            assertThat(draftResp.getStatusCode())
                    .as("POST /draft/apply must return 200")
                    .isEqualTo(HttpStatus.OK);
            assertThat(draftResp.getBody()).isNotNull();
            List<UUID> phaseIds = draftResp.getBody().phaseIds();
            assertThat(phaseIds).as("draft/apply must return at least one phase ID").isNotEmpty();

            // Verify via new PhaseRepository (de.vvwt.tm.tournament.PhaseRepository)
            assertThat(phaseRepository.findById(phaseIds.get(0)))
                    .as("PhaseRepository (new tournament.*) must find created phase")
                    .isPresent();

        } finally {
            tenantBinder.unbind();
        }
    }

    // =========================================================================
    // Security smoke: unauthenticated → 401
    // =========================================================================

    @Test
    @DisplayName("unauthenticated POST /api/tournaments returns 401 post-cutover")
    void unauthenticatedRequestReturns401() throws Exception {
        var request =
                new TournamentCreateRequest(
                        "Unauthorized",
                        null,
                        4,
                        2,
                        "BEST_OF_3",
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        null,
                        null,
                        null, // E53S05: seedMannschaftsfoto = null
                        null); // E68S01: organizer = null
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        new URI(baseUrl + "/api/tournaments"), request, String.class);
        assertThat(response.getStatusCode())
                .as("unauthenticated request must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // =========================================================================
    // Test configuration
    // =========================================================================

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        AdminCredentialsProvider testAdminCredentialsProvider(PasswordEncoder passwordEncoder) {
            String hash = passwordEncoder.encode(ADMIN_PASS);
            return () -> hash;
        }
    }
}
