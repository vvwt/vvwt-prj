package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.scoring.ScoringService;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.SetResultInput;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression IT verifying that {@link DefaultScoringService#registerMatchResult} does NOT change
 * any phase's status — D-12 / O-14 enforcement (AC-TEST-CASCADE-DOES-NOT-CHANGE-PHASE-STATUS,
 * AC-GOVERNANCE-NO-AUTO-ADVANCE-IN-CASCADE, E48S06).
 *
 * <h2>Test scenario</h2>
 *
 * <ol>
 *   <li>An ACTIVE tournament with an ACTIVE phase and two matches is created.
 *   <li>One match is submitted as a set result via {@link ScoringService#registerMatchResult}.
 *   <li>After the cascade, the phase status is verified to remain ACTIVE (NOT auto-advanced to
 *       COMPLETED).
 * </ol>
 *
 * <p>The scoring cascade (DefaultScoringService, 13 steps) MUST NOT call {@code phase.setStatus()}
 * under any circumstances — phase lifecycle is driven exclusively by {@link
 * de.vvwt.tm.tournament.PhaseLifecycleService} per D-12 / O-14.
 *
 * <h2>DEC-26 compliance</h2>
 *
 * <ul>
 *   <li>Rule 1: schema from production migration (applied by Spring Boot context at startup)
 *   <li>Rule 2: post-cascade phase status verified via direct JDBC query (not via DAO read)
 *   <li>Rule 3: fixtures inserted via direct JDBC
 * </ul>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>Subject injected as {@link ScoringService} (public interface) — cross-package from {@code
 * de.vvwt.tm.scoring.internal}.
 *
 * @see DefaultScoringService
 * @see de.vvwt.tm.tournament.PhaseLifecycleService
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="E48S06">E48S06 — AC-TEST-CASCADE-DOES-NOT-CHANGE-PHASE-STATUS</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cascadeguarddb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("CascadePhaseStatusGuardIT — scoring cascade must NOT change phase status (D-12)")
class CascadePhaseStatusGuardIT {

    /** Subject: injected via INTERFACE per DEC-36 (cross-package test typing). */
    @Autowired
    @Qualifier("defaultScoringService")
    private ScoringService scoringService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID locationId;
    private UUID phaseId;
    private UUID matchId;
    private UUID teamId1;
    private UUID teamId2;
    private UUID avatarId1;
    private UUID avatarId2;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "CascadeGuardIT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "CascadeGuardIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                4);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Vorrunde",
                "ACTIVE",
                0);

        teamId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId1,
                tournamentId,
                1,
                "Team 1",
                LocalDateTime.now());

        teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId2,
                tournamentId,
                2,
                "Team 2",
                LocalDateTime.now());

        avatarId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarId1,
                tournamentId,
                phaseId,
                teamId1,
                1,
                1,
                LocalDateTime.now());

        avatarId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarId2,
                tournamentId,
                phaseId,
                teamId2,
                1,
                2,
                LocalDateTime.now());

        // One match in ENABLED state — can receive a set result
        matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, lap_number, field_number,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatarId1,
                avatarId2,
                10, // ENABLED
                3,
                1,
                1,
                LocalDateTime.now());

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM set_result WHERE match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match_outcome WHERE match_id = ?", matchId);
        jdbcTemplate.update("DELETE FROM match WHERE id = ?", matchId);
        jdbcTemplate.update(
                "DELETE FROM team_avatar_rating WHERE avatar_id IN (?, ?)", avatarId1, avatarId2);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE id IN (?, ?)", avatarId1, avatarId2);
        jdbcTemplate.update("DELETE FROM team WHERE id IN (?, ?)", teamId1, teamId2);
        jdbcTemplate.update("DELETE FROM phase WHERE id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-CASCADE-DOES-NOT-CHANGE-PHASE-STATUS / AC-GOVERNANCE-NO-AUTO-ADVANCE-IN-CASCADE.
     *
     * <p>After registerMatchResult, the phase status remains ACTIVE — the cascade does NOT
     * auto-advance it to COMPLETED (D-12 / O-14 enforcement).
     */
    @Test
    @DisplayName("registerMatchResult — phase status remains ACTIVE after cascade (D-12 / O-14)")
    void registerMatchResult_doesNotChangePhaseStatus() {
        tenantBinder.bindDefaultTenant();
        try {
            // Arrange: submit a set result for the ENABLED match
            // Use withTournament factory (DEC-37 lock-first contract): tournamentId, matchId,
            // setIndex (0-based), team1Points, team2Points, actorId, reason
            SetResultInput input =
                    SetResultInput.withTournament(
                            tournamentId,
                            matchId,
                            0, // setIndex 0-based (first set)
                            25, // team1Points — decisive win (target 25 per standardVolleyball
                            // rule)
                            15,
                            null,
                            null);

            scoringService.registerMatchResult(input);

            // Assert: phase status is still ACTIVE — NOT auto-advanced (DEC-26 Rule 2: direct JDBC)
            String phaseStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
            assertThat(phaseStatus)
                    .as("Phase status must remain ACTIVE — cascade must NOT auto-advance it (D-12)")
                    .isEqualTo("ACTIVE");
        } finally {
            tenantBinder.unbind();
        }
    }
}
