package de.vvwt.tm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.ActivityTypeService;
import de.vvwt.tm.domain.AssignmentRule;
import de.vvwt.tm.domain.repo.ActivityTypeRepository;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that the V13__e08s02_activity_types.sql Flyway migration is applied correctly and that
 * the {@link ActivityType} domain model satisfies all E08S02 acceptance criteria.
 *
 * <p>Checks:
 *
 * <ul>
 *   <li>AC1 — {@code activity_types} table exists with all required columns and constraints
 *   <li>AC2 — {@link ActivityTypeRepository} is injectable and enforces tenant scope
 *   <li>AC3 — {@link AssignmentRule} enum has {@code FIRST_FREE_ROUND}; stored as VARCHAR name
 *   <li>AC4 — Unknown assignment rule is rejected with validation error
 *   <li>AC5 — Duplicate name within the same tournament is rejected
 *   <li>AC6 — capacity_per_round &le; 0 is rejected; null and positive values are accepted
 *   <li>AC7 — Repository guard fires when no tenant context is active
 *   <li>AC9 — Existing migrations V1–V12 unaffected; V13 applies cleanly (context loads)
 * </ul>
 *
 * @see <a href="../../../../../.gaai/project/contexts/artefacts/stories/E08S02.story.md">Story
 *     E08S02</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e08s02v13db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
class E08S02MigrationIT {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired private ActivityTypeRepository activityTypeRepository;

    @Autowired private ActivityTypeService activityTypeService;

    @Autowired private TournamentRepository tournamentRepository;

    private UUID defaultTenantId;

    @BeforeTransaction
    void bindTenantBeforeTransaction() {
        defaultTenantId = tenantContextBinder.bindDefaultTenant();
    }

    @AfterTransaction
    void unbindTenantAfterTransaction() {
        tenantContextBinder.unbind();
    }

    @BeforeEach
    void setUpTenantContext() {
        defaultTenantId = tenantContextBinder.bindDefaultTenant();
    }

    @AfterEach
    void clearTenantContext() {
        tenantContextBinder.unbind();
    }

    // =========================================================================
    // AC1 — Schema: activity_types table exists with correct columns
    // =========================================================================

    @Test
    void activityTypesTableExists() {
        List<Map<String, Object>> tables =
                jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE TABLE_NAME = 'ACTIVITY_TYPES'");
        assertThat(tables).as("AC1 — activity_types table must exist").hasSize(1);
    }

    @Test
    void activityTypesTableHasIdColumn() {
        assertColumnExists("ACTIVITY_TYPES", "ID", "NO");
    }

    @Test
    void activityTypesTableHasTournamentIdColumn() {
        assertColumnExists("ACTIVITY_TYPES", "TOURNAMENT_ID", "NO");
    }

    @Test
    void activityTypesTableHasNameColumn() {
        assertColumnExists("ACTIVITY_TYPES", "NAME", "NO");
    }

    @Test
    void activityTypesTableHasAssignmentRuleColumn() {
        assertColumnExists("ACTIVITY_TYPES", "ASSIGNMENT_RULE", "NO");
    }

    @Test
    void activityTypesTableHasCapacityPerRoundColumnNullable() {
        assertColumnExists("ACTIVITY_TYPES", "CAPACITY_PER_ROUND", "YES");
    }

    @Test
    void activityTypesTableHasSortOrderColumn() {
        assertColumnExists("ACTIVITY_TYPES", "SORT_ORDER", "NO");
    }

    @Test
    void activityTypesTableHasTenantIdColumn() {
        assertColumnExists("ACTIVITY_TYPES", "TENANT_ID", "NO");
    }

    // =========================================================================
    // AC1 — Schema: UNIQUE constraint on (tournament_id, name)
    // =========================================================================

    @Test
    void uniqueConstraintOnTournamentIdAndName() {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        insertMinimalTenantAndTournament(tenantId, tournamentId);

        // First insert succeeds
        jdbcTemplate.update(
                "INSERT INTO activity_types (id, tournament_id, name, assignment_rule, "
                        + "capacity_per_round, sort_order, tenant_id) "
                        + "VALUES (?, ?, 'Photo', 'FIRST_FREE_ROUND', NULL, 1, ?)",
                UUID.randomUUID().toString(),
                tournamentId,
                tenantId);

        // Second insert with same tournament + name must fail
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO activity_types (id, tournament_id, name,"
                                            + " assignment_rule, capacity_per_round, sort_order,"
                                            + " tenant_id) VALUES (?, ?, 'Photo',"
                                            + " 'FIRST_FREE_ROUND', NULL, 2, ?)",
                                        UUID.randomUUID().toString(),
                                        tournamentId,
                                        tenantId))
                .as("AC1 / AC5 — UNIQUE (tournament_id, name) constraint must reject duplicate")
                .isInstanceOf(Exception.class);
    }

    // =========================================================================
    // AC1 — Schema: CHECK constraint on capacity_per_round
    // =========================================================================

    @Test
    void checkConstraintRejectsZeroCapacity() {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        insertMinimalTenantAndTournament(tenantId, tournamentId);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO activity_types (id, tournament_id, name,"
                                            + " assignment_rule, capacity_per_round, sort_order,"
                                            + " tenant_id) VALUES (?, ?, 'Video',"
                                            + " 'FIRST_FREE_ROUND', 0, 1, ?)",
                                        UUID.randomUUID().toString(),
                                        tournamentId,
                                        tenantId))
                .as("AC1 / AC6 — CHECK constraint must reject capacity_per_round = 0")
                .isInstanceOf(Exception.class);
    }

    @Test
    void checkConstraintRejectsNegativeCapacity() {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        insertMinimalTenantAndTournament(tenantId, tournamentId);

        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO activity_types (id, tournament_id, name,"
                                            + " assignment_rule, capacity_per_round, sort_order,"
                                            + " tenant_id) VALUES (?, ?, 'Video',"
                                            + " 'FIRST_FREE_ROUND', -1, 1, ?)",
                                        UUID.randomUUID().toString(),
                                        tournamentId,
                                        tenantId))
                .as("AC1 / AC6 — CHECK constraint must reject capacity_per_round = -1")
                .isInstanceOf(Exception.class);
    }

    @Test
    void nullCapacityAndPositiveCapacityAreAccepted() {
        String tenantId = UUID.randomUUID().toString();
        String tournamentId = UUID.randomUUID().toString();
        insertMinimalTenantAndTournament(tenantId, tournamentId);

        // NULL capacity is accepted
        jdbcTemplate.update(
                "INSERT INTO activity_types (id, tournament_id, name, assignment_rule, "
                        + "capacity_per_round, sort_order, tenant_id) "
                        + "VALUES (?, ?, 'Unlimited', 'FIRST_FREE_ROUND', NULL, 1, ?)",
                UUID.randomUUID().toString(),
                tournamentId,
                tenantId);

        // Positive capacity is accepted
        jdbcTemplate.update(
                "INSERT INTO activity_types (id, tournament_id, name, assignment_rule, "
                        + "capacity_per_round, sort_order, tenant_id) "
                        + "VALUES (?, ?, 'Capped', 'FIRST_FREE_ROUND', 5, 2, ?)",
                UUID.randomUUID().toString(),
                tournamentId,
                tenantId);

        long count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM activity_types WHERE tournament_id = ?",
                        Long.class,
                        tournamentId);
        assertThat(count)
                .as("AC6 — both NULL and positive capacity rows must be present")
                .isEqualTo(2);
    }

    // =========================================================================
    // AC2 — ActivityTypeRepository is injectable and tenant-scoped
    // =========================================================================

    @Test
    void activityTypeRepositoryIsInjectable() {
        assertThat(activityTypeRepository)
                .as("AC2 — ActivityTypeRepository must be injectable")
                .isNotNull();
    }

    @Test
    @Transactional
    void activityTypeRepositoryCrudRoundTrip() {
        UUID tournamentId = createTournament();

        ActivityType at =
                new ActivityType(
                        UUID.randomUUID(),
                        tournamentId,
                        "Mannschaftsfoto",
                        AssignmentRule.FIRST_FREE_ROUND.name(),
                        null,
                        1,
                        null);
        ActivityType saved = activityTypeRepository.save(at);

        assertThat(saved).isNotNull();
        assertThat(activityTypeRepository.findById(saved.getId())).isPresent();
        assertThat(activityTypeRepository.findById(saved.getId()).get().getName())
                .isEqualTo("Mannschaftsfoto");
        assertThat(activityTypeRepository.findById(saved.getId()).get().getTenantId())
                .isEqualTo(defaultTenantId);
    }

    // =========================================================================
    // AC3 — AssignmentRule enum has FIRST_FREE_ROUND; stored as VARCHAR name
    // =========================================================================

    @Test
    void assignmentRuleEnumHasFirstFreeRound() {
        assertThat(AssignmentRule.FIRST_FREE_ROUND)
                .as("AC3 — AssignmentRule enum must have FIRST_FREE_ROUND constant")
                .isNotNull();
    }

    @Test
    @Transactional
    void assignmentRuleStoredAsVarcharName() {
        UUID tournamentId = createTournament();

        ActivityType at =
                new ActivityType(
                        UUID.randomUUID(),
                        tournamentId,
                        "StorageTest",
                        AssignmentRule.FIRST_FREE_ROUND.name(),
                        null,
                        1,
                        null);
        activityTypeRepository.save(at);

        String storedRule =
                jdbcTemplate.queryForObject(
                        "SELECT assignment_rule FROM activity_types WHERE name = 'StorageTest' "
                                + "AND tournament_id = ?",
                        String.class,
                        tournamentId.toString());
        assertThat(storedRule)
                .as("AC3 — assignment_rule must be stored as the enum name string")
                .isEqualTo("FIRST_FREE_ROUND");
    }

    // =========================================================================
    // AC4 — Unknown assignment rule is rejected by the service
    // =========================================================================

    @Test
    void unknownAssignmentRuleIsRejected() {
        UUID tournamentId = createTournament();

        assertThatThrownBy(
                        () ->
                                activityTypeService.create(
                                        tournamentId, "Photo", "UNKNOWN_RULE", null, 1))
                .as("AC4 — unknown assignment rule must be rejected")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN_RULE");
    }

    @Test
    void knownAssignmentRuleIsAccepted() {
        UUID tournamentId = createTournament();

        ActivityType result =
                activityTypeService.create(
                        tournamentId,
                        "Photo-" + UUID.randomUUID(),
                        AssignmentRule.FIRST_FREE_ROUND.name(),
                        null,
                        1);
        assertThat(result).as("AC4 — known assignment rule must be accepted").isNotNull();
        assertThat(result.getAssignmentRule()).isEqualTo("FIRST_FREE_ROUND");
    }

    // =========================================================================
    // AC5 — Duplicate name within the same tournament is rejected
    // =========================================================================

    @Test
    void duplicateActivityNameRejected() {
        UUID tournamentId = createTournament();
        String uniqueName = "DupPhoto-" + UUID.randomUUID();

        activityTypeService.create(
                tournamentId, uniqueName, AssignmentRule.FIRST_FREE_ROUND.name(), null, 1);

        assertThatThrownBy(
                        () ->
                                activityTypeService.create(
                                        tournamentId,
                                        uniqueName,
                                        AssignmentRule.FIRST_FREE_ROUND.name(),
                                        null,
                                        2))
                .as("AC5 — duplicate activity name in same tournament must be rejected")
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void sameNameInDifferentTournamentsIsAllowed() {
        UUID t1 = createTournament();
        UUID t2 = createTournament();

        activityTypeService.create(
                t1, "SharedName", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1);
        // Must not throw — same name but different tournament
        ActivityType second =
                activityTypeService.create(
                        t2, "SharedName", AssignmentRule.FIRST_FREE_ROUND.name(), null, 1);
        assertThat(second)
                .as("AC5 — same name in different tournaments must be allowed")
                .isNotNull();
    }

    // =========================================================================
    // AC6 — capacity_per_round <= 0 rejected by service; null and positive accepted
    // =========================================================================

    @Test
    void zeroCapacityRejectedByService() {
        UUID tournamentId = createTournament();

        assertThatThrownBy(
                        () ->
                                activityTypeService.create(
                                        tournamentId,
                                        "Cap0-" + UUID.randomUUID(),
                                        AssignmentRule.FIRST_FREE_ROUND.name(),
                                        0,
                                        1))
                .as("AC6 — capacity_per_round = 0 must be rejected by service")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeCapacityRejectedByService() {
        UUID tournamentId = createTournament();

        assertThatThrownBy(
                        () ->
                                activityTypeService.create(
                                        tournamentId,
                                        "CapNeg-" + UUID.randomUUID(),
                                        AssignmentRule.FIRST_FREE_ROUND.name(),
                                        -5,
                                        1))
                .as("AC6 — negative capacity_per_round must be rejected by service")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullCapacityAcceptedByService() {
        UUID tournamentId = createTournament();

        ActivityType result =
                activityTypeService.create(
                        tournamentId,
                        "NullCap-" + UUID.randomUUID(),
                        AssignmentRule.FIRST_FREE_ROUND.name(),
                        null,
                        1);
        assertThat(result.getCapacityPerRound())
                .as("AC6 — null capacity must be accepted and persisted as null")
                .isNull();
    }

    @Test
    void positiveCapacityAcceptedByService() {
        UUID tournamentId = createTournament();

        ActivityType result =
                activityTypeService.create(
                        tournamentId,
                        "PosCap-" + UUID.randomUUID(),
                        AssignmentRule.FIRST_FREE_ROUND.name(),
                        3,
                        1);
        assertThat(result.getCapacityPerRound())
                .as("AC6 — positive capacity must be accepted and persisted")
                .isEqualTo(3);
    }

    // =========================================================================
    // AC7 — Repository guard fires when no tenant context is active
    // =========================================================================

    @Test
    void repositoryGuardFiresWithoutTenantContext() {
        tenantContextBinder.unbind();
        try {
            assertThatThrownBy(() -> activityTypeRepository.findAll())
                    .as("AC7 — repository guard must fire before SQL when no tenant context")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No tenant is bound to the current thread");
        } finally {
            defaultTenantId = tenantContextBinder.bindDefaultTenant();
        }
    }

    @Test
    void repositoryGuardFiresOnFindByTournamentIdWithoutTenantContext() {
        tenantContextBinder.unbind();
        try {
            assertThatThrownBy(() -> activityTypeRepository.findByTournamentId(UUID.randomUUID()))
                    .as("AC7 — findByTournamentId must guard on tenant context")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No tenant is bound to the current thread");
        } finally {
            defaultTenantId = tenantContextBinder.bindDefaultTenant();
        }
    }

    // =========================================================================
    // AC9 — Migration safety: Spring context loads with all migrations applied
    // =========================================================================

    @Test
    void springContextLoadedWithAllMigrationsApplied() {
        // If the Spring context loaded successfully (this class is @SpringBootTest), all
        // Flyway migrations V1–V13 have been applied in sequence without error.
        // This test is the presence proof for AC9.
        assertThat(activityTypeRepository)
                .as("AC9 — Spring context and all Flyway migrations must apply cleanly")
                .isNotNull();
    }

    @Test
    void previousMigrationTablesStillExist() {
        // AC9: verify representative tables from earlier migrations are unaffected
        assertTableExists("tournament"); // V2
        assertTableExists("phase"); // V2
        assertTableExists("team"); // V2
        assertTableExists("team_avatar"); // V2
        assertTableExists("devices"); // V8 (E06S03)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void assertColumnExists(String tableName, String columnName, String expectedNullable) {
        List<Map<String, Object>> cols =
                jdbcTemplate.queryForList(
                        "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                                + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                        tableName,
                        columnName);
        assertThat(cols)
                .as("AC1 — column " + columnName + " must exist in " + tableName)
                .hasSize(1);
        assertThat((String) cols.get(0).get("IS_NULLABLE"))
                .as("AC1 — column " + columnName + " nullable must be " + expectedNullable)
                .isEqualToIgnoringCase(expectedNullable);
    }

    private void assertTableExists(String tableName) {
        List<Map<String, Object>> tables =
                jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                                + "WHERE LOWER(TABLE_NAME) = ?",
                        tableName.toLowerCase());
        assertThat(tables).as("AC9 — table " + tableName + " must still exist").hasSize(1);
    }

    private void insertMinimalTenantAndTournament(String tenantId, String tournamentId) {
        jdbcTemplate.update(
                "INSERT INTO tenants (id, display_name, tenant_location_count, is_default,"
                        + " created_at) VALUES (?, 'TestTenant', 1, FALSE, CURRENT_TIMESTAMP)",
                tenantId);
        jdbcTemplate.update(
                "INSERT INTO tournament (id, tenant_id, description, match_format, scoring_rule_id,"
                    + " set_validation_rule_id, match_generator_id, status, created_at) VALUES (?,"
                    + " ?, 'Test', 'BEST_OF_3', 'r', 'v', 'g', 'DRAFT', CURRENT_TIMESTAMP)",
                tournamentId,
                tenantId);
    }

    private UUID createTournament() {
        UUID id = UUID.randomUUID();
        Tournament t =
                new Tournament(
                        id,
                        defaultTenantId,
                        "Test Tournament " + id,
                        MatchFormat.BEST_OF_3.name(),
                        "setPoints",
                        "standardVolleyball",
                        "roundRobin",
                        "DRAFT",
                        LocalDateTime.now());
        tournamentRepository.save(t);
        return id;
    }
}
