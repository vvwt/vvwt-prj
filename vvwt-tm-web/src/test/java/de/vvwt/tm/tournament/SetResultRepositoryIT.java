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
        tenantId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        matchId = UUID.randomUUID();
        assertDb = AssertDbConnectionFactory.of(dataSource).create();

        jdbcTemplate.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Tenant-E21S05-SR");
        jdbcTemplate.update(
                "INSERT INTO tournament (id, tenant_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                tournamentId, tenantId, "T", "BEST_OF_3", "r", "v", "g", "CREATED");
        jdbcTemplate.update(
                "INSERT INTO phase (id, tenant_id, tournament_id, name, group_count,"
                        + " teams_per_group, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                phaseId, tenantId, tournamentId, "P", 1, 2);
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tenant_id, tournament_id, name) VALUES (?, ?, ?, ?)",
                teamId1, tenantId, tournamentId, "TA");
        jdbcTemplate.update(
                "INSERT INTO team (id, tenant_id, tournament_id, name) VALUES (?, ?, ?, ?)",
                teamId2, tenantId, tournamentId, "TB");
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tenant_id, tournament_id, phase_id, team_id,"
                        + " group_number, group_position, without_assessment)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                avatar1Id, tenantId, tournamentId, phaseId, teamId1, 1, 1, false);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tenant_id, tournament_id, phase_id, team_id,"
                        + " group_number, group_position, without_assessment)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                avatar2Id, tenantId, tournamentId, phaseId, teamId2, 1, 2, false);
        jdbcTemplate.update(
                "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId, tenantId, tournamentId, phaseId, avatar1Id, avatar2Id, 30, 3);

        tenantBinder.bind(tenantId);
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    @Test
    @DisplayName("insert() creates a set_result row — verified via assertj-db")
    void insertCreatesSetResultRow() {
        SetResult sr = new SetResult(
                matchId, 0, tenantId, phaseId, 25, 20,
                SetState.WINNER1.getLegacyCode(), null, null);

        setResultRepository.insert(sr);

        // DEC-26 Rule 2: verify via assertj-db, not via repository read
        Table table = assertDb.table("set_result").build();
        assertThat(table).hasNumberOfRows(1);
    }

    @Test
    @DisplayName("findByMatchId returns rows for existing match (fixture via direct JDBC)")
    void findByMatchIdReturnsRows() {
        // DEC-26 Rule 3: fixture via direct JDBC
        jdbcTemplate.update(
                "INSERT INTO set_result (match_id, set_index, tenant_id, phase_id,"
                        + " team1_points, team2_points, set_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                matchId, 0, tenantId, phaseId, 25, 20, 1);

        List<SetResult> results = setResultRepository.findByMatchId(matchId);
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("findByMatchIdAndSetIndex returns empty when no row exists")
    void findByMatchIdAndSetIndexReturnsEmptyWhenNotFound() {
        Optional<SetResult> result = setResultRepository.findByMatchIdAndSetIndex(matchId, 99);
        assertThat(result).isEmpty();
    }
}
