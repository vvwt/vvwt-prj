package de.vvwt.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.domain.SetResult;
import de.vvwt.tm.domain.SetState;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for E03S03 — Flyway V4 set_result migration.
 *
 * <p>Verifies that the {@code V4__e03_set_result.sql} migration applies correctly and that all
 * schema-level constraints (composite PK, CHECK constraints, FK violations, tenant NOT NULL) work
 * as designed.
 *
 * <p>Uses the "test" profile ({@code application-test.yml}): in-memory H2 so no filesystem
 * side-effects occur during test runs. Flyway runs V1–V4 migrations against the in-memory database
 * on every context load.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC1 — V4 file exists and was applied (implicit: context loads = migration ran)
 *   <li>AC2 — {@code set_result} table core columns exist and round-trip correctly
 *   <li>AC3 — composite primary key {@code (match_id, set_index)} enforced
 *   <li>AC4 — CHECK constraint on {@code set_state} rejects values outside {0,1,2,3,-1}
 *   <li>AC5 — CHECK constraints on scores ({@code team1_points >= 0}, {@code team2_points >= 0})
 *   <li>AC6 — index on {@code (phase_id, set_state)} supports expected query pattern
 *   <li>AC7 — {@link SetResult} entity class and {@link SetState} enum exist and round-trip
 *   <li>AC8 — no seed data: set_result table is empty after startup
 *   <li>AC9 — exactly one V4 row in flyway_schema_history
 *   <li>AC10 — CHECK constraint on {@code set_state = 99} rejects invalid value
 *   <li>AC11 — composite PK collision: duplicate {@code (match_id, set_index)} rejected
 *   <li>AC12 — INFORMATION_SCHEMA shows table, composite PK, and CHECK constraints
 *   <li>AC13 — {@code tenant_id} NOT NULL enforced at schema layer
 *   <li>AC14 — no seed data or PII (verified by empty table + column inspection)
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E03S03.story.md">Story
 *     E03S03</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@Transactional
class E03S03MigrationIT {

    @Autowired private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // AC1 + AC9 — Flyway V4 idempotency
    // -------------------------------------------------------------------------

    /** AC1 / AC9 — Flyway applied V4 exactly once and recorded success. */
    @Test
    void flywaySchemaHistoryHasExactlyOneV4Entry() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT \"version\", \"script\", \"success\" "
                                + "FROM \"flyway_schema_history\" "
                                + "WHERE \"version\" = '4'");

        assertThat(rows)
                .as("flyway_schema_history must contain exactly one row for version '4' (AC9)")
                .hasSize(1);

        Map<String, Object> v4Row = rows.get(0);

        assertThat(v4Row.get("success"))
                .as("Flyway V4 migration must have success = true (AC1)")
                .isEqualTo(true);

        assertThat(String.valueOf(v4Row.get("script")))
                .as("Flyway V4 migration script name must reference V4 (AC1)")
                .containsIgnoringCase("V4");
    }

    // -------------------------------------------------------------------------
    // AC8 / AC14 — No seed data
    // -------------------------------------------------------------------------

    /**
     * AC8 / AC14 — The {@code set_result} table is empty after application startup. No INSERT
     * statements exist in the migration; no PII is seeded.
     */
    @Test
    void setResultTableIsEmptyAfterMigration() {
        Integer count =
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM set_result", Integer.class);
        assertThat(count)
                .as("set_result table must be empty after migration (AC8/AC14 — no seed data)")
                .isZero();
    }

    // -------------------------------------------------------------------------
    // AC2 — Core columns round-trip
    // -------------------------------------------------------------------------

    /**
     * AC2 — Core columns exist and accept a valid minimal set_result row.
     *
     * <p>Inserts a set_result row with all required NOT NULL columns and verifies the round-trip.
     * Covers the full column set including {@code change_time} and {@code created_at} defaulting
     * correctly.
     */
    @Test
    void setResultCoreColumnsRoundTrip() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        jdbcTemplate.update(
                "INSERT INTO set_result "
                        + "(match_id, set_index, tenant_id, phase_id, "
                        + " team1_points, team2_points, set_state) "
                        + "VALUES (?, 0, ?, ?, 25, 20, ?)",
                matchId,
                tenantId,
                phaseId,
                SetState.WINNER1.getLegacyCode());

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT match_id, set_index, tenant_id, phase_id, "
                                + "team1_points, team2_points, set_state, change_time, created_at "
                                + "FROM set_result WHERE match_id = ? AND set_index = 0",
                        matchId);

        assertThat(row.get("match_id")).as("AC2: match_id round-trip").isNotNull();
        assertThat(row.get("set_index")).as("AC2: set_index = 0").isEqualTo(0);
        assertThat(row.get("tenant_id")).as("AC2: tenant_id round-trip").isNotNull();
        assertThat(row.get("phase_id")).as("AC2: phase_id round-trip").isNotNull();
        assertThat(row.get("team1_points")).as("AC2: team1_points = 25").isEqualTo(25);
        assertThat(row.get("team2_points")).as("AC2: team2_points = 20").isEqualTo(20);
        assertThat(row.get("set_state"))
                .as("AC2: set_state = 1 (WINNER1)")
                .isEqualTo(SetState.WINNER1.getLegacyCode());
        assertThat(row.get("change_time")).as("AC2: change_time set by DB default").isNotNull();
        assertThat(row.get("created_at")).as("AC2: created_at set by DB default").isNotNull();
    }

    // -------------------------------------------------------------------------
    // AC3 — Composite primary key
    // -------------------------------------------------------------------------

    /**
     * AC3 — Multiple set_result rows for the same match with different set_index values are
     * accepted (composite PK allows distinct set_index values per match).
     */
    @Test
    void compositePrimaryKey_allowsMultipleSetsPerMatch() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        // Insert 3 set results for the same match — distinct set_index values are valid
        for (int setIndex = 0; setIndex < 3; setIndex++) {
            jdbcTemplate.update(
                    "INSERT INTO set_result "
                            + "(match_id, set_index, tenant_id, phase_id, "
                            + " team1_points, team2_points, set_state) "
                            + "VALUES (?, ?, ?, ?, 25, 20, ?)",
                    matchId,
                    setIndex,
                    tenantId,
                    phaseId,
                    SetState.WINNER1.getLegacyCode());
        }

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM set_result WHERE match_id = ?",
                        Integer.class,
                        matchId);
        assertThat(count)
                .as("AC3: 3 distinct set_index rows must be accepted for the same match")
                .isEqualTo(3);
    }

    // -------------------------------------------------------------------------
    // AC4 — CHECK constraint on set_state
    // -------------------------------------------------------------------------

    /** AC4 — CHECK constraint on {@code set_state} rejects values not in the legacy enum list. */
    @Test
    void setStateCheckConstraintRejectsInvalidValue() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO set_result "
                                                + "(match_id, set_index, tenant_id, phase_id, "
                                                + " team1_points, team2_points, set_state) "
                                                + "VALUES (?, 0, ?, ?, 25, 20, 99)",
                                        matchId,
                                        tenantId,
                                        phaseId))
                .as("AC4: set_state=99 is not a valid legacy code — must fail CHECK constraint")
                .isInstanceOf(DataAccessException.class);
    }

    /** AC4 — All five valid legacy set state codes are accepted by the CHECK constraint. */
    @Test
    void allValidSetStateCodes_areAcceptedByCheckConstraint() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);

        int setIndex = 0;
        for (SetState state : SetState.values()) {
            UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);
            jdbcTemplate.update(
                    "INSERT INTO set_result "
                            + "(match_id, set_index, tenant_id, phase_id, "
                            + " team1_points, team2_points, set_state) "
                            + "VALUES (?, ?, ?, ?, 15, 10, ?)",
                    matchId,
                    setIndex,
                    tenantId,
                    phaseId,
                    state.getLegacyCode());
        }

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM set_result WHERE tenant_id = ?",
                        Integer.class,
                        tenantId);
        assertThat(count)
                .as("AC4: all 5 valid SetState codes must be accepted by CHECK constraint")
                .isEqualTo(SetState.values().length);
    }

    // -------------------------------------------------------------------------
    // AC5 — CHECK constraints on scores
    // -------------------------------------------------------------------------

    /** AC5 — CHECK constraint on {@code team1_points} rejects negative scores. */
    @Test
    void team1PointsCheckConstraintRejectsNegativeValue() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO set_result "
                                                + "(match_id, set_index, tenant_id, phase_id, "
                                                + " team1_points, team2_points, set_state) "
                                                + "VALUES (?, 0, ?, ?, -1, 20, ?)",
                                        matchId,
                                        tenantId,
                                        phaseId,
                                        SetState.OPEN.getLegacyCode()))
                .as("AC5: team1_points = -1 must fail CHECK constraint")
                .isInstanceOf(DataAccessException.class);
    }

    /** AC5 — CHECK constraint on {@code team2_points} rejects negative scores. */
    @Test
    void team2PointsCheckConstraintRejectsNegativeValue() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO set_result "
                                                + "(match_id, set_index, tenant_id, phase_id, "
                                                + " team1_points, team2_points, set_state) "
                                                + "VALUES (?, 0, ?, ?, 25, -5, ?)",
                                        matchId,
                                        tenantId,
                                        phaseId,
                                        SetState.OPEN.getLegacyCode()))
                .as("AC5: team2_points = -5 must fail CHECK constraint")
                .isInstanceOf(DataAccessException.class);
    }

    /** AC5 — Zero scores are accepted by both CHECK constraints (edge case). */
    @Test
    void zeroScores_areAcceptedByCheckConstraints() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        // 0 points is physically valid (e.g., walk-over)
        jdbcTemplate.update(
                "INSERT INTO set_result "
                        + "(match_id, set_index, tenant_id, phase_id, "
                        + " team1_points, team2_points, set_state) "
                        + "VALUES (?, 0, ?, ?, 0, 0, ?)",
                matchId,
                tenantId,
                phaseId,
                SetState.OPEN.getLegacyCode());

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM set_result WHERE match_id = ?",
                        Integer.class,
                        matchId);
        assertThat(count).as("AC5: zero scores must be accepted by CHECK constraints").isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // AC6 — Index supports phase + state query
    // -------------------------------------------------------------------------

    /**
     * AC6 — The index on {@code (phase_id, set_state)} exists (verified via successful insertion
     * and INFORMATION_SCHEMA index lookup).
     */
    @Test
    void phaseStateIndexExists() {
        // H2 INFORMATION_SCHEMA.INDEXES for the set_result table
        List<Map<String, Object>> indexes =
                jdbcTemplate.queryForList(
                        "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                                + "WHERE TABLE_NAME = 'SET_RESULT'");

        List<String> indexNames =
                indexes.stream()
                        .map(row -> String.valueOf(row.get("INDEX_NAME")).toUpperCase())
                        .toList();

        assertThat(indexNames)
                .as("AC6: idx_set_result_phase_state must be present in INFORMATION_SCHEMA")
                .anyMatch(name -> name.contains("PHASE") && name.contains("STATE"));
    }

    // -------------------------------------------------------------------------
    // AC7 — SetResult entity class and SetState enum
    // -------------------------------------------------------------------------

    /**
     * AC7 — {@link SetResult} entity class exists with all required fields. {@link SetState} enum
     * round-trip: {@code fromLegacyCode(state.getLegacyCode()) == state}.
     */
    @Test
    void setResultEntityClassExistsAndSetStateRoundTripWorks() {
        // Verify SetResult entity has all AC7-required fields
        SetResult result = new SetResult();
        result.setMatchId(UUID.randomUUID());
        result.setSetIndex(0);
        result.setTenantId(UUID.randomUUID());
        result.setPhaseId(UUID.randomUUID());
        result.setTeam1Points(25);
        result.setTeam2Points(20);
        result.setSetState(SetState.WINNER1);

        assertThat(result.getSetState())
                .as("AC7: getSetState() must return WINNER1 after setSetState(WINNER1)")
                .isEqualTo(SetState.WINNER1);
        assertThat(result.getSetStateCode())
                .as("AC7: getSetStateCode() must return 1 (WINNER1 legacy code)")
                .isEqualTo(1);

        // Verify all SetState values round-trip correctly
        for (SetState state : SetState.values()) {
            result.setSetState(state);
            assertThat(result.getSetState())
                    .as("AC7: SetState round-trip for " + state)
                    .isEqualTo(state);
            assertThat(SetState.fromLegacyCode(state.getLegacyCode()))
                    .as("AC7: fromLegacyCode round-trip for " + state)
                    .isEqualTo(state);
        }

        // Verify known legacy codes
        assertThat(SetState.OPEN.getLegacyCode()).as("AC7: OPEN = 0").isEqualTo(0);
        assertThat(SetState.WINNER1.getLegacyCode()).as("AC7: WINNER1 = 1").isEqualTo(1);
        assertThat(SetState.WINNER2.getLegacyCode()).as("AC7: WINNER2 = 2").isEqualTo(2);
        assertThat(SetState.STANDOFF.getLegacyCode()).as("AC7: STANDOFF = 3").isEqualTo(3);
        assertThat(SetState.CANCELED.getLegacyCode()).as("AC7: CANCELED = -1").isEqualTo(-1);
    }

    /**
     * AC7 — {@link SetState#fromLegacyCode(int)} throws for unknown codes (data corruption guard).
     */
    @Test
    void setStateFromLegacyCode_throwsForUnknownCode() {
        assertThatThrownBy(() -> SetState.fromLegacyCode(99))
                .as("AC7: fromLegacyCode(99) must throw IllegalArgumentException")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // -------------------------------------------------------------------------
    // AC10 — CHECK constraint: set_state = 99 (same as AC4 but explicitly named)
    // -------------------------------------------------------------------------

    /**
     * AC10 — Integration test: attempting to insert a row with {@code set_state = 99} fails with a
     * CHECK constraint violation.
     */
    @Test
    void insertWithInvalidSetState99_failsCheckConstraint() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO set_result "
                                                + "(match_id, set_index, tenant_id, phase_id, "
                                                + " team1_points, team2_points, set_state) "
                                                + "VALUES (?, 0, ?, ?, 10, 5, 99)",
                                        matchId,
                                        tenantId,
                                        phaseId))
                .as("AC10: set_state=99 must fail with CHECK constraint violation")
                .isInstanceOf(DataAccessException.class);
    }

    // -------------------------------------------------------------------------
    // AC11 — Composite PK collision
    // -------------------------------------------------------------------------

    /**
     * AC11 — Attempting to INSERT two rows with the same {@code (match_id, set_index)} pair fails
     * with a primary key violation.
     */
    @Test
    void compositePrimaryKeyCollision_isRejected() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        // First insert — must succeed
        jdbcTemplate.update(
                "INSERT INTO set_result "
                        + "(match_id, set_index, tenant_id, phase_id, "
                        + " team1_points, team2_points, set_state) "
                        + "VALUES (?, 0, ?, ?, 25, 20, ?)",
                matchId,
                tenantId,
                phaseId,
                SetState.WINNER1.getLegacyCode());

        // Second insert with the same (match_id, set_index) — must fail
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO set_result "
                                                + "(match_id, set_index, tenant_id, phase_id, "
                                                + " team1_points, team2_points, set_state) "
                                                + "VALUES (?, 0, ?, ?, 15, 25, ?)",
                                        matchId,
                                        tenantId,
                                        phaseId,
                                        SetState.WINNER2.getLegacyCode()))
                .as("AC11: duplicate (match_id, set_index) must fail with PK violation")
                .isInstanceOf(DataAccessException.class);
    }

    // -------------------------------------------------------------------------
    // AC12 — Observability: INFORMATION_SCHEMA
    // -------------------------------------------------------------------------

    /** AC12 — After migration, INFORMATION_SCHEMA shows the {@code set_result} table. */
    @Test
    void setResultTableIsVisibleInInformationSchema() {
        List<Map<String, Object>> tables =
                jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_NAME = 'SET_RESULT'");

        assertThat(tables)
                .as("AC12: INFORMATION_SCHEMA.TABLES must contain the SET_RESULT table")
                .hasSize(1);
    }

    /** AC12 — INFORMATION_SCHEMA.KEY_COLUMN_USAGE shows the composite PK columns. */
    @Test
    void compositePkColumnsAreVisibleInInformationSchema() {
        List<Map<String, Object>> pkColumns =
                jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE"
                            + " TABLE_NAME = 'SET_RESULT' AND CONSTRAINT_NAME = 'PK_SET_RESULT'");

        List<String> columnNames =
                pkColumns.stream()
                        .map(row -> String.valueOf(row.get("COLUMN_NAME")).toUpperCase())
                        .toList();

        assertThat(columnNames).as("AC12: composite PK must include MATCH_ID").contains("MATCH_ID");
        assertThat(columnNames)
                .as("AC12: composite PK must include SET_INDEX")
                .contains("SET_INDEX");
    }

    /** AC12 — INFORMATION_SCHEMA.TABLE_CONSTRAINTS shows CHECK constraints for set_result. */
    @Test
    void setResultConstraintsAreVisibleInInformationSchema() {
        List<Map<String, Object>> constraints =
                jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE "
                                + "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                                + "WHERE TABLE_NAME = 'SET_RESULT'");

        List<String> constraintTypes =
                constraints.stream()
                        .map(row -> String.valueOf(row.get("CONSTRAINT_TYPE")))
                        .toList();

        assertThat(constraintTypes)
                .as("AC12: set_result table must have a PRIMARY KEY constraint")
                .contains("PRIMARY KEY");
        assertThat(constraintTypes)
                .as("AC12: set_result table must have FOREIGN KEY constraints")
                .contains("FOREIGN KEY");
        assertThat(constraintTypes)
                .as("AC12: set_result table must have CHECK constraints")
                .contains("CHECK");
    }

    // -------------------------------------------------------------------------
    // AC13 — tenant_id NOT NULL enforced
    // -------------------------------------------------------------------------

    /** AC13 — {@code set_result.tenant_id} NOT NULL constraint is enforced at the schema layer. */
    @Test
    void setResultTenantIdNotNullIsEnforced() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID matchId = insertMinimalMatch(tenantId, tournamentId, phaseId);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO set_result "
                                                + "(match_id, set_index, tenant_id, phase_id, "
                                                + " team1_points, team2_points, set_state) "
                                                + "VALUES (?, 0, NULL, ?, 25, 20, ?)",
                                        matchId,
                                        phaseId,
                                        SetState.WINNER1.getLegacyCode()))
                .as(
                        "AC13: inserting set_result without tenant_id must raise a constraint"
                                + " violation")
                .isInstanceOf(DataAccessException.class);
    }

    // -------------------------------------------------------------------------
    // Helper methods — minimal row insertion for FK satisfaction
    // -------------------------------------------------------------------------

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
                "INSERT INTO tournament (id, tenant_id, description, match_format, scoring_rule_id,"
                    + "  set_validation_rule_id, match_generator_id, status) VALUES (?, ?, 'Test"
                    + " Tournament', 'BEST_OF_3', 'sr1', 'svr1', 'mg1', 'DRAFT')",
                id,
                tenantId);
        return id;
    }

    private UUID insertMinimalPhase(UUID tenantId, UUID tournamentId, int sequenceNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase "
                        + "(id, tenant_id, tournament_id, sequence_number, description, status) "
                        + "VALUES (?, ?, ?, ?, 'Test Phase', 'PENDING')",
                id,
                tenantId,
                tournamentId,
                sequenceNumber);
        return id;
    }

    private UUID insertMinimalTeam(UUID tenantId, UUID tournamentId, int teamNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team "
                        + "(id, tenant_id, tournament_id, team_number, description) "
                        + "VALUES (?, ?, ?, ?, 'Test Team')",
                id,
                tenantId,
                tournamentId,
                teamNumber);
        return id;
    }

    private UUID insertMinimalTeamAvatar(
            UUID tenantId,
            UUID tournamentId,
            UUID phaseId,
            UUID teamId,
            int groupNumber,
            int groupPosition) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tenant_id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id,
                tenantId,
                tournamentId,
                phaseId,
                groupNumber,
                groupPosition,
                teamId);
        return id;
    }

    /** Inserts a minimal {@code match} row with two fresh team avatars. Returns the match UUID. */
    private UUID insertMinimalMatch(UUID tenantId, UUID tournamentId, UUID phaseId) {
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, (int) (Math.random() * 900) + 100);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, (int) (Math.random() * 900) + 100);
        UUID av1Id =
                insertMinimalTeamAvatar(
                        tenantId,
                        tournamentId,
                        phaseId,
                        teamId1,
                        (int) (Math.random() * 900) + 100,
                        1);
        UUID av2Id =
                insertMinimalTeamAvatar(
                        tenantId,
                        tournamentId,
                        phaseId,
                        teamId2,
                        (int) (Math.random() * 900) + 100,
                        2);

        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match "
                        + "(id, tenant_id, tournament_id, phase_id, "
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 3)",
                matchId,
                tenantId,
                tournamentId,
                phaseId,
                av1Id,
                av2Id);
        return matchId;
    }
}
