package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
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
 * Integration test for {@link RoundSnapshotRepository} — DEC-26 three-rule compliance.
 *
 * <p>AC-TDD-RoundSnapshotRepository, AC-DAO-3RULES-RoundSnapshotRepository.
 *
 * <ul>
 *   <li>Rule 1: schema via {@code TenantDaoTestSupport.applyTournamentSchema()} — no inlined DDL
 *   <li>Rule 2: write-verification via assertj-db {@code table("round_snapshots")} — never via the
 *       repository's own read method
 *   <li>Rule 3: fixture insertion via {@code TenantDaoTestSupport.insertDirectly()} — not via the
 *       repository under test
 * </ul>
 *
 * @see RoundSnapshotRepository
 * @see TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance: three rules</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 300)</a>
 */
class RoundSnapshotRepositoryIT {

    private DataSource ds;
    private AssertDbConnection assertDb;
    private RoundSnapshotRepository repo;
    private TenantContext tenantContext;
    private UUID tenantId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        ds = TenantDaoTestSupport.freshDataSource();
        // Rule 1: schema from production migration helper (DEC-26)
        TenantDaoTestSupport.applyTournamentSchema(ds);
        assertDb = TenantDaoTestSupport.assertDbOf(ds);
        tenantId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
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
        // Insert prerequisite rows for FK constraints
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
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase",
                Map.of(
                        "id",
                        phaseId,
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
        repo = new RoundSnapshotRepository(new JdbcTemplate(ds), tenantContext);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    /** AC-TDD-RoundSnapshotRepository: save persists a RoundSnapshot row (Rule 2 — assertj-db). */
    @Test
    void save_persistsRoundSnapshotRow() {
        UUID id = UUID.randomUUID();
        String payload = "{\"lapNumber\":1,\"standings\":[]}";
        RoundSnapshot snapshot =
                new RoundSnapshot(id, tenantId, tournamentId, phaseId, 1, payload, null);

        repo.save(snapshot);

        // Rule 2: verify via assertj-db Table, NOT via repo.findById()
        Table table = assertDb.table("round_snapshots").build();
        assertThat(table)
                .hasNumberOfRows(1)
                .row(0)
                .value("id")
                .isEqualTo(id)
                .value("lap_number")
                .isEqualTo(1)
                .value("snapshot_payload")
                .isEqualTo(payload);
    }

    /** AC-TDD-RoundSnapshotRepository: findById returns the saved snapshot scoped to tenant. */
    @Test
    void findById_returnsSavedSnapshot() {
        UUID id = UUID.randomUUID();
        String payload = "{\"lapNumber\":2,\"standings\":[]}";
        // Rule 3: insert fixture via direct JDBC for read-path test
        TenantDaoTestSupport.insertDirectly(
                ds,
                "round_snapshots",
                Map.of(
                        "id", id,
                        "tenant_id", tenantId,
                        "tournament_id", tournamentId,
                        "phase_id", phaseId,
                        "lap_number", 2,
                        "snapshot_payload", payload));

        Optional<RoundSnapshot> result = repo.findById(id);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
        assertThat(result.get().getLapNumber()).isEqualTo(2);
        assertThat(result.get().getSnapshotPayload()).isEqualTo(payload);
    }

    /** AC-TDD-RoundSnapshotRepository: findById returns empty for a different tenant. */
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
                "round_snapshots",
                Map.of(
                        "id", id,
                        "tenant_id", otherTenantId,
                        "tournament_id", tournamentId,
                        "phase_id", phaseId,
                        "lap_number", 1,
                        "snapshot_payload", "{}"));

        Optional<RoundSnapshot> result = repo.findById(id);

        assertThat(result).isEmpty();
    }
}
