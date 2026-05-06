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
 * Concurrency integration test for E48S04 Match-Cancel-Lockdown — verifying DEC-37 Clause B lock
 * enforcement for the bulk-cancel path (AC-TEST-DEC-37-LOCK-MATCH-BULK-RED).
 *
 * <h2>Test scenario (CascadeLockIT-Pattern)</h2>
 *
 * <ol>
 *   <li>One ACTIVE tournament with several OPEN matches is prepared.
 *   <li>Two threads simultaneously call {@code cancel(tournamentId)}.
 *   <li>Exactly ONE must succeed; the other MUST throw {@link ConflictException} (tournament
 *       already CANCELLED — invalid transition).
 *   <li>Post-assertion verifies: match bulk-cancel ran exactly once (all unfinished matches are
 *       CANCELED, and the operation was not duplicated).
 * </ol>
 *
 * <h2>DEC-37 Clause B rationale</h2>
 *
 * <p>The per-tournament pessimistic DB row-lock ({@code SELECT … FOR UPDATE}) acquired at the start
 * of {@code cancel()} serialises concurrent calls. The second thread either (a) blocks until the
 * first commits and then sees {@code status=CANCELLED} → throws {@link ConflictException}, or (b)
 * deadlocks and throws an exception. Either way, the bulk-cancel executes exactly once.
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in {@code de.vvwt.tm.tournament} — cross-package relative to the implementation.
 * Per DEC-36, the subject is injected as {@link TournamentLifecycleService} (the public interface).
 *
 * @see TournamentLifecycleService#cancel(UUID)
 * @see MatchLockdownService
 * @see TournamentRepository#findByIdForUpdate(UUID)
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="E48S04">E48S04 — AC-TEST-DEC-37-LOCK-MATCH-BULK-RED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            // Fixed DB name so all threads share the same H2 in-memory instance
            "spring.datasource.url=jdbc:h2:mem:cancelmatchlockdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("CancelMatchLockIT — per-tournament lock serialises concurrent cancel() + bulk-cancel")
class CancelMatchLockIT {

    /** Subject: injected via INTERFACE per DEC-36 (cross-package test typing). */
    @Autowired private TournamentLifecycleService lifecycleService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID locationId;
    private UUID phaseId;
    private UUID team1Id;
    private UUID team2Id;
    private UUID avatar1Id;
    private UUID avatar2Id;
    private UUID tenantId;

    // Matches in unfinished states — should be CANCELED exactly once
    private UUID matchOpen1Id;
    private UUID matchOpen2Id;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        tenantId = tenantContext.current();

        // schema-source: db/migration/tenant/V1__initial_schema.sql §locations
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "CancelMatchLock IT Location");

        // schema-source: db/migration/tournament/V1__initial_schema.sql §tournament
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "CancelMatchLock IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                4);

        // schema-source: db/migration/tournament/V1__initial_schema.sql §phase
        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "ACTIVE",
                1,
                LocalDateTime.now());

        // schema-source: db/migration/tournament/V1__initial_schema.sql §team
        team1Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                team1Id,
                tournamentId,
                1,
                "Team Alpha",
                LocalDateTime.now());

        team2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                team2Id,
                tournamentId,
                2,
                "Team Beta",
                LocalDateTime.now());

        // schema-source: db/migration/tournament/V1__initial_schema.sql §team_avatar
        // Pitfall 2: seed team_avatar BEFORE match rows
        avatar1Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                1,
                1,
                team1Id,
                LocalDateTime.now());

        avatar2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                1,
                2,
                team2Id,
                LocalDateTime.now());

        // Two OPEN matches — schema-source: db/migration/tournament/V1__initial_schema.sql §match
        matchOpen1Id = insertMatch(MatchState.OPEN.getLegacyCode());
        matchOpen2Id = insertMatch(MatchState.OPEN.getLegacyCode());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-DEC-37-LOCK-MATCH-BULK-RED — DEC-37 Clause B verification for bulk-cancel path.
     *
     * <p>Two threads race to cancel the same ACTIVE tournament. The per-tournament DB row-lock
     * serialises them: exactly ONE succeeds (both tournament status → CANCELLED and matches →
     * CANCELED), the other sees status CANCELLED on its own read → {@link ConflictException}.
     * Bulk-cancel must have run exactly once: both OPEN matches are CANCELED.
     */
    @Test
    @DisplayName("concurrent cancel() — exactly one succeeds, bulk-cancel runs exactly once")
    void concurrentCancel_exactlyOneSucceeds_bulkCancelRunsOnce() throws Exception {
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
                                    lifecycleService.cancel(tournamentId);
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

        List<Throwable> conflictExceptions = new ArrayList<>();
        List<Object> successes = new ArrayList<>();
        for (Future<Throwable> f : futures) {
            Throwable result = f.get();
            if (result == null) {
                successes.add(result);
            } else if (result instanceof ConflictException) {
                conflictExceptions.add(result);
            } else {
                throw new AssertionError("Unexpected exception in concurrent thread", result);
            }
        }

        // Exactly one thread succeeded, one threw ConflictException
        assertThat(successes).as("Exactly one cancel() must succeed").hasSize(1);
        assertThat(conflictExceptions)
                .as("Exactly one cancel() must throw ConflictException (second sees CANCELLED)")
                .hasSize(1);

        // Tournament must be CANCELLED
        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus)
                .as("Tournament status must be CANCELLED after exactly one cancel()")
                .isEqualTo("CANCELLED");

        // Bulk-cancel ran exactly once: both OPEN matches must be CANCELED
        int canceledMatchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE tournament_id = ? AND state = ?",
                        Integer.class,
                        tournamentId,
                        MatchState.CANCELED.getLegacyCode());
        assertThat(canceledMatchCount)
                .as("Both OPEN matches must be CANCELED (bulk-cancel ran exactly once)")
                .isEqualTo(2);
    }

    /**
     * Inserts a match row with the given state (DEC-26 Rule 3 — direct JDBC). schema-source:
     * db/migration/tournament/V1__initial_schema.sql §match
     */
    private UUID insertMatch(int matchState) {
        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id, // FK_MATCH_MEMBER_AVATAR_1 — seeded in setUp()
                avatar2Id, // FK_MATCH_MEMBER_AVATAR_2 — seeded in setUp()
                matchState,
                3,
                LocalDateTime.now());
        return matchId;
    }
}
