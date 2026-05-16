package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.internal.ThreadLocalTenantContextImpl;
import de.vvwt.tm.tournament.RoundSnapshot;
import de.vvwt.tm.tournament.RoundSnapshotRepository;
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
 * Integration test for {@link DefaultRoundSnapshotRepository} — DEC-26 three-rule compliance.
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
 * @see DefaultRoundSnapshotRepository
 * @see RoundSnapshotRepository
 * @see TenantDaoTestSupport
 * @since E57S01 (moved from tournament.RoundSnapshotRepositoryIT to tournament.internal per DEC-58
 *     interface extraction)
 */
class DefaultRoundSnapshotRepositoryIT {

    private DataSource ds;
    private AssertDbConnection assertDb;
    private RoundSnapshotRepository repo;
    private TenantContext tenantContext;
    private TenantContext.Scope tenantScope;
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
        // Standalone test — no Spring context; use ThreadLocalTenantContextImpl directly.
        tenantContext = new ThreadLocalTenantContextImpl();
        tenantScope = tenantContext.bind(tenantId);
        // E45S06: tenant_id removed (DEC-39 D1); tournament requires location_id (DEC-39 D2)
        UUID locationId = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                ds, "locations", Map.of("id", locationId, "display_name", "IT Location"));
        TenantDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "id", tournamentId,
                        "location_id", locationId,
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
        repo = new DefaultRoundSnapshotRepository(new JdbcTemplate(ds));
    }

    @AfterEach
    void tearDown() {
        tenantScope.close();
    }

    /** AC-TDD-RoundSnapshotRepository: save persists a RoundSnapshot row (Rule 2 — assertj-db). */
    @Test
    void save_persistsRoundSnapshotRow() {
        UUID id = UUID.randomUUID();
        String payload = "{\"lapNumber\":1,\"standings\":[]}";
        RoundSnapshot snapshot = new RoundSnapshot(id, tournamentId, phaseId, 1, payload, null);

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
        // E45S06: tenant_id removed from round_snapshots (DEC-39 D1)
        TenantDaoTestSupport.insertDirectly(
                ds,
                "round_snapshots",
                Map.of(
                        "id", id,
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

    /**
     * E45S06 — DEC-41 Snapshot-Driven: findById executes without tenant_id WHERE predicate.
     * Post-S06: round_snapshots table has no tenant_id column (DEC-39 D1); isolation via DEC-20
     * routing.
     */
    @Test
    void findById_noTenantPredicate_returnsRow() {
        UUID id = UUID.randomUUID();
        // E45S06: tenant_id removed from round_snapshots (DEC-39 D1)
        TenantDaoTestSupport.insertDirectly(
                ds,
                "round_snapshots",
                Map.of(
                        "id", id,
                        "tournament_id", tournamentId,
                        "phase_id", phaseId,
                        "lap_number", 1,
                        "snapshot_payload", "{}"));

        Optional<RoundSnapshot> result = repo.findById(id);

        // Post-predicate-removal: row is returned; isolation comes from DataSource routing (DEC-20)
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(id);
    }
}
