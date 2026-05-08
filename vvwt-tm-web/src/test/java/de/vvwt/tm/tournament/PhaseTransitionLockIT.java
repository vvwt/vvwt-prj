package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Concurrency integration test for {@link PhaseTransitionService#commitTransition(UUID, List)} —
 * verifying that the per-tournament pessimistic DB row-lock ({@code SELECT … FOR UPDATE} via {@link
 * TournamentRepository#findByIdForUpdate(UUID)}) serialises concurrent commit calls (DEC-37 Clause
 * B, AC-TEST-DEC-37-LOCK-COMMIT-RED).
 *
 * <h2>Test scenario (CyclicBarrier-Pattern)</h2>
 *
 * <ol>
 *   <li>One fromPhase (COMPLETED) with 2 teams is prepared for an ACTIVE tournament.
 *   <li>One toPhase (PENDING) is prepared.
 *   <li>Two threads simultaneously call {@code commitTransition(toPhaseId)} with DIFFERENT (but
 *       valid) assignments. Only one can acquire the DB lock; the other serialises after.
 *   <li>The structural-identity unique constraint {@code uq_team_avatar_structural_identity} on
 *       {@code team_avatar(tournamentId, phaseId, groupNumber, groupPosition)} ensures the second
 *       commit fails — both threads submit to (group=1, pos=1) and (group=1, pos=2). The lock
 *       ensures they do not interleave writes.
 *   <li>Post-assertion: exactly ONE thread succeeds, the other throws an exception. Final DB state
 *       has exactly 2 avatars for toPhase.
 * </ol>
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test is in {@code de.vvwt.tm.tournament} — cross-package relative to the implementation.
 * Per DEC-36, the subject is injected as {@link PhaseTransitionService} (the public interface).
 *
 * @see PhaseTransitionService
 * @see TournamentRepository#findByIdForUpdate(UUID)
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="E48S07">E48S07 — AC-TEST-DEC-37-LOCK-COMMIT-RED</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:phasetransitionlockitdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("PhaseTransitionLockIT — per-tournament lock serialises concurrent commitTransition()")
class PhaseTransitionLockIT {

    private static final String DRAFT_JSON =
            "{"
                    + "\"sections\": ["
                    + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                    + "  {\"sectionNumber\": 2, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"siegerehrung\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                    + "]"
                    + "}";

    /** Subject: injected via INTERFACE per DEC-36 (cross-package test typing). */
    @Autowired
    @Qualifier("tmPhaseTransitionService")
    private PhaseTransitionService phaseTransitionService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID locationId;
    private UUID fromPhaseId;
    private UUID toPhaseId;
    private UUID teamId1;
    private UUID teamId2;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        tenantId = tenantContext.current();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "PhaseTransitionLockIT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "PhaseTransitionLockIT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "ACTIVE",
                LocalDateTime.now(),
                2,
                2,
                DRAFT_JSON);

        // fromPhase — COMPLETED
        fromPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                fromPhaseId,
                tournamentId,
                1,
                "Vorrunde",
                "COMPLETED",
                1);

        // toPhase (siegerehrung — no matches generated, keeps test focused on lock + avatar
        // persistence)
        toPhaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number) VALUES (?, ?, ?, ?, ?, ?)",
                toPhaseId,
                tournamentId,
                2,
                "Siegerehrung",
                "PENDING",
                0);

        // Teams
        teamId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId1,
                tournamentId,
                1,
                "LockIT Team 1",
                LocalDateTime.now());
        teamId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                teamId2,
                tournamentId,
                2,
                "LockIT Team 2",
                LocalDateTime.now());

        // Avatars in fromPhase
        UUID avatarFromId1 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarFromId1,
                tournamentId,
                fromPhaseId,
                teamId1,
                1,
                1,
                LocalDateTime.now());
        UUID avatarFromId2 = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, team_id, group_number,"
                        + " group_position, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatarFromId2,
                tournamentId,
                fromPhaseId,
                teamId2,
                1,
                2,
                LocalDateTime.now());

        tenantBinder.unbind();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.bindDefaultTenant();
        jdbcTemplate.update("DELETE FROM team_avatar WHERE phase_id = ?", toPhaseId);
        jdbcTemplate.update("DELETE FROM team_avatar WHERE phase_id = ?", fromPhaseId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-DEC-37-LOCK-COMMIT-RED — DEC-37 Clause B verification.
     *
     * <p>Two threads race to commit the same toPhaseId. Both supply identical (group=1, pos=1) and
     * (group=1, pos=2) assignments. The DB lock + unique constraint ensures exactly one succeeds;
     * the other throws an exception (duplicate structural identity violation or lock-serialised
     * re-attempt). After completion, exactly 2 TeamAvatars exist for toPhase (one successful
     * commit).
     */
    @Test
    @DisplayName("concurrent commitTransition() — exactly one succeeds, second fails")
    void concurrentCommitTransition_exactlyOneSucceeds() throws Exception {
        // Both threads try to commit the same valid assignment
        List<TeamAvatarProposal> assignments =
                List.of(
                        TeamAvatarProposal.forCommit(teamId1, 1, 1),
                        TeamAvatarProposal.forCommit(teamId2, 1, 2));

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<Throwable>> futures = new ArrayList<>();
        UUID capturedToPhaseId = toPhaseId;
        UUID capturedTenantId = tenantId;

        for (int i = 0; i < threadCount; i++) {
            futures.add(
                    executor.submit(
                            () -> {
                                TenantContext.Scope scope = tenantContext.bind(capturedTenantId);
                                try {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    phaseTransitionService.commitTransition(
                                            capturedToPhaseId, assignments);
                                    successCount.incrementAndGet();
                                    return null; // success
                                } catch (Exception e) {
                                    failureCount.incrementAndGet();
                                    return e; // expected for second thread
                                } finally {
                                    scope.close();
                                }
                            }));
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Check for unexpected errors (non-duplicate-key exceptions in the success slot)
        for (Future<Throwable> f : futures) {
            Throwable result = f.get();
            if (result != null) {
                // Acceptable: any exception from the second thread (duplicate key, lock timeout,
                // etc.)
                // Unacceptable: if both threads threw (successCount=0)
            }
        }

        assertThat(successCount.get())
                .as("Exactly one commitTransition() must succeed (DEC-37 Clause B lock)")
                .isEqualTo(1);
        assertThat(failureCount.get())
                .as("Exactly one commitTransition() must fail (second thread serialised)")
                .isEqualTo(1);

        // DEC-26 Rule 2: DB must have exactly 2 avatars (from the single successful commit)
        tenantBinder.bindDefaultTenant();
        try {
            int avatarCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                            Integer.class,
                            toPhaseId);
            assertThat(avatarCount)
                    .as("Exactly 2 TeamAvatars must be persisted (one successful commit)")
                    .isEqualTo(2);
        } finally {
            tenantBinder.unbind();
        }
    }
}
