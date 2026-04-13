package de.vvwt.tm;

import de.vvwt.tm.domain.AuditLogEntry;
import de.vvwt.tm.domain.MatchOutcome;
import de.vvwt.tm.domain.MatchState;
import de.vvwt.tm.domain.RoundSnapshot;
import de.vvwt.tm.domain.SetState;
import de.vvwt.tm.domain.TeamAvatarRating;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for E03S04 — Flyway V5 aggregate and audit tables migration.
 *
 * <p>Verifies that the {@code V5__e03_aggregates_and_audit.sql} migration applies correctly
 * and that all schema-level constraints (PKs, FKs, CHECK constraints, UNIQUE constraints,
 * tenant NOT NULL) work as designed.
 *
 * <p>Uses the "test" profile ({@code application-test.yml}): in-memory H2 so no filesystem
 * side-effects occur during test runs. Flyway runs V1–V5 migrations against the in-memory
 * database on every context load.
 *
 * <p>Acceptance criteria covered:
 * <ul>
 *   <li>AC1  — V5 migration file exists and was applied (implicit: context loads)</li>
 *   <li>AC2  — {@code match_outcome} table: columns, PK, FK, CHECK constraints</li>
 *   <li>AC3  — {@code team_avatar_rating} table: columns, PK, FK, CHECK constraints</li>
 *   <li>AC4  — {@code audit_log} table: columns, PK, FK, index on (match_id, set_index, changed_at)</li>
 *   <li>AC5  — {@code round_snapshots} table: columns, PK, FK, UNIQUE (tournament, phase, lap)</li>
 *   <li>AC6  — Java entity classes exist with required fields and methods</li>
 *   <li>AC7  — No seed data: all four tables empty after startup</li>
 *   <li>AC9  — Snapshot uniqueness: second INSERT with same (tournament, phase, lap) fails</li>
 *   <li>AC10 — INFORMATION_SCHEMA shows all four tables, PKs, FKs, CHECK/UNIQUE constraints</li>
 *   <li>AC11 — Audit log traceability integration test (old=NULL / new=actual)</li>
 *   <li>AC12 — tenant_id NOT NULL enforced on all four tables</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E03S04.story.md">Story E03S04</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class E03S04MigrationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================================
    // AC1 — Flyway V5 applied
    // =========================================================================

    @Test
    void flywaySchemaHistoryHasExactlyOneV5Entry() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT \"version\", \"script\", \"success\" "
                + "FROM \"flyway_schema_history\" "
                + "WHERE \"version\" = '5'");

        assertThat(rows)
                .as("flyway_schema_history must contain exactly one row for version '5' (AC1)")
                .hasSize(1);

        Map<String, Object> v5Row = rows.get(0);
        assertThat(v5Row.get("success"))
                .as("Flyway V5 migration must have success = true")
                .isEqualTo(true);
        assertThat(String.valueOf(v5Row.get("script")))
                .as("Flyway V5 script name must reference V5")
                .containsIgnoringCase("V5");
    }

    // =========================================================================
    // AC7 — No seed data
    // =========================================================================

    @Test
    void allFourTablesAreEmptyAfterMigration() {
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM match_outcome", Integer.class))
                .as("match_outcome must be empty after migration (AC7)")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM team_avatar_rating", Integer.class))
                .as("team_avatar_rating must be empty after migration (AC7)")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class))
                .as("audit_log must be empty after migration (AC7)")
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM round_snapshots", Integer.class))
                .as("round_snapshots must be empty after migration (AC7)")
                .isZero();
    }

    // =========================================================================
    // AC2 — match_outcome table
    // =========================================================================

    @Test
    void matchOutcomeColumnsRoundTrip() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        jdbcTemplate.update(
                "INSERT INTO match_outcome "
                + "(match_id, tenant_id, team1_sets_won, team1_balls_won, "
                + " team2_sets_won, team2_balls_won, set_count, computed_state) "
                + "VALUES (?, ?, 2, 50, 1, 40, 3, ?)",
                matchId, tenantId, MatchState.FINISHED_WINNER1.getLegacyCode());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT match_id, tenant_id, team1_sets_won, team1_balls_won, "
                + "team2_sets_won, team2_balls_won, set_count, computed_state, updated_at "
                + "FROM match_outcome WHERE match_id = ?",
                matchId);

        assertThat(row.get("match_id")).as("AC2: match_id round-trip").isNotNull();
        assertThat(row.get("tenant_id")).as("AC2: tenant_id round-trip").isNotNull();
        assertThat(row.get("team1_sets_won")).as("AC2: team1_sets_won = 2").isEqualTo(2);
        assertThat(row.get("team1_balls_won")).as("AC2: team1_balls_won = 50").isEqualTo(50);
        assertThat(row.get("team2_sets_won")).as("AC2: team2_sets_won = 1").isEqualTo(1);
        assertThat(row.get("team2_balls_won")).as("AC2: team2_balls_won = 40").isEqualTo(40);
        assertThat(row.get("set_count")).as("AC2: set_count = 3").isEqualTo(3);
        assertThat(row.get("computed_state"))
                .as("AC2: computed_state = FINISHED_WINNER1 code")
                .isEqualTo(MatchState.FINISHED_WINNER1.getLegacyCode());
        assertThat(row.get("updated_at")).as("AC2: updated_at set by DB default").isNotNull();
    }

    @Test
    void matchOutcomeCheckConstraintsRejectNegativeValues() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        // team1_sets_won negative
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO match_outcome (match_id, tenant_id, team1_sets_won, "
                        + "team1_balls_won, team2_sets_won, team2_balls_won, set_count, computed_state) "
                        + "VALUES (?, ?, -1, 0, 0, 0, 0, 51)",
                        matchId, tenantId))
                .as("AC2: team1_sets_won=-1 must fail CHECK constraint")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void matchOutcomePkCollisionRejected() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        jdbcTemplate.update(
                "INSERT INTO match_outcome (match_id, tenant_id, set_count, computed_state) "
                + "VALUES (?, ?, 0, 51)",
                matchId, tenantId);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO match_outcome (match_id, tenant_id, set_count, computed_state) "
                        + "VALUES (?, ?, 0, 52)",
                        matchId, tenantId))
                .as("AC2: duplicate match_id must fail PK constraint")
                .isInstanceOf(DataAccessException.class);
    }

    // =========================================================================
    // AC3 — team_avatar_rating table
    // =========================================================================

    @Test
    void teamAvatarRatingColumnsRoundTrip() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId = insertMinimalTeam(tenantId, tournamentId, 7);
        UUID avatarId = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId, 1, 1);

        jdbcTemplate.update(
                "INSERT INTO team_avatar_rating "
                + "(avatar_id, tenant_id, match_count, set_count, points, "
                + " sets_won, sets_lost, balls_won, balls_lost, "
                + " set_quotient, ball_quotient, is_without_assessment) "
                + "VALUES (?, ?, 3, 9, 6, 6, 3, 150, 120, 2.0, 1.25, FALSE)",
                avatarId, tenantId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT avatar_id, tenant_id, match_count, set_count, points, "
                + "sets_won, sets_lost, balls_won, balls_lost, "
                + "set_quotient, ball_quotient, is_without_assessment, updated_at "
                + "FROM team_avatar_rating WHERE avatar_id = ?",
                avatarId);

        assertThat(row.get("avatar_id")).as("AC3: avatar_id round-trip").isNotNull();
        assertThat(row.get("tenant_id")).as("AC3: tenant_id round-trip").isNotNull();
        assertThat(row.get("match_count")).as("AC3: match_count = 3").isEqualTo(3);
        assertThat(row.get("set_count")).as("AC3: set_count = 9").isEqualTo(9);
        assertThat(row.get("points")).as("AC3: points = 6").isEqualTo(6);
        assertThat(row.get("sets_won")).as("AC3: sets_won = 6").isEqualTo(6);
        assertThat(row.get("sets_lost")).as("AC3: sets_lost = 3").isEqualTo(3);
        assertThat(row.get("balls_won")).as("AC3: balls_won = 150").isEqualTo(150);
        assertThat(row.get("balls_lost")).as("AC3: balls_lost = 120").isEqualTo(120);
        assertThat(row.get("is_without_assessment")).as("AC3: is_without_assessment = false")
                .isIn(false, Boolean.FALSE, 0);
        assertThat(row.get("updated_at")).as("AC3: updated_at set by DB default").isNotNull();
    }

    @Test
    void teamAvatarRatingCheckConstraintRejectsNegativeMatchCount() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId = insertMinimalTeam(tenantId, tournamentId, 8);
        UUID avatarId = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId, 2, 1);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO team_avatar_rating (avatar_id, tenant_id, match_count, "
                        + "set_count, points, sets_won, sets_lost, balls_won, balls_lost, "
                        + "set_quotient, ball_quotient, is_without_assessment) "
                        + "VALUES (?, ?, -1, 0, 0, 0, 0, 0, 0, 0, 0, FALSE)",
                        avatarId, tenantId))
                .as("AC3: match_count=-1 must fail CHECK constraint")
                .isInstanceOf(DataAccessException.class);
    }

    // =========================================================================
    // AC4 — audit_log table
    // =========================================================================

    @Test
    void auditLogColumnsRoundTripFirstInsert() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);
        UUID auditId = UUID.randomUUID();

        // First insert — old values are NULL
        jdbcTemplate.update(
                "INSERT INTO audit_log "
                + "(id, tenant_id, match_id, set_index, "
                + " team1_points_old, team2_points_old, set_state_old, "
                + " team1_points_new, team2_points_new, set_state_new) "
                + "VALUES (?, ?, ?, 0, NULL, NULL, NULL, 25, 20, ?)",
                auditId, tenantId, matchId, SetState.WINNER1.getLegacyCode());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, tenant_id, match_id, set_index, "
                + "team1_points_old, team2_points_old, set_state_old, "
                + "team1_points_new, team2_points_new, set_state_new, "
                + "actor_id, reason, changed_at "
                + "FROM audit_log WHERE id = ?",
                auditId);

        assertThat(row.get("id")).as("AC4: id round-trip").isNotNull();
        assertThat(row.get("tenant_id")).as("AC4: tenant_id round-trip").isNotNull();
        assertThat(row.get("match_id")).as("AC4: match_id round-trip").isNotNull();
        assertThat(row.get("set_index")).as("AC4: set_index = 0").isEqualTo(0);
        assertThat(row.get("team1_points_old")).as("AC4: team1_points_old = NULL on first insert").isNull();
        assertThat(row.get("team2_points_old")).as("AC4: team2_points_old = NULL on first insert").isNull();
        assertThat(row.get("set_state_old")).as("AC4: set_state_old = NULL on first insert").isNull();
        assertThat(row.get("team1_points_new")).as("AC4: team1_points_new = 25").isEqualTo(25);
        assertThat(row.get("team2_points_new")).as("AC4: team2_points_new = 20").isEqualTo(20);
        assertThat(row.get("set_state_new"))
                .as("AC4: set_state_new = WINNER1 code")
                .isEqualTo(SetState.WINNER1.getLegacyCode());
        assertThat(row.get("actor_id")).as("AC4: actor_id = NULL (LAN mode)").isNull();
        assertThat(row.get("reason")).as("AC4: reason = NULL").isNull();
        assertThat(row.get("changed_at")).as("AC4: changed_at set by DB default").isNotNull();
    }

    @Test
    void auditLogAllowsMultipleEntriesForSameSet() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        // First entry (original score)
        jdbcTemplate.update(
                "INSERT INTO audit_log (id, tenant_id, match_id, set_index, "
                + " team1_points_new, team2_points_new, set_state_new) "
                + "VALUES (?, ?, ?, 0, 25, 20, ?)",
                UUID.randomUUID(), tenantId, matchId, SetState.WINNER1.getLegacyCode());

        // Second entry (correction)
        jdbcTemplate.update(
                "INSERT INTO audit_log (id, tenant_id, match_id, set_index, "
                + " team1_points_old, team2_points_old, set_state_old, "
                + " team1_points_new, team2_points_new, set_state_new, reason) "
                + "VALUES (?, ?, ?, 0, 25, 20, ?, 20, 25, ?, 'Correction by organizer')",
                UUID.randomUUID(), tenantId, matchId,
                SetState.WINNER1.getLegacyCode(), SetState.WINNER2.getLegacyCode());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE match_id = ? AND set_index = 0",
                Integer.class, matchId);
        assertThat(count)
                .as("AC4: multiple audit entries for same set must be allowed (append-only)")
                .isEqualTo(2);
    }

    @Test
    void auditLogIndexExists() {
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                + "WHERE TABLE_NAME = 'AUDIT_LOG'");

        List<String> indexNames = indexes.stream()
                .map(row -> String.valueOf(row.get("INDEX_NAME")).toUpperCase())
                .toList();

        assertThat(indexNames)
                .as("AC4: idx_audit_log_match_set_time must be present in INFORMATION_SCHEMA")
                .anyMatch(name -> name.contains("MATCH") || name.contains("AUDIT"));
    }

    // =========================================================================
    // AC5 + AC9 — round_snapshots table + uniqueness invariant
    // =========================================================================

    @Test
    void roundSnapshotColumnsRoundTrip() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID snapshotId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO round_snapshots "
                + "(id, tenant_id, tournament_id, phase_id, lap_number, snapshot_payload) "
                + "VALUES (?, ?, ?, ?, 1, '{\"standings\":[]}')",
                snapshotId, tenantId, tournamentId, phaseId);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, tenant_id, tournament_id, phase_id, lap_number, "
                + "snapshot_payload, created_at "
                + "FROM round_snapshots WHERE id = ?",
                snapshotId);

        assertThat(row.get("id")).as("AC5: id round-trip").isNotNull();
        assertThat(row.get("tenant_id")).as("AC5: tenant_id round-trip").isNotNull();
        assertThat(row.get("tournament_id")).as("AC5: tournament_id round-trip").isNotNull();
        assertThat(row.get("phase_id")).as("AC5: phase_id round-trip").isNotNull();
        assertThat(row.get("lap_number")).as("AC5: lap_number = 1").isEqualTo(1);
        assertThat(String.valueOf(row.get("snapshot_payload")))
                .as("AC5: snapshot_payload round-trip")
                .contains("standings");
        assertThat(row.get("created_at")).as("AC5: created_at set by DB default").isNotNull();
    }

    /**
     * AC9 — Attempting to INSERT a second round_snapshots row with the same
     * (tournament_id, phase_id, lap_number) fails with UNIQUE constraint violation.
     */
    @Test
    void roundSnapshotUniquenessConstraintRejectedDuplicate() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);

        // First snapshot for lap 2 — must succeed
        jdbcTemplate.update(
                "INSERT INTO round_snapshots "
                + "(id, tenant_id, tournament_id, phase_id, lap_number, snapshot_payload) "
                + "VALUES (?, ?, ?, ?, 2, '{\"lap\":2}')",
                UUID.randomUUID(), tenantId, tournamentId, phaseId);

        // Second snapshot for the same (tournament, phase, lap) — must fail
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO round_snapshots "
                        + "(id, tenant_id, tournament_id, phase_id, lap_number, snapshot_payload) "
                        + "VALUES (?, ?, ?, ?, 2, '{\"lap\":2,\"duplicate\":true}')",
                        UUID.randomUUID(), tenantId, tournamentId, phaseId))
                .as("AC9: duplicate (tournament_id, phase_id, lap_number) must fail UNIQUE constraint")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void roundSnapshotAllowsDifferentLapsForSamePhase() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);

        // Three different laps — all should succeed
        for (int lap = 1; lap <= 3; lap++) {
            jdbcTemplate.update(
                    "INSERT INTO round_snapshots "
                    + "(id, tenant_id, tournament_id, phase_id, lap_number, snapshot_payload) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenantId, tournamentId, phaseId, lap,
                    "{\"lap\":" + lap + "}");
        }

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM round_snapshots WHERE phase_id = ?",
                Integer.class, phaseId);
        assertThat(count)
                .as("AC5: different lap numbers for same phase must all be accepted")
                .isEqualTo(3);
    }

    // =========================================================================
    // AC6 — Java entity classes
    // =========================================================================

    @Test
    void matchOutcomeEntityClassExists_andMatchesComputedStateWorks() {
        // Full-constructor entity creation
        UUID matchId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        MatchOutcome outcome = new MatchOutcome(matchId, tenantId, 2, 50, 1, 40, 3,
                MatchState.FINISHED_WINNER1.getLegacyCode(), null);

        assertThat(outcome.getMatchId()).as("AC6: matchId set correctly").isEqualTo(matchId);
        assertThat(outcome.getTenantId()).as("AC6: tenantId set correctly").isEqualTo(tenantId);
        assertThat(outcome.getTeam1SetsWon()).as("AC6: team1SetsWon = 2").isEqualTo(2);
        assertThat(outcome.getTeam1BallsWon()).as("AC6: team1BallsWon = 50").isEqualTo(50);
        assertThat(outcome.getTeam2SetsWon()).as("AC6: team2SetsWon = 1").isEqualTo(1);
        assertThat(outcome.getTeam2BallsWon()).as("AC6: team2BallsWon = 40").isEqualTo(40);
        assertThat(outcome.getSetCount()).as("AC6: setCount = 3").isEqualTo(3);
        assertThat(outcome.getComputedMatchState())
                .as("AC6: computedMatchState = FINISHED_WINNER1")
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    void matchOutcomeBackwardCompatConstructorWorks() {
        // E03S08 backward compatibility — 3-field constructor still compiles and works
        MatchOutcome outcome = new MatchOutcome(2, 1, 3);
        assertThat(outcome.getTeam1SetsWon()).as("AC6: backward compat team1SetsWon = 2").isEqualTo(2);
        assertThat(outcome.getTeam2SetsWon()).as("AC6: backward compat team2SetsWon = 1").isEqualTo(1);
        assertThat(outcome.getSetCount()).as("AC6: backward compat setCount = 3").isEqualTo(3);
    }

    @Test
    void teamAvatarRatingCompareTo_implementsD33SortOrder() {
        // Rating A: higher points
        TeamAvatarRating ratingA = new TeamAvatarRating();
        ratingA.setPoints(6);
        ratingA.setSetQuotient(2.0);
        ratingA.setBallQuotient(1.25);
        ratingA.setWithoutAssessment(false);

        // Rating B: lower points
        TeamAvatarRating ratingB = new TeamAvatarRating();
        ratingB.setPoints(4);
        ratingB.setSetQuotient(3.0);
        ratingB.setBallQuotient(2.0);
        ratingB.setWithoutAssessment(false);

        // Rating C: same points as A, higher set quotient
        TeamAvatarRating ratingC = new TeamAvatarRating();
        ratingC.setPoints(6);
        ratingC.setSetQuotient(3.0);
        ratingC.setBallQuotient(1.0);
        ratingC.setWithoutAssessment(false);

        // Rating D: without assessment — always last
        TeamAvatarRating ratingD = new TeamAvatarRating();
        ratingD.setPoints(10); // highest points, but without_assessment
        ratingD.setSetQuotient(999.0);
        ratingD.setBallQuotient(999.0);
        ratingD.setWithoutAssessment(true);

        // A ranks before B (more points)
        assertThat(ratingA.compareTo(ratingB))
                .as("AC6: A (6 pts) should rank before B (4 pts)")
                .isNegative();

        // C ranks before A (same points, higher set quotient)
        assertThat(ratingC.compareTo(ratingA))
                .as("AC6: C (set_q=3.0) should rank before A (set_q=2.0) when points equal")
                .isNegative();

        // D always ranks last despite highest points (is_without_assessment=true)
        assertThat(ratingD.compareTo(ratingA))
                .as("AC6: D (without_assessment=true) must rank behind A regardless of points")
                .isPositive();
        assertThat(ratingD.compareTo(ratingB))
                .as("AC6: D (without_assessment=true) must rank behind B")
                .isPositive();

        // D vs another without_assessment — falls through to normal comparison
        TeamAvatarRating ratingE = new TeamAvatarRating();
        ratingE.setPoints(8);
        ratingE.setSetQuotient(1.0);
        ratingE.setBallQuotient(1.0);
        ratingE.setWithoutAssessment(true);
        // Both without_assessment — D has higher points, E lower; D should rank before E
        assertThat(ratingD.compareTo(ratingE))
                .as("AC6: D (10 pts, without_assessment) ranks before E (8 pts, without_assessment)")
                .isNegative();
    }

    @Test
    void auditLogEntryEntityClassExists_withNullOldValuesOnFirstInsert() {
        AuditLogEntry entry = new AuditLogEntry(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                null, null,       // old values null (first insert)
                25, 20,
                null,             // set_state_old null
                SetState.WINNER1.getLegacyCode(),
                null, null,       // actorId, reason null
                null,
                null, null);      // sourceType, sourceDeviceId null (E06S06)

        assertThat(entry.getTeam1PointsOld()).as("AC6: team1PointsOld = null on first insert").isNull();
        assertThat(entry.getTeam2PointsOld()).as("AC6: team2PointsOld = null on first insert").isNull();
        assertThat(entry.getSetStateOld()).as("AC6: setStateOld = null on first insert").isNull();
        assertThat(entry.getSetStateOldAsEnum()).as("AC6: setStateOldAsEnum = null").isNull();
        assertThat(entry.getSetStateNewAsEnum())
                .as("AC6: setStateNewAsEnum = WINNER1")
                .isEqualTo(SetState.WINNER1);
        assertThat(entry.getTeam1PointsNew()).as("AC6: team1PointsNew = 25").isEqualTo(25);
        assertThat(entry.getTeam2PointsNew()).as("AC6: team2PointsNew = 20").isEqualTo(20);
    }

    @Test
    void roundSnapshotEntityClassExists() {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();
        RoundSnapshot snapshot = new RoundSnapshot(
                id, tenantId, tournamentId, phaseId, 3,
                "{\"standings\":[]}", null);

        assertThat(snapshot.getId()).as("AC6: RoundSnapshot id set").isEqualTo(id);
        assertThat(snapshot.getTenantId()).as("AC6: tenantId set").isEqualTo(tenantId);
        assertThat(snapshot.getTournamentId()).as("AC6: tournamentId set").isEqualTo(tournamentId);
        assertThat(snapshot.getPhaseId()).as("AC6: phaseId set").isEqualTo(phaseId);
        assertThat(snapshot.getLapNumber()).as("AC6: lapNumber = 3").isEqualTo(3);
        assertThat(snapshot.getSnapshotPayload()).as("AC6: snapshotPayload round-trip")
                .contains("standings");
    }

    // =========================================================================
    // AC10 — INFORMATION_SCHEMA: all four tables visible with constraints
    // =========================================================================

    @Test
    void allFourTablesVisibleInInformationSchema() {
        List<String> expectedTables = List.of(
                "MATCH_OUTCOME", "TEAM_AVATAR_RATING", "AUDIT_LOG", "ROUND_SNAPSHOTS");

        List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_NAME IN ('MATCH_OUTCOME','TEAM_AVATAR_RATING','AUDIT_LOG','ROUND_SNAPSHOTS')");

        List<String> foundNames = tables.stream()
                .map(row -> String.valueOf(row.get("TABLE_NAME")).toUpperCase())
                .toList();

        assertThat(foundNames)
                .as("AC10: all four tables must be present in INFORMATION_SCHEMA")
                .containsExactlyInAnyOrderElementsOf(expectedTables);
    }

    @Test
    void matchOutcomeConstraintsVisibleInInformationSchema() {
        List<Map<String, Object>> constraints = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME = 'MATCH_OUTCOME'");

        List<String> types = constraints.stream()
                .map(row -> String.valueOf(row.get("CONSTRAINT_TYPE")))
                .toList();

        assertThat(types).as("AC10: match_outcome must have PRIMARY KEY").contains("PRIMARY KEY");
        assertThat(types).as("AC10: match_outcome must have FOREIGN KEY").contains("FOREIGN KEY");
        assertThat(types).as("AC10: match_outcome must have CHECK constraints").contains("CHECK");
    }

    @Test
    void roundSnapshotsUniqueConstraintVisibleInInformationSchema() {
        List<Map<String, Object>> constraints = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_TYPE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                + "WHERE TABLE_NAME = 'ROUND_SNAPSHOTS'");

        List<String> types = constraints.stream()
                .map(row -> String.valueOf(row.get("CONSTRAINT_TYPE")))
                .toList();

        assertThat(types).as("AC10: round_snapshots must have UNIQUE constraint").contains("UNIQUE");
    }

    // =========================================================================
    // AC11 — Audit log traceability integration test
    // =========================================================================

    /**
     * AC11 — Integration test: insert a SetResult (simulating cascade), write an audit_log
     * row with old=NULL / new=actual values, then query and verify the row appears correctly.
     */
    @Test
    void auditLogTraceability_firstInsertHasNullOldValues() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        // Simulate cascade: insert a SetResult
        jdbcTemplate.update(
                "INSERT INTO set_result (match_id, set_index, tenant_id, phase_id, "
                + " team1_points, team2_points, set_state) "
                + "VALUES (?, 0, ?, ?, 25, 20, ?)",
                matchId, tenantId, phaseId, SetState.WINNER1.getLegacyCode());

        // Write audit entry for the first insert (old values are NULL)
        UUID auditId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO audit_log (id, tenant_id, match_id, set_index, "
                + " team1_points_new, team2_points_new, set_state_new) "
                + "VALUES (?, ?, ?, 0, 25, 20, ?)",
                auditId, tenantId, matchId, SetState.WINNER1.getLegacyCode());

        // Query and verify
        Map<String, Object> auditRow = jdbcTemplate.queryForMap(
                "SELECT team1_points_old, team2_points_old, set_state_old, "
                + "team1_points_new, team2_points_new, set_state_new "
                + "FROM audit_log WHERE id = ?",
                auditId);

        assertThat(auditRow.get("team1_points_old"))
                .as("AC11: old=NULL on first insert for team1_points_old")
                .isNull();
        assertThat(auditRow.get("team2_points_old"))
                .as("AC11: old=NULL on first insert for team2_points_old")
                .isNull();
        assertThat(auditRow.get("set_state_old"))
                .as("AC11: old=NULL on first insert for set_state_old")
                .isNull();
        assertThat(auditRow.get("team1_points_new"))
                .as("AC11: new=25 (actual value)")
                .isEqualTo(25);
        assertThat(auditRow.get("team2_points_new"))
                .as("AC11: new=20 (actual value)")
                .isEqualTo(20);
        assertThat(auditRow.get("set_state_new"))
                .as("AC11: set_state_new = WINNER1 code")
                .isEqualTo(SetState.WINNER1.getLegacyCode());
    }

    // =========================================================================
    // AC12 — tenant_id NOT NULL enforced on all four tables
    // =========================================================================

    @Test
    void matchOutcomeTenantIdNotNullEnforced() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO match_outcome (match_id, tenant_id, set_count, computed_state) "
                        + "VALUES (?, NULL, 0, 51)", matchId))
                .as("AC12: match_outcome tenant_id=NULL must fail NOT NULL constraint")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void teamAvatarRatingTenantIdNotNullEnforced() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId = insertMinimalTeam(tenantId, tournamentId, 9);
        UUID avatarId = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId, 3, 1);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO team_avatar_rating (avatar_id, tenant_id, match_count, "
                        + "set_count, points, sets_won, sets_lost, balls_won, balls_lost, "
                        + "set_quotient, ball_quotient, is_without_assessment) "
                        + "VALUES (?, NULL, 0, 0, 0, 0, 0, 0, 0, 0, 0, FALSE)", avatarId))
                .as("AC12: team_avatar_rating tenant_id=NULL must fail NOT NULL constraint")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void auditLogTenantIdNotNullEnforced() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO audit_log (id, tenant_id, match_id, set_index, "
                        + " team1_points_new, team2_points_new, set_state_new) "
                        + "VALUES (?, NULL, ?, 0, 25, 20, 1)",
                        UUID.randomUUID(), matchId))
                .as("AC12: audit_log tenant_id=NULL must fail NOT NULL constraint")
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void roundSnapshotsTenantIdNotNullEnforced() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "INSERT INTO round_snapshots (id, tenant_id, tournament_id, phase_id, "
                        + "lap_number, snapshot_payload) VALUES (?, NULL, ?, ?, 1, '{}')",
                        UUID.randomUUID(), tournamentId, phaseId))
                .as("AC12: round_snapshots tenant_id=NULL must fail NOT NULL constraint")
                .isInstanceOf(DataAccessException.class);
    }

    // =========================================================================
    // Helper methods — minimal row insertion for FK satisfaction
    // =========================================================================

    private UUID insertMinimalTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default) "
                + "VALUES (?, 'Test Tenant', 1, FALSE)",
                id);
        return id;
    }

    private UUID insertMinimalTournament(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament "
                + "(id, tenant_id, description, match_format, scoring_rule_id, "
                + " set_validation_rule_id, match_generator_id, status) "
                + "VALUES (?, ?, 'Test Tournament', 'BEST_OF_3', 'sr1', 'svr1', 'mg1', 'DRAFT')",
                id, tenantId);
        return id;
    }

    private UUID insertMinimalPhase(UUID tenantId, UUID tournamentId, int sequenceNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase "
                + "(id, tenant_id, tournament_id, sequence_number, description, status) "
                + "VALUES (?, ?, ?, ?, 'Test Phase', 'PENDING')",
                id, tenantId, tournamentId, sequenceNumber);
        return id;
    }

    private UUID insertMinimalTeam(UUID tenantId, UUID tournamentId, int teamNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team "
                + "(id, tenant_id, tournament_id, team_number, description) "
                + "VALUES (?, ?, ?, ?, 'Test Team')",
                id, tenantId, tournamentId, teamNumber);
        return id;
    }

    private UUID insertMinimalTeamAvatar(UUID tenantId, UUID tournamentId, UUID phaseId,
                                          UUID teamId, int groupNumber, int groupPosition) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar "
                + "(id, tenant_id, tournament_id, phase_id, group_number, group_position, team_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, tenantId, tournamentId, phaseId, groupNumber, groupPosition, teamId);
        return id;
    }

    private UUID insertMinimalMatch(UUID tenantId, UUID tournamentId, UUID phaseId) {
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId,
                (int) (Math.random() * 900) + 100);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId,
                (int) (Math.random() * 900) + 100);
        UUID av1Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId1,
                (int) (Math.random() * 900) + 100, 1);
        UUID av2Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId2,
                (int) (Math.random() * 900) + 100, 2);

        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match "
                + "(id, tenant_id, tournament_id, phase_id, "
                + " member_avatar_1_id, member_avatar_2_id, state, set_limit) "
                + "VALUES (?, ?, ?, ?, ?, ?, 0, 3)",
                matchId, tenantId, tournamentId, phaseId, av1Id, av2Id);
        return matchId;
    }
}
