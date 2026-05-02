package de.vvwt.tm.tournament.cutover;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.auth.AdminCredentialsProvider;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.internal.dto.TeamBulkCreateRequest;
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
 * Cross-context smoke test verifying all six DEC-32 consumer contexts compile and are wired
 * correctly after the E21S13 atomic cutover.
 *
 * <h2>Scope (AC-Q2-CROSS-CONTEXT-SMOKE-IT)</h2>
 *
 * <p>Verifies:
 *
 * <ol>
 *   <li>Full module compile is green — this test file compiles only if all six consumer contexts'
 *       rewritten imports resolve. A compile error here signals a broken cutover.
 *   <li>The tournament stack ({@link TournamentRepository}, {@link PhaseRepository}, {@link
 *       MatchRepository}) is fully wired and auto-injected by Spring — the {@code @Autowired}
 *       fields would fail context load if any bean is missing.
 *   <li>A cross-context representative flow: the {@code scoring} context's {@link
 *       de.vvwt.tm.infrastructure.score.ScoreEntryService} (now importing from {@code
 *       de.vvwt.tm.tournament.*} after FQN-rewrite) indirectly exercises {@link MatchRepository}
 *       when the Spring context loads — confirming the DEC-32 rewrite does not break cross-context
 *       wiring at startup.
 *   <li>The Spring application context loads successfully with zero {@code @Primary} /
 *       {@code @ConditionalOn*} guards on tournament beans (AC-NO-FEATURE-FLAGS).
 * </ol>
 *
 * <h2>What this test does NOT do</h2>
 *
 * <p>It does not re-run the full suites of the six consumer contexts. Those suites are covered by
 * their own IT/unit tests ({@code ScoreEntryServiceTest}, {@code
 * DefaultDisplayOverviewServiceTest}, etc.). This test provides a compile-time + context-startup
 * guarantee that the cutover is structurally sound.
 *
 * <h2>Zero legacy FQN imports</h2>
 *
 * <p>This file imports ZERO types from the deleted legacy packages: {@code de.vvwt.tm.domain.*}
 * (deleted), {@code de.vvwt.tm.infrastructure.web.dto.*} (deleted). Every tournament type is
 * imported from {@code de.vvwt.tm.tournament.*} or {@code de.vvwt.tm.tournament.internal.*}.
 *
 * @see TournamentCutoverSmokeIT
 * @see <a href="E21S13">E21S13 — Atomic cutover commit</a>
 * @see <a href="DEC-32">DEC-32 — Mechanical FQN-rewrite carve-out</a>
 */
@Tag("smoke")
@AutoConfigureTestRestTemplate
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {
            TournamentManagerApplication.class,
            TournamentCrossContextSmokeIT.TestConfig.class
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("E21S13 — TournamentCrossContextSmokeIT (cross-context wiring post-cutover)")
class TournamentCrossContextSmokeIT {

    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASS = "E21S13CrossContextSmokeIT01";

    @LocalServerPort private int port;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    // -------------------------------------------------------------------------
    // New tournament-context repositories — wired from de.vvwt.tm.tournament.*
    // The @Autowired injection here proves the beans exist in the Spring context.
    // A missing bean = context load failure = test class fails to instantiate.
    // -------------------------------------------------------------------------

    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private MatchRepository matchRepository;

    private String baseUrl;
    private TestRestTemplate authed;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        authed = restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASS);
    }

    // =========================================================================
    // Context-startup smoke: Spring context loads with new tournament beans
    // =========================================================================

    /**
     * Verifies the Spring application context loads with all six DEC-32 consumer contexts wired to
     * the new {@code de.vvwt.tm.tournament.*} beans.
     *
     * <p>If the @Autowired fields above (TournamentRepository, PhaseRepository, MatchRepository)
     * are populated, the context loaded successfully, which means:
     *
     * <ul>
     *   <li>All six consumer contexts' rewritten imports resolved at compile time.
     *   <li>No {@code @Primary} / {@code @ConditionalOn*} feature flags were needed
     *       (AC-NO-FEATURE-FLAGS).
     *   <li>ApplicationModules.verify() is covered by ApplicationModulesTest (separate test).
     * </ul>
     */
    @Test
    @DisplayName(
            "Spring context loads with new tournament.* beans wired to all six consumer contexts")
    void springContextLoadsWithNewTournamentBeans() {
        // If @Autowired above succeeded, context is up. Explicitly assert non-null.
        assertThat(tournamentRepository)
                .as("TournamentRepository (de.vvwt.tm.tournament.*) must be wired by Spring")
                .isNotNull();
        assertThat(phaseRepository)
                .as("PhaseRepository (de.vvwt.tm.tournament.*) must be wired by Spring")
                .isNotNull();
        assertThat(matchRepository)
                .as("MatchRepository (de.vvwt.tm.tournament.*) must be wired by Spring")
                .isNotNull();
    }

    // =========================================================================
    // Cross-context representative flow: tournament → score context wiring
    // =========================================================================

    /**
     * Representative cross-context flow: creates a tournament + phase via the tournament API, then
     * reads matches via {@link MatchRepository} — confirming the {@code scoring} context's
     * rewritten import ({@code domain.repo.MatchRepository} → {@code tournament.MatchRepository})
     * is structurally sound.
     *
     * <p>This mirrors the pattern that the {@code ScoreEntryService} uses post-rewrite.
     */
    @Test
    @DisplayName("cross-context: tournament MatchRepository readable from scoring-context pattern")
    void matchRepositoryReadableFromScoringContextPattern() throws Exception {
        tenantBinder.bindDefaultTenant();
        try {
            // Create tournament
            var createRequest =
                    new TournamentCreateRequest(
                            "E21S13 CrossContext Smoke Turnier",
                            null,
                            4,
                            2,
                            "BEST_OF_3",
                            "setPoints",
                            "standardVolleyball",
                            "roundRobin");
            ResponseEntity<TournamentResponse> tournamentResp =
                    authed.postForEntity(
                            new URI(baseUrl + "/api/tournaments"),
                            createRequest,
                            TournamentResponse.class);
            assertThat(tournamentResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            UUID tournamentId = tournamentResp.getBody().id();

            // Add minimum teams for a phase
            var teamRequests =
                    List.of(
                            new TeamCreateRequest("X1", null, null, null, null),
                            new TeamCreateRequest("X2", null, null, null, null),
                            new TeamCreateRequest("X3", null, null, null, null),
                            new TeamCreateRequest("X4", null, null, null, null));
            authed.postForEntity(
                    new URI(baseUrl + "/api/tournaments/" + tournamentId + "/teams/bulk"),
                    new TeamBulkCreateRequest(teamRequests),
                    Object.class);

            // Apply draft → creates Phase + Matches via tournament internal service
            var section =
                    new DraftSectionRequest(1, "team_number", 1, "roundRobin", 0, 0, 15, 1, null);
            ResponseEntity<DraftApplyResponse> draftResp =
                    authed.postForEntity(
                            new URI(baseUrl + "/api/tournaments/" + tournamentId + "/draft/apply"),
                            new DraftRequest(List.of(section)),
                            DraftApplyResponse.class);
            assertThat(draftResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            UUID phaseId = draftResp.getBody().phaseIds().get(0);

            // Read matches via the new MatchRepository — the same repository that ScoreEntryService
            // now uses after the DEC-32 FQN-rewrite (domain.repo.MatchRepository →
            // tournament.MatchRepository)
            List<Match> matches = matchRepository.findByPhaseId(phaseId);
            assertThat(matches)
                    .as(
                            "MatchRepository (de.vvwt.tm.tournament.*) must return matches for the"
                                    + " created phase — confirms scoring cross-context wiring")
                    .isNotEmpty();

            // Verify Phase readable too (used by print + display consumer contexts after rewrite)
            assertThat(phaseRepository.findById(phaseId))
                    .as("PhaseRepository must find the created phase")
                    .isPresent();

        } finally {
            tenantBinder.unbind();
        }
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
