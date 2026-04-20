package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for {@link PhaseRepository} — DEC-26 three-rule compliance.
 *
 * <p>AC-TDD-PhaseRepository, AC-DAO-3RULES-PhaseRepository.
 *
 * <ul>
 *   <li>Rule 1: schema via {@code TenantDaoTestSupport.applyTournamentSchema()} — no inlined DDL
 *   <li>Rule 2: write-verification via assertj-db {@code table("phase")} — never via the
 *       repository's own read method
 *   <li>Rule 3: fixture insertion via {@code TenantDaoTestSupport.insertDirectly()} — not via the
 *       repository under test
 * </ul>
 *
 * @see PhaseRepository
 * @see TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance: three rules</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 298)</a>
 */
class PhaseRepositoryIT {

    private DataSource ds;
    private AssertDbConnection assertDb;
    private PhaseRepository repo;
    private TenantContext tenantContext;
    private UUID tenantId;
    private UUID tournamentId;

    @BeforeEach
    void setUp() {
        ds = TenantDaoTestSupport.freshDataSource();
        // Rule 1: schema from production migration helper (DEC-26)
        TenantDaoTestSupport.applyTournamentSchema(ds);
        assertDb = TenantDaoTestSupport.assertDbOf(ds);
        tenantId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        // TenantContext for standalone test — uses LOCAL_HOLDER fallback via set()
        tenantContext =
                new TenantContext(
                        new de.vvwt.tm.tenant.TenantContext() {
                            @Override
                            public UUID current() {
                                throw new IllegalStateException(
                                        "no new context in standalone test");
                            }

                            @Override
                            public de.vvwt.tm.tenant.TenantContext.Scope bind(UUID id) {
                                return () -> {};
                            }
                        });
        tenantContext.set(tenantId);
        // Insert prerequisite tenant and tournament rows for FK constraints
        TenantDaoTestSupport.insertDirectly(
                ds,
                "tenants",
                Map.of(
                        "id",
                        tenantId,
                        "display_name",
                        "IT Tenant",
                        "tenant_location_count",
                        1,
                        "is_default",
                        false));
        TenantDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "id", tournamentId,
                        "tenant_id", tenantId,
                        "description", "IT Tournament",
                        "match_format", "BEST_OF_1",
                        "scoring_rule_id", "default",
                        "set_validation_rule_id", "default",
                        "match_generator_id", "default",
                        "status", "DRAFT",
                        "field_count", 2,
                        "team_count", 4));
        repo = new PhaseRepository(new JdbcTemplate(ds), tenantContext);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    /** AC-TDD-PhaseRepository: save persists a Phase row (Rule 2 — assertj-db verifier). */
    @Test
    void save_persistsPhaseRow() {
        UUID id = UUID.randomUUID();
        Phase phase = new Phase(id, tenantId, tournamentId, 1, "Vorrunde", "PENDING", 0, null);

        repo.save(phase);

        // Rule 2: verify via assertj-db Table, NOT via repo.findById()
        Table table = assertDb.table("phase").build();
        assertThat(table)
                .hasNumberOfRows(1)
                .row(0)
                .value("id")
                .isEqualTo(id)
                .value("description")
                .isEqualTo("Vorrunde")
                .value("status")
                .isEqualTo("PENDING");
    }

    /** AC-TDD-PhaseRepository: findById returns the saved phase scoped to the tenant. */
    @Test
    void findById_returnsSavedPhase() {
        UUID id = UUID.randomUUID();
        // Rule 3: insert fixture via direct JDBC for read-path test
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase",
                Map.of(
                        "id",
                        id,
                        "tenant_id",
                        tenantId,
                        "tournament_id",
                        tournamentId,
                        "sequence_number",
                        1,
                        "description",
                        "Vorrunde",
                        "status",
                        "PENDING",
                        "current_lap_number",
                        0));

        Optional<Phase> result = repo.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
        assertThat(result.get().getTenantId()).isEqualTo(tenantId);
        assertThat(result.get().getDescription()).isEqualTo("Vorrunde");
    }

    /** AC-TDD-PhaseRepository: findById returns empty for a different tenant. */
    @Test
    void findById_differentTenant_returnsEmpty() {
        UUID id = UUID.randomUUID();
        UUID otherTenantId = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                ds,
                "tenants",
                Map.of(
                        "id",
                        otherTenantId,
                        "display_name",
                        "Other Tenant",
                        "tenant_location_count",
                        1,
                        "is_default",
                        false));
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase",
                Map.of(
                        "id",
                        id,
                        "tenant_id",
                        otherTenantId,
                        "tournament_id",
                        tournamentId,
                        "sequence_number",
                        1,
                        "description",
                        "Vorrunde",
                        "status",
                        "PENDING",
                        "current_lap_number",
                        0));

        Optional<Phase> result = repo.findById(id);

        assertThat(result).isEmpty();
    }

    /** AC-TDD-PhaseRepository: findByTournamentId returns all phases for a tournament. */
    @Test
    void findByTournamentId_returnsAllPhasesForTournament() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase",
                Map.of(
                        "id",
                        id1,
                        "tenant_id",
                        tenantId,
                        "tournament_id",
                        tournamentId,
                        "sequence_number",
                        1,
                        "description",
                        "Vorrunde",
                        "status",
                        "PENDING",
                        "current_lap_number",
                        0));
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase",
                Map.of(
                        "id",
                        id2,
                        "tenant_id",
                        tenantId,
                        "tournament_id",
                        tournamentId,
                        "sequence_number",
                        2,
                        "description",
                        "Finale",
                        "status",
                        "PENDING",
                        "current_lap_number",
                        0));

        List<Phase> results = repo.findByTournamentId(tournamentId);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(Phase::getId).containsExactlyInAnyOrder(id1, id2);
    }
}
