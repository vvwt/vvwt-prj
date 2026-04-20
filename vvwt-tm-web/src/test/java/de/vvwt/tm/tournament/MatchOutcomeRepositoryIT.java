package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContextTestSupport;
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
 * DAO integration tests for {@link MatchOutcomeRepository} (E21S05, AC-TDD-MatchOutcomeRepository,
 * AC-DAO-3RULES-MatchOutcomeRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link MatchOutcomeRepository} did not exist at commit time —
 * satisfying DEC-22.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li>Schema from migration: {@code @SpringBootTest} with Flyway (V5 provides {@code match_outcome})
 *   <li>Independent verifier: assertj-db against {@code match_outcome} table
 *   <li>Read/write decoupling: fixtures via direct JDBC
 * </ol>
 *
 * @see MatchOutcomeRepository
 * @see <a href="DEC-26">DEC-26</a>
 * @see <a href="E21S05">E21S05 — inventory lines 293, 292</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s05-matchoutcome-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("MatchOutcomeRepository DAO IT — E21S05 DEC-26 three rules")
class MatchOutcomeRepositoryIT {

    @Autowired
    @Qualifier("tmMatchOutcomeRepository")
    private MatchOutcomeRepository matchOutcomeRepository;

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

        // Insert FK dependencies (DEC-26 Rule 3)
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name) VALUES (?, ?)", tenantId, "Tenant-E21S05-MOR");
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
                matchId, tenantId, tournamentId, phaseId, avatar1Id, avatar2Id, 51, 3);

        tenantBinder.bind(tenantId);
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    @Test
    @DisplayName("save() inserts a match_outcome row — verified via assertj-db")
    void saveInsertsMatchOutcomeRow() {
        MatchOutcome mo = new MatchOutcome();
        mo.setMatchId(matchId);
        mo.setTenantId(tenantId);
        mo.setTeam1SetsWon(2);
        mo.setTeam2SetsWon(0);
        mo.setSetCount(2);
        mo.setComputedMatchState(MatchState.FINISHED_WINNER1);

        matchOutcomeRepository.save(mo);

        // DEC-26 Rule 2: independent verifier via assertj-db
        Table table = assertDb.table("match_outcome").build();
        assertThat(table).hasNumberOfRows(1);
    }

    @Test
    @DisplayName("findById returns present for existing match_outcome (fixture via direct JDBC)")
    void findByIdReturnsPresentForExistingRow() {
        // DEC-26 Rule 3: fixture via direct JDBC
        jdbcTemplate.update(
                "INSERT INTO match_outcome (match_id, tenant_id, team1_sets_won,"
                        + " team2_sets_won, set_count, computed_state, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                matchId, tenantId, 2, 0, 2, 51);

        Optional<MatchOutcome> found = matchOutcomeRepository.findById(matchId);
        assertThat(found).isPresent();
        assertThat(found.get().getMatchId()).isEqualTo(matchId);
    }
}
