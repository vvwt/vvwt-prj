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
 *   <li>Schema from migration: {@code @SpringBootTest} with Flyway (V5 provides {@code
 *       match_outcome})
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
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        matchId = UUID.randomUUID();
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        // Bind the default tenant — no manual tenants INSERT needed (DEC-26 Rule 3)
        tenantId = tenantBinder.bindDefaultTenant();
        // E45S06: tenant_id removed (DEC-39 D1); tournament uses location_id (D2)
        UUID locationId = tenantBinder.getDefaultLocationId();

        // Insert FK dependencies (DEC-26 Rule 3)
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
                51,
                3);
    }

    @AfterEach
    void tearDown() {
        // Best-effort cleanup of this test's rows (FK-ordered, child-before-parent)
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
    @DisplayName("save() inserts a match_outcome row — verified via assertj-db")
    void saveInsertsMatchOutcomeRow() {
        MatchOutcome mo = new MatchOutcome();
        mo.setMatchId(matchId);
        mo.setTeam1SetsWon(2);
        mo.setTeam2SetsWon(0);
        mo.setSetCount(2);
        mo.setComputedMatchState(MatchState.FINISHED_WINNER1);

        matchOutcomeRepository.save(mo);

        // DEC-26 Rule 2: independent verifier via assertj-db
        // Row-presence check (not exact count) for isolation in shared H2 DB.
        Table table = assertDb.table("match_outcome").build();
        final UUID savedMatchId = matchId;
        assertThat(table.getRowsList())
                .as("saved match_outcome must appear in the match_outcome table")
                .anyMatch(row -> savedMatchId.equals(row.getColumnValue("MATCH_ID").getValue()));
    }

    @Test
    @DisplayName("findById returns present for existing match_outcome (fixture via direct JDBC)")
    void findByIdReturnsPresentForExistingRow() {
        // DEC-26 Rule 3: fixture via direct JDBC
        jdbcTemplate.update(
                "INSERT INTO match_outcome (match_id, team1_sets_won,"
                        + " team2_sets_won, set_count, computed_state, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                matchId,
                2,
                0,
                2,
                51);

        Optional<MatchOutcome> found = matchOutcomeRepository.findById(matchId);
        assertThat(found).isPresent();
        assertThat(found.get().getMatchId()).isEqualTo(matchId);
    }

    /**
     * E45S03 — DEC-41 Snapshot-Driven: WHERE tenant_id predicate removed from findById and
     * deleteByMatchId. Verifies save-then-delete cycle executes without tenant_id in WHERE clauses.
     */
    @Test
    @DisplayName("E45S03: save and deleteByMatchId execute without tenant_id WHERE predicate")
    void e45s03_saveAndDelete_noTenantPredicate() {
        MatchOutcome mo = new MatchOutcome();
        mo.setMatchId(matchId);
        mo.setTeam1SetsWon(1);
        mo.setTeam2SetsWon(2);
        mo.setSetCount(3);
        mo.setComputedMatchState(MatchState.FINISHED_WINNER2);

        matchOutcomeRepository.save(mo);
        matchOutcomeRepository.deleteByMatchId(matchId);

        Optional<MatchOutcome> found = matchOutcomeRepository.findById(matchId);
        assertThat(found).isEmpty();
    }
}
