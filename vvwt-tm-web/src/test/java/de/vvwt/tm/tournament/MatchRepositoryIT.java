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
 * DAO integration tests for {@link MatchRepository} (E21S05, AC-TDD-MatchRepository,
 * AC-DAO-3RULES-MatchRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link MatchRepository} at {@code
 * de.vvwt.tm.tournament.MatchRepository} did not exist at commit time — satisfying DEC-22.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from migration:</b> {@code @SpringBootTest} with Flyway applies all root
 *       migrations (V1–V16 subset per TenantDaoTestSupport contract). No inline DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write tests verify DB state via
 *       assertj-db ({@link AssertDbConnection}) — never via repository's own read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path tests insert fixtures via direct JDBC.
 * </ol>
 *
 * @see MatchRepository
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — Match cluster reconstruction (inventory lines 173, 294)</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s05-match-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("MatchRepository DAO IT — E21S05 DEC-26 three rules")
class MatchRepositoryIT {

    @Autowired
    @Qualifier("tmMatchRepository")
    private MatchRepository matchRepository;

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID avatar1Id;
    private UUID avatar2Id;
    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        // Bind the default tenant — no manual tenants INSERT needed (DEC-26 Rule 3)
        tenantId = tenantBinder.bindDefaultTenant();

        // Insert required FK rows directly via JDBC (DEC-26 Rule 3)
        jdbcTemplate.update(
                "INSERT INTO tournament (id, tenant_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                tournamentId,
                tenantId,
                "Test Tournament",
                "BEST_OF_3",
                "r1",
                "v1",
                "g1",
                "CREATED");
        jdbcTemplate.update(
                "INSERT INTO phase (id, tenant_id, tournament_id, sequence_number,"
                        + " description, status, current_lap_number)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tenantId,
                tournamentId,
                1,
                "Group Phase",
                "PENDING",
                0);
        // Team for avatars
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tenant_id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId1,
                tenantId,
                tournamentId,
                1,
                "Team A");
        jdbcTemplate.update(
                "INSERT INTO team (id, tenant_id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId2,
                tenantId,
                tournamentId,
                2,
                "Team B");
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tenant_id, tournament_id, phase_id, team_id,"
                        + " group_number, group_position)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tenantId,
                tournamentId,
                phaseId,
                teamId1,
                1,
                1);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tenant_id, tournament_id, phase_id, team_id,"
                        + " group_number, group_position)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tenantId,
                tournamentId,
                phaseId,
                teamId2,
                1,
                2);
    }

    @AfterEach
    void tearDown() {
        // Best-effort cleanup of this test's rows (FK-ordered, child-before-parent)
        jdbcTemplate.update("DELETE FROM set_result WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM audit_log WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM match_outcome WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM match WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM team_avatar_rating WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM team WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM activity_types WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM phase WHERE tenant_id = ?", tenantId);
        jdbcTemplate.update("DELETE FROM tournament WHERE tenant_id = ?", tenantId);
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // Write-path tests (DEC-26 Rule 2: verify via assertj-db, not repository reads)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("save() inserts a new match row — verified via assertj-db table")
    void saveInsertsNewMatchRow() {
        UUID matchId = UUID.randomUUID();
        Match match = new Match();
        match.setId(matchId);
        match.setTenantId(tenantId);
        match.setTournamentId(tournamentId);
        match.setPhaseId(phaseId);
        match.setMemberAvatar1Id(avatar1Id);
        match.setMemberAvatar2Id(avatar2Id);
        match.setMatchState(MatchState.OPEN);
        match.setSetLimit(3);

        matchRepository.save(match);

        // Rule 2: verify via assertj-db, NOT via matchRepository.findById(...)
        // Row-presence check (not exact count) for isolation in shared H2 DB.
        Table table = assertDb.table("match").build();
        final UUID savedId = matchId;
        assertThat(table.getRowsList())
                .as("saved match must appear in the match table")
                .anyMatch(row -> savedId.equals(row.getColumnValue("ID").getValue()));
    }

    // -------------------------------------------------------------------------
    // Read-path tests (DEC-26 Rule 3: fixtures via direct JDBC, not repository writes)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findById returns present when match exists (fixture via direct JDBC)")
    void findByIdReturnsPresentForExistingRow() {
        UUID matchId = UUID.randomUUID();
        // Rule 3: insert fixture via direct JDBC
        jdbcTemplate.update(
                "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tenantId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                0,
                3);

        Optional<Match> found = matchRepository.findById(matchId);
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(matchId);
    }

    @Test
    @DisplayName("findByPhaseId returns all matches for a phase (fixture via direct JDBC)")
    void findByPhaseIdReturnsMatchesForPhase() {
        UUID matchId1 = UUID.randomUUID();
        UUID matchId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId1,
                tenantId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                0,
                3);
        jdbcTemplate.update(
                "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id, state, set_limit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId2,
                tenantId,
                tournamentId,
                phaseId,
                avatar2Id,
                avatar1Id,
                0,
                3);

        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        assertThat(matches).hasSize(2);
    }
}
