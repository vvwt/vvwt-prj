// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.MatchState;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.SetResultInput;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * Concurrency integration test for {@link ScoringService#registerMatchResult(SetResultInput)} —
 * verifying that the per-tournament pessimistic DB row-lock ({@code SELECT … FOR UPDATE} via {@code
 * TournamentRepository.findByIdForUpdate}) serialises concurrent cascades for the same tournament,
 * preventing lost-update on {@code TeamAvatarRating} refresh (DEC-37 Clause B,
 * AC-CASCADE-LOCK-IT-CONTRACT).
 *
 * <h2>DEC-36 cross-package test typing</h2>
 *
 * <p>This test class is co-located in {@code de.vvwt.tm.scoring} — the PUBLIC API package of the
 * scoring module — and is therefore cross-package relative to the implementation class {@code
 * de.vvwt.tm.scoring.internal.DefaultScoringService}. Per DEC-36, cross-package tests MUST
 * reference the subject via its interface ({@link ScoringService}), not the implementation class.
 * This class therefore injects {@code ScoringService} (NOT {@code DefaultScoringService}).
 *
 * <h2>Why {@code @SpringBootTest} (DEC-38 Clause C)</h2>
 *
 * <p>A full Spring context is required because:
 *
 * <ol>
 *   <li>The test spawns real OS threads; {@code @ApplicationModuleTest} does not support
 *       multi-thread harness execution with the full TX machinery.
 *   <li>The lock-under-test ({@code SELECT … FOR UPDATE}) is a DB-level primitive; it must be
 *       verified through a real JDBC connection under a real {@code @Transactional} boundary.
 *   <li>The full routing-DataSource stack (TenantContext + DataSourceRouter) is needed to reproduce
 *       the exact production-mode concurrency path.
 * </ol>
 *
 * <h2>Test scenario</h2>
 *
 * <ol>
 *   <li>One tournament, one phase containing two matches in lap 1.
 *   <li>AVATAR1 (Team 1) plays in BOTH matches — so {@code refreshAvatarRating(AVATAR1)} is
 *       triggered by BOTH cascade invocations.
 *   <li>Two threads are synchronized with a {@link CyclicBarrier} to maximize overlap probability;
 *       each thread submits a set-result for one of the two matches.
 *   <li>Post-cascade assertion: {@code TeamAvatarRating.matchCount} for AVATAR1 must equal 2,
 *       proving that both terminal matches were visible to the rating refresh of the second
 *       cascade.
 * </ol>
 *
 * <h2>RED-first attestation (DEC-22, AC-CASCADE-LOCK-IT-RED-FIRST)</h2>
 *
 * <p>This test was committed RED in git history: at the time of the RED commit, {@code
 * DefaultScoringService.registerMatchResult} did NOT call {@code
 * tournamentRepository.findByIdForUpdate(input.tournamentId())} — instead it used a plain {@code
 * findById} lookup, acquiring no row-lock. Under concurrent execution with H2 MVCC, both threads
 * read AVATAR1's terminal matches before either committed, each seeing only one match → final
 * {@code matchCount=1} instead of 2 → assertion failed → RED. The GREEN transition was a single
 * commit adding the {@code findByIdForUpdate} call.
 *
 * @since E31S03
 * @see ScoringService
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic lock</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing</a>
 * @see <a href="DEC-38">DEC-38 Clause C — {@code @SpringBootTest} for lock IT</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        properties = {
            // Fixed DB name so all threads share the same H2 in-memory instance
            // (H2 2.x uses MVCC by default; MVCC=TRUE flag removed in 2.x)
            "spring.datasource.url=jdbc:h2:mem:cascadelockdb;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("CascadeLockIT — per-tournament pessimistic lock serialises concurrent cascades")
class CascadeLockIT {

    // -----------------------------------------------------------------------
    // Spring-injected dependencies
    // -----------------------------------------------------------------------

    /** Subject under test — referenced via INTERFACE per DEC-36 (cross-package). */
    @Autowired private ScoringService scoringService;

    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private MatchRepository matchRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private TeamAvatarRatingRepository teamAvatarRatingRepository;
    @Autowired private TenantContextTestSupport.Binder tenantContextBinder;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private JdbcTemplate jdbcTemplate;

    // -----------------------------------------------------------------------
    // Shared test data — populated in @BeforeEach
    // -----------------------------------------------------------------------

    private UUID tenantId;
    private UUID defaultLocationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID matchAId;
    private UUID matchBId;
    private UUID team1Id;
    private UUID team2Id;
    private UUID team3Id;
    private UUID avatar1Id; // plays in BOTH matches — the rating race target
    private UUID avatar2Id; // opponent in Match A
    private UUID avatar3Id; // opponent in Match B

    // -----------------------------------------------------------------------
    // Setup / teardown
    // -----------------------------------------------------------------------

    @BeforeEach
    void setUpData() {
        tenantId = tenantContextBinder.bindDefaultTenant();
        defaultLocationId = tenantContextBinder.getDefaultLocationId();

        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();
        matchAId = UUID.randomUUID();
        matchBId = UUID.randomUUID();
        team1Id = UUID.randomUUID();
        team2Id = UUID.randomUUID();
        team3Id = UUID.randomUUID();
        avatar1Id = UUID.randomUUID();
        avatar2Id = UUID.randomUUID();
        avatar3Id = UUID.randomUUID();

        // Tournament
        Tournament tournament = new Tournament();
        tournament.setId(tournamentId);
        tournament.setDescription("CascadeLockIT-Tournament");
        tournament.setMatchFormat("BEST_OF_1");
        tournament.setScoringRuleId(
                "setPoints"); // ScoringRule bean id (threePoint/twoPoint/setPoints)
        tournament.setSetValidationRuleId("standardVolleyball"); // SetValidationRule bean id
        tournament.setMatchGeneratorId("roundRobin");
        tournament.setStatus("ACTIVE");
        tournament.setCreatedAt(LocalDateTime.now());
        tournament.setFieldCount(2);
        tournament.setTeamCount(3);
        // E45S06: location_id NOT NULL (DEC-39 D2)
        tournament.setLocationId(defaultLocationId);
        tournamentRepository.save(tournament);

        // Phase
        Phase phase = new Phase();
        phase.setId(phaseId);
        phase.setTournamentId(tournamentId);
        phase.setSequenceNumber(1);
        phase.setDescription("Phase 1");
        phase.setStatus("ACTIVE");
        phase.setCurrentLapNumber(0);
        phaseRepository.save(phase);

        // Teams — required by team_avatar FK
        LocalDateTime now = LocalDateTime.now();
        teamRepository.save(new Team(team1Id, tournamentId, 1, "Team 1", true, false, false, now));
        teamRepository.save(new Team(team2Id, tournamentId, 2, "Team 2", true, false, false, now));
        teamRepository.save(new Team(team3Id, tournamentId, 3, "Team 3", true, false, false, now));

        // TeamAvatars — required by match FK (FK_MATCH_MEMBER_AVATAR_1/2)
        teamAvatarRepository.save(
                new TeamAvatar(
                        avatar1Id, tournamentId, phaseId, 1, 1, team1Id, "Team 1 avatar", now));
        teamAvatarRepository.save(
                new TeamAvatar(
                        avatar2Id, tournamentId, phaseId, 1, 2, team2Id, "Team 2 avatar", now));
        teamAvatarRepository.save(
                new TeamAvatar(
                        avatar3Id, tournamentId, phaseId, 1, 3, team3Id, "Team 3 avatar", now));

        // Match A: AVATAR1 vs AVATAR2, lap 1, field 1
        Match matchA = new Match();
        matchA.setId(matchAId);
        matchA.setTournamentId(tournamentId);
        matchA.setPhaseId(phaseId);
        matchA.setMemberAvatar1Id(avatar1Id);
        matchA.setMemberAvatar2Id(avatar2Id);
        matchA.setMatchState(MatchState.INPROGRESS);
        matchA.setLapNumber(1);
        matchA.setFieldNumber(1);
        matchRepository.save(matchA);

        // Match B: AVATAR1 vs AVATAR3, lap 1, field 2
        Match matchB = new Match();
        matchB.setId(matchBId);
        matchB.setTournamentId(tournamentId);
        matchB.setPhaseId(phaseId);
        matchB.setMemberAvatar1Id(avatar1Id);
        matchB.setMemberAvatar2Id(avatar3Id);
        matchB.setMatchState(MatchState.INPROGRESS);
        matchB.setLapNumber(1);
        matchB.setFieldNumber(2);
        matchRepository.save(matchB);
    }

    @AfterEach
    void tearDown() {
        // Clean up BEFORE unbinding the tenant — JDBC operations require tenant context
        jdbcTemplate.execute(
                "DELETE FROM match_outcome WHERE match_id IN (SELECT id FROM match WHERE"
                        + " tournament_id = '"
                        + tournamentId
                        + "')");
        jdbcTemplate.execute(
                "DELETE FROM set_result WHERE match_id IN (SELECT id FROM match WHERE"
                        + " tournament_id = '"
                        + tournamentId
                        + "')");
        jdbcTemplate.execute("DELETE FROM match WHERE tournament_id = '" + tournamentId + "'");
        // team_avatar_rating rows must be removed before team_avatar (FK constraint)
        jdbcTemplate.execute(
                "DELETE FROM team_avatar_rating WHERE avatar_id IN ('"
                        + avatar1Id
                        + "','"
                        + avatar2Id
                        + "','"
                        + avatar3Id
                        + "')");
        jdbcTemplate.execute(
                "DELETE FROM team_avatar WHERE tournament_id = '" + tournamentId + "'");
        jdbcTemplate.execute("DELETE FROM phase WHERE tournament_id = '" + tournamentId + "'");
        jdbcTemplate.execute("DELETE FROM team WHERE tournament_id = '" + tournamentId + "'");
        jdbcTemplate.execute("DELETE FROM tournament WHERE id = '" + tournamentId + "'");
        // Unbind tenant context LAST
        tenantContextBinder.unbind();
    }

    // -----------------------------------------------------------------------
    // Test — AC-CASCADE-LOCK-IT-CONTRACT
    // -----------------------------------------------------------------------

    /**
     * Two concurrent {@link ScoringService#registerMatchResult(SetResultInput)} calls for the same
     * tournament must serialize, so that the second cascade's {@code refreshAvatarRating} for
     * AVATAR1 sees both terminal matches and persists {@code matchCount=2}.
     *
     * <p>Without the per-tournament lock ({@code SELECT … FOR UPDATE}), both threads read AVATAR1's
     * terminal matches before either commits → each sees only one match → final {@code
     * matchCount=1} → assertion fails (RED). With the lock, the second thread blocks until the
     * first commits → second recompute sees both matches → {@code matchCount=2} → GREEN.
     *
     * <p>The test is run {@value #ITERATIONS} times to reduce false-GREEN probability in the RED
     * state (each run is an independent CyclicBarrier trial).
     */
    @Test
    @DisplayName("Concurrent cascade for AVATAR1 → matchCount=2 (lock serialises)")
    void concurrentCascade_avatar1RatingReflectsBothMatches() throws Exception {
        final int iterations = 5; // sufficient for H2/local; see impl-report note
        for (int i = 0; i < iterations; i++) {
            runOneConcurrentTrial();
            // Reset state between iterations
            resetRatingsAndMatchStates();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Runs one concurrent trial: 2 threads submit results simultaneously and we verify state. */
    private void runOneConcurrentTrial() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        SetResultInput inputA =
                SetResultInput.withTournament(tournamentId, matchAId, 0, 25, 15, "actorA", null);
        SetResultInput inputB =
                SetResultInput.withTournament(tournamentId, matchBId, 0, 25, 18, "actorB", null);

        UUID capturedTenantId = tenantId;

        List<Future<Void>> futures = new ArrayList<>();

        futures.add(
                executor.submit(
                        () -> {
                            // Re-bind tenant context on spawned thread (ThreadLocal not inherited)
                            TenantContext.Scope scope = tenantContext.bind(capturedTenantId);
                            try {
                                barrier.await(5, TimeUnit.SECONDS); // synchronize start
                                scoringService.registerMatchResult(inputA);
                            } finally {
                                scope.close();
                            }
                            return null;
                        }));

        futures.add(
                executor.submit(
                        () -> {
                            TenantContext.Scope scope = tenantContext.bind(capturedTenantId);
                            try {
                                barrier.await(5, TimeUnit.SECONDS); // synchronize start
                                scoringService.registerMatchResult(inputB);
                            } finally {
                                scope.close();
                            }
                            return null;
                        }));

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // Propagate exceptions from threads
        for (Future<Void> f : futures) {
            f.get(); // throws if thread threw
        }

        // Assert: AVATAR1's rating must reflect BOTH matches (matchCount=2)
        // Bind tenant to read back from DB on this (test) thread
        Optional<TeamAvatarRating> ratingOpt = teamAvatarRatingRepository.findById(avatar1Id);
        assertThat(ratingOpt)
                .as("TeamAvatarRating for AVATAR1 must exist after both cascades")
                .isPresent();
        TeamAvatarRating rating = ratingOpt.get();
        assertThat(rating.getMatchCount())
                .as(
                        "AVATAR1 must have matchCount=2 — both matches counted (lock serialises"
                                + " refreshAvatarRating)")
                .isEqualTo(2);
    }

    /**
     * Resets match states back to INPROGRESS and clears intermediate data between iterations.
     *
     * <p>Between iterations, both matches must be reset to INPROGRESS (non-terminal) so the next
     * trial's concurrent cascades can produce terminal transitions again.
     */
    private void resetRatingsAndMatchStates() {
        // Reset match A and B back to INPROGRESS for next iteration
        jdbcTemplate.execute(
                "UPDATE match SET state = "
                        + MatchState.INPROGRESS.getLegacyCode()
                        + " WHERE id = '"
                        + matchAId
                        + "'");
        jdbcTemplate.execute(
                "UPDATE match SET state = "
                        + MatchState.INPROGRESS.getLegacyCode()
                        + " WHERE id = '"
                        + matchBId
                        + "'");

        // Clear set results and outcomes for next trial
        jdbcTemplate.execute(
                "DELETE FROM set_result WHERE match_id IN ('" + matchAId + "','" + matchBId + "')");
        jdbcTemplate.execute(
                "DELETE FROM match_outcome WHERE match_id IN ('"
                        + matchAId
                        + "','"
                        + matchBId
                        + "')");

        // Clear ratings
        jdbcTemplate.execute(
                "DELETE FROM team_avatar_rating WHERE avatar_id IN ('"
                        + avatar1Id
                        + "','"
                        + avatar2Id
                        + "','"
                        + avatar3Id
                        + "')");

        // Reset phase lap number
        jdbcTemplate.execute(
                "UPDATE phase SET current_lap_number = 0 WHERE id = '" + phaseId + "'");
    }
}
