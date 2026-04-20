package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link PhaseBreakRepository} — DEC-26 three-rule compliance.
 *
 * <p>AC-TDD-PhaseBreakRepository, AC-DAO-3RULES-PhaseBreakRepository.
 *
 * <ul>
 *   <li>Rule 1: schema via {@code TenantDaoTestSupport.applyTournamentSchema()} — no inlined DDL
 *   <li>Rule 2: write-verification via assertj-db {@code table("phase_break")} — never via the
 *       repository's own read method
 *   <li>Rule 3: fixture insertion via {@code TenantDaoTestSupport.insertDirectly()} — not via the
 *       repository under test
 * </ul>
 *
 * @see PhaseBreakRepository
 * @see TenantDaoTestSupport
 * @see <a href="DEC-26">DEC-26 — DAO test governance: three rules</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 296)</a>
 */
class PhaseBreakRepositoryIT {

    private DataSource ds;
    private AssertDbConnection assertDb;
    private PhaseBreakRepository repo;
    private UUID tenantId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        ds = TenantDaoTestSupport.freshDataSource();
        // Rule 1: schema from production migration helper (DEC-26)
        TenantDaoTestSupport.applyTournamentSchema(ds);
        assertDb = TenantDaoTestSupport.assertDbOf(ds);
        tenantId = UUID.randomUUID();
        UUID tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        // Insert prerequisite tenant, tournament, and phase rows for FK constraints
        TenantDaoTestSupport.insertDirectly(
                ds,
                "tenants",
                Map.of(
                        "id", tenantId,
                        "display_name", "IT Tenant",
                        "tenant_location_count", 1,
                        "is_default", false));
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
                        "id", phaseId,
                        "tenant_id", tenantId,
                        "tournament_id", tournamentId,
                        "sequence_number", 1,
                        "description", "Vorrunde",
                        "status", "PENDING",
                        "current_lap_number", 0));
        repo = new PhaseBreakRepository(ds, tenantId);
    }

    /** AC-TDD-PhaseBreakRepository: save persists a PhaseBreak row (Rule 2 — assertj-db). */
    @Test
    void save_persistsPhaseBreakRow() {
        UUID id = UUID.randomUUID();
        PhaseBreak phaseBreak = new PhaseBreak(id, tenantId, phaseId, 2, 30, "Mittagspause");

        repo.save(phaseBreak);

        // Rule 2: verify via assertj-db Table, NOT via repo.findByPhaseId()
        Table table = assertDb.table("phase_breaks").build();
        assertThat(table)
                .hasNumberOfRows(1)
                .row(0)
                .value("id").isEqualTo(id)
                .value("after_lap_number").isEqualTo(2)
                .value("duration_minutes").isEqualTo(30)
                .value("label").isEqualTo("Mittagspause");
    }

    /** AC-TDD-PhaseBreakRepository: findByPhaseId returns all breaks for a phase. */
    @Test
    void findByPhaseId_returnsAllBreaksForPhase() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        // Rule 3: fixture via direct JDBC
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase_breaks",
                Map.of(
                        "id", id1,
                        "phase_id", phaseId,
                        "after_lap_number", 2,
                        "duration_minutes", 30,
                        "tenant_id", tenantId));
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase_breaks",
                Map.of(
                        "id", id2,
                        "phase_id", phaseId,
                        "after_lap_number", 4,
                        "duration_minutes", 15,
                        "tenant_id", tenantId));

        List<PhaseBreak> results = repo.findByPhaseId(phaseId);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PhaseBreak::getId).containsExactlyInAnyOrder(id1, id2);
    }

    /** AC-TDD-PhaseBreakRepository: findByPhaseIdAndAfterLapNumber returns present when found. */
    @Test
    void findByPhaseIdAndAfterLapNumber_whenExists_returnsPresent() {
        UUID id = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase_breaks",
                Map.of(
                        "id", id,
                        "phase_id", phaseId,
                        "after_lap_number", 3,
                        "duration_minutes", 20,
                        "tenant_id", tenantId));

        Optional<PhaseBreak> result = repo.findByPhaseIdAndAfterLapNumber(phaseId, 3);

        assertThat(result).isPresent();
        assertThat(result.get().getAfterLapNumber()).isEqualTo(3);
    }

    /** AC-TDD-PhaseBreakRepository: findByPhaseIdAndAfterLapNumber returns empty when absent. */
    @Test
    void findByPhaseIdAndAfterLapNumber_whenAbsent_returnsEmpty() {
        Optional<PhaseBreak> result = repo.findByPhaseIdAndAfterLapNumber(phaseId, 99);
        assertThat(result).isEmpty();
    }
}
