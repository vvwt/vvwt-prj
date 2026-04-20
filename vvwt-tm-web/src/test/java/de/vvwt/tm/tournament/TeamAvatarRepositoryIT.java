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
 * DAO integration tests for {@link TeamAvatarRepository} (E21S04, AC-DAO-3RULES-TeamAvatarRepo).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TeamAvatarRepository} at {@code
 * de.vvwt.tm.tournament.TeamAvatarRepository} did not exist at commit time, causing a compile error
 * — satisfying the DEC-22 Iron Law.
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
 * @see TeamAvatarRepository
 * @see TeamAvatar
 * @see de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 456)</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s04-team-avatar-repository-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TeamAvatarRepository DAO IT — E21S04 DEC-26 three rules")
class TeamAvatarRepositoryIT {

    @Autowired
    @Qualifier("tmTeamAvatarRepository")
    private TeamAvatarRepository teamAvatarRepository;

    @Autowired private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID teamId;
    private AssertDbConnection assertDb;
    private int teamNumberCounter = 0;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantId = tenantBinder.bindDefaultTenant();
        teamNumberCounter = 0;

        tournamentId = insertTournamentFixture();
        phaseId = insertPhaseFixture(tournamentId);
        teamId = insertTeamFixture(tournamentId);
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var conn = dataSource.getConnection()) {
            for (String sql :
                    new String[] {
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
    @DisplayName("save() — new TeamAvatar persists to 'team_avatar' table (assertj-db)")
    void save_newAvatar_persistsToTeamAvatarTable() {
        TeamAvatar avatar = newAvatar(phaseId, 1, 1, teamId);
        teamAvatarRepository.save(avatar);

        // DEC-26 Rule 2: verify via assertj-db, not repo read.
        // Row-presence check (not exact row count) for isolation in shared H2 DB.
        Table table = assertDb.table("team_avatar").build();
        final UUID savedId = avatar.getId();
        assertThat(table.getRowsList())
                .as("saved avatar must appear in team_avatar table")
                .anyMatch(
                        row ->
                                savedId.equals(row.getColumnValue("ID").getValue())
                                        && tenantId.equals(
                                                row.getColumnValue("TENANT_ID").getValue())
                                        && tournamentId.equals(
                                                row.getColumnValue("TOURNAMENT_ID").getValue())
                                        && phaseId.equals(row.getColumnValue("PHASE_ID").getValue())
                                        && Objects.equals(
                                                row.getColumnValue("GROUP_NUMBER").getValue(), 1)
                                        && Objects.equals(
                                                row.getColumnValue("GROUP_POSITION").getValue(), 1)
                                        && teamId.equals(row.getColumnValue("TEAM_ID").getValue()));
    }

    // -------------------------------------------------------------------------
    // Read-path tests — DEC-26 Rule 3: insert via direct JDBC
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findByTeamId() — returns avatars for the given team (Rule 3)")
    void findByTeamId_returnsAvatarsForTeam() {
        UUID avatarId1 = UUID.randomUUID();
        UUID avatarId2 = UUID.randomUUID();
        insertAvatarDirectly(avatarId1, phaseId, 1, 1, teamId);
        insertAvatarDirectly(avatarId2, phaseId, 1, 2, teamId);

        // Insert another team's avatar — must not appear
        UUID otherTeamId = insertTeamFixture(tournamentId);
        insertAvatarDirectly(UUID.randomUUID(), phaseId, 1, 3, otherTeamId);

        List<TeamAvatar> avatars = teamAvatarRepository.findByTeamId(teamId);
        assertThat(avatars).hasSize(2);
        assertThat(avatars)
                .extracting(TeamAvatar::getId)
                .containsExactlyInAnyOrder(avatarId1, avatarId2);
    }

    @Test
    @DisplayName("findById() — returns present when avatar exists")
    void findById_returnsPresentWhenExists() {
        UUID id = UUID.randomUUID();
        insertAvatarDirectly(id, phaseId, 2, 1, teamId);

        Optional<TeamAvatar> result = teamAvatarRepository.findById(id);
        assertThat(result).isPresent();
        assertThat(result.get().getGroupNumber()).isEqualTo(2);
        assertThat(result.get().getGroupPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("findById() — returns empty for unknown id")
    void findById_returnsEmptyForUnknown() {
        Optional<TeamAvatar> result = teamAvatarRepository.findById(UUID.randomUUID());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteById() — removes avatar row from 'team_avatar' table (assertj-db)")
    void deleteById_removesRow() {
        TeamAvatar avatar = newAvatar(phaseId, 3, 1, teamId);
        teamAvatarRepository.save(avatar);

        teamAvatarRepository.deleteById(avatar.getId());

        // DEC-26 Rule 2: verify the specific row is gone. Row-absence check for isolation.
        Table table = assertDb.table("team_avatar").build();
        final UUID deletedId = avatar.getId();
        assertThat(table.getRowsList())
                .as("deleted avatar must not appear in team_avatar table")
                .noneMatch(row -> deletedId.equals(row.getColumnValue("ID").getValue()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TeamAvatar newAvatar(UUID pId, int groupNum, int groupPos, UUID tId) {
        TeamAvatar a = new TeamAvatar();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setTournamentId(tournamentId);
        a.setPhaseId(pId);
        a.setGroupNumber(groupNum);
        a.setGroupPosition(groupPos);
        a.setTeamId(tId);
        return a;
    }

    private void insertAvatarDirectly(UUID id, UUID pId, int groupNum, int groupPos, UUID tId) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", id);
        cols.put("tenant_id", tenantId);
        cols.put("tournament_id", tournamentId);
        cols.put("phase_id", pId);
        cols.put("group_number", groupNum);
        cols.put("group_position", groupPos);
        cols.put("team_id", tId);
        TenantDaoTestSupport.insertDirectly(dataSource, "team_avatar", cols);
    }

    private UUID insertTournamentFixture() {
        // Insert tenant row first
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
        cols.put("description", "Test Tournament - TeamAvatarRepositoryIT");
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
        cols.put("team_number", ++teamNumberCounter);
        cols.put("description", "Fixture Team " + teamNumberCounter);
        cols.put("participate", true);
        cols.put("referee_assignment", false);
        cols.put("without_assessment", false);
        TenantDaoTestSupport.insertDirectly(dataSource, "team", cols);
        return id;
    }
}
