package de.vvwt.tm.tournament;

import de.vvwt.tm.infrastructure.testsupport.TenantDaoTestSupport;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.internal.ThreadLocalTenantContextImpl;
import de.vvwt.tm.tournament.internal.DefaultPhaseRepository;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AC-TEST-COLUMN-SCOPED-DOES-NOT-TOUCH-OTHER-FIELDS (E55S09, DEC-22 RED-first).
 *
 * <p>RED-first structural-invariant test for the new {@link
 * PhaseRepository#updateLastJobState(UUID, String)} method.
 *
 * <p>Invariant under test: calling {@code updateLastJobState(phaseId, "idle")} MUST update ONLY the
 * {@code last_job_state} column. Every other column (status, optimized, current_lap_number,
 * description) MUST remain unchanged from the pre-call value.
 *
 * <p>RED rationale: before the fix, no {@code updateLastJobState} method exists on the interface
 * (compilation failure) or an equivalent would use {@code save(phase)} which writes all fields —
 * this test fails RED because the method is absent. GREEN post-fix: the method exists and executes
 * a column-scoped {@code UPDATE phase SET last_job_state=? WHERE id=?}.
 *
 * <p>DEC-26 three-rule compliance:
 *
 * <ul>
 *   <li>Rule 1: schema via {@link TenantDaoTestSupport#applyTournamentSchema(DataSource)} — no
 *       inline DDL.
 *   <li>Rule 2: write-verification via assertj-db {@code Table("phase")} — not via {@code
 *       repo.findById()}.
 *   <li>Rule 3: fixture insertion via {@link TenantDaoTestSupport#insertDirectly} — not via the
 *       repository under test.
 * </ul>
 *
 * @since E55S09
 */
@DisplayName(
        "AC-TEST-COLUMN-SCOPED-DOES-NOT-TOUCH-OTHER-FIELDS: updateLastJobState writes only"
                + " last_job_state (E55S09, DEC-22 RED-first)")
class UpdateLastJobStateColumnScopedIT {

    private DataSource ds;
    private AssertDbConnection assertDb;
    private PhaseRepository repo;
    private TenantContext tenantContext;
    private TenantContext.Scope tenantScope;
    private UUID tournamentId;
    private UUID locationId;

    @BeforeEach
    void setUp() {
        ds = TenantDaoTestSupport.freshDataSource();
        TenantDaoTestSupport.applyTournamentSchema(ds);
        assertDb = TenantDaoTestSupport.assertDbOf(ds);
        tenantContext = new ThreadLocalTenantContextImpl();
        tenantScope = tenantContext.bind(UUID.randomUUID());

        locationId = UUID.randomUUID();
        tournamentId = UUID.randomUUID();
        TenantDaoTestSupport.insertDirectly(
                ds, "locations", Map.of("id", locationId, "display_name", "ColScopedIT Loc"));
        TenantDaoTestSupport.insertDirectly(
                ds,
                "tournament",
                Map.of(
                        "id", tournamentId,
                        "location_id", locationId,
                        "description", "ColScopedIT Tournament",
                        "match_format", "BEST_OF_1",
                        "scoring_rule_id", "default",
                        "set_validation_rule_id", "default",
                        "match_generator_id", "default",
                        "status", "DRAFT",
                        "field_count", 2,
                        "team_count", 4));
        repo = new DefaultPhaseRepository(new JdbcTemplate(ds));
    }

    @AfterEach
    void tearDown() {
        tenantScope.close();
    }

    /**
     * AC-TEST-COLUMN-SCOPED-DOES-NOT-TOUCH-OTHER-FIELDS: inserts a phase with all fields populated
     * (status=ACTIVE, optimized=TRUE, current_lap_number=2, description=original,
     * last_job_state=slot_opt_queued). Calls updateLastJobState(phaseId, "idle"). Asserts that
     * last_job_state changed to 'idle' AND every other field is unchanged.
     *
     * <p>RED on pre-fix codebase: method {@code updateLastJobState} does not exist → compilation
     * failure or save(phase) writes all fields.
     *
     * <p>GREEN post-fix: column-scoped UPDATE preserves all other fields.
     */
    @Test
    @DisplayName(
            "updateLastJobState writes only last_job_state; status/optimized/lap/description"
                    + " unchanged")
    void updateLastJobState_writesOnlyLastJobState_allOtherFieldsPreserved() {
        UUID phaseId = UUID.randomUUID();
        // Rule 3: insert fixture via direct JDBC — pre-fix state simulates post-pipeline-post-
        // operator-activation: status=ACTIVE, optimized=TRUE, current_lap_number=2,
        // description=original, last_job_state=slot_opt_queued
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
                        "original description",
                        "status",
                        "ACTIVE",
                        "current_lap_number",
                        2,
                        "optimized",
                        true,
                        "last_job_state",
                        "slot_opt_queued"));

        // Act — column-scoped write (RED: method does not exist on pre-fix interface)
        repo.updateLastJobState(phaseId, "idle");

        // Rule 2: verify via assertj-db Table, NOT via repo.findById()
        Table table = assertDb.table("phase").build();
        org.assertj.db.api.Assertions.assertThat(table)
                .hasNumberOfRows(1)
                .row(0)
                // last_job_state MUST be updated
                .value("last_job_state")
                .isEqualTo("idle")
                // All other mutable columns MUST be unchanged
                .value("status")
                .isEqualTo("ACTIVE")
                .value("optimized")
                .isTrue()
                .value("current_lap_number")
                .isEqualTo(2)
                .value("description")
                .isEqualTo("original description");
    }

    /**
     * Boundary: calling updateLastJobState on a non-existent phase MUST NOT throw an unhandled
     * exception — it is a no-op or returns gracefully per
     * AC-ERROR-HANDLING-H-B-COLUMN-WRITE-ATOMIC.
     *
     * <p>The table remains empty (no row inserted). The method must not throw.
     */
    @Test
    @DisplayName("updateLastJobState on non-existent phase is a graceful no-op (atomic no-row)")
    void updateLastJobState_nonExistentPhase_noOp() {
        UUID nonExistentPhaseId = UUID.randomUUID();

        // Must not throw
        repo.updateLastJobState(nonExistentPhaseId, "idle");

        Table table = assertDb.table("phase").build();
        org.assertj.db.api.Assertions.assertThat(table).hasNumberOfRows(0);
    }
}
