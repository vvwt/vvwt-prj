package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.scoring.ScoreEntryService;
import de.vvwt.tm.scoring.SetSubmitInput;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.exceptions.MatchCanceledException;
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
 * RED-first integration test for the Score-Service CANCELED match guard (E48S04).
 *
 * <p>Covers {@code AC-TEST-SCORE-SERVICE-REJECT-CANCELED-RED}: after a tournament is cancelled (and
 * all open matches are marked CANCELED via E48S04 bulk-cancel logic), submitting a set result on a
 * CANCELED match MUST throw {@link MatchCanceledException} — which the controller maps to HTTP 409.
 *
 * <h2>DEC-22 TDD attestation</h2>
 *
 * <p>This test is authored RED-first — written BEFORE the CANCELED guard was added to {@link
 * DefaultScoreEntryService#submitSetResult}. The test must be observed FAILING (throwing {@code
 * NoSuchBeanDefinitionException} or the delegation reaching ScoringService without checking match
 * state) before the guard implementation is added.
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in {@code de.vvwt.tm.scoring.internal} — SAME package as {@link
 * DefaultScoreEntryService}. Per DEC-36, same-package tests MAY white-box reference the
 * implementation. However, since the guard behaviour is also part of the {@link ScoreEntryService}
 * public contract, this test injects via the interface type (DEC-36 cross-package rule applies
 * because the test aims at the public-contract behaviour, not the internal state).
 *
 * @see ScoreEntryService
 * @see DefaultScoreEntryService
 * @see MatchCanceledException
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="E48S04">E48S04 — AC-TEST-SCORE-SERVICE-REJECT-CANCELED-RED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:scorecanceledmatchit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DefaultScoreEntryServiceCanceledMatchIT — AC-TEST-SCORE-SERVICE-REJECT-CANCELED-RED")
class DefaultScoreEntryServiceCanceledMatchIT {

    /**
     * Subject injected via public interface (DEC-36).
     *
     * <p>Uses the {@code defaultScoreEntryService} qualifier because the legacy {@code
     * ScoreEntryService} bean also exists during reconstruction-in-place (DEC-22 coexistence).
     */
    @Autowired
    @Qualifier("defaultScoreEntryService")
    private ScoreEntryService scoreEntryService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID locationId;
    private UUID phaseId;
    private UUID canceledMatchId;
    private UUID deviceId;
    private String deviceToken;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        canceledMatchId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        deviceToken = "test-device-token-" + UUID.randomUUID();

        // Seed a locations row (FK tournament.location_id → locations.id, DEC-39 D2)
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "ScoreCanceled IT Location");

        // Insert tournament in CANCELLED status
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E48S04 Score-Cancel IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "CANCELLED",
                LocalDateTime.now(),
                2,
                8);

        // Insert phase (description NOT NULL per schema)
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "ACTIVE",
                1);

        // Insert team + team_avatar rows (FK chain: match → team_avatar → team)
        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?)",
                team1Id,
                tournamentId,
                1,
                "Team A");
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?)",
                team2Id,
                tournamentId,
                2,
                "Team B");
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                1,
                1,
                team1Id);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                1,
                2,
                team2Id);

        // Insert a CANCELED match (state = -10, MatchState.CANCELED.getLegacyCode())
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, field_number, lap_number)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                canceledMatchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                -10, // CANCELED
                3,
                1,
                1);

        // Insert an ASSIGNED device (location_id nullable per schema comment)
        jdbcTemplate.update(
                "INSERT INTO devices (id, device_token, status, device_type, assigned_field)"
                        + " VALUES (?, ?, ?, ?, ?)",
                deviceId,
                deviceToken,
                "ASSIGNED",
                "SCORING_TABLET",
                1);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM devices WHERE id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-SCORE-SERVICE-REJECT-CANCELED-RED
    // =========================================================================

    /**
     * Verifies that submitting a set result on a CANCELED match throws {@link
     * MatchCanceledException} with an operator-actionable message.
     *
     * <p>The exception must contain "CANCELED" in the message
     * (AC-ERROR-HANDLING-CANCELED-MATCH-MESSAGE).
     */
    @Test
    @DisplayName("submitSetResult() on CANCELED match throws MatchCanceledException")
    void submitSetResult_canceledMatch_throwsMatchCanceledException() {
        SetSubmitInput request = new SetSubmitInput(canceledMatchId, 0, 15, 10, deviceToken);

        assertThatThrownBy(() -> scoreEntryService.submitSetResult(request))
                .isInstanceOf(MatchCanceledException.class)
                .hasMessageContaining("CANCELED")
                .hasMessageContaining(canceledMatchId.toString());
    }
}
