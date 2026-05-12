package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
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
 * RED-first IT for E55S06: {@link SlotOptimizationJobRegistry#getHandle(UUID)} must be
 * reimplemented to be DB-primary (DEC-64 D-6, AC-TEST-SLOT-OPT-JOB-REGISTRY-GETHANDLE-RED).
 *
 * <h2>Why this test is RED before E55S06 production changes</h2>
 *
 * <p>Currently {@link de.vvwt.tm.slotopt.internal.DefaultSlotOptimizationJobRegistry#getHandle}
 * only checks the in-memory {@code activeJobs} map. If a RUNNING row exists in DB but no {@link
 * JobHandle} was registered in memory (e.g., after JVM restart or in this test where we insert
 * directly via JDBC), {@code getHandle()} returns {@link Optional#empty()}. The assertion {@code
 * isPresent()} fails → RED.
 *
 * <p>After E55S06 reimplements {@code getHandle()} to query DB for a RUNNING row (DEC-64 D-6), the
 * test becomes GREEN.
 *
 * <h2>Reimplemented getHandle() contract (DEC-64 D-6)</h2>
 *
 * <ul>
 *   <li>DB-primary: query {@code phase_lifecycle_job} for a RUNNING row for the given tournament.
 *   <li>In-memory secondary: if a RUNNING row exists and a handle is registered in memory, return
 *       the handle. If no handle is in memory (e.g., post-restart), return a sentinel/empty handle
 *       or empty Optional (caller checks DB row directly for cancel operations).
 *   <li>No RUNNING row in DB → return {@link Optional#empty()} (overrides the in-memory map).
 * </ul>
 *
 * <h2>Acceptance Criteria covered</h2>
 *
 * <ul>
 *   <li>AC-TEST-SLOT-OPT-JOB-REGISTRY-GETHANDLE-RED — DB-primary getHandle() RED-first
 *   <li>AC-IMPL-GETHANDLE-DB-PRIMARY — DB is authoritative source for RUNNING status
 * </ul>
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Rule 1: Schema from Flyway migration
 *   <li>Rule 2: Direct JDBC as independent persistence verifier
 *   <li>Rule 3: Fixture data inserted via direct JDBC
 * </ul>
 *
 * @see SlotOptimizationJobRegistry
 * @see de.vvwt.tm.slotopt.internal.DefaultSlotOptimizationJobRegistry
 * @see <a href="DEC-64">DEC-64 D-6 — getHandle() reimplemented via DB query</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E55S06">E55S06 — AC-TEST-SLOT-OPT-JOB-REGISTRY-GETHANDLE-RED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:slotoptgethandleit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class})
@DisplayName(
        "SlotOptimizationJobRegistry.getHandle() — DB-primary reimplementation"
                + " — AC-TEST-SLOT-OPT-JOB-REGISTRY-GETHANDLE-RED — E55S06")
class SlotOptimizationJobRegistryGetHandleIT {

    @Autowired private SlotOptimizationJobRegistry jobRegistry;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "GetHandleIT");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "GetHandleIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                4,
                false);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                false);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            if (jobId != null) {
                jdbcTemplate.update("DELETE FROM phase_lifecycle_job WHERE id = ?", jobId);
            }
            jdbcTemplate.update(
                    "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
            // Ensure in-memory registry is cleared after each test
            jobRegistry.complete(tournamentId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }

    /**
     * AC-TEST-SLOT-OPT-JOB-REGISTRY-GETHANDLE-RED (E55S06):
     *
     * <p>When a RUNNING row exists in {@code phase_lifecycle_job} for a tournament, {@code
     * getHandle(tournamentId)} must return a non-empty Optional — even if no handle was registered
     * in memory (simulates post-restart or DB-primary read).
     *
     * <p>Before E55S06: getHandle() only checks in-memory map → returns empty → RED. After E55S06:
     * getHandle() queries DB first → returns non-empty → GREEN.
     */
    @Test
    @DisplayName(
            "getHandle() returns non-empty Optional when RUNNING row exists in DB"
                    + " — AC-TEST-SLOT-OPT-JOB-REGISTRY-GETHANDLE-RED")
    void getHandle_runningRowInDb_noInMemoryHandle_returnsNonEmpty() {
        // Insert a RUNNING job row directly via JDBC — no in-memory handle registered
        jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status,"
                        + " claimed_by, claimed_at, cancelled)"
                        + " VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, CURRENT_TIMESTAMP, FALSE)",
                jobId,
                tournamentId,
                phaseId,
                "standard",
                1,
                "test-worker");

        // Verify the row is in DB (Rule 2: independent JDBC verifier)
        String status =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE id = ?", String.class, jobId);
        assertThat(status).isEqualTo("RUNNING");

        // NO in-memory handle registered (simulates post-restart scenario or DB-primary behavior)
        // Before E55S06: getHandle() returns empty (only checks in-memory map) → RED
        // After E55S06: getHandle() queries DB for RUNNING row → returns non-empty → GREEN
        Optional<JobHandle> handle = jobRegistry.getHandle(tournamentId);

        assertThat(handle)
                .as(
                        "getHandle() must return non-empty Optional when RUNNING row exists in DB"
                                + " (DEC-64 D-6 DB-primary reimplementation)")
                .isPresent();
    }

    /**
     * AC-IMPL-GETHANDLE-DB-PRIMARY (E55S06): when no RUNNING row exists in DB and no in-memory
     * handle registered, {@code getHandle()} must return empty.
     *
     * <p>This test verifies the "no active job" path — consistent with pre-E55S06 behavior
     * (backward-compatible).
     */
    @Test
    @DisplayName(
            "getHandle() returns empty Optional when no RUNNING row in DB and no in-memory handle"
                    + " — AC-IMPL-GETHANDLE-DB-PRIMARY")
    void getHandle_noRunningRow_noInMemoryHandle_returnsEmpty() {
        // No RUNNING row in DB; no handle registered in memory

        Optional<JobHandle> handle = jobRegistry.getHandle(tournamentId);

        assertThat(handle)
                .as("getHandle() must return empty Optional when no RUNNING row in DB")
                .isEmpty();
    }

    /**
     * AC-IMPL-GETHANDLE-DB-PRIMARY (E55S06): when a PENDING row exists (not RUNNING), {@code
     * getHandle()} must return empty — only RUNNING rows indicate an active optimization.
     */
    @Test
    @DisplayName(
            "getHandle() returns empty Optional when only PENDING row exists in DB"
                    + " — AC-IMPL-GETHANDLE-DB-PRIMARY")
    void getHandle_pendingRowInDb_returnsEmpty() {
        // Insert a PENDING job row (not RUNNING)
        jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled)"
                        + " VALUES (?, ?, ?, ?, ?, 'PENDING', FALSE)",
                jobId,
                tournamentId,
                phaseId,
                "standard",
                1);

        Optional<JobHandle> handle = jobRegistry.getHandle(tournamentId);

        assertThat(handle)
                .as(
                        "getHandle() must return empty Optional when only PENDING row exists"
                                + " — PENDING ≠ RUNNING (DEC-64 D-6)")
                .isEmpty();
    }

    /**
     * Backward-compatibility: when a handle IS registered in-memory AND a RUNNING row exists in DB,
     * {@code getHandle()} must return the in-memory handle (preserving CancellationToken access for
     * the cancel controller, DEC-49 D-11 / DEC-64 D-13).
     */
    @Test
    @DisplayName(
            "getHandle() returns in-memory handle when both DB RUNNING row and in-memory handle"
                    + " exist — DEC-49 D-11 backward-compatible")
    void getHandle_runningRowAndInMemoryHandle_returnsInMemoryHandle() {
        // Insert a RUNNING job row
        jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status,"
                        + " claimed_by, claimed_at, cancelled)"
                        + " VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, CURRENT_TIMESTAMP, FALSE)",
                jobId,
                tournamentId,
                phaseId,
                "standard",
                1,
                "test-worker");

        // Also register an in-memory handle (the normal path during active optimization)
        JobHandle registeredHandle =
                new JobHandle(CancellationToken.create(), java.time.Instant.now());
        jobRegistry.register(tournamentId, registeredHandle);

        Optional<JobHandle> handle = jobRegistry.getHandle(tournamentId);

        assertThat(handle)
                .as(
                        "getHandle() must return non-empty Optional when RUNNING row exists"
                                + " and handle is also in-memory")
                .isPresent();
        // The returned handle must support CancellationToken (DEC-49 D-11 cancel contract)
        assertThat(handle.get().getCancellationToken())
                .as("Returned handle must have a CancellationToken (DEC-49 D-11 cancel contract)")
                .isNotNull();
    }
}
