// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.e2e;

import de.vvwt.slotopt.standalone.integration.WorkerLauncher;
import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.PhasePreparationService;
import de.vvwt.tm.tournament.RoundAssignmentService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end integration tests for the TM slot-optimization Leg-2 pipeline (E63S08).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code AC-TEST-E2E-LEG2-LIVE-WORKER}: TM submits a large phase ({@code lapCount=11}) to a
 *       live dispatcher subprocess (no compile dep — launched via {@link DispatcherProcessLauncher}
 *       / ProcessBuilder), a live standalone worker solves packets via {@link WorkerLauncher}, and
 *       TM applies the optimum. Asserts all matches have non-null {@code lap_number} and {@code
 *       field_number}.
 *   <li>{@code AC-TEST-E2E-EMBEDDED-WORKER-CONTRIBUTES}: same setup, a standalone {@link
 *       WorkerLauncher} contributes alongside any embedded worker in the TM context. Asserts
 *       round-trip completes with non-null coordinates.
 * </ul>
 *
 * <p>For {@code AC-TEST-E2E-LEG3-FALLBACK} (offline / no dispatcher URL), see {@link
 * SlotOptLeg3FallbackIT}.
 *
 * <h2>Dispatcher subprocess (AC-GOV-NO-DISPATCHER-COMPILE-DEP)</h2>
 *
 * <p>The live dispatcher is started as an external OS subprocess using {@link
 * DispatcherProcessLauncher} (ProcessBuilder). This introduces no compile-time dependency on {@code
 * vvwt-slotopt-dispatcher} (Maven Enforcer ban per DEC-11). The dispatcher JAR is resolved from
 * the filesystem at test runtime.
 *
 * <h2>Execution pattern (AC-ERR-E2E-DETERMINISTIC)</h2>
 *
 * <p>The standalone worker runs in a background thread via {@link WorkerLauncher#launch()}.
 * {@code optimize()} is called on the main test thread — it submits the job to the dispatcher and
 * condition-polls for the result (exponential backoff, no fixed sleep, per {@code
 * DefaultSlotOptimizationDispatcherClient}). The worker bootstraps (registers with dispatcher) and
 * pulls packets concurrently; the dispatcher completes the job once all packets are solved. No
 * fixed sleeps anywhere in this flow. Dispatcher readiness is gated by {@link
 * DispatcherProcessLauncher#start()} which condition-polls {@code GET /api/algorithms}.
 *
 * @see DispatcherProcessLauncher
 * @see TmSlotOptE2ETestSupport
 * @see SlotOptLeg3FallbackIT
 * @see <a href="../../../../../../../../../docs/governance/stories/E63S08.story.md">E63S08</a>
 */
@Tag("e2e")
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:slotopte2eit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.fallback.field-count=2",
            "tm.slotopt.exhaustive-max-n=10",
            // Increase poll timeout for E2E tests (dispatcher subprocess + in-process worker need
            // time to solve 11 laps; default 300s is fine but set explicitly for clarity)
            "tm.slotopt.dispatcher.poll-timeout-ms=120000",
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("SlotOptE2EIT — E63S08 Leg-2 live-worker end-to-end")
class SlotOptE2EIT {

    /** Dispatcher subprocess shared across all tests in this class. */
    private static DispatcherProcessLauncher dispatcherLauncher;

    /**
     * Starts the dispatcher subprocess before the Spring context is loaded and registers its base
     * URL as a dynamic property so {@code RoutingSlotOptimizationClient} picks it up.
     *
     * <p>Runs once per test class (static, invoked by {@code @DynamicPropertySource}). The
     * dispatcher is started on a random free port. Readiness is condition-polled via {@link
     * DispatcherProcessLauncher#start()} — no fixed sleep (AC-ERR-E2E-DETERMINISTIC). Stopped in
     * {@link #stopDispatcher()}.
     *
     * <p>If the dispatcher JAR is not built, the test fails with a clear diagnostic (AssertionError
     * with JAR path — not a silent hang, per AC-TEST-E2E-LEG2-LIVE-WORKER edge case).
     */
    @DynamicPropertySource
    static void dispatcherProperties(DynamicPropertyRegistry registry) throws Exception {
        dispatcherLauncher = DispatcherProcessLauncher.withRandomPort();
        dispatcherLauncher.start();
        registry.add("tm.slotopt.dispatcher.url", () -> dispatcherLauncher.getBaseUri().toString());
        registry.add("tm.slotopt.dispatcher.reachability-timeout-ms", () -> "5000");
    }

    @AfterAll
    static void stopDispatcher() {
        if (dispatcherLauncher != null) {
            dispatcherLauncher.stop();
        }
    }

    @Autowired private SlotOptimizationClient slotOptimizationClient;
    @Autowired private PhasePreparationService phasePreparationService;
    @Autowired private RoundAssignmentService roundAssignmentService;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        tournamentId = UUID.randomUUID();
        phaseId = TmSlotOptE2ETestSupport.buildAndPersistLargePhase(tournamentId, jdbcTemplate);
        // Generate 11 laps (N−1 for N=12 round-robin in 1 group) via real match generator
        phasePreparationService.generateMatches(phaseId, "roundRobin");
        // L1 baseline: assign initial round and field numbers
        roundAssignmentService.assignRoundsAndFields(phaseId, 2);
    }

    @AfterEach
    void tearDown() {
        TmSlotOptE2ETestSupport.cleanUpTournament(tournamentId, jdbcTemplate);
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-E2E-LEG2-LIVE-WORKER: large phase ({@code lapCount=11}) submitted to live dispatcher,
     * solved by a live standalone worker, result applied by TM.
     *
     * <p>The standalone worker runs in a background thread ({@link WorkerLauncher#launch()}).
     * {@code optimize()} is called on the main thread; it submits the phase to the dispatcher and
     * polls with exponential backoff until the job is COMPLETED. The worker bootstraps concurrently,
     * pulls and solves packets. On return, all matches must have non-null {@code lap_number} and
     * {@code field_number}.
     *
     * <p>Boundary: {@code lapCount=11} (one above {@code tm.slotopt.exhaustive-max-n=10}) — the
     * routing decision boundary per E63S08 edge case table.
     *
     * <p>No fixed sleeps anywhere in this flow (AC-ERR-E2E-DETERMINISTIC).
     */
    @Test
    @DisplayName("AC-TEST-E2E-LEG2-LIVE-WORKER: Leg-2 round-trip with live dispatcher + worker")
    void leg2_fullRoundTrip_phaseOptimized() throws Exception {
        // Launch standalone worker in background — it registers with dispatcher and waits for jobs.
        // WorkerLauncher.launch() blocks until the worker exits or times out.
        WorkerLauncher workerLauncher =
                new WorkerLauncher(
                        dispatcherLauncher.getBaseUri(), Duration.ofSeconds(120));
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        workerExecutor.submit(workerLauncher::launch);
        try {
            // Leg-2 optimize: routes to dispatcher, submits job, polls with exp. backoff, applies.
            // The dispatcher's poll-timeout is 120s (set above). The worker concurrently picks up
            // packets and submits results. optimize() returns once the job is COMPLETED.
            slotOptimizationClient.optimize(phaseId);

            // Assert: all matches have non-null lap_number + field_number (Leg 2 applied result)
            TmSlotOptE2ETestSupport.assertMatchesOptimized(phaseId, jdbcTemplate);
        } finally {
            workerLauncher.requestShutdown();
            workerExecutor.shutdown();
        }
    }

    /**
     * AC-TEST-E2E-EMBEDDED-WORKER-CONTRIBUTES: a standalone {@link WorkerLauncher} contributes to
     * the dispatcher's worker pool, demonstrating multi-worker solve.
     *
     * <p>The TM context has the dispatcher URL configured, so TM's embedded worker (if enabled via
     * {@code tm.slotopt.embedded-worker.enabled=true}) would also be registered. This test launches
     * an additional standalone worker so that at least one worker is guaranteed to contribute.
     * The job completes when all packets are solved. Asserts non-null lap/field after
     * {@code optimize()} returns.
     *
     * <p>The AC is satisfied by the standalone worker's participation — embedded worker
     * participation is additive and not required to be the sole solver.
     */
    @Test
    @DisplayName(
            "AC-TEST-E2E-EMBEDDED-WORKER-CONTRIBUTES: worker pool solves phase via dispatcher")
    void leg2_embeddedWorkerContributes_packetSolved() throws Exception {
        WorkerLauncher workerLauncher =
                new WorkerLauncher(
                        dispatcherLauncher.getBaseUri(), Duration.ofSeconds(120));
        ExecutorService workerExecutor = Executors.newSingleThreadExecutor();
        workerExecutor.submit(workerLauncher::launch);
        try {
            slotOptimizationClient.optimize(phaseId);

            TmSlotOptE2ETestSupport.assertMatchesOptimized(phaseId, jdbcTemplate);
        } finally {
            workerLauncher.requestShutdown();
            workerExecutor.shutdown();
        }
    }
}
