package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * DAO integration tests for {@link TournamentRepository} (E21S02, AC-DAO-3RULES-TournamentRepository).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link TournamentRepository} at
 * {@code de.vvwt.tm.tournament.TournamentRepository} did not exist at commit time, causing a
 * compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>DEC-26 three-rule compliance</h2>
 *
 * <ol>
 *   <li><b>Rule 1 — Schema from migration:</b> {@code @SpringBootTest} with Flyway applies all
 *       root migrations including V1–V16 in order. No inline DDL.
 *   <li><b>Rule 2 — Independent persistence verifier:</b> Write tests verify DB state via
 *       assertj-db ({@link AssertDbConnection}) against the DataSource — never via the
 *       repository's own read methods.
 *   <li><b>Rule 3 — Read/write decoupling:</b> Read-path tests insert fixtures via direct JDBC
 *       ({@link JdbcTemplate}) — never via the repository's own save/insert methods.
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
            "spring.datasource.url=jdbc:h2:mem:e21s02repodb;DB_CLOSE_DELAY=-1;"
                    + "DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TournamentRepository DAO IT — E21S02 DEC-26 three rules")
class TournamentRepositoryIT {

    @Autowired private TournamentRepository tournamentRepository;

    @Autowired private DataSource dataSource;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    private UUID tenantId;
    private AssertDbConnection assertDb;
    private TenantContextTestSupport.Binder.Scope tenantScope;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantScope = tenantBinder.bind(tenantId);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Remove test-inserted tournaments via direct JDBC to keep DB clean between tests
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement("DELETE FROM tournament WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            ps.executeUpdate();
        }
        if (tenantScope != null) {
            tenantScope.close();
        }
    }

    // =========================================================================
    // Write-path test (DEC-26 Rule 2 — independent DB verifier)
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
    void findById_withDirectlyInsertedRow_returnsEntity() throws Exception {
        // GIVEN — fixture inserted via direct JDBC (DEC-26 Rule 3: no DAO write in read-path test)
        UUID id = UUID.randomUUID();
        insertTournamentDirectly(id, tenantId, "Direct-fixture tournament", "BEST_OF_1");

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
    @DisplayName("findAll() returns only rows belonging to the current tenant")
    void findAll_returnsTenantScopedRows() throws Exception {
        // GIVEN — one row for current tenant, one for a different tenant
        UUID idOurs = UUID.randomUUID();
        UUID idOther = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();

        insertTournamentDirectly(idOurs, tenantId, "Ours", "BEST_OF_3");
        insertTournamentDirectly(idOther, otherTenantId, "Theirs", "BEST_OF_5");

        // WHEN
        List<Tournament> result = tournamentRepository.findAll();

        // THEN — must only see our tenant's tournament
        assertThat(result.stream().map(Tournament::getId).toList())
                .as("findAll() must return only the current tenant's tournament")
                .contains(idOurs)
                .doesNotContain(idOther);
    }

    @Test
    @DisplayName("findById() returns empty for a tournament belonging to a different tenant")
    void findById_differentTenant_returnsEmpty() throws Exception {
        // GIVEN — tournament belongs to another tenant
        UUID id = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        insertTournamentDirectly(id, otherTenantId, "Other tenant's tournament", "BEST_OF_3");

        // WHEN
        Optional<Tournament> result = tournamentRepository.findById(id);

        // THEN — tenant isolation must prevent cross-tenant access
        assertThat(result)
                .as("findById() must not return a tournament belonging to a different tenant")
                .isEmpty();
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
     * Inserts a tournament row directly via JDBC — DEC-26 Rule 3 compliance for read-path tests.
     * Must not be replaced with {@code tournamentRepository.save()} in any read-path test.
     */
    private void insertTournamentDirectly(
            UUID id, UUID tenantId, String description, String matchFormat) throws Exception {
        String sql =
                "INSERT INTO tournament (id, tenant_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, tenantId);
            ps.setString(3, description);
            ps.setString(4, matchFormat);
            ps.setString(5, "setPoints");
            ps.setString(6, "standardVolleyball");
            ps.setString(7, "roundRobin");
            ps.setString(8, "DRAFT");
            ps.setInt(9, 2);
            ps.setInt(10, 4);
            ps.executeUpdate();
        }
    }
}
