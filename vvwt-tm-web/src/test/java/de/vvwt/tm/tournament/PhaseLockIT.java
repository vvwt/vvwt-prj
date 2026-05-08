package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Concurrency integration test for {@link PhaseLifecycleService#start(UUID)} — verifying that the
 * per-tournament pessimistic DB row-lock ({@code SELECT … FOR UPDATE} via {@link
 * TournamentRepository#findByIdForUpdate(UUID)}) serialises concurrent phase lifecycle transitions
 * (DEC-37 Clause B, AC-TEST-DEC-37-LOCK-PHASE-RED).
 *
 * <h2>Test scenario (CascadeLockIT-Pattern)</h2>
 *
 * <ol>
 *   <li>One ASSIGNED phase (E51S06: start() now requires ASSIGNED) is set up for an ACTIVE
 *       tournament.
 *   <li>Two threads simultaneously call {@code start(phaseId)}.
 *   <li>Exactly ONE must succeed (phase status = ACTIVE); the other MUST throw {@link
 *       ConflictException} (status already ACTIVE — invalid transition per lifecycle rules).
 *   <li>Post-assertion verifies: phase status is ACTIVE (exactly one transition succeeded).
 * </ol>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in {@code de.vvwt.tm.tournament} — cross-package relative to the implementation.
 * Per DEC-36, the subject is injected as {@link PhaseLifecycleService} (the public interface).
 *
 * <h2>Why {@code @SpringBootTest}</h2>
 *
 * <p>Full Spring context is required: real OS threads need real JDBC connections + real
 * {@code @Transactional} boundary + full tenant-routing DataSource stack.
 *
 * @see PhaseLifecycleService
 * @see TournamentRepository#findByIdForUpdate(UUID)
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="E48S06">E48S06 — AC-TEST-DEC-37-LOCK-PHASE-RED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phaselockitdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("PhaseLockIT — per-tournament lock serialises concurrent start()")
class PhaseLockIT {

    /** Subject: injected via INTERFACE per DEC-36 (cross-package test typing). */
    @Autowired
    @Qualifier("tmPhaseLifecycleService")
    private PhaseLifecycleService phaseLifecycleService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID locationId;
    private UUID phaseId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        tenantId = tenantContext.current();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "PhaseLockIT Location");

        // ACTIVE tournament (required — phases belong to active tournaments)
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "PhaseLockIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                4);

        // ASSIGNED phase — ready to start (E51S06: start() now requires ASSIGNED, not PREPARED)
        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Vorrunde",
                "ASSIGNED",
                0);

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM phase WHERE id = ?", phaseId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-DEC-37-LOCK-PHASE-RED — DEC-37 Clause B verification.
     *
     * <p>Two threads race to start the same ASSIGNED phase (E51S06: start() now requires ASSIGNED).
     * The per-tournament DB row-lock serialises them: exactly ONE succeeds, the other sees status
     * ACTIVE on its own read → {@link ConflictException}.
     */
    @Test
    @DisplayName("concurrent start() — exactly one succeeds, second throws ConflictException")
    void concurrentStart_exactlyOneSucceeds() throws Exception {
        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        List<Future<Throwable>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            UUID capturedTenantId = tenantId;
            futures.add(
                    executor.submit(
                            () -> {
                                TenantContext.Scope scope = tenantContext.bind(capturedTenantId);
                                try {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    phaseLifecycleService.start(phaseId);
                                    return null; // success
                                } catch (ConflictException e) {
                                    return e; // expected for the second thread
                                } catch (Exception e) {
                                    return e; // unexpected exception
                                } finally {
                                    scope.close();
                                }
                            }));
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        List<Throwable> exceptions = new ArrayList<>();
        List<Throwable> successes = new ArrayList<>();
        for (Future<Throwable> f : futures) {
            Throwable result = f.get();
            if (result == null) {
                successes.add(result);
            } else if (result instanceof ConflictException) {
                exceptions.add(result);
            } else {
                throw new AssertionError("Unexpected exception in concurrent thread", result);
            }
        }

        assertThat(successes).as("Exactly one start() must succeed").hasSize(1);
        assertThat(exceptions)
                .as("Exactly one start() must throw ConflictException (second thread sees ACTIVE)")
                .hasSize(1);

        // DB state: phase must be ACTIVE
        tenantBinder.bindDefaultTenant();
        try {
            String dbStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
            assertThat(dbStatus)
                    .as("Phase status must be ACTIVE after exactly one successful start()")
                    .isEqualTo("ACTIVE");
        } finally {
            tenantBinder.unbind();
        }
    }
}
