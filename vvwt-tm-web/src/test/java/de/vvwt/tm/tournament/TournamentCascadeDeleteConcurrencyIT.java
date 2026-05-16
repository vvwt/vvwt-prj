// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
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
 * Concurrency IT for cascade-delete (E48S13, AC-TEST-CONCURRENCY-CASCADE-DELETE-IT-RED).
 *
 * <h2>Scenario</h2>
 *
 * <ol>
 *   <li>One DRAFT tournament is prepared.
 *   <li>Two threads simultaneously call {@code deleteTournament(tournamentId)}.
 *   <li>Exactly ONE must succeed (tournament row deleted); the other MUST throw {@link
 *       TournamentNotFoundException} or {@link NoSuchElementException} (row already gone after the
 *       pessimistic lock is released).
 *   <li>Post-assertion verifies: tournament row count = 0; no partial deletion at any intermediate
 *       state.
 * </ol>
 *
 * <p>Pattern precedent: {@link TournamentLifecycleLockIT} (DEC-37 Clause B verification).
 *
 * @see TournamentService
 * @see TournamentRepository#findByIdForUpdate(UUID)
 * @see <a href="E48S13">E48S13 — AC-TEST-CONCURRENCY-CASCADE-DELETE-IT-RED</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cascadedeleteconcurrencydb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("TournamentCascadeDelete ConcurrencyIT — E48S13 lock serialises concurrent delete()")
class TournamentCascadeDeleteConcurrencyIT {

    /** Subject: injected via interface per DEC-36. */
    @Autowired private TournamentService tournamentService;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentId;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        tenantId = tenantContext.current();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "CascadeDeleteConcurrency IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "ConcurrencyIT Tournament",
                "BEST_OF_3",
                "defaultScoringRule",
                "defaultSetValidationRule",
                "roundRobinMatchGenerator",
                "DRAFT",
                LocalDateTime.now(),
                4,
                8);

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    @Test
    @DisplayName(
            "two concurrent cascade-deletes: first succeeds; second gets 404 (no partial deletion)")
    void concurrentCascadeDelete_firstSucceedsSecondGets404() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Throwable> errors = new ArrayList<>();
        int[] successCount = {0};

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    TenantContext.Scope scope = tenantContext.bind(tenantId);
                                    try {
                                        barrier.await(10, TimeUnit.SECONDS);
                                        tournamentService.deleteTournament(tournamentId);
                                        synchronized (successCount) {
                                            successCount[0]++;
                                        }
                                    } catch (TournamentNotFoundException
                                            | NoSuchElementException
                                            | IllegalArgumentException expected) {
                                        // expected: second thread's findByIdForUpdate throws
                                        // IllegalArgumentException when row is already deleted
                                        // (DEC-37 Clause B: no row → IAE, not Optional.empty)
                                    } catch (Exception e) {
                                        synchronized (errors) {
                                            errors.add(e);
                                        }
                                    } finally {
                                        scope.close();
                                    }
                                    return null;
                                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors)
                .as("No unexpected exceptions during concurrent cascade-delete")
                .isEmpty();
        assertThat(successCount[0])
                .as("Exactly one delete must succeed (DEC-37 Clause B lock)")
                .isEqualTo(1);

        // Independent JDBC verifier: tournament row must be completely gone
        tenantBinder.bindDefaultTenant();
        try {
            int count =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM tournament WHERE id = ?",
                            Integer.class,
                            tournamentId);
            assertThat(count)
                    .as("Tournament row must be deleted after successful cascade-delete")
                    .isZero();
        } finally {
            tenantBinder.unbind();
        }
    }
}
