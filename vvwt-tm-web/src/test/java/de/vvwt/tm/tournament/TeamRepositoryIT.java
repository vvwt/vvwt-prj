package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * DAO integration tests for {@link TeamRepository} (E21S04, AC-DAO-3RULES-TeamRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TeamRepository} at {@code
 * de.vvwt.tm.tournament.TeamRepository} did not exist at commit time, causing a compile error —
 * satisfying the DEC-22 Iron Law.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from migration:</b> {@code @SpringBootTest} with Flyway applies all root
 *       migrations in version order. No inline DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write tests verify DB state via
 *       assertj-db ({@link AssertDbConnection}) against the DataSource — never via the repository's
 *       own read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path tests insert fixtures via direct JDBC
 *       ({@link TenantDaoTestSupport#insertDirectly}) — never via the repository's own save
 *       methods.
 * </ol>
 *
 * @see TeamRepository
 * @see de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 307)</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s04-team-repository-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TeamRepository DAO IT — E21S04 DEC-26 three rules")
class TeamRepositoryIT {

    @Autowired
    @Qualifier("tmTeamRepository")
    private TeamRepository teamRepository;

    @Autowired private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private AssertDbConnection assertDb;

    /** Pre-existing tournament row required by FK on team.tournament_id. */
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantId = tenantBinder.bindDefaultTenant();
        tournamentId = insertTournamentFixture();
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var conn = dataSource.getConnection()) {
            for (String sql :
                    new String[] {
                        "DELETE FROM team WHERE tenant_id = ?",
                        "DELETE FROM tournament WHERE tenant_id = ?",
                        "DELETE FROM tenants WHERE id = ?"
                    }) {
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, tenantId);
                    ps.executeUpdate();
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
        }
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // Write-path tests — DEC-26 Rule 2: verify via assertj-db, not repo read
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("save() — new team persists to 'team' table (verified via assertj-db)")
    void save_newTeam_persistsToTeamTable() {
        Team team = newTeam(tenantId, tournamentId, 1, "Alpha");
        teamRepository.save(team);

        // DEC-26 Rule 2: verify via assertj-db, not repo read.
        // Uses row-presence check (not exact row count) for isolation in shared H2 DB.
        Table table = assertDb.table("team").build();
        final UUID savedId = team.getId();
        assertThat(table.getRowsList())
                .as("saved team must appear in the team table")
                .anyMatch(
                        row ->
                                savedId.equals(row.getColumnValue("ID").getValue())
                                        && tenantId.equals(
                                                row.getColumnValue("TENANT_ID").getValue())
                                        && tournamentId.equals(
                                                row.getColumnValue("TOURNAMENT_ID").getValue())
                                        && Objects.equals(
                                                row.getColumnValue("DESCRIPTION").getValue(),
                                                "Alpha"));
    }

    @Test
    @DisplayName("save() — update existing team changes description in DB (assertj-db)")
    void save_existingTeam_updatesInDb() {
        // Arrange: insert via repo (write-path test — Rule 2 applies)
        Team team = newTeam(tenantId, tournamentId, 2, "Before");
        teamRepository.save(team);

        // Act: update
        team.setDescription("After");
        teamRepository.save(team);

        // Assert via assertj-db: the specific row must show "After" and "Before" must not exist
        // for this team's ID. Row-presence check for isolation in shared H2 DB.
        Table table = assertDb.table("team").build();
        final UUID savedId = team.getId();
        assertThat(table.getRowsList())
                .as("updated team must have description 'After' in DB")
                .anyMatch(
                        row ->
                                savedId.equals(row.getColumnValue("ID").getValue())
                                        && Objects.equals(
                                                row.getColumnValue("DESCRIPTION").getValue(),
                                                "After"));
    }

    // -------------------------------------------------------------------------
    // Read-path tests — DEC-26 Rule 3: insert via direct JDBC
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByTournamentId() — returns teams for the queried tournament only (Rule 3)")
    void findByTournamentId_returnsTenantScopedTeams() {
        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();

        // Fixture via direct JDBC — DEC-26 Rule 3
        insertTeamDirectly(team1Id, tenantId, tournamentId, 1, "TeamOne");
        insertTeamDirectly(team2Id, tenantId, tournamentId, 2, "TeamTwo");

        // Also insert a row for a different tournament (same tenant) — must not appear
        UUID otherTournamentId = insertTournamentFixtureForTenant(tenantId);
        insertTeamDirectly(UUID.randomUUID(), tenantId, otherTournamentId, 1, "OtherTeam");

        List<Team> teams = teamRepository.findByTournamentId(tournamentId);
        assertThat(teams).hasSize(2);
        assertThat(teams).extracting(Team::getTeamNumber).containsExactlyInAnyOrder(1, 2);
    }

    @Test
    @DisplayName("findById() — returns present when team exists for tenant")
    void findById_returnsPresentWhenExists() {
        UUID id = UUID.randomUUID();
        insertTeamDirectly(id, tenantId, tournamentId, 3, "FindMe");

        Optional<Team> result = teamRepository.findById(id);
        assertThat(result).isPresent();
        assertThat(result.get().getDescription()).isEqualTo("FindMe");
    }

    @Test
    @DisplayName("findById() — returns empty for unknown ID")
    void findById_returnsEmptyForUnknown() {
        Optional<Team> result = teamRepository.findById(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteById() — removes team row from 'team' table (assertj-db)")
    void deleteById_removesRow() {
        Team team = newTeam(tenantId, tournamentId, 4, "ToDelete");
        teamRepository.save(team);

        teamRepository.deleteById(team.getId());

        // DEC-26 Rule 2: verify the specific row is gone. Row-absence check for isolation.
        Table table = assertDb.table("team").build();
        final UUID deletedId = team.getId();
        assertThat(table.getRowsList())
                .as("deleted team must not appear in the team table")
                .noneMatch(row -> deletedId.equals(row.getColumnValue("ID").getValue()));
    }

    @Test
    @DisplayName("nextTeamNumber() — returns 1 when no teams exist")
    void nextTeamNumber_returnsOneWhenEmpty() {
        assertThat(teamRepository.nextTeamNumber(tournamentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("nextTeamNumber() — returns max+1 when teams exist")
    void nextTeamNumber_returnsMaxPlusOne() {
        insertTeamDirectly(UUID.randomUUID(), tenantId, tournamentId, 3, "Three");
        insertTeamDirectly(UUID.randomUUID(), tenantId, tournamentId, 7, "Seven");

        assertThat(teamRepository.nextTeamNumber(tournamentId)).isEqualTo(8);
    }

    @Test
    @DisplayName("teamNumberExists() — detects duplicate within tournament")
    void teamNumberExists_detectsDuplicate() {
        insertTeamDirectly(UUID.randomUUID(), tenantId, tournamentId, 5, "Five");

        UUID excludeId = new UUID(0, 0);
        assertThat(teamRepository.teamNumberExists(tournamentId, 5, excludeId)).isTrue();
        assertThat(teamRepository.teamNumberExists(tournamentId, 6, excludeId)).isFalse();
    }

    /**
     * E45S03 — DEC-41 Snapshot-Driven: WHERE tenant_id predicate removed from findById.
     * Post-removal, findById executes without tenant_id in the WHERE clause; isolation via DEC-20
     * routing.
     */
    @Test
    @DisplayName("E45S03: findById executes without tenant_id WHERE predicate (DEC-20 isolates)")
    void e45s03_findById_noTenantPredicate_returnsRow() {
        UUID id = UUID.randomUUID();
        insertTeamDirectly(id, tenantId, tournamentId, 99, "E45S03-team");

        Optional<Team> result = teamRepository.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Team newTeam(UUID tId, UUID trnId, int num, String desc) {
        Team t = new Team();
        t.setId(UUID.randomUUID());
        t.setTenantId(tId);
        t.setTournamentId(trnId);
        t.setTeamNumber(num);
        t.setDescription(desc);
        t.setParticipate(true);
        return t;
    }

    private void insertTeamDirectly(UUID id, UUID tId, UUID trnId, int num, String desc) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        cols.put("tenant_id", tId);
        cols.put("tournament_id", trnId);
        cols.put("team_number", num);
        cols.put("description", desc);
        cols.put("participate", true);
        cols.put("referee_assignment", false);
        cols.put("without_assessment", false);
        TenantDaoTestSupport.insertDirectly(dataSource, "team", cols);
    }

    private UUID insertTournamentFixture() {
        return insertTournamentFixtureForTenant(tenantId);
    }

    private UUID insertTournamentFixtureForTenant(UUID tId) {
        // Insert a minimal tenant row first if not already present
        try {
            Map<String, Object> tenantCols = new LinkedHashMap<>();
            UUID tid = tId;
            tenantCols.put("id", tid);
            tenantCols.put("name", "tenant-" + tid);
            tenantCols.put("subdomain", "t-" + tid.toString().substring(0, 8));
            TenantDaoTestSupport.insertDirectly(dataSource, "tenants", tenantCols);
        } catch (Exception ignored) {
            // tenant may already exist
        }

        UUID trnId = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", trnId);
        cols.put("tenant_id", tId);
        cols.put("description", "Test Tournament");
        cols.put("match_format", "BEST_OF_1");
        cols.put("scoring_rule_id", "defaultScoringRule");
        cols.put("set_validation_rule_id", "defaultSetValidationRule");
        cols.put("match_generator_id", "roundRobinMatchGenerator");
        cols.put("status", "DRAFT");
        TenantDaoTestSupport.insertDirectly(dataSource, "tournament", cols);
        return trnId;
    }
}
