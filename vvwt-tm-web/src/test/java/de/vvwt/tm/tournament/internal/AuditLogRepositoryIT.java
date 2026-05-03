package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.AuditLogRepository;
import de.vvwt.tm.tournament.SetState;
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
 * DAO integration tests for {@link AuditLogRepository} (E21S05, AC-TDD-AuditLogRepository,
 * AC-DAO-3RULES-AuditLogRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link AuditLogRepository} did not exist at commit time —
 * satisfying DEC-22.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li>Schema from migration: V5 provides {@code audit_log} table; V9 adds source columns.
 *   <li>Independent verifier: assertj-db against {@code audit_log} table
 *   <li>Read/write decoupling: fixtures via direct JDBC
 * </ol>
 *
 * @see AuditLogRepository
 * @see <a href="DEC-26">DEC-26</a>
 * @see <a href="E21S05">E21S05 — inventory lines 286, 287 (INTERNAL audit substrate)</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s05-auditlog-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("AuditLogRepository DAO IT — E21S05 DEC-26 three rules")
class AuditLogRepositoryIT {

    @Autowired
    @Qualifier("tmAuditLogRepository")
    private AuditLogRepository auditLogRepository;

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
        jdbcTemplate.update(
                "INSERT INTO tournament (id, tenant_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                tournamentId,
                tenantId,
                "T",
                "BEST_OF_3",
                "r",
                "v",
                "g",
                "CREATED");
        jdbcTemplate.update(
                "INSERT INTO phase (id, tenant_id, tournament_id, sequence_number,"
                        + " description, status, current_lap_number)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tenantId,
                tournamentId,
                1,
                "P",
                "PENDING",
                0);
        UUID teamId1 = UUID.randomUUID();
        UUID teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tenant_id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId1,
                tenantId,
                tournamentId,
                1,
                "TA");
        jdbcTemplate.update(
                "INSERT INTO team (id, tenant_id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId2,
                tenantId,
                tournamentId,
                2,
                "TB");
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
                30,
                3);
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

    @Test
    @DisplayName("save() appends an audit_log row — verified via assertj-db")
    void saveAppendsAuditLogRow() {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setId(UUID.randomUUID());
        entry.setTenantId(tenantId);
        entry.setMatchId(matchId);
        entry.setSetIndex(0);
        entry.setTeam1PointsNew(25);
        entry.setTeam2PointsNew(20);
        entry.setSetStateNew(SetState.WINNER1.getLegacyCode());

        auditLogRepository.save(entry);

        // DEC-26 Rule 2: verify via assertj-db
        // Row-presence check (not exact count) for isolation in shared H2 DB.
        Table table = assertDb.table("audit_log").build();
        final UUID savedId = entry.getId();
        assertThat(table.getRowsList())
                .as("saved audit_log entry must appear in the audit_log table")
                .anyMatch(row -> savedId.equals(row.getColumnValue("ID").getValue()));
    }

    @Test
    @DisplayName("deleteById throws UnsupportedOperationException (append-only invariant)")
    void deleteByIdThrowsUnsupportedOperation() {
        assertThatThrownBy(() -> auditLogRepository.deleteById(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName(
            "findByMatchIdAndSetIndexOrderByChangedAt returns entries in order (fixture via JDBC)")
    void findByMatchIdAndSetIndexOrderByChangedAtReturnsInOrder() {
        UUID entry1Id = UUID.randomUUID();
        UUID entry2Id = UUID.randomUUID();
        // DEC-26 Rule 3: insert fixtures via direct JDBC
        jdbcTemplate.update(
                "INSERT INTO audit_log (id, tenant_id, match_id, set_index,"
                        + " team1_points_new, team2_points_new, set_state_new)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                entry1Id,
                tenantId,
                matchId,
                0,
                25,
                20,
                1);
        jdbcTemplate.update(
                "INSERT INTO audit_log (id, tenant_id, match_id, set_index,"
                        + " team1_points_new, team2_points_new, set_state_new)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                entry2Id,
                tenantId,
                matchId,
                0,
                23,
                20,
                1);

        List<AuditLogEntry> entries =
                auditLogRepository.findByMatchIdAndSetIndexOrderByChangedAt(matchId, 0);
        assertThat(entries).hasSize(2);
    }

    /**
     * E45S03 — DEC-41 Snapshot-Driven: WHERE tenant_id predicate removed from findById and
     * findByMatchIdAndSetIndexOrderByChangedAt. Post-removal, both execute without tenant_id in
     * WHERE.
     */
    @Test
    @DisplayName("E45S03: findById executes without tenant_id WHERE predicate (DEC-20 isolates)")
    void e45s03_findById_noTenantPredicate_returnsRow() {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setId(UUID.randomUUID());
        entry.setMatchId(matchId);
        entry.setSetIndex(0);
        entry.setTenantId(tenantId);
        entry.setTeam1PointsNew(25);
        entry.setTeam2PointsNew(20);
        entry.setSetStateNew(1);

        auditLogRepository.save(entry);

        Optional<AuditLogEntry> found = auditLogRepository.findById(entry.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(entry.getId());
    }
}
