package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DAO integration tests for {@link SetResultRepository} (E21S05, AC-TDD-SetResultRepository,
 * AC-DAO-3RULES-SetResultRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link SetResultRepository} did not exist at commit time —
 * satisfying DEC-22.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li>Schema from migration: V4 provides {@code set_result} table via SpringBootTest Flyway
 *   <li>Independent verifier: assertj-db against {@code set_result} table
 *   <li>Read/write decoupling: fixtures via direct JDBC
 * </ol>
 *
 * @see SetResultRepository
 * @see <a href="DEC-26">DEC-26</a>
 * @see <a href="E21S05">E21S05 — inventory line 301</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s05-setresult-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("SetResultRepository DAO IT — E21S05 DEC-26 three rules")
class SetResultRepositoryIT {

    @Autowired
    @Qualifier("tmSetResultRepository")
    private SetResultRepository setResultRepository;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID avatar1Id;
    private UUID avatar2Id;
    private UUID matchId;
    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        matchId = UUID.randomUUID();
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        // Bind the default tenant — no manual tenants INSERT needed (DEC-26 Rule 3)
        tenantId = tenantBinder.bindDefaultTenant();
        // E45S06: tenant_id removed from all tables (DEC-39 D1); tournament uses location_id (D2)
        UUID locationId = tenantBinder.getDefaultLocationId();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                tournamentId,
                locationId,
                "T",
                "BEST_OF_3",
                "r",
                "v",
                "g",
                "DRAFT");
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number,"
                        + " description, status, current_lap_number)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "P",
                "PENDING",
                0);
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?)",
                teamId1,
                tournamentId,
                1,
                "TA");
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?)",
                teamId2,
                tournamentId,
                2,
                "TB");
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id,"
                        + " group_number, group_position)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                teamId1,
                1,
                1);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id,"
                        + " group_number, group_position)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                teamId2,
                1,
                2);
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                30,
                3);
    }

    @AfterEach
    void tearDown() {
        // Best-effort cleanup of this test's rows (FK-ordered, child-before-parent)
        // E45S06: tenant_id removed — simple DELETE without WHERE (per-tenant DB isolation via
        // DEC-20)
        jdbcTemplate.update("DELETE FROM set_result");
        jdbcTemplate.update("DELETE FROM match_outcome");
        jdbcTemplate.update("DELETE FROM match");
        jdbcTemplate.update("DELETE FROM team_avatar");
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM activity_types");
        jdbcTemplate.update("DELETE FROM phase");
        jdbcTemplate.update("DELETE FROM tournament");
        tenantBinder.unbind();
    }

    @Test
    @DisplayName("insert() creates a set_result row — verified via assertj-db")
    void insertCreatesSetResultRow() {
        SetResult sr =
                new SetResult(
                        matchId, 0, phaseId, 25, 20, SetState.WINNER1.getLegacyCode(), null, null);

        setResultRepository.insert(sr);

        // DEC-26 Rule 2: verify via assertj-db, not via repository read
        // Row-presence check (not exact count) for isolation in shared H2 DB.
        Table table = assertDb.table("set_result").build();
        final UUID savedMatchId = matchId;
        assertThat(table.getRowsList())
                .as("inserted set_result must appear in the set_result table")
                .anyMatch(row -> savedMatchId.equals(row.getColumnValue("MATCH_ID").getValue()));
    }

    @Test
    @DisplayName("findByMatchId returns rows for existing match (fixture via direct JDBC)")
    void findByMatchIdReturnsRows() {
        // DEC-26 Rule 3: fixture via direct JDBC
        // E45S06: tenant_id removed from set_result (DEC-39 D1)
        jdbcTemplate.update(
                "INSERT INTO set_result (match_id, set_index, phase_id,"
                        + " team1_points, team2_points, set_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                matchId,
                0,
                phaseId,
                25,
                20,
                1);

        List<SetResult> results = setResultRepository.findByMatchId(matchId);
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("findByMatchIdAndSetIndex returns empty when no row exists")
    void findByMatchIdAndSetIndexReturnsEmptyWhenNotFound() {
        Optional<SetResult> result = setResultRepository.findByMatchIdAndSetIndex(matchId, 99);
        assertThat(result).isEmpty();
    }

    /**
     * E45S03 — DEC-41 Snapshot-Driven: WHERE tenant_id predicate removed from SELECT_BY_MATCH and
     * DELETE_BY_PK. Verifies insert-find-delete cycle executes without tenant_id in WHERE clauses.
     */
    @Test
    @DisplayName(
            "E45S03: insert, findByMatchId, deleteByMatchIdAndSetIndex without tenant_id WHERE")
    void e45s03_insertFindDelete_noTenantPredicate() {
        SetResult sr =
                new SetResult(
                        matchId, 0, phaseId, 25, 20, SetState.WINNER1.getLegacyCode(), null, null);
        setResultRepository.insert(sr);

        List<SetResult> found = setResultRepository.findByMatchId(matchId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTeam1Points()).isEqualTo(25);

        setResultRepository.deleteByMatchIdAndSetIndex(matchId, 0);
        assertThat(setResultRepository.findByMatchIdAndSetIndex(matchId, 0)).isEmpty();
    }
}
