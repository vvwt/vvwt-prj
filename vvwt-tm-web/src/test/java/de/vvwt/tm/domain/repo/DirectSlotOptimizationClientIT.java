package de.vvwt.tm.domain.repo;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhasePreparationService;
import de.vvwt.tm.domain.Team;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.slotopt.DirectSlotOptimizationClient;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantRegistryPort;
import de.vvwt.worker.score.VarietyScorer;
import de.vvwt.worker.solver.PacketSolver;
import de.vvwt.worker.types.CanonicalPhaseDef;
import de.vvwt.worker.types.JobDef;
import de.vvwt.worker.types.PacketResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link DirectSlotOptimizationClient} — AC11, AC12, AC13 (E04S03).
 *
 * <p>Uses a full Spring context with H2 in-memory database. The test is in the
 * {@code de.vvwt.tm.domain.repo} package to access package-private
 * {@link TenantContext#set} and {@link TenantContext#clear} methods.
 *
 * <h2>Tournament fixture design</h2>
 * <p>Tests use a 4-team (round-robin) phase: C(4,2) = 6 matches. With
 * {@code exhaustive-max-n=10}, N = rowCount = 6 ≤ 10, so exhaustive search applies.
 * Factorial(6) = 720 permutations — fast for integration test context.
 *
 * <p>The slot assignment algorithm uses a greedy round-constraint-aware approach: matches are
 * placed in the earliest lap where neither avatar has played yet. For 4 teams (4 avatars),
 * each lap holds exactly 2 concurrent matches (avatarCount/2 = 2), giving 3 laps total.
 * The round constraint is satisfied by construction.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>AC11 — full preparation flow with DirectSlotOptimizationClient, round constraint satisfied</li>
 *   <li>AC12 — exhaustive result strictly better (lower variety score) than identity permutation
 *       for a 4-team phase (all 720 permutations evaluated)</li>
 *   <li>AC13 — calling optimize() twice produces identical (lapNumber, fieldNumber) values</li>
 * </ul>
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E04S03.story.md">Story E04S03</a>
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:e04s03db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
                    + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.exhaustive-max-n=10"
        })
@ActiveProfiles("test")
class DirectSlotOptimizationClientIT {

    @Autowired private SlotOptimizationClient slotOptimizationClient;
    @Autowired private PhasePreparationService phasePreparationService;
    @Autowired private PhaseToRawPhaseDefMapper phaseToRawPhaseDefMapper;
    @Autowired private TenantContext tenantContext;
    @Autowired private TenantRegistryPort tenantRegistryPort;
    @Autowired private TournamentRepository tournamentRepository;
    @Autowired private PhaseRepository phaseRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamAvatarRepository teamAvatarRepository;
    @Autowired private MatchRepository matchRepository;

    private UUID defaultTenantId;

    @BeforeEach
    void setUpTenantContext() {
        defaultTenantId = tenantRegistryPort.findAll().get(0).tenantId();
        tenantContext.set(defaultTenantId);
    }

    @AfterEach
    void clearTenantContext() {
        tenantContext.clear();
    }

    // =========================================================================
    // AC11 — Full preparation flow with DirectSlotOptimizationClient
    // =========================================================================

    /**
     * AC11: Full preparation flow for a 4-team phase using DirectSlotOptimizationClient.
     *
     * <p>4 teams → C(4,2) = 6 matches. N = rowCount = 6 ≤ exhaustiveMaxN = 10. Exhaustive.
     *
     * Verifies:
     * - Injected bean is DirectSlotOptimizationClient (not fallback), confirming AC1 (bean wiring)
     * - 6 matches generated
     * - All matches have non-null lapNumber and fieldNumber after optimizeSlots (AC4)
     * - Round constraint satisfied: no avatar plays twice in the same lap (AC11)
     */
    @Test
    @Transactional
    void ac11_fullFlow_4teams_directOptimizer_allMatchesHaveValidSlots() {
        // AC1: verify the injected bean is DirectSlotOptimizationClient, not the fallback
        assertThat(slotOptimizationClient)
                .isInstanceOf(DirectSlotOptimizationClient.class);

        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);

        // Create 4 teams with 4 avatars (all in group 1, positions 1-4)
        for (int i = 1; i <= 4; i++) {
            UUID teamId = createTeam(tournamentId, i, true);
            createAvatar(tournamentId, phaseId, teamId, 1, i);
        }

        // Step 1: generate matches — C(4,2) = 6
        phasePreparationService.generateMatches(phaseId);

        List<Match> matchesAfterGen = matchRepository.findByPhaseId(phaseId);
        assertThat(matchesAfterGen)
                .as("C(4,2) = 6 matches expected for 4 teams")
                .hasSize(6);
        for (Match m : matchesAfterGen) {
            assertThat(m.getLapNumber()).isNull();
            assertThat(m.getFieldNumber()).isNull();
        }

        // Step 2: optimize slots via DirectSlotOptimizationClient (exhaustive over 6!=720 perms)
        phasePreparationService.optimizeSlots(phaseId);

        List<Match> matchesAfterOpt = matchRepository.findByPhaseId(phaseId);
        assertThat(matchesAfterOpt).hasSize(6);

        // AC4/AC11: all matches must have non-null lapNumber and fieldNumber
        for (Match m : matchesAfterOpt) {
            assertThat(m.getLapNumber())
                    .as("lapNumber must be non-null for match %s", m.getId())
                    .isNotNull();
            assertThat(m.getFieldNumber())
                    .as("fieldNumber must be non-null for match %s", m.getId())
                    .isNotNull();
        }

        // AC11 round constraint: no avatar plays twice in the same lap
        Map<Integer, Set<UUID>> avatarsByLap = new HashMap<>();
        for (Match m : matchesAfterOpt) {
            int lap = m.getLapNumber();
            Set<UUID> avatarsInLap = avatarsByLap.computeIfAbsent(lap, k -> new HashSet<>());

            assertThat(avatarsInLap)
                    .as("Avatar %s appears twice in lap %d (round constraint violated)",
                            m.getMemberAvatar1Id(), lap)
                    .doesNotContain(m.getMemberAvatar1Id());
            assertThat(avatarsInLap)
                    .as("Avatar %s appears twice in lap %d (round constraint violated)",
                            m.getMemberAvatar2Id(), lap)
                    .doesNotContain(m.getMemberAvatar2Id());

            avatarsInLap.add(m.getMemberAvatar1Id());
            avatarsInLap.add(m.getMemberAvatar2Id());
        }
    }

    // =========================================================================
    // AC12 — Exhaustive result strictly better than sequential (identity) ordering
    // =========================================================================

    /**
     * AC12: For a 4-team phase (6 matches, N = rowCount = 6), the exhaustive optimizer finds
     * a row permutation with strictly lower variety score than the identity permutation [0,1,2,3,4,5].
     *
     * <p>The identity permutation corresponds to processing matches in UUID sort order (the
     * default ordering used by the mapper). The exhaustive search over all 6! = 720 permutations
     * finds the globally optimal ordering. For a 4-team tournament with UUID-random match order,
     * the identity permutation is statistically unlikely to be the global optimum.
     *
     * <p>The test directly invokes {@link PacketSolver} and {@link VarietyScorer} to verify the
     * score comparison, then runs the actual optimization end-to-end.
     */
    @Test
    @Transactional
    void ac12_exhaustiveResult_strictlyBetterThanIdentity_4teams() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);

        for (int i = 1; i <= 4; i++) {
            UUID teamId = createTeam(tournamentId, i, true);
            createAvatar(tournamentId, phaseId, teamId, 1, i);
        }

        // Generate matches
        phasePreparationService.generateMatches(phaseId);
        assertThat(matchRepository.findByPhaseId(phaseId)).hasSize(6);

        // Get the mapping BEFORE optimization to compute scores
        MappingResult mapping = phaseToRawPhaseDefMapper.map(phaseId);
        CanonicalPhaseDef canonical = mapping.canonical();
        int n = canonical.rowCount();  // = 6 for 4 teams
        int avatarCount = canonical.avatarCount();  // = 4

        assertThat(n).isEqualTo(6);
        assertThat(avatarCount).isEqualTo(4);

        // Compute the identity permutation variety score.
        // The mapper sorts matches by UUID ascending, so identity permutation [0,1,...,5]
        // represents the default (UUID-sorted) ordering of matches.
        VarietyScorer scorer = new VarietyScorer();
        boolean[][] activeMatrix = scorer.buildActiveMatrix(canonical.rows(), n, avatarCount);

        int[] identityPermutation = new int[n];
        for (int i = 0; i < n; i++) {
            identityPermutation[i] = i;
        }
        double identityScore = scorer.scoreWithMatrix(identityPermutation, n, avatarCount, activeMatrix);

        // Compute the exhaustive best score over all n! = 720 permutations
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, canonical);
        long totalPerms = 720L;  // 6!
        PacketResult exhaustiveResult = PacketSolver.solvePacket(jobDef, 0L, totalPerms);
        double exhaustiveScore = exhaustiveResult.bestScore();

        // AC12: exhaustive score must be strictly less than (better than) identity score.
        // For a 4-team tournament with UUID-random match order, the identity permutation
        // is virtually never the global optimum — verified directly here.
        assertThat(exhaustiveScore)
                .as("Exhaustive optimizer score (%.4f) must be strictly better (lower) than "
                        + "identity permutation score (%.4f) for a 4-team phase. "
                        + "exhaustive bestRank=%d out of %d permutations.",
                        exhaustiveScore, identityScore, exhaustiveResult.bestRank(), totalPerms)
                .isLessThan(identityScore);

        // Run the actual optimization via the client (verifies end-to-end flow)
        phasePreparationService.optimizeSlots(phaseId);

        List<Match> optimizedMatches = matchRepository.findByPhaseId(phaseId);
        for (Match m : optimizedMatches) {
            assertThat(m.getLapNumber()).isNotNull();
            assertThat(m.getFieldNumber()).isNotNull();
        }
    }

    // =========================================================================
    // AC13 — Idempotency
    // =========================================================================

    /**
     * AC13: Calling optimize(phaseId) twice produces identical (lapNumber, fieldNumber) values.
     * PacketSolver's determinism (E01S03 AC2) guarantees identical bestRank for identical inputs.
     * The greedy slot assignment algorithm is also deterministic for the same row sequence.
     */
    @Test
    @Transactional
    void ac13_idempotency_twoCallsProduceSameSlotAssignment() {
        UUID tournamentId = createTournament();
        UUID phaseId = createPhase(tournamentId);

        for (int i = 1; i <= 4; i++) {
            UUID teamId = createTeam(tournamentId, i, true);
            createAvatar(tournamentId, phaseId, teamId, 1, i);
        }

        phasePreparationService.generateMatches(phaseId);

        // First optimization call
        slotOptimizationClient.optimize(phaseId);
        List<Match> firstResult = matchRepository.findByPhaseId(phaseId);
        firstResult.sort(Comparator.comparing(Match::getId));

        int[] firstLaps = firstResult.stream().mapToInt(Match::getLapNumber).toArray();
        int[] firstFields = firstResult.stream().mapToInt(Match::getFieldNumber).toArray();

        // Second optimization call (idempotency — AC4/AC13)
        slotOptimizationClient.optimize(phaseId);
        List<Match> secondResult = matchRepository.findByPhaseId(phaseId);
        secondResult.sort(Comparator.comparing(Match::getId));

        // AC13: both calls must produce identical slot assignments
        assertThat(secondResult).hasSize(6);
        for (int i = 0; i < secondResult.size(); i++) {
            Match m = secondResult.get(i);
            assertThat(m.getLapNumber())
                    .as("lapNumber for match %s must be identical on second call", m.getId())
                    .isEqualTo(firstLaps[i]);
            assertThat(m.getFieldNumber())
                    .as("fieldNumber for match %s must be identical on second call", m.getId())
                    .isEqualTo(firstFields[i]);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID createTournament() {
        UUID id = UUID.randomUUID();
        Tournament t = new Tournament(
                id, defaultTenantId, "Test Tournament " + id,
                MatchFormat.BEST_OF_3.name(), "setPoints", "standardVolleyball", "roundRobin",
                "DRAFT", LocalDateTime.now());
        tournamentRepository.save(t);
        return id;
    }

    private UUID createPhase(UUID tournamentId) {
        UUID id = UUID.randomUUID();
        Phase p = new Phase(id, defaultTenantId, tournamentId, 1, "Vorrunde", "PENDING", 0,
                LocalDateTime.now());
        phaseRepository.save(p);
        return id;
    }

    private UUID createTeam(UUID tournamentId, int teamNumber, boolean refereeAssignment) {
        UUID id = UUID.randomUUID();
        Team t = new Team(id, defaultTenantId, tournamentId, teamNumber,
                "Team " + teamNumber, true, refereeAssignment, false, LocalDateTime.now());
        teamRepository.save(t);
        return id;
    }

    private UUID createAvatar(UUID tournamentId, UUID phaseId, UUID teamId,
                               int groupNumber, int groupPosition) {
        UUID id = UUID.randomUUID();
        TeamAvatar ta = new TeamAvatar(id, defaultTenantId, tournamentId, phaseId,
                groupNumber, groupPosition, teamId, null, LocalDateTime.now());
        teamAvatarRepository.save(ta);
        return id;
    }
}
