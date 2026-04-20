package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.util.LinkedHashMap;
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
 * DAO integration tests for {@link TeamAvatarRatingRepository} (E21S04,
 * AC-DAO-3RULES-TeamAvatarRatingRepo).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TeamAvatarRatingRepository} at {@code
 * de.vvwt.tm.tournament.TeamAvatarRatingRepository} did not exist at commit time, causing a compile
 * error — satisfying the DEC-22 Iron Law.
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
 * @see TeamAvatarRatingRepository
 * @see TeamAvatarRating
 * @see de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 457)</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s04-team-avatar-rating-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TeamAvatarRatingRepository DAO IT — E21S04 DEC-26 three rules")
class TeamAvatarRatingRepositoryIT {

    @Autowired
    @Qualifier("tmTeamAvatarRatingRepository")
    private TeamAvatarRatingRepository teamAvatarRatingRepository;

    @Autowired private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID teamId;
    private UUID avatarId;
    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantId = tenantBinder.bindDefaultTenant();

        tournamentId = insertTournamentFixture();
        phaseId = insertPhaseFixture(tournamentId);
        teamId = insertTeamFixture(tournamentId);
        avatarId = insertAvatarFixture(tournamentId, phaseId, teamId);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var conn = dataSource.getConnection()) {
            for (String sql :
                    new String[] {
                        "DELETE FROM team_avatar_rating WHERE tenant_id = ?",
                        "DELETE FROM team_avatar WHERE tenant_id = ?",
                        "DELETE FROM team WHERE tenant_id = ?",
                        "DELETE FROM phase WHERE tenant_id = ?",
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
    @DisplayName(
            "save() — new TeamAvatarRating persists to 'team_avatar_rating' table (assertj-db)")
    void save_newRating_persistsToTeamAvatarRatingTable() {
        TeamAvatarRating rating = newRating(avatarId);
        teamAvatarRatingRepository.save(rating);

        // DEC-26 Rule 2: verify via assertj-db, not repo read.
        // Row-presence check (not exact row count) for isolation in shared H2 DB.
        Table table = assertDb.table("team_avatar_rating").build();
        assertThat(table.getRowsList())
                .as("saved rating must appear in team_avatar_rating table")
                .anyMatch(
                        row ->
                                avatarId.equals(row.getColumnValue("AVATAR_ID").getValue())
                                        && tenantId.equals(
                                                row.getColumnValue("TENANT_ID").getValue())
                                        && Objects.equals(
                                                row.getColumnValue("POINTS").getValue(), 10)
                                        && Objects.equals(
                                                row.getColumnValue("SETS_WON").getValue(), 4)
                                        && Objects.equals(
                                                row.getColumnValue("SETS_LOST").getValue(), 2));
    }

    @Test
    @DisplayName("save() — update existing rating changes stats in DB (assertj-db)")
    void save_existingRating_updatesInDb() {
        TeamAvatarRating rating = newRating(avatarId);
        teamAvatarRatingRepository.save(rating);

        rating.setPoints(20);
        rating.setSetsWon(8);
        teamAvatarRatingRepository.save(rating);

        // Row-presence check for isolation in shared H2 DB.
        Table table = assertDb.table("team_avatar_rating").build();
        assertThat(table.getRowsList())
                .as("updated rating must show points=20, sets_won=8 in DB")
                .anyMatch(
                        row ->
                                avatarId.equals(row.getColumnValue("AVATAR_ID").getValue())
                                        && Objects.equals(
                                                row.getColumnValue("POINTS").getValue(), 20)
                                        && Objects.equals(
                                                row.getColumnValue("SETS_WON").getValue(), 8));
    }

    // -------------------------------------------------------------------------
    // Read-path tests — DEC-26 Rule 3: insert via direct JDBC
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByAvatarId() — returns rating when it exists (Rule 3)")
    void findByAvatarId_returnsPresentWhenExists() {
        insertRatingDirectly(avatarId, 5, 2, 1, 15.0, 1.2);

        Optional<TeamAvatarRating> result = teamAvatarRatingRepository.findByAvatarId(avatarId);
        assertThat(result).isPresent();
        assertThat(result.get().getAvatarId()).isEqualTo(avatarId);
        assertThat(result.get().getPoints()).isEqualTo(5);
    }

    @Test
    @DisplayName("findByAvatarId() — returns empty for unknown avatarId")
    void findByAvatarId_returnsEmptyForUnknown() {
        Optional<TeamAvatarRating> result =
                teamAvatarRatingRepository.findByAvatarId(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteByAvatarId() — removes row from 'team_avatar_rating' (assertj-db)")
    void deleteByAvatarId_removesRow() {
        TeamAvatarRating rating = newRating(avatarId);
        teamAvatarRatingRepository.save(rating);

        teamAvatarRatingRepository.deleteByAvatarId(avatarId);

        // DEC-26 Rule 2: verify the specific row is gone. Row-absence check for isolation.
        Table table = assertDb.table("team_avatar_rating").build();
        assertThat(table.getRowsList())
                .as("deleted rating must not appear in team_avatar_rating table")
                .noneMatch(row -> avatarId.equals(row.getColumnValue("AVATAR_ID").getValue()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TeamAvatarRating newRating(UUID avId) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avId);
        r.setTenantId(tenantId);
        r.setMatchCount(3);
        r.setSetCount(6);
        r.setPoints(10);
        r.setSetsWon(4);
        r.setSetsLost(2);
        r.setBallsWon(90);
        r.setBallsLost(60);
        r.setSetQuotient(2.0);
        r.setBallQuotient(1.5);
        return r;
    }

    private void insertRatingDirectly(
            UUID avId, int points, int setsWon, int setsLost, double setQ, double ballQ) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("avatar_id", avId);
        cols.put("tenant_id", tenantId);
        cols.put("match_count", 2);
        cols.put("set_count", 4);
        cols.put("points", points);
        cols.put("sets_won", setsWon);
        cols.put("sets_lost", setsLost);
        cols.put("balls_won", 80);
        cols.put("balls_lost", 50);
        cols.put("set_quotient", setQ);
        cols.put("ball_quotient", ballQ);
        cols.put("is_without_assessment", false);
        TenantDaoTestSupport.insertDirectly(dataSource, "team_avatar_rating", cols);
    }

    private UUID insertTournamentFixture() {
        try {
            Map<String, Object> tenantCols = new LinkedHashMap<>();
            tenantCols.put("id", tenantId);
            tenantCols.put("name", "tenant-" + tenantId);
            tenantCols.put("subdomain", "t-" + tenantId.toString().substring(0, 8));
            TenantDaoTestSupport.insertDirectly(dataSource, "tenants", tenantCols);
        } catch (Exception ignored) {
            // tenant may already exist
        }

        UUID trnId = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", trnId);
        cols.put("tenant_id", tenantId);
        cols.put("description", "Test Tournament - TeamAvatarRatingRepositoryIT");
        cols.put("match_format", "BEST_OF_1");
        cols.put("scoring_rule_id", "defaultScoringRule");
        cols.put("set_validation_rule_id", "defaultSetValidationRule");
        cols.put("match_generator_id", "roundRobinMatchGenerator");
        cols.put("status", "DRAFT");
        TenantDaoTestSupport.insertDirectly(dataSource, "tournament", cols);
        return trnId;
    }

    private UUID insertPhaseFixture(UUID trnId) {
        UUID id = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        cols.put("tenant_id", tenantId);
        cols.put("tournament_id", trnId);
        cols.put("sequence_number", 1);
        cols.put("description", "Phase 1");
        cols.put("status", "DRAFT");
        TenantDaoTestSupport.insertDirectly(dataSource, "phase", cols);
        return id;
    }

    private UUID insertTeamFixture(UUID trnId) {
        UUID id = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        cols.put("tenant_id", tenantId);
        cols.put("tournament_id", trnId);
        cols.put("team_number", 1);
        cols.put("description", "Fixture Team");
        cols.put("participate", true);
        cols.put("referee_assignment", false);
        cols.put("without_assessment", false);
        TenantDaoTestSupport.insertDirectly(dataSource, "team", cols);
        return id;
    }

    private UUID insertAvatarFixture(UUID trnId, UUID pId, UUID tId) {
        UUID id = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        cols.put("tenant_id", tenantId);
        cols.put("tournament_id", trnId);
        cols.put("phase_id", pId);
        cols.put("group_number", 1);
        cols.put("group_position", 1);
        cols.put("team_id", tId);
        TenantDaoTestSupport.insertDirectly(dataSource, "team_avatar", cols);
        return id;
    }
}
