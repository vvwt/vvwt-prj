package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.db.api.Assertions.assertThat;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.internal.ThreadLocalTenantContextImpl;
import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
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
 * Integration test for {@link DefaultPhaseBreakRepository} — DEC-26 three-rule compliance.
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
 * @see DefaultPhaseBreakRepository
 * @see PhaseBreakRepository
 * @see TenantDaoTestSupport
 * @since E57S01 (moved from tournament.PhaseBreakRepositoryIT to tournament.internal per DEC-58
 *     interface extraction)
 */
class DefaultPhaseBreakRepositoryIT {

    private DataSource ds;
    private AssertDbConnection assertDb;
    private PhaseBreakRepository repo;
    private TenantContext tenantContext;
    private TenantContext.Scope tenantScope;
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
        repo = new DefaultPhaseBreakRepository(new JdbcTemplate(ds));
    }

    @AfterEach
    void tearDown() {
        tenantScope.close();
    }

    /** AC-TDD-PhaseBreakRepository: save persists a PhaseBreak row (Rule 2 — assertj-db). */
    @Test
    void save_persistsPhaseBreakRow() {
        UUID id = UUID.randomUUID();
        PhaseBreak phaseBreak = new PhaseBreak(id, phaseId, 2, 30, "Mittagspause");

        repo.save(phaseBreak);

        // Rule 2: verify via assertj-db Table, NOT via repo.findByPhaseId()
        Table table = assertDb.table("phase_breaks").build();
        assertThat(table)
                .hasNumberOfRows(1)
                .row(0)
                .value("id")
                .isEqualTo(id)
                .value("after_lap_number")
                .isEqualTo(2)
                .value("duration_minutes")
                .isEqualTo(30)
                .value("label")
                .isEqualTo("Mittagspause");
    }

    /** AC-TDD-PhaseBreakRepository: findByPhaseId returns all breaks for a phase. */
    @Test
    void findByPhaseId_returnsAllBreaksForPhase() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        // Rule 3: fixture via direct JDBC
        // E45S06: tenant_id removed from phase_breaks (DEC-39 D1)
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase_breaks",
                Map.of(
                        "id",
                        id1,
                        "phase_id",
                        phaseId,
                        "after_lap_number",
                        2,
                        "duration_minutes",
                        30));
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase_breaks",
                Map.of(
                        "id",
                        id2,
                        "phase_id",
                        phaseId,
                        "after_lap_number",
                        4,
                        "duration_minutes",
                        15));

        List<PhaseBreak> results = repo.findByPhaseId(phaseId);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(PhaseBreak::getId).containsExactlyInAnyOrder(id1, id2);
    }

    /** AC-TDD-PhaseBreakRepository: findByPhaseIdAndAfterLapNumber returns present when found. */
    @Test
    void findByPhaseIdAndAfterLapNumber_whenExists_returnsPresent() {
        UUID id = UUID.randomUUID();
        // E45S06: tenant_id removed from phase_breaks (DEC-39 D1)
        TenantDaoTestSupport.insertDirectly(
                ds,
                "phase_breaks",
                Map.of(
                        "id",
                        id,
                        "phase_id",
                        phaseId,
                        "after_lap_number",
                        3,
                        "duration_minutes",
                        20));

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
