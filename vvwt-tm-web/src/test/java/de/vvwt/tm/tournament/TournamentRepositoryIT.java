package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * DAO integration tests for {@link TournamentRepository} (E21S02,
 * AC-DAO-3RULES-TournamentRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TournamentRepository} at {@code
 * de.vvwt.tm.tournament.TournamentRepository} did not exist at commit time, causing a compile error
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
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path tests insert fixtures via direct JDBC —
 *       never via the repository's own save/insert methods.
 * </ol>
 *
 * @see TournamentRepository
 * @see de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction (inventory line 311)</a>
 */
@SpringBootTest(
        classes = {TournamentManagerApplication.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e21s02-repository-it;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TournamentRepository DAO IT — E21S02 DEC-26 three rules")
class TournamentRepositoryIT {

    @Autowired
    @Qualifier("tmTournamentRepository")
    private TournamentRepository tournamentRepository;

    @Autowired private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private AssertDbConnection assertDb;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        // Bind the default tenant for all operations in this test
        tenantId = tenantBinder.bindDefaultTenant();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Remove test-inserted data via direct JDBC. Delete child tables first to satisfy FK
        // constraints (team, phase, match etc. reference tournament). Only clean up the tenant's
        // rows so other concurrent test contexts are unaffected.
        try (var conn = dataSource.getConnection()) {
            // Delete dependent child rows first (ordered by FK depth)
            for (String sql :
                    new String[] {
                        "DELETE FROM set_result WHERE tenant_id = ?",
                        "DELETE FROM match_entity WHERE tenant_id = ?",
                        "DELETE FROM team WHERE tenant_id = ?",
                        "DELETE FROM phase WHERE tenant_id = ?",
                        "DELETE FROM tournament WHERE tenant_id = ?"
                    }) {
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setObject(1, tenantId);
                    ps.executeUpdate();
                } catch (Exception ignored) {
                    // Best-effort cleanup: ignore tables that do not exist or have no rows
                }
            }
        }
        tenantBinder.unbind();
    }

    // =========================================================================
    // Write-path test (DEC-26 Rule 2 — independent DB verifier via assertj-db)
    // =========================================================================

    @Test
    @DisplayName("save() persists a tournament row verified independently via assertj-db")
    void save_persistsTournamentRow_verifiedViaAssertjDb() {
        // GIVEN
        UUID id = UUID.randomUUID();
        Tournament t = buildTournament(id, tenantId, "Hallenturnier E21S02", "BEST_OF_3");

        // WHEN — write via repository
        tournamentRepository.save(t);

        // THEN — verify via assertj-db (DEC-26 Rule 2: never use repo.findById to verify a write)
        Table table = assertDb.table("tournament").build();
        List<Object> ids =
                table.getRowsList().stream()
                        .map(row -> row.getColumnValue("ID").getValue())
                        .toList();
        assertThat(ids)
                .as("The saved tournament UUID must be present in the tournament table")
                .contains(id);
    }

    // =========================================================================
    // Read-path test (DEC-26 Rule 3 — direct-JDBC fixture)
    // =========================================================================

    @Test
    @DisplayName("findById() retrieves a row inserted directly via JDBC (DEC-26 Rule 3)")
    void findById_withDirectlyInsertedRow_returnsEntity() {
        // GIVEN — fixture inserted via TenantDaoTestSupport.insertDirectly (DEC-26 Rule 3)
        UUID id = UUID.randomUUID();
        insertTournamentViaTestSupport(id, tenantId, "Direct-fixture tournament", "BEST_OF_1");

        // WHEN
        Optional<Tournament> result = tournamentRepository.findById(id);

        // THEN
        assertThat(result)
                .as("findById() must return the tournament inserted via direct JDBC")
                .isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
        assertThat(result.get().getDescription()).isEqualTo("Direct-fixture tournament");
        assertThat(result.get().getMatchFormat()).isEqualTo("BEST_OF_1");
    }

    @Test
    @DisplayName("findById() returns empty for unknown ID")
    void findById_unknownId_returnsEmpty() {
        UUID unknownId = UUID.randomUUID();

        Optional<Tournament> result = tournamentRepository.findById(unknownId);

        assertThat(result).as("findById() must return empty for an unknown ID").isEmpty();
    }

    @Test
    @DisplayName("findAll() returns all rows for the current tenant")
    void findAll_returnsCurrentTenantRows() {
        // GIVEN — two rows for current tenant
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        insertTournamentViaTestSupport(id1, tenantId, "Tournament A", "BEST_OF_3");
        insertTournamentViaTestSupport(id2, tenantId, "Tournament B", "BEST_OF_1");

        // WHEN
        List<Tournament> result = tournamentRepository.findAll();

        // THEN
        assertThat(result.stream().map(Tournament::getId).toList())
                .as("findAll() must return both tournaments for the current tenant")
                .contains(id1, id2);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Tournament buildTournament(UUID id, UUID tenantId, String description, String fmt) {
        Tournament t = new Tournament();
        t.setId(id);
        t.setTenantId(tenantId);
        t.setDescription(description);
        t.setMatchFormat(fmt);
        t.setScoringRuleId("setPoints");
        t.setSetValidationRuleId("standardVolleyball");
        t.setMatchGeneratorId("roundRobin");
        t.setStatus("DRAFT");
        t.setCreatedAt(LocalDateTime.now());
        t.setFieldCount(2);
        t.setTeamCount(4);
        return t;
    }

    /**
     * Inserts a tournament row via {@link TenantDaoTestSupport#insertDirectly} — DEC-26 Rule 3
     * compliance for read-path tests. Must not be replaced with {@code tournamentRepository.save()}
     * in any read-path test.
     */
    private void insertTournamentViaTestSupport(
            UUID id, UUID tId, String description, String matchFormat) {
        var cols = new LinkedHashMap<String, Object>();
        cols.put("id", id);
        cols.put("tenant_id", tId);
        cols.put("description", description);
        cols.put("match_format", matchFormat);
        cols.put("scoring_rule_id", "setPoints");
        cols.put("set_validation_rule_id", "standardVolleyball");
        cols.put("match_generator_id", "roundRobin");
        cols.put("status", "DRAFT");
        cols.put("field_count", 2);
        cols.put("team_count", 4);
        TenantDaoTestSupport.insertDirectly(dataSource, "tournament", cols);
    }
}
