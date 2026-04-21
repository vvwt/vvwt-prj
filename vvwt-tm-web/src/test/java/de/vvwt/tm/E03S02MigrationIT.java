package de.vvwt.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchState;
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
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for E03S02 — Flyway V3 match migration.
 *
 * <p>Verifies that the {@code V3__e03_match.sql} migration applies correctly and that all
 * schema-level constraints (CHECK on state, FK violations, tenant NOT NULL) work as designed.
 *
 * <p>Uses the "test" profile ({@code application-test.yml}): in-memory H2 so no filesystem
 * side-effects occur during test runs. Flyway runs V1, V2, and V3 migrations against the in-memory
 * database on every context load.
 *
 * <p>Acceptance criteria covered:
 *
 * <ul>
 *   <li>AC1 — V3 file exists and was applied (implicit: context loads = migration ran)
 *   <li>AC2 — {@code match} table core columns exist and round-trip correctly
 *   <li>AC3 — nullable slot coordinates ({@code lap_number}, {@code field_number}) accepted
 *   <li>AC4 — referee columns ({@code referee_team_id}, {@code referee_description}, {@code
 *       referee_preference_config}) are nullable
 *   <li>AC5 — CHECK constraint on {@code state} rejects invalid values
 *   <li>AC6 — indexes exist (indirectly verified: context load and queries succeed)
 *   <li>AC7 — {@link Match} entity class exists; {@link MatchState} round-trip works
 *   <li>AC8 — no seed data: match table is empty after startup
 *   <li>AC9 — exactly one V3 row in flyway_schema_history
 *   <li>AC10 — FK violation on invalid phase_id is rejected
 *   <li>AC11 — INFORMATION_SCHEMA shows match table and constraints
 *   <li>AC12 — tenant_id NOT NULL enforced at schema layer
 *   <li>AC13 — no secrets or PII in migration (code review; test verifies expected columns only)
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E03S02.story.md">Story
 *     E03S02</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e03s02migdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@Transactional
class E03S02MigrationIT {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @BeforeTransaction
    void bindTenantBeforeTransaction() {
        tenantContextBinder.bindDefaultTenant();
    }

    @AfterTransaction
    void unbindTenantAfterTransaction() {
        tenantContextBinder.unbind();
    }

    /**
     * E14S11: clean up any data left in the per-tenant routing DB from previous test runs. Runs
     * inside the test transaction and is rolled back after each test.
     */
    @org.junit.jupiter.api.BeforeEach
    void cleanUpPerTenantDb() {
        jdbcTemplate.update("DELETE FROM set_result");
        jdbcTemplate.update("DELETE FROM match_outcome");
        jdbcTemplate.update("DELETE FROM audit_log");
        jdbcTemplate.update("DELETE FROM match");
        jdbcTemplate.update("DELETE FROM team_avatar_rating");
        jdbcTemplate.update("DELETE FROM team_avatar");
        jdbcTemplate.update("DELETE FROM phase");
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM activity_types");
        jdbcTemplate.update("DELETE FROM tournament");
    }

    // -------------------------------------------------------------------------
    // AC1 + AC9 — Flyway V3 idempotency
    // -------------------------------------------------------------------------

    /**
     * AC1 / AC9 — Flyway applied V3 exactly once and recorded success.
     *
     * <p>Queries {@code flyway_schema_history} for version '3'. Verifies:
     *
     * <ul>
     *   <li>Exactly one row (idempotency: AC9)
     *   <li>{@code success = true} (AC1 — migration ran and succeeded)
     *   <li>Script name references {@code V3} (AC1 — correct file)
     * </ul>
     */
    @Test
    void flywaySchemaHistoryHasExactlyOneV3Entry() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT \"version\", \"script\", \"success\" "
                                + "FROM \"flyway_schema_history\" "
                                + "WHERE \"version\" = '3'");

        assertThat(rows)
                .as("flyway_schema_history must contain exactly one row for version '3' (AC9)")
                .hasSize(1);

        Map<String, Object> v3Row = rows.get(0);

        assertThat(v3Row.get("success"))
                .as("Flyway V3 migration must have success = true (AC1)")
                .isEqualTo(true);

        assertThat(String.valueOf(v3Row.get("script")))
                .as("Flyway V3 migration script name must reference V3 (AC1)")
                .containsIgnoringCase("V3");
    }

    // -------------------------------------------------------------------------
    // AC8 — No seed data in migration
    // -------------------------------------------------------------------------

    /**
     * AC8 — The {@code match} table is empty after application startup.
     *
     * <p>The migration contains no INSERT statements. The table must be empty at boot time (before
     * any test-data insertions).
     */
    @Test
    void matchTableIsEmptyAfterMigration() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM match", Integer.class);
        assertThat(count)
                .as("match table must be empty after migration (AC8 — no seed data)")
                .isZero();
    }

    // -------------------------------------------------------------------------
    // AC2 — Core columns round-trip
    // -------------------------------------------------------------------------

    /**
     * AC2 — Core columns exist and accept a valid minimal match row.
     *
     * <p>Inserts a match with all required NOT NULL columns and verifies it persists correctly.
     * Proves the column set and FK wiring for the minimal case.
     */
    @Test
    void matchCoreColumnsRoundTrip() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, 1);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, 2);
        UUID av1Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId1, 1, 1);
        UUID av2Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId2, 1, 2);

        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match "
                        + "(id, tenant_id, tournament_id, phase_id, "
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tenantId,
                tournamentId,
                phaseId,
                av1Id,
                av2Id,
                MatchState.OPEN.getLegacyCode(),
                3);

        Map<String, Object> row =
                jdbcTemplate.queryForMap(
                        "SELECT id, tenant_id, state, set_limit, lap_number, field_number "
                                + "FROM match WHERE id = ?",
                        matchId);

        assertThat(row.get("id")).as("AC2: id round-trip").isNotNull();
        assertThat(row.get("tenant_id")).as("AC2: tenant_id round-trip").isNotNull();
        assertThat(row.get("state"))
                .as("AC2: state = 0 (OPEN)")
                .isEqualTo(MatchState.OPEN.getLegacyCode());
        assertThat(row.get("set_limit")).as("AC2: set_limit = 3").isEqualTo(3);
        assertThat(row.get("lap_number")).as("AC3: lap_number is NULL when not set").isNull();
        assertThat(row.get("field_number")).as("AC3: field_number is NULL when not set").isNull();
    }

    // -------------------------------------------------------------------------
    // AC3 — Nullable slot coordinates
    // -------------------------------------------------------------------------

    /**
     * AC3 — Slot coordinates ({@code lap_number}, {@code field_number}) accept NULL at insert and
     * accept non-NULL values after UPDATE (simulating slot-optimization).
     */
    @Test
    void nullableSlotCoordinatesAcceptNullThenFilledByUpdate() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, 1);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, 2);
        UUID av1Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId1, 1, 1);
        UUID av2Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId2, 1, 2);

        UUID matchId =
                insertMinimalMatch(
                        tenantId,
                        tournamentId,
                        phaseId,
                        av1Id,
                        av2Id,
                        MatchState.OPEN.getLegacyCode());

        // Verify NULL at insert (AC3)
        Map<String, Object> before =
                jdbcTemplate.queryForMap(
                        "SELECT lap_number, field_number FROM match WHERE id = ?", matchId);
        assertThat(before.get("lap_number"))
                .as("AC3: lap_number must be NULL after initial insert")
                .isNull();
        assertThat(before.get("field_number"))
                .as("AC3: field_number must be NULL after initial insert")
                .isNull();

        // Simulate slot-optimization filling coordinates
        jdbcTemplate.update(
                "UPDATE match SET lap_number = 2, field_number = 5 WHERE id = ?", matchId);

        Map<String, Object> after =
                jdbcTemplate.queryForMap(
                        "SELECT lap_number, field_number FROM match WHERE id = ?", matchId);
        assertThat(after.get("lap_number")).as("AC3: lap_number filled by slot-opt").isEqualTo(2);
        assertThat(after.get("field_number"))
                .as("AC3: field_number filled by slot-opt")
                .isEqualTo(5);
    }

    // -------------------------------------------------------------------------
    // AC4 — Referee columns accept NULL
    // -------------------------------------------------------------------------

    /**
     * AC4 — All three referee columns are nullable and accept non-null values.
     *
     * <p>Inserts a match without referee columns (all null), then updates them — simulating what
     * the RefereeAssigner (E03S10) will do.
     */
    @Test
    void refereeColumnsAreNullableAndAcceptValues() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, 1);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, 2);
        UUID teamId3 = insertMinimalTeam(tenantId, tournamentId, 3); // referee team
        UUID av1Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId1, 1, 1);
        UUID av2Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId2, 1, 2);

        UUID matchId =
                insertMinimalMatch(
                        tenantId,
                        tournamentId,
                        phaseId,
                        av1Id,
                        av2Id,
                        MatchState.OPEN.getLegacyCode());

        // Verify all NULL initially (AC4)
        Map<String, Object> before =
                jdbcTemplate.queryForMap(
                        "SELECT referee_team_id, referee_description, referee_preference_config "
                                + "FROM match WHERE id = ?",
                        matchId);
        assertThat(before.get("referee_team_id"))
                .as("AC4: referee_team_id null initially")
                .isNull();
        assertThat(before.get("referee_description"))
                .as("AC4: referee_description null initially")
                .isNull();
        assertThat(before.get("referee_preference_config"))
                .as("AC4: referee_preference_config null initially")
                .isNull();

        // Simulate RefereeAssigner setting referee data (AC4)
        jdbcTemplate.update(
                "UPDATE match SET referee_team_id = ?, "
                        + "referee_description = 'Auto-assigned', "
                        + "referee_preference_config = '{\"prefer_same_club\": false}' "
                        + "WHERE id = ?",
                teamId3,
                matchId);

        Map<String, Object> after =
                jdbcTemplate.queryForMap(
                        "SELECT referee_team_id, referee_description, referee_preference_config "
                                + "FROM match WHERE id = ?",
                        matchId);
        assertThat(after.get("referee_team_id"))
                .as("AC4: referee_team_id set by RefereeAssigner")
                .isNotNull();
        assertThat(String.valueOf(after.get("referee_description")))
                .as("AC4: referee_description set")
                .isEqualTo("Auto-assigned");
        assertThat(String.valueOf(after.get("referee_preference_config")))
                .as("AC4: referee_preference_config set")
                .contains("prefer_same_club");
    }

    // -------------------------------------------------------------------------
    // AC5 — CHECK constraint on state enum
    // -------------------------------------------------------------------------

    /**
     * AC5 — CHECK constraint on {@code state} rejects values not in the legacy enum list.
     *
     * <p>Attempts to insert a match with {@code state = 99} (not in the set {0, 10, 30, 35, 50, 51,
     * 52, -10}). Expects a constraint violation.
     *
     * <p>Then verifies that all eight valid state codes are accepted.
     */
    @Test
    void stateCheckConstraintRejectsInvalidValues() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, 1);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, 2);
        UUID av1Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId1, 1, 1);
        UUID av2Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId2, 1, 2);

        // Invalid state 99 must be rejected (AC5)
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                                                + "  member_avatar_1_id, member_avatar_2_id, state,"
                                                + " set_limit) VALUES (?, ?, ?, ?, ?, ?, 99, 3)",
                                        UUID.randomUUID(),
                                        tenantId,
                                        tournamentId,
                                        phaseId,
                                        av1Id,
                                        av2Id))
                .as("AC5: state=99 is not a valid legacy code — must fail CHECK constraint")
                .isInstanceOf(DataAccessException.class);
    }

    /** AC5 — All eight valid legacy state codes are accepted by the CHECK constraint. */
    @Test
    void allValidStateCodes_areAcceptedByCheckConstraint() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);

        int insertIndex = 0;
        for (MatchState state : MatchState.values()) {
            UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, ++insertIndex * 10);
            UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, ++insertIndex * 10);
            UUID av1Id =
                    insertMinimalTeamAvatar(
                            tenantId, tournamentId, phaseId, teamId1, insertIndex, 1);
            UUID av2Id =
                    insertMinimalTeamAvatar(
                            tenantId, tournamentId, phaseId, teamId2, insertIndex, 2);

            final int legacyCode = state.getLegacyCode();
            jdbcTemplate.update(
                    "INSERT INTO match "
                            + "(id, tenant_id, tournament_id, phase_id, "
                            + " member_avatar_1_id, member_avatar_2_id, state, set_limit) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)",
                    UUID.randomUUID(),
                    tenantId,
                    tournamentId,
                    phaseId,
                    av1Id,
                    av2Id,
                    legacyCode);
        }

        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE tournament_id = ?",
                        Integer.class,
                        tournamentId);
        assertThat(count)
                .as("AC5: all 8 valid MatchState codes must be accepted by CHECK constraint")
                .isEqualTo(MatchState.values().length);
    }

    // -------------------------------------------------------------------------
    // AC7 — Match entity class and MatchState enum round-trip
    // -------------------------------------------------------------------------

    /**
     * AC7 — {@link Match} entity class exists with all required fields. {@link MatchState} enum
     * round-trip: {@code fromLegacyCode(state.getLegacyCode()) == state}.
     */
    @Test
    void matchEntityClassExistsAndMatchStateRoundTripWorks() {
        // Verify Match entity has all AC7-required fields by constructing it
        Match match = new Match();
        match.setId(UUID.randomUUID());
        match.setTenantId(UUID.randomUUID());
        match.setTournamentId(UUID.randomUUID());
        match.setPhaseId(UUID.randomUUID());
        match.setMemberAvatar1Id(UUID.randomUUID());
        match.setMemberAvatar2Id(UUID.randomUUID());
        match.setMatchState(MatchState.OPEN);
        match.setSetLimit(3);
        match.setLapNumber(null); // nullable AC3
        match.setFieldNumber(null); // nullable AC3
        match.setRefereeTeamId(null); // nullable AC4

        assertThat(match.getMatchState())
                .as("AC7: getMatchState() must return OPEN after setMatchState(OPEN)")
                .isEqualTo(MatchState.OPEN);
        assertThat(match.getState())
                .as("AC7: getState() must return 0 (OPEN legacy code)")
                .isEqualTo(0);

        // Verify all MatchState values round-trip correctly
        for (MatchState state : MatchState.values()) {
            match.setMatchState(state);
            assertThat(match.getMatchState())
                    .as("AC7: MatchState round-trip for " + state)
                    .isEqualTo(state);
            assertThat(MatchState.fromLegacyCode(state.getLegacyCode()))
                    .as("AC7: fromLegacyCode round-trip for " + state)
                    .isEqualTo(state);
        }

        // Verify setLimit field is present (AC7)
        match.setSetLimit(5);
        assertThat(match.getSetLimit()).as("AC7: setLimit field").isEqualTo(5);
    }

    // -------------------------------------------------------------------------
    // AC10 — FK violation: invalid phase_id rejected
    // -------------------------------------------------------------------------

    /** AC10 — Inserting a match with a non-existent {@code phase_id} fails with FK violation. */
    @Test
    void matchPhaseIdFkViolationIsRejected() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, 1);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, 2);
        UUID av1Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId1, 1, 1);
        UUID av2Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId2, 1, 2);

        UUID nonExistentPhaseId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                                                + "  member_avatar_1_id, member_avatar_2_id, state,"
                                                + " set_limit) VALUES (?, ?, ?, ?, ?, ?, ?, 3)",
                                        UUID.randomUUID(),
                                        tenantId,
                                        tournamentId,
                                        nonExistentPhaseId,
                                        av1Id,
                                        av2Id,
                                        MatchState.OPEN.getLegacyCode()))
                .as("AC10: inserting match with non-existent phase_id must raise FK violation")
                .isInstanceOf(DataAccessException.class);
    }

    // -------------------------------------------------------------------------
    // AC11 — Observability: INFORMATION_SCHEMA shows match table and constraints
    // -------------------------------------------------------------------------

    /** AC11 — After migration, INFORMATION_SCHEMA shows the {@code match} table exists. */
    @Test
    void matchTableIsVisibleInInformationSchema() {
        List<Map<String, Object>> tables =
                jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_NAME = 'MATCH'");

        assertThat(tables)
                .as("AC11: INFORMATION_SCHEMA.TABLES must contain the MATCH table")
                .hasSize(1);
    }

    /** AC11 — INFORMATION_SCHEMA.TABLE_CONSTRAINTS shows FKs and CHECK constraint for match. */
    @Test
    void matchConstraintsAreVisibleInInformationSchema() {
        List<Map<String, Object>> constraints =
                jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME, CONSTRAINT_TYPE "
                                + "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                                + "WHERE TABLE_NAME = 'MATCH'");

        List<String> constraintTypes =
                constraints.stream()
                        .map(row -> String.valueOf(row.get("CONSTRAINT_TYPE")))
                        .toList();

        assertThat(constraintTypes)
                .as("AC11: match table must have a PRIMARY KEY constraint")
                .contains("PRIMARY KEY");
        assertThat(constraintTypes)
                .as("AC11: match table must have at least one FOREIGN KEY constraint")
                .contains("FOREIGN KEY"); // H2 uses FOREIGN KEY in INFORMATION_SCHEMA
        assertThat(constraintTypes)
                .as("AC11: match table must have a CHECK constraint (state enum values)")
                .contains("CHECK");
    }

    // -------------------------------------------------------------------------
    // AC12 — tenant_id NOT NULL enforced
    // -------------------------------------------------------------------------

    /** AC12 — {@code match.tenant_id} NOT NULL constraint is enforced at the schema layer. */
    @Test
    void matchTenantIdNotNullIsEnforced() {
        UUID tenantId = insertMinimalTenant();
        UUID tournamentId = insertMinimalTournament(tenantId);
        UUID phaseId = insertMinimalPhase(tenantId, tournamentId, 1);
        UUID teamId1 = insertMinimalTeam(tenantId, tournamentId, 1);
        UUID teamId2 = insertMinimalTeam(tenantId, tournamentId, 2);
        UUID av1Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId1, 1, 1);
        UUID av2Id = insertMinimalTeamAvatar(tenantId, tournamentId, phaseId, teamId2, 1, 2);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                                                + "  member_avatar_1_id, member_avatar_2_id, state,"
                                                + " set_limit) VALUES (?, NULL, ?, ?, ?, ?, ?, 3)",
                                        UUID.randomUUID(),
                                        tournamentId,
                                        phaseId,
                                        av1Id,
                                        av2Id,
                                        MatchState.OPEN.getLegacyCode()))
                .as("AC12: inserting match without tenant_id must raise a constraint violation")
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

    private UUID insertMinimalMatch(
            UUID tenantId, UUID tournamentId, UUID phaseId, UUID av1Id, UUID av2Id, int state) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match "
                        + "(id, tenant_id, tournament_id, phase_id, "
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 3)",
                id,
                tenantId,
                tournamentId,
                phaseId,
                av1Id,
                av2Id,
                state);
        return id;
    }
}
