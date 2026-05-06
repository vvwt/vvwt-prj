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

// Note: assertMatchState() uses jdbcTemplate directly as an independent read verifier (DEC-26 Rule
// 2); no assertj-db/DataSource dependency needed since H2 doesn't support AssertDbConnection JDBC
// URL format used by TenantDaoTestSupport.

/**
 * RED-first integration tests for Match-Bulk-Cancel-Lockdown (E48S04).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code AC-TEST-MATCH-BULK-CANCEL-RED} — {@code cancel(tournamentId)} marks all unfinished
 *       matches (OPEN/ENABLED/INPROGRESS/ONCHECK) as CANCELED; FINISHED_* matches are unchanged.
 *   <li>{@code AC-TEST-FINISHED-MATCH-AUDIT-PRESERVED-RED} — after cancel, FINISHED_WINNER1 match
 *       and its set_results are unchanged (assertj-db count + content per DEC-26 Rule 2).
 *   <li>{@code AC-TEST-DEC-37-LOCK-MATCH-BULK-RED} — CascadeLockIT-Pattern: two parallel threads
 *       invoking {@code cancel(sameTournamentId)}; exactly one succeeds, the other throws {@link
 *       ConflictException}; bulk-update runs exactly once.
 * </ul>
 *
 * <h2>DEC-22 TDD attestation</h2>
 *
 * <p>These tests were authored RED-first — written BEFORE the bulk-cancel implementation was added
 * to {@code DefaultTournamentLifecycleService} and {@code DefaultMatchRepository}. Each test must
 * be observed FAILING before the implementation is added.
 *
 * <h2>DEC-26 DAO three rules</h2>
 *
 * <ul>
 *   <li>Rule 1 (schema from migration): schema loaded from the production Flyway migration via
 *       {@code @SpringBootTest} (test datasource configured via properties).
 *   <li>Rule 2 (independent persistence verifier): match state verified via assertj-db ({@link
 *       TenantDaoTestSupport#assertDbOf(DataSource)}) — NOT by calling {@link MatchRepository} read
 *       methods after a cancel.
 *   <li>Rule 3 (read/write decoupling): matches inserted via {@link JdbcTemplate} directly (JDBC
 *       fixture inserts), not via {@link MatchRepository#save}.
 * </ul>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in {@code de.vvwt.tm.tournament} — cross-package relative to the implementation
 * class. Subject injected as {@link TournamentLifecycleService} (the public interface) per DEC-36.
 *
 * @see TournamentLifecycleService
 * @see MatchRepository
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (3-rules)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="E48S04">E48S04 — Match-Cancel-Lockdown Backend</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:matchbulkcancelit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("MatchBulkCancelIT — E48S04 RED-first bulk-cancel + audit-preserved + lock tests")
class MatchBulkCancelIT {

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
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenantBinder.bindDefaultTenant();

        // Seed a locations row (FK tournament.location_id → locations.id, DEC-39 D2)
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "BulkCancel IT Location");

        // Insert tournament in PLANNED status (valid for cancel() per lifecycle rules)
        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E48S04 BulkCancel IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                8);

        // Insert phase (description NOT NULL per schema)
        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "ACTIVE",
                1);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM set_result WHERE match_id IN "
                        + "(SELECT id FROM match WHERE tournament_id = ?)",
                tournamentId);
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-MATCH-BULK-CANCEL-RED
    // =========================================================================

    /**
     * Verifies that {@code cancel(tournamentId)} marks all unfinished matches (OPEN, ENABLED,
     * INPROGRESS, ONCHECK) as CANCELED(-10), while FINISHED_* matches remain unchanged.
     *
     * <p>DEC-26 Rule 2: state verified via assertj-db — NOT by calling MatchRepository.findAll().
     * DEC-26 Rule 3: matches inserted via direct JDBC (not via MatchRepository.save).
     */
    @Test
    @DisplayName("cancel() bulk-marks unfinished matches CANCELED; FINISHED_* unchanged")
    void cancel_bulkMarksUnfinishedMatchesCanceled_finishedMatchesUnchanged() {
        // DEC-26 Rule 3: insert fixture matches via direct JDBC (read/write decoupling)
        UUID openMatchId = insertMatchDirectly(0); // OPEN(0)
        UUID enabledMatchId = insertMatchDirectly(10); // ENABLED(10)
        UUID inProgressId = insertMatchDirectly(30); // INPROGRESS(30)
        UUID onCheckId = insertMatchDirectly(35); // ONCHECK(35)
        UUID finishedW1Id = insertMatchDirectly(51); // FINISHED_WINNER1(51) — must remain
        UUID finishedW2Id = insertMatchDirectly(52); // FINISHED_WINNER2(52) — must remain
        UUID finishedSoId = insertMatchDirectly(50); // FINISHED_STANDOFF(50) — must remain
        UUID canceledId = insertMatchDirectly(-10); // already CANCELED(-10) — idempotent

        // Act: cancel the tournament
        lifecycleService.cancel(tournamentId);

        // DEC-26 Rule 2: verify via assertj-db (independent of MatchRepository read methods)
        // All unfinished matches must be CANCELED(-10)
        assertMatchState(openMatchId, -10);
        assertMatchState(enabledMatchId, -10);
        assertMatchState(inProgressId, -10);
        assertMatchState(onCheckId, -10);

        // FINISHED_* must remain unchanged
        assertMatchState(finishedW1Id, 51);
        assertMatchState(finishedW2Id, 52);
        assertMatchState(finishedSoId, 50);

        // Already-CANCELED must remain -10 (idempotent — WHERE state IN (0,10,30,35) excludes -10)
        assertMatchState(canceledId, -10);
    }

    // =========================================================================
    // AC-TEST-FINISHED-MATCH-AUDIT-PRESERVED-RED
    // =========================================================================

    /**
     * Verifies that after {@code cancel(tournamentId)}, a FINISHED_WINNER1 match and its
     * set_results remain unchanged in the database.
     *
     * <p>DEC-26 Rule 2: verified via assertj-db count + row content. DEC-26 Rule 3: set_results
     * inserted via direct JDBC.
     */
    @Test
    @DisplayName("cancel() preserves FINISHED_WINNER1 match and its set_results unchanged")
    void cancel_finishedMatchAndSetResultsArePreservedUnchanged() {
        UUID finishedMatchId = insertMatchDirectly(51); // FINISHED_WINNER1

        // DEC-26 Rule 3: insert set_results for the finished match via direct JDBC
        // set_result schema: match_id, set_index, phase_id, team1_points, team2_points, set_state
        jdbcTemplate.update(
                "INSERT INTO set_result (match_id, set_index, phase_id, team1_points,"
                        + " team2_points, set_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                finishedMatchId,
                0,
                phaseId,
                25,
                20,
                1);
        jdbcTemplate.update(
                "INSERT INTO set_result (match_id, set_index, phase_id, team1_points,"
                        + " team2_points, set_state)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                finishedMatchId,
                1,
                phaseId,
                25,
                18,
                1);

        // Also insert an unfinished match so cancel() has something to cancel
        insertMatchDirectly(0);

        // Act: cancel the tournament
        lifecycleService.cancel(tournamentId);

        // DEC-26 Rule 2: verify match still FINISHED_WINNER1
        assertMatchState(finishedMatchId, 51);

        // DEC-26 Rule 2: verify set_results count still 2 for the finished match
        Integer setResultCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM set_result WHERE match_id = ?",
                        Integer.class,
                        finishedMatchId);
        assertThat(setResultCount).isEqualTo(2);

        // Verify set_result content unchanged (team1_points = 25 in both rows)
        List<Integer> team1Points =
                jdbcTemplate.queryForList(
                        "SELECT team1_points FROM set_result WHERE match_id = ? ORDER BY set_index",
                        Integer.class,
                        finishedMatchId);
        assertThat(team1Points).containsExactly(25, 25);
    }

    // =========================================================================
    // AC-TEST-DEC-37-LOCK-MATCH-BULK-RED (CascadeLockIT-Pattern)
    // =========================================================================

    /**
     * Verifies that two parallel threads calling {@code cancel(sameTournamentId)} are serialised by
     * the per-tournament pessimistic DB row-lock (DEC-37 Clause B): exactly one succeeds; the other
     * throws {@link ConflictException} (tournament already CANCELLED). Match bulk-cancel runs
     * exactly once.
     *
     * <p>Uses CyclicBarrier to synchronise thread start — same pattern as {@code
     * TournamentLifecycleLockIT} from E48S03.
     */
    @Test
    @DisplayName(
            "concurrent cancel() — lock serialises: exactly 1 succeeds, 1 throws"
                    + " ConflictException; bulk-cancel runs once")
    void cancel_concurrentCalls_lockSerialisesExactlyOneSuccess() throws Exception {
        // Insert some unfinished matches for the tournament
        insertMatchDirectly(0); // OPEN
        insertMatchDirectly(10); // ENABLED
        insertMatchDirectly(30); // INPROGRESS

        UUID capturedTenantId = tenantId;

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        List<Future<Exception>> results = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            results.add(
                    executor.submit(
                            () -> {
                                // Each thread needs its own tenant context binding (ThreadLocal)
                                TenantContext.Scope scope = tenantContext.bind(capturedTenantId);
                                try {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    lifecycleService.cancel(tournamentId);
                                    return null; // success
                                } catch (ConflictException e) {
                                    return e; // expected: tournament already CANCELLED
                                } catch (Exception e) {
                                    return e; // unexpected failure
                                } finally {
                                    scope.close();
                                }
                            }));
        }

        executor.shutdown();
        boolean terminated = executor.awaitTermination(15, TimeUnit.SECONDS);
        assertThat(terminated).isTrue();

        Exception result1 = results.get(0).get();
        Exception result2 = results.get(1).get();

        // Exactly one thread succeeded (returned null) and one threw ConflictException
        boolean thread1Success = result1 == null;
        boolean thread2Success = result2 == null;

        assertThat(thread1Success ^ thread2Success)
                .as("Exactly one cancel() must succeed; the other must throw ConflictException")
                .isTrue();

        Exception failing = thread1Success ? result2 : result1;
        assertThat(failing)
                .as("The failing cancel() must throw ConflictException")
                .isInstanceOf(ConflictException.class);

        // Verify tournament is CANCELLED
        String dbStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(dbStatus)
                .as("Tournament must be CANCELLED after exactly one cancel()")
                .isEqualTo("CANCELLED");

        // Verify all unfinished matches are CANCELED — bulk-cancel ran exactly once
        Integer openCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE tournament_id = ? AND state IN (0, 10,"
                                + " 30, 35)",
                        Integer.class,
                        tournamentId);
        assertThat(openCount).as("No unfinished matches should remain after cancel()").isEqualTo(0);

        Integer canceledCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE tournament_id = ? AND state = -10",
                        Integer.class,
                        tournamentId);
        assertThat(canceledCount)
                .as("All 3 inserted unfinished matches must be CANCELED")
                .isEqualTo(3);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Inserts a match with the given state via direct JDBC (DEC-26 Rule 3 — read/write decoupling).
     *
     * <p>Inserts the required FK rows (team → team_avatar) before inserting the match row.
     *
     * @param state the legacy integer state code
     * @return the generated match UUID
     */
    private UUID insertMatchDirectly(int state) {
        // Insert team1 and team2 (FK: match.member_avatar → team_avatar → team)
        UUID team1Id = UUID.randomUUID();
        UUID team2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?)",
                team1Id,
                tournamentId,
                (int) (Math.random() * 100000),
                "Team A");
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description)"
                        + " VALUES (?, ?, ?, ?)",
                team2Id,
                tournamentId,
                (int) (Math.random() * 100000),
                "Team B");

        // Insert team_avatar rows (FK: team_avatar → team, tournament, phase)
        UUID avatar1Id = UUID.randomUUID();
        UUID avatar2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phaseId,
                (int) (Math.random() * 100000),
                1,
                team1Id);
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phaseId,
                (int) (Math.random() * 100000),
                2,
                team2Id);

        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                        + " member_avatar_2_id, state, set_limit)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phaseId,
                avatar1Id,
                avatar2Id,
                state,
                3);
        return matchId;
    }

    /**
     * Asserts the match's state column value via direct JDBC query (DEC-26 Rule 2 — verifier
     * independent of MatchRepository read path).
     *
     * @param matchId the match UUID
     * @param expectedState the expected legacy integer state code
     */
    private void assertMatchState(UUID matchId, int expectedState) {
        Integer actualState =
                jdbcTemplate.queryForObject(
                        "SELECT state FROM match WHERE id = ?", Integer.class, matchId);
        assertThat(actualState).as("Match %s state", matchId).isEqualTo(expectedState);
    }
}
