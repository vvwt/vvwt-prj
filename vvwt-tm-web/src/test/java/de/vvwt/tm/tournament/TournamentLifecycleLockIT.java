// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
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
 * Concurrency integration test for {@link TournamentLifecycleService#activate(UUID)} — verifying
 * that the per-tournament pessimistic DB row-lock ({@code SELECT … FOR UPDATE} via {@link
 * TournamentRepository#findByIdForUpdate(UUID)}) serialises concurrent lifecycle transitions for
 * the same tournament (DEC-37 Clause B, AC-TEST-LIFECYCLE-LOCK-RED).
 *
 * <h2>Test scenario (CascadeLockIT-Pattern)</h2>
 *
 * <ol>
 *   <li>One PLANNED tournament is prepared.
 *   <li>Two threads simultaneously call {@code activate(tournamentId)}.
 *   <li>Exactly ONE must succeed (tournament status = ACTIVE); the other MUST throw {@link
 *       ConflictException} (status already ACTIVE — invalid transition per lifecycle rules).
 *   <li>Post-assertion verifies: tournament status is ACTIVE (exactly one transition succeeded).
 * </ol>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in {@code de.vvwt.tm.tournament} — cross-package relative to the implementation.
 * Per DEC-36, the subject is injected as {@link TournamentLifecycleService} (the public interface).
 *
 * <h2>Why {@code @SpringBootTest} (DEC-38 Clause C analogy)</h2>
 *
 * <p>Full Spring context is required:
 *
 * <ol>
 *   <li>The test spawns real OS threads; module test limitations apply to thread-bound TX contexts.
 *   <li>The lock ({@code SELECT … FOR UPDATE}) requires a real JDBC connection under real
 *       {@code @Transactional} boundary.
 *   <li>The full tenant-routing DataSource stack is needed for the concurrency path.
 * </ol>
 *
 * @see TournamentLifecycleService
 * @see TournamentRepository#findByIdForUpdate(UUID)
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="E48S03">E48S03 — AC-TEST-LIFECYCLE-LOCK-RED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            // Fixed DB name so all threads share the same H2 in-memory instance
            "spring.datasource.url=jdbc:h2:mem:lifecyclelockdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TournamentLifecycleLockIT — per-tournament lock serialises concurrent activate()")
class TournamentLifecycleLockIT {

    /** Subject: injected via INTERFACE per DEC-36 (cross-package test typing). */
    @Autowired private TournamentLifecycleService lifecycleService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID locationId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        tenantId = tenantContext.current();

        // Seed a locations row
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "Lock IT Location");

        // Create a PLANNED tournament — ready to activate
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "Lock IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                4);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-LIFECYCLE-LOCK-RED — DEC-37 Clause B verification.
     *
     * <p>Two threads race to activate the same PLANNED tournament. The per-tournament DB row-lock
     * serialises them: exactly ONE succeeds, the other sees status ACTIVE on its own read → {@link
     * ConflictException}.
     */
    @Test
    @DisplayName("concurrent activate() — exactly one succeeds, second throws ConflictException")
    void concurrentActivate_exactlyOneSucceeds() throws Exception {
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
                                    // Synchronize both threads to maximise overlap probability
                                    barrier.await(5, TimeUnit.SECONDS);
                                    lifecycleService.activate(tournamentId);
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
                // Re-throw unexpected exceptions from threads
                throw new AssertionError("Unexpected exception in concurrent thread", result);
            }
        }

        // Exactly one thread succeeded (null result), exactly one threw ConflictException
        assertThat(successes).as("Exactly one activate() must succeed").hasSize(1);
        assertThat(exceptions)
                .as(
                        "Exactly one activate() must throw ConflictException (second thread sees"
                                + " ACTIVE)")
                .hasSize(1);

        // DB state: tournament must be ACTIVE
        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus)
                .as("Tournament status must be ACTIVE after exactly one successful activate()")
                .isEqualTo("ACTIVE");
    }
}
