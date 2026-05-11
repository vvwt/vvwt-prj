package de.vvwt.slotopt.worker.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first TDD tests for {@link BalancedVarietyScorer} (E54S07 / DEC-63 Clauses A+B+C; E54S10
 * HOTFIX — weighted mean+variance formula replacing pure-variance).
 *
 * <p>Formula (E54S10 HOTFIX / DEC-63 amendment-by-pointer):
 *
 * <pre>
 *   mean  = SUM(rating_i) / avatarCount
 *   score = α * mean + β * variance
 *         = 1.0 * mean + 10.0 * SUM((rating_i - mean)^2) / avatarCount
 * </pre>
 *
 * <p>Lower score = better balance. Mean term breaks the degenerate-case tie (rank-0 clustered
 * output preferred over alternating when both have variance=0 — Discovery bug introduced by
 * E54S07's pure-variance formulation).
 *
 * <p>All tests in this class were authored before the production class existed (RED-first, DEC-22
 * Iron Law). The RED commit precedes the GREEN commit in git log per
 * AC-GOVERNANCE-DEC-22-RED-FIRST.
 *
 * <h2>DEC-63 Clause B — per-avatar computation identical to VarietyScorer</h2>
 *
 * <p>Both scorers share {@link AvatarRunRatings#computeRatings} for per-avatar product-of-run-
 * lengths computation. Only the final aggregation differs (MEAN vs α*mean+β*variance).
 */
@DisplayName("BalancedVarietyScorer — RED-first TDD (E54S07 / DEC-63; E54S10 HOTFIX)")
class BalancedVarietyScorerTest {

    /** Default α weight (mean coefficient). E54S10 HOTFIX chosen value. */
    private static final double ALPHA = 1.0;

    /** Default β weight (variance coefficient). E54S10 HOTFIX chosen value. */
    private static final double BETA = 10.0;

    private BalancedVarietyScorer scorer;
    private VarietyScorer referenceScorer;

    @BeforeEach
    void setUp() {
        scorer = new BalancedVarietyScorer();
        referenceScorer = new VarietyScorer();
    }

    // =========================================================================
    // AC-TEST-NEW-SCORER-CLASS-EXISTS-RED
    // Verifies the class exists and is instantiatable.
    // =========================================================================

    @Test
    @DisplayName("AC-TEST-NEW-SCORER-CLASS-EXISTS-RED: class exists and is instantiatable")
    void classExists() {
        assertThat(scorer).isNotNull();
    }

    // =========================================================================
    // AC-TEST-WEIGHTED-FORMULA-DOCUMENTED-RED (E54S10)
    //
    // The weighted formula score = α*mean + β*variance is used.
    // Fixture: ratings [1, 5, 3], mean=3.0, variance=8/3.
    //   score = 1.0 * 3.0 + 10.0 * (8.0/3.0) = 3.0 + 80.0/3.0 = 89.0/3.0
    //
    // With pure variance (E54S07): score = 8.0/3.0 ≈ 2.667 → this test FAILS pre-HOTFIX.
    // With weighted formula (E54S10): score = 89.0/3.0 ≈ 29.667 → this test PASSES post-HOTFIX.
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-WEIGHTED-FORMULA-DOCUMENTED-RED (E54S10): α*mean+β*variance formula —"
                    + " ratings [1,5,3] → score=89/3 (α=1, β=10)")
    void weightedFormula_threeAvatarFixture() {
        // rowCount=5, avatarCount=3, identity permutation [0,1,2,3,4]
        int rowCount = 5;
        int avatarCount = 3;
        int[] perm = {0, 1, 2, 3, 4};

        // activeMatrix[row][avatar]
        // avatar-0: [T,F,T,F,T] → rating=1
        // avatar-1: [T,T,T,T,T] → rating=5
        // avatar-2: [F,T,T,T,F] → rating=3 (runs: 1-idle, 3-active, 1-idle → 1×3×1=3)
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        activeMatrix[0][0] = true;
        activeMatrix[2][0] = true;
        activeMatrix[4][0] = true;
        // avatar-1 all active
        for (int r = 0; r < rowCount; r++) activeMatrix[r][1] = true;
        // avatar-2: false,true,true,true,false
        activeMatrix[1][2] = true;
        activeMatrix[2][2] = true;
        activeMatrix[3][2] = true;

        double result = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);

        // ratings = [1, 5, 3]; mean = 3.0; variance = ((1-3)^2 + (5-3)^2 + (3-3)^2) / 3 = 8/3
        // score = α*mean + β*variance = 1.0*3.0 + 10.0*(8.0/3.0) = 3.0 + 80.0/3.0 = 89.0/3.0
        double mean = 3.0;
        double variance = 8.0 / 3.0;
        double expected = ALPHA * mean + BETA * variance;
        assertThat(result)
                .as(
                        "Weighted formula: ratings [1,5,3] → expected α*mean+β*var=%.6f (α=%.1f,"
                                + " β=%.1f)",
                        expected, ALPHA, BETA)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "AC-TEST-WEIGHTED-FORMULA-DOCUMENTED-RED (E54S10): uniform ratings →"
                    + " score = α*mean (variance=0, mean-term determines score)")
    void weightedFormula_uniformRatings_scoreDeterminedByMean() {
        // 3 avatars, all with same rating (single run of 3) → variance = 0; mean = 3.0
        // score = α*mean + β*0 = 1.0*3.0 = 3.0  (NOT 0.0 as with pure variance)
        int rowCount = 3;
        int avatarCount = 3;
        int[] perm = {0, 1, 2};

        // All avatars active in all rows → each has single run of 3 → rating=3
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        for (int r = 0; r < rowCount; r++) {
            activeMatrix[r][0] = true;
            activeMatrix[r][1] = true;
            activeMatrix[r][2] = true;
        }

        double result = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);

        // With pure variance: 0.0; with weighted formula: α*3.0 + β*0.0 = 3.0
        double expected = ALPHA * 3.0;
        assertThat(result)
                .as(
                        "Uniform ratings [3,3,3]: variance=0, mean=3 → score=α*mean=%.1f (NOT 0.0)",
                        expected)
                .isEqualTo(expected);
    }

    // =========================================================================
    // AC-TEST-MEAN-TIEBREAKER-RED (E54S10)
    //
    // Both Config-C and Config-D have variance=0 but different means.
    // With pure variance: score(C)=0.0, score(D)=0.0 → tie → assertion C<D FAILS.
    // With weighted formula: score(C)=α*1=1.0, score(D)=α*25=25.0 → C<D PASSES.
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-MEAN-TIEBREAKER-RED (E54S10): Config-C (all-1, var=0, mean=1) scores lower"
                    + " than Config-D (all-25, var=0, mean=25)")
    void meanTiebreaker_lowMeanPreferredOverHighMean_bothVarianceZero() {
        int avatarCount = 12;

        // Config-C: 12 avatars, all rating = 1 (alternating, rowCount=1 → 1 active row, product=1)
        // Construct via scoreWithMatrix with a 1-row phase where all avatars are active
        // → each avatar has 1 row, single active run of 1 → product=1
        int rowCountC = 1;
        int[] permC = {0};
        boolean[][] matrixC = new boolean[rowCountC][avatarCount];
        for (int a = 0; a < avatarCount; a++) matrixC[0][a] = true;

        double scoreC = scorer.scoreWithMatrix(permC, rowCountC, avatarCount, matrixC);

        // Config-D: 12 avatars, all rating = 25 (all active, rowCount=25 → product=25)
        int rowCountD = 25;
        int[] permD = new int[rowCountD];
        for (int i = 0; i < rowCountD; i++) permD[i] = i;
        boolean[][] matrixD = new boolean[rowCountD][avatarCount];
        for (int r = 0; r < rowCountD; r++) {
            for (int a = 0; a < avatarCount; a++) matrixD[r][a] = true;
        }

        double scoreD = scorer.scoreWithMatrix(permD, rowCountD, avatarCount, matrixD);

        // Config-C: var=0, mean=1 → score=α*1+β*0=1.0
        // Config-D: var=0, mean=25 → score=α*25+β*0=25.0
        // Assert: C < D (lower mean → lower score → rank-0 cluster avoidance)
        assertThat(scoreC)
                .as(
                        "Config-C (mean=1, var=0) score=%.2f must be < Config-D (mean=25, var=0)"
                                + " score=%.2f — mean tiebreaker",
                        scoreC, scoreD)
                .isLessThan(scoreD);
    }

    // =========================================================================
    // AC-TEST-NON-DEGENERATE-PREFERENCE-RED (E54S10)
    //
    // Config-A: {4,4,...,4} (mean=4, var=0) → perfectly balanced
    // Config-B: {25,1,1,...,1} (mean≈3, var≈40) → extreme outlier
    // Assert: score(Config-A) < score(Config-B)
    //
    // With pure variance: score(A)=0.0 < score(B)≈40 → assertion holds (test is GREEN pre-HOTFIX).
    // With weighted formula: score(A)=1*4+10*0=4.0 < score(B)=1*3+10*44=443.0 → holds.
    //
    // This test is included for regression-guard (must stay GREEN post-HOTFIX).
    // It also verifies the weighted formula's exact values
    // (AC-TEST-WEIGHTED-FORMULA-DOCUMENTED-RED).
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-NON-DEGENERATE-PREFERENCE-RED (E54S10): Config-A (balanced, mean=4, var=0)"
                    + " → score=4.0; Config-B (outlier, mean=3, var=44) → score=443.0; A<B")
    void nonDegeneratePreference_balancedBeatsOutlier() {
        int avatarCount = 12;

        // Config-A: 12 avatars, all rating=4
        int rowCountA = 4;
        int[] permA = new int[rowCountA];
        for (int i = 0; i < rowCountA; i++) permA[i] = i;
        boolean[][] matrixA = new boolean[rowCountA][avatarCount];
        for (int r = 0; r < rowCountA; r++) {
            for (int a = 0; a < avatarCount; a++) matrixA[r][a] = true;
        }

        double scoreA = scorer.scoreWithMatrix(permA, rowCountA, avatarCount, matrixA);

        // Config-B: avatar-0 rating=25 (all 25 rows active), avatars 1-11 rating=1 (alternating)
        // mean = (25 + 11*1) / 12 = 36/12 = 3.0
        // variance = ((25-3)^2 + 11*(1-3)^2) / 12 = (484 + 44) / 12 = 528/12 = 44.0
        int rowCountB = 25;
        int[] permB = new int[rowCountB];
        for (int i = 0; i < rowCountB; i++) permB[i] = i;
        boolean[][] matrixB = new boolean[rowCountB][avatarCount];
        for (int r = 0; r < rowCountB; r++) matrixB[r][0] = true;
        for (int a = 1; a < avatarCount; a++) {
            for (int r = 0; r < rowCountB; r++) matrixB[r][a] = (r % 2 == 0);
        }

        double scoreB = scorer.scoreWithMatrix(permB, rowCountB, avatarCount, matrixB);

        // Exact expected values with weighted formula (α=1, β=10):
        // score(A) = 1.0*4.0 + 10.0*0.0 = 4.0
        // score(B) = 1.0*3.0 + 10.0*44.0 = 443.0
        double expectedA = ALPHA * 4.0 + BETA * 0.0; // = 4.0
        double expectedB = ALPHA * 3.0 + BETA * 44.0; // = 443.0
        assertThat(scoreA)
                .as("Config-A (balanced, mean=4, var=0): expected score=%.1f", expectedA)
                .isEqualTo(expectedA);
        assertThat(scoreB)
                .as("Config-B (outlier, mean=3, var=44): expected score=%.1f", expectedB)
                .isEqualTo(expectedB);
        assertThat(scoreA)
                .as(
                        "Config-A (score=%.2f) must be < Config-B (score=%.2f) — balance preferred"
                                + " over outlier",
                        scoreA, scoreB)
                .isLessThan(scoreB);
    }

    // =========================================================================
    // AC-TEST-SCENARIO-A2-ALTERNATING-RED (E54S10)
    //
    // Reproduces Discovery's Scenario A2: 12 avatars, 2 groups, 10 rows (=laps).
    // Pure-group clustered start: rows 0-4 = pure g1 (avatars 0-5), rows 5-9 = pure g2 (6-11).
    //
    // With pure variance (E54S07): identity perm and alternating perm BOTH have variance=0.
    //   → PacketSolver stable-sort picks rank=0 (identity = clustered) → test FAILS (bestRank==0).
    // With weighted formula (E54S10): identity perm scores 5.0 (mean=5, var=0).
    //   Alternating perms score 1.0 (mean=1, var=0). bestRank != 0 → test PASSES.
    //
    // Implementation note: we iterate all 10! = 3628800 permutations using LehmerCodec directly
    // (mirrors the RoutingSlotOptimizationClient L3 inline-solve loop; PacketSolver hardcodes
    // VarietyScorer and is not used here per AC-GOVERNANCE-NO-L1-L2-L3-INVOCATION-CHANGE).
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-SCENARIO-A2-ALTERNATING-RED (E54S10): pure-group clustered start → BVS finds"
                    + " alternating permutation (bestRank != 0, MaxConsecutiveIdle ≤ 1)")
    void scenarioA2_clusteredStart_optimizerReorders() {
        // 12 avatars (0-11), 2 groups: g1={0..5}, g2={6..11}
        // 10 rows: rows 0-4 = pure g1, rows 5-9 = pure g2
        int avatarCount = 12;
        int rowCount = 10;

        // Build activeMatrix: activeMatrix[row][avatar]
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        // rows 0-4: avatars 0-5 active (pure g1)
        for (int r = 0; r < 5; r++) {
            for (int a = 0; a < 6; a++) activeMatrix[r][a] = true;
        }
        // rows 5-9: avatars 6-11 active (pure g2)
        for (int r = 5; r < 10; r++) {
            for (int a = 6; a < 12; a++) activeMatrix[r][a] = true;
        }

        // Iterate all 10! permutations; track best rank and best score
        long n = 10;
        long nFactorial = 1;
        for (long i = 1; i <= n; i++) nFactorial *= i; // 10! = 3628800

        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;

        for (long rank = 0; rank < nFactorial; rank++) {
            int[] perm = LehmerCodec.rankToPermutation(rank, (int) n);
            double score = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
            }
        }

        // Assert 1: bestRank != 0 (identity permutation must NOT win — clustered must be reordered)
        assertThat(bestRank)
                .as(
                        "Scenario A2: bestRank must NOT be 0 (clustered identity); optimizer must"
                                + " reorder to alternating. bestScore=%.4f",
                        bestScore)
                .isNotEqualTo(0L);

        // Assert 2: for the best permutation, every avatar's MaxConsecutiveIdle <= 1
        int[] bestPerm = LehmerCodec.rankToPermutation(bestRank, (int) n);
        int maxIdle = computeMaxConsecutiveIdle(bestPerm, rowCount, avatarCount, activeMatrix);
        assertThat(maxIdle)
                .as(
                        "Scenario A2: MaxConsecutiveIdle for best permutation (rank=%d) must be"
                                + " ≤ 1 (alternating achieved). Actual=%d",
                        bestRank, maxIdle)
                .isLessThanOrEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-SCENARIO-A2-PER-AVATAR-PRODUCT-LOW-RED (E54S10)
    //
    // For the best permutation of Scenario A2 (post-HOTFIX), per-avatar products are ALL == 1.0.
    // With pure variance: rank=0 wins, products are all 5.0 → test FAILS pre-HOTFIX.
    // With weighted formula: alternating perm wins, products are all 1.0 → test PASSES post-HOTFIX.
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-SCENARIO-A2-PER-AVATAR-PRODUCT-LOW-RED (E54S10): best perm of Scenario A2"
                    + " has all per-avatar products == 1.0 (alternating)")
    void scenarioA2_bestPerm_allProductsEqualOne() {
        int avatarCount = 12;
        int rowCount = 10;

        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        for (int r = 0; r < 5; r++) {
            for (int a = 0; a < 6; a++) activeMatrix[r][a] = true;
        }
        for (int r = 5; r < 10; r++) {
            for (int a = 6; a < 12; a++) activeMatrix[r][a] = true;
        }

        long n = 10;
        long nFactorial = 1;
        for (long i = 1; i <= n; i++) nFactorial *= i;

        long bestRank = 0L;
        double bestScore = Double.MAX_VALUE;
        for (long rank = 0; rank < nFactorial; rank++) {
            int[] perm = LehmerCodec.rankToPermutation(rank, (int) n);
            double score = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
            if (score < bestScore) {
                bestScore = score;
                bestRank = rank;
            }
        }

        int[] bestPerm = LehmerCodec.rankToPermutation(bestRank, (int) n);
        double[] ratings =
                AvatarRunRatings.computeRatings(bestPerm, rowCount, avatarCount, activeMatrix);

        for (int a = 0; a < avatarCount; a++) {
            assertThat(ratings[a])
                    .as(
                            "Scenario A2: per-avatar product for avatar %d must be 1.0 (alternating"
                                    + " achieved). bestRank=%d, actual rating=%.2f",
                            a, bestRank, ratings[a])
                    .isEqualTo(1.0);
        }
    }

    // =========================================================================
    // AC-TEST-SCORER-PREFERS-BALANCED-CONFIG-RED
    //
    // Config-A: 12 avatars, ratings approx {25,1,1,...,1} → unbalanced; high variance
    // Config-B: 12 avatars, ratings {4,4,...,4} → balanced; variance=0
    // BalancedVarietyScorer MUST prefer B (lower score for B than A).
    //
    // With pure variance (E54S07): score(B)=0.0 <= score(A)=44.0 → assertion holds.
    // With weighted formula (E54S10): score(B)=4.0 <= score(A)=443.0 → assertion still holds.
    // (This test was GREEN pre-HOTFIX and must stay GREEN post-HOTFIX.)
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-SCORER-PREFERS-BALANCED-CONFIG-RED: Config-B (balanced, var=0) scores lower"
                    + " than Config-A (unbalanced, var=44)")
    void scorerPrefersBalancedConfig() {
        // Config-A: rowCount=25, avatarCount=12
        int rowCountA = 25;
        int avatarCountA = 12;
        int[] permA = new int[rowCountA];
        for (int i = 0; i < rowCountA; i++) permA[i] = i;

        boolean[][] matrixA = new boolean[rowCountA][avatarCountA];
        // avatar-0: active all 25 rows
        for (int r = 0; r < rowCountA; r++) matrixA[r][0] = true;
        // avatars 1-11: alternating T/F
        for (int a = 1; a < avatarCountA; a++) {
            for (int r = 0; r < rowCountA; r++) {
                matrixA[r][a] = (r % 2 == 0);
            }
        }

        double scoreA = scorer.scoreWithMatrix(permA, rowCountA, avatarCountA, matrixA);

        // Config-B: rowCount=4, avatarCount=12, all active → each rating=4, variance=0
        int rowCountB = 4;
        int[] permB = new int[rowCountB];
        for (int i = 0; i < rowCountB; i++) permB[i] = i;

        boolean[][] matrixB = new boolean[rowCountB][avatarCountA];
        for (int r = 0; r < rowCountB; r++) {
            for (int a = 0; a < avatarCountA; a++) {
                matrixB[r][a] = true;
            }
        }

        double scoreB = scorer.scoreWithMatrix(permB, rowCountB, avatarCountA, matrixB);

        assertThat(scoreB)
                .as(
                        "BalancedVarietyScorer: Config-B (balanced) score=%.2f must be"
                                + " <= Config-A (unbalanced) score=%.2f",
                        scoreB, scoreA)
                .isLessThanOrEqualTo(scoreA);

        // Also verify VarietyScorer (MEAN-of-products) prefers A over B (reference check)
        double meanScoreA =
                referenceScorer.scoreWithMatrix(permA, rowCountA, avatarCountA, matrixA);
        double meanScoreB =
                referenceScorer.scoreWithMatrix(permB, rowCountB, avatarCountA, matrixB);
        // VarietyScorer: score(A) = (25 + 11*1)/12 = 3.0; score(B) = 4.0 → A < B (prefers A)
        assertThat(meanScoreA)
                .as(
                        "VarietyScorer prefers Config-A (score=%.2f) over Config-B (score=%.2f)"
                                + " — confirms the balance-vs-mean misalignment",
                        meanScoreA, meanScoreB)
                .isLessThan(meanScoreB);
    }

    // =========================================================================
    // AC-TEST-SCORER-PER-AVATAR-LOGIC-IDENTICAL-RED
    //
    // Per-avatar product-of-run-lengths computation is IDENTICAL between scorers.
    // Both use AvatarRunRatings.computeRatings(rowSeq, rowCount, avatarCount, activeMatrix).
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-SCORER-PER-AVATAR-LOGIC-IDENTICAL-RED: per-avatar ratings identical between"
                    + " BalancedVarietyScorer and VarietyScorer for 2-avatar 3-row phase")
    void perAvatarLogicIdentical_phase2a3r() {
        int rowCount = 3;
        int avatarCount = 2;
        int[] perm = {0, 1, 2};

        // avatar-0: all active → rating=3; avatar-1: all idle → rating=3
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        for (int r = 0; r < rowCount; r++) activeMatrix[r][0] = true;

        // Get per-avatar ratings from shared utility
        double[] ratings =
                AvatarRunRatings.computeRatings(perm, rowCount, avatarCount, activeMatrix);
        assertThat(ratings).hasSize(2);

        // avatar-0: single run of 3 active → product=3
        assertThat(ratings[0]).as("avatar-0 rating (active all 3 rows)").isEqualTo(3.0);
        // avatar-1: single run of 3 idle → product=3
        assertThat(ratings[1]).as("avatar-1 rating (idle all 3 rows)").isEqualTo(3.0);

        // VarietyScorer score = (3+3)/2 = 3.0
        double varietyScore =
                referenceScorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
        assertThat(varietyScore).as("VarietyScorer score = mean = 3.0").isEqualTo(3.0);

        // BalancedVarietyScorer (E54S10 weighted formula):
        // ratings=[3,3], mean=3.0, var=0.0 → score = α*3.0 + β*0.0 = 3.0
        double balancedScore = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
        double expectedBalanced = ALPHA * 3.0 + BETA * 0.0; // = 3.0
        assertThat(balancedScore)
                .as(
                        "BalancedVarietyScorer (E54S10 weighted): ratings [3,3] → score=α*mean+β*0="
                                + "%.1f",
                        expectedBalanced)
                .isEqualTo(expectedBalanced);
    }

    @Test
    @DisplayName(
            "AC-TEST-SCORER-PER-AVATAR-LOGIC-IDENTICAL-RED: per-avatar ratings identical for"
                    + " 3-avatar 5-row phase (ratings [1,5,3])")
    void perAvatarLogicIdentical_phase3a5r() {
        int rowCount = 5;
        int avatarCount = 3;
        int[] perm = {0, 1, 2, 3, 4};

        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        // avatar-0: [T,F,T,F,T] → rating=1
        activeMatrix[0][0] = true;
        activeMatrix[2][0] = true;
        activeMatrix[4][0] = true;
        // avatar-1: all active → rating=5
        for (int r = 0; r < rowCount; r++) activeMatrix[r][1] = true;
        // avatar-2: [F,T,T,T,F] → rating=3
        activeMatrix[1][2] = true;
        activeMatrix[2][2] = true;
        activeMatrix[3][2] = true;

        double[] ratings =
                AvatarRunRatings.computeRatings(perm, rowCount, avatarCount, activeMatrix);
        assertThat(ratings[0]).as("avatar-0 rating (alternating, 5 runs of 1)").isEqualTo(1.0);
        assertThat(ratings[1]).as("avatar-1 rating (always active, 1 run of 5)").isEqualTo(5.0);
        assertThat(ratings[2]).as("avatar-2 rating (idle-3active-idle → 1×3×1=3)").isEqualTo(3.0);
    }

    // =========================================================================
    // AC-TEST-DETERMINISM-PRESERVED-GREEN (E54S10)
    // 10 fresh invocations return bit-identical results (DEC-49 D-3).
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-DETERMINISM-PRESERVED-GREEN: 10 fresh invocations of scoreWithMatrix produce"
                    + " bit-identical results")
    void determinism_10FreshInvocations_bitIdentical() {
        int rowCount = 5;
        int avatarCount = 3;
        int[] perm = {0, 1, 2, 3, 4};

        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        activeMatrix[0][0] = true;
        activeMatrix[2][0] = true;
        activeMatrix[4][0] = true;
        for (int r = 0; r < rowCount; r++) activeMatrix[r][1] = true;
        activeMatrix[1][2] = true;
        activeMatrix[2][2] = true;
        activeMatrix[3][2] = true;

        double first = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
        for (int i = 0; i < 9; i++) {
            double next =
                    new BalancedVarietyScorer()
                            .scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
            assertThat(next)
                    .as("Invocation %d must be bit-identical to first result %.15f", i + 2, first)
                    .isEqualTo(first);
        }
    }

    // =========================================================================
    // AC-TEST-CONFIG-PROPERTY-RESOLUTION-RED
    // ScorerFactory.createScorer("mean") → VarietyScorer instance
    // ScorerFactory.createScorer("balanced") → BalancedVarietyScorer instance
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-CONFIG-PROPERTY-RESOLUTION-RED: ScorerFactory 'mean' resolves to"
                    + " VarietyScorer")
    void configProperty_mean_resolvesToVarietyScorer() {
        Object result = ScorerFactory.createScorer("mean");
        assertThat(result).isInstanceOf(VarietyScorer.class);
    }

    @Test
    @DisplayName(
            "AC-TEST-CONFIG-PROPERTY-RESOLUTION-RED: ScorerFactory 'balanced' resolves to"
                    + " BalancedVarietyScorer")
    void configProperty_balanced_resolvesToBalancedVarietyScorer() {
        Object result = ScorerFactory.createScorer("balanced");
        assertThat(result).isInstanceOf(BalancedVarietyScorer.class);
    }

    // =========================================================================
    // AC-TEST-CONFIG-INVALID-FALLS-BACK-RED
    // ScorerFactory.createScorer("invalid_value") → VarietyScorer + WARN log
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-CONFIG-INVALID-FALLS-BACK-RED: invalid scorer value falls back to"
                    + " VarietyScorer without exception")
    void configProperty_invalid_fallsBackToVarietyScorer_noException() {
        // Must not throw — falls back silently with WARN log
        Object result = ScorerFactory.createScorer("invalid_value");
        assertThat(result).isInstanceOf(VarietyScorer.class);
    }

    // =========================================================================
    // AC-ERROR-EMPTY-PHASE-NO-OP-PRESERVED
    // score() with avatarCount=0 returns 0.0
    // =========================================================================

    @Test
    @DisplayName("AC-ERROR-EMPTY-PHASE-NO-OP-PRESERVED: score with avatarCount=0 returns 0.0")
    void emptyPhase_returnsZero() {
        CanonicalPhaseDef phase = new CanonicalPhaseDef(0, 0, List.of());
        double result = scorer.score(new int[0], phase);
        assertThat(result).as("Empty phase → score 0.0").isEqualTo(0.0);
    }

    // =========================================================================
    // AC-ERROR-INPUT-VALIDATION-PRESERVED
    // Same guard clauses as VarietyScorer
    // =========================================================================

    @Test
    @DisplayName("AC-ERROR-INPUT-VALIDATION: null phaseDef throws IAE")
    void guardClause_nullPhaseDef() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> scorer.score(new int[] {0}, null))
                .withMessageContaining("phaseDef must not be null");
    }

    @Test
    @DisplayName("AC-ERROR-INPUT-VALIDATION: null rowSequence throws IAE")
    void guardClause_nullRowSequence() {
        CanonicalPhaseDef phase = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> scorer.score(null, phase))
                .withMessageContaining("rowSequence must not be null");
    }

    @Test
    @DisplayName("AC-ERROR-INPUT-VALIDATION: rowSequence.length mismatch throws IAE")
    void guardClause_rowSequenceLengthMismatch() {
        CanonicalPhaseDef phase =
                new CanonicalPhaseDef(3, 2, List.of(List.of(0), List.of(1), List.of(0, 1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> scorer.score(new int[] {0, 1}, phase))
                .withMessageContaining("rowSequence.length=2")
                .withMessageContaining("phaseDef.rowCount=3");
    }

    @Test
    @DisplayName("AC-ERROR-INPUT-VALIDATION: out-of-range rowSequence index throws IAE")
    void guardClause_rowSequenceOutOfRange() {
        CanonicalPhaseDef phase = new CanonicalPhaseDef(2, 2, List.of(List.of(0), List.of(1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> scorer.score(new int[] {0, 5}, phase))
                .withMessageContaining("out of range");
    }

    @Test
    @DisplayName("AC-ERROR-INPUT-VALIDATION: duplicate value in rowSequence throws IAE")
    void guardClause_rowSequenceDuplicate() {
        CanonicalPhaseDef phase =
                new CanonicalPhaseDef(3, 2, List.of(List.of(0), List.of(1), List.of(0, 1)));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> scorer.score(new int[] {0, 0, 2}, phase))
                .withMessageContaining("duplicate value");
    }

    @Test
    @DisplayName("AC-ERROR-INPUT-VALIDATION: negative avatar index in phaseDef throws IAE")
    void guardClause_negativeAvatarIndex() {
        List<List<Integer>> rows = new ArrayList<>();
        rows.add(new ArrayList<>(List.of(-1, 0)));
        CanonicalPhaseDef phase = new CanonicalPhaseDef(1, 1, rows);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> scorer.score(new int[] {0}, phase))
                .withMessageContaining("Negative avatar index");
    }

    // =========================================================================
    // Private helper methods
    // =========================================================================

    /**
     * Computes the maximum consecutive idle laps across all avatars for a given row permutation.
     *
     * <p>For each avatar, counts consecutive rows where the avatar is idle (not active). Returns
     * the maximum idle run length across all avatars in the given permutation.
     *
     * @param rowSequence the row permutation
     * @param rowCount number of rows
     * @param avatarCount number of avatars
     * @param activeMatrix active-matrix[row][avatar]
     * @return maximum consecutive idle run length across all avatars
     */
    private static int computeMaxConsecutiveIdle(
            int[] rowSequence, int rowCount, int avatarCount, boolean[][] activeMatrix) {
        int globalMax = 0;
        for (int a = 0; a < avatarCount; a++) {
            int maxIdle = 0;
            int currentIdle = 0;
            for (int pos = 0; pos < rowCount; pos++) {
                if (!activeMatrix[rowSequence[pos]][a]) {
                    currentIdle++;
                    maxIdle = Math.max(maxIdle, currentIdle);
                } else {
                    currentIdle = 0;
                }
            }
            globalMax = Math.max(globalMax, maxIdle);
        }
        return globalMax;
    }
}
