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
    private UUID defaultLocationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID teamId;
    private UUID avatarId;
    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantId = tenantBinder.bindDefaultTenant();
        // E45S06: tenant_id removed (DEC-39 D1); tournament uses location_id (D2)
        defaultLocationId = tenantBinder.getDefaultLocationId();

        tournamentId = insertTournamentFixture();
        phaseId = insertPhaseFixture(tournamentId);
        teamId = insertTeamFixture(tournamentId);
        avatarId = insertAvatarFixture(tournamentId, phaseId, teamId);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var conn = dataSource.getConnection()) {
            // E45S06: tenant_id removed — simple DELETE (per-tenant DB isolation via DEC-20)
            for (String sql :
                    new String[] {
                        "DELETE FROM team_avatar_rating",
                        "DELETE FROM team_avatar",
                        "DELETE FROM team",
                        "DELETE FROM phase",
                        "DELETE FROM tournament"
                    }) {
                try (var ps = conn.prepareStatement(sql)) {
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
        // E45S06: TENANT_ID column removed from team_avatar_rating (DEC-39 D1)
        assertThat(table.getRowsList())
                .as("saved rating must appear in team_avatar_rating table")
                .anyMatch(
                        row ->
                                avatarId.equals(row.getColumnValue("AVATAR_ID").getValue())
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

    /**
     * E45S03 — DEC-41 Snapshot-Driven: WHERE tenant_id predicate removed from findByAvatarId.
     * Post-removal, findByAvatarId executes without tenant_id in the WHERE clause.
     */
    @Test
    @DisplayName(
            "E45S03: findByAvatarId executes without tenant_id WHERE predicate (DEC-20 isolates)")
    void e45s03_findByAvatarId_noTenantPredicate_returnsRow() {
        insertRatingDirectly(avatarId, 15, 5, 1, 5.0, 2.0);

        Optional<TeamAvatarRating> result = teamAvatarRatingRepository.findByAvatarId(avatarId);

        assertThat(result).isPresent();
        assertThat(result.get().getAvatarId()).isEqualTo(avatarId);
    }

    // -------------------------------------------------------------------------
    // findByPhaseId — AC7 (E58S03 DEC-46 three-rule compliance)
    // -------------------------------------------------------------------------

    /**
     * AC7 (E58S03): {@link TeamAvatarRatingRepository#findByPhaseId(UUID)} returns all ratings for
     * avatars in the given phase.
     *
     * <p>DEC-46 three-rule compliance:
     *
     * <ol>
     *   <li>Schema from migration (Flyway via @SpringBootTest).
     *   <li>Independent verifier: write tests use assertj-db (not covered here — this is the
     *       read-path test).
     *   <li>Read-path fixtures inserted via direct JDBC ({@link
     *       TenantDaoTestSupport#insertDirectly}), never via the repository's own save methods.
     * </ol>
     */
    @Test
    @DisplayName("findByPhaseId() — returns all ratings for avatars in the given phase (AC7)")
    void findByPhaseId_returnsAllRatingsForPhase() {
        // Insert a second avatar in the same phase (team_number=2 to avoid
        // UQ_TEAM_TOURNAMENT_NUMBER)
        UUID teamId2 = insertTeamFixture(tournamentId, 2);
        UUID avatarId2 = insertAvatarFixtureAt(tournamentId, phaseId, teamId2, 1, 2);

        // Insert ratings via direct JDBC (DEC-26 Rule 3)
        insertRatingDirectly(avatarId, 20, 4, 1, 4.0, 2.0);
        insertRatingDirectly(avatarId2, 15, 3, 2, 1.5, 1.2);

        List<TeamAvatarRating> ratings = teamAvatarRatingRepository.findByPhaseId(phaseId);

        assertThat(ratings).hasSize(2);
        assertThat(ratings)
                .extracting(TeamAvatarRating::getAvatarId)
                .containsExactlyInAnyOrder(avatarId, avatarId2);
    }

    @Test
    @DisplayName("findByPhaseId() — returns empty list when no ratings exist for phase")
    void findByPhaseId_returnsEmptyWhenNoRatingsForPhase() {
        // No ratings inserted — avatarId exists but has no rating row
        List<TeamAvatarRating> ratings = teamAvatarRatingRepository.findByPhaseId(phaseId);

        assertThat(ratings).isEmpty();
    }

    @Test
    @DisplayName("findByPhaseId() — does not return ratings from a different phase")
    void findByPhaseId_doesNotReturnRatingsFromOtherPhase() {
        // Insert a second phase
        UUID otherPhaseId = insertPhaseFixture(tournamentId, 2);
        // team_number=2 to avoid UQ_TEAM_TOURNAMENT_NUMBER unique constraint
        UUID teamId2 = insertTeamFixture(tournamentId, 2);
        UUID avatarInOtherPhase = insertAvatarFixtureAt(tournamentId, otherPhaseId, teamId2, 1, 1);

        // Insert rating for the first phase and the other phase
        insertRatingDirectly(avatarId, 25, 5, 0, 5.0, 3.0);
        insertRatingDirectly(avatarInOtherPhase, 10, 2, 1, 2.0, 1.0);

        List<TeamAvatarRating> ratings = teamAvatarRatingRepository.findByPhaseId(phaseId);

        assertThat(ratings).hasSize(1);
        assertThat(ratings.get(0).getAvatarId()).isEqualTo(avatarId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TeamAvatarRating newRating(UUID avId) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avId);
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
        // E45S06: tenant_id removed from team_avatar_rating (DEC-39 D1)
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
        UUID trnId = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", trnId);
        // E45S06: tenant_id removed; location_id NOT NULL (DEC-39 D1/D2)
        cols.put("location_id", defaultLocationId);
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
        // E45S06: tenant_id removed from phase (DEC-39 D1)
        cols.put("tournament_id", trnId);
        cols.put("sequence_number", 1);
        cols.put("description", "Phase 1");
        cols.put("status", "DRAFT");
        TenantDaoTestSupport.insertDirectly(dataSource, "phase", cols);
        return id;
    }

    private UUID insertTeamFixture(UUID trnId) {
        return insertTeamFixture(trnId, 1);
    }

    /**
     * Inserts a team with the given teamNumber — used by E58S03 AC7 tests that need a second team
     * in the same tournament without violating the UQ_TEAM_TOURNAMENT_NUMBER unique constraint.
     */
    private UUID insertTeamFixture(UUID trnId, int teamNumber) {
        UUID id = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        // E45S06: tenant_id removed from team (DEC-39 D1)
        cols.put("tournament_id", trnId);
        cols.put("team_number", teamNumber);
        cols.put("description", "Fixture Team " + teamNumber);
        cols.put("participate", true);
        cols.put("referee_assignment", false);
        cols.put("without_assessment", false);
        TenantDaoTestSupport.insertDirectly(dataSource, "team", cols);
        return id;
    }

    private UUID insertAvatarFixture(UUID trnId, UUID pId, UUID tId) {
        return insertAvatarFixtureAt(trnId, pId, tId, 1, 1);
    }

    /** Inserts avatar at specific (groupNumber, groupPosition) coordinates — E58S03 AC7. */
    private UUID insertAvatarFixtureAt(
            UUID trnId, UUID pId, UUID tId, int groupNumber, int groupPosition) {
        UUID id = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        // E45S06: tenant_id removed from team_avatar (DEC-39 D1)
        cols.put("tournament_id", trnId);
        cols.put("phase_id", pId);
        cols.put("group_number", groupNumber);
        cols.put("group_position", groupPosition);
        cols.put("team_id", tId);
        TenantDaoTestSupport.insertDirectly(dataSource, "team_avatar", cols);
        return id;
    }

    /** Inserts a second phase fixture at sequenceNumber — E58S03 AC7. */
    private UUID insertPhaseFixture(UUID trnId, int sequenceNumber) {
        UUID id = UUID.randomUUID();
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        // E45S06: tenant_id removed from phase (DEC-39 D1)
        cols.put("tournament_id", trnId);
        cols.put("sequence_number", sequenceNumber);
        cols.put("description", "Phase " + sequenceNumber);
        cols.put("status", "DRAFT");
        TenantDaoTestSupport.insertDirectly(dataSource, "phase", cols);
        return id;
    }
}
