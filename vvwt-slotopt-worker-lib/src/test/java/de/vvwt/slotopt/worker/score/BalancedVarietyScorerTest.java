package de.vvwt.slotopt.worker.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * RED-first TDD tests for {@link BalancedVarietyScorer} (E54S07 / DEC-63 Clauses A+B+C).
 *
 * <p>Formulation chosen: pure variance — {@code SUM((rating_i - mean)^2) / avatarCount}.
 * Lower score = better balance (variance=0 ↔ all avatars have identical idle-run product).
 *
 * <p>All tests in this class were authored before the production class existed (RED-first, DEC-22
 * Iron Law). The RED commit precedes the GREEN commit in git log per
 * AC-GOVERNANCE-DEC-22-RED-FIRST.
 *
 * <h2>DEC-63 Clause B — per-avatar computation identical to VarietyScorer</h2>
 *
 * <p>Both scorers share {@link AvatarRunRatings#computeRatings} for per-avatar product-of-run-
 * lengths computation. Only the final aggregation differs (MEAN vs VARIANCE).
 */
@DisplayName("BalancedVarietyScorer — RED-first TDD (E54S07 / DEC-63)")
class BalancedVarietyScorerTest {

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
    // AC-TEST-VARIANCE-FORMULATION-CHOSEN-RED
    // Formulation: pure variance = SUM((rating_i - mean)^2) / avatarCount
    //
    // Manually computed fixture:
    //   Phase: 2 avatars, 3 rows
    //   avatar-0: active in rows 0,1,2 → single run of 3 → product = 3
    //   avatar-1: active in row 0 only; idle in rows 1,2 →
    //             run: 1 active (len 1), then 2 idle (len 2) → product = 1 × 2 = 2
    //   Wait, let's build a simpler cleaner fixture with analytically derivable products:
    //
    //   Fixture A: 2 avatars, 3 rows
    //     avatar-0: active rows 0,1,2 → single run of 3 → rating = 3
    //     avatar-1: idle rows 0,1,2  → single run of 3 → rating = 3
    //     mean = (3+3)/2 = 3.0; variance = ((3-3)^2 + (3-3)^2)/2 = 0.0
    //
    //   Fixture B: 2 avatars, 2 rows
    //     avatar-0: active rows 0,1 → single run of 2 → rating = 2
    //     avatar-1: idle rows 0,1   → single run of 2 → rating = 2
    //     mean = 2.0; variance = 0.0
    //
    //   Fixture C: 3 avatars — ratings [1, 3, 5]
    //     mean = (1+3+5)/3 = 3.0
    //     variance = ((1-3)^2 + (3-3)^2 + (5-3)^2)/3 = (4+0+4)/3 = 8/3 ≈ 2.667
    //     → score ≈ 2.667
    //
    //   For Fixture C we need a phase that produces per-avatar ratings [1, 3, 5]:
    //     - avatar-0: rating=1 (alternating every row; each run=1; 1-row perm would give 1*1=1 for
    //       single row. Use: 1-row phase with avatar-0 active → single run of 1 → rating=1)
    //     - Better: build via scoreWithMatrix with known active-matrix that produces [1,3,5].
    //       Use rowCount=3, single permutation [0,1,2]:
    //         avatar-0: active rows 0,1,2 → single run 3 → rating=3. NO — that gives 3 not 1.
    //
    //   Use scoreWithMatrix directly with a manually crafted active matrix:
    //     rowCount=3, avatarCount=3, perm=[0,1,2]
    //     Designed so per-avatar products are [1, 3, 5]:
    //       avatar-0: alternating each row → 3 runs of 1 → product = 1 × 1 × 1 = 1
    //                 rows [true, false, true] for avatar-0
    //       avatar-1: active all 3 rows → single run of 3 → product = 3
    //                 rows [true, true, true] for avatar-1
    //       avatar-2: target rating=5 — needs 5. With 3 rows:
    //                 row-0: active (run 1), row-1: active (run 2), row-2: active (run 3) — no,
    //                 same state all through → single run of 3 → rating=3. Can't get 5 with 3 rows
    //                 and a single permutation of 3 distinct states.
    //                 Alternative: active rows 0,1 then idle row 2 → runs [2, 1] → 2×1=2. Still not 5.
    //                 With 5 rows: active all 5 → rating=5. Use rowCount=5:
    //
    //   Revised Fixture C: rowCount=5, avatarCount=3, perm=[0,1,2,3,4]
    //     activeMatrix[row][avatar]:
    //       avatar-0: [T,F,T,F,T] → runs: 1-active,1-idle,1-active,1-idle,1-active → 5 runs of 1
    //                 product = 1×1×1×1×1 = 1
    //       avatar-1: [T,T,T,T,T] → single run of 5 → product = 5
    //       avatar-2: [F,T,T,T,F] → runs: 1-idle, 3-active, 1-idle → product = 1×3×1 = 3
    //     ratings = [1, 5, 3]; mean = (1+5+3)/3 = 9/3 = 3.0
    //     variance = ((1-3)^2 + (5-3)^2 + (3-3)^2)/3 = (4+4+0)/3 = 8/3 ≈ 2.6667
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-VARIANCE-FORMULATION-CHOSEN-RED: pure variance formula — 3-avatar fixture"
                    + " ratings [1,5,3] → variance=8/3")
    void formulationPureVariance_threeAvatarFixture() {
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
        activeMatrix[1][0] = false;
        activeMatrix[2][0] = true;
        activeMatrix[3][0] = false;
        activeMatrix[4][0] = true;
        // avatar-1 all active
        for (int r = 0; r < rowCount; r++) activeMatrix[r][1] = true;
        // avatar-2: false,true,true,true,false
        activeMatrix[0][2] = false;
        activeMatrix[1][2] = true;
        activeMatrix[2][2] = true;
        activeMatrix[3][2] = true;
        activeMatrix[4][2] = false;

        double result = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);

        // ratings = [1, 5, 3]; mean = 3.0
        // variance = ((1-3)^2 + (5-3)^2 + (3-3)^2) / 3 = (4+4+0)/3 = 8/3
        double expected = 8.0 / 3.0;
        assertThat(result)
                .as("Pure variance formula: ratings [1,5,3] → expected variance=8/3=%.6f", expected)
                .isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "AC-TEST-VARIANCE-FORMULATION-CHOSEN-RED: uniform ratings produce variance=0 (perfectly"
                    + " balanced)")
    void formulationPureVariance_uniformRatings_varianceZero() {
        // 3 avatars, all with same rating (single run of 3) → variance = 0
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

        assertThat(result)
                .as("Uniform ratings → variance = 0.0 (perfectly balanced)")
                .isEqualTo(0.0);
    }

    // =========================================================================
    // AC-TEST-SCORER-PREFERS-BALANCED-CONFIG-RED
    //
    // Config-A: 12 avatars, ratings approx {25,1,1,1,1,1,1,1,1,1,1,1}
    //   → unbalanced; high variance
    // Config-B: 12 avatars, ratings approx {4,4,4,4,4,4,4,4,4,4,4,4}
    //   → balanced; variance = 0
    // BalancedVarietyScorer MUST prefer B (lower score for B than A).
    //
    // Existing VarietyScorer: score(A)=3.0, score(B)=4.0 → prefers A (wrong for balance).
    //
    // We use scoreWithMatrix directly to control per-avatar ratings precisely.
    //
    // Config-A construction (rowCount=25, avatarCount=12):
    //   avatar-0: active all 25 rows → single run → rating=25
    //   avatars 1-11: alternating T/F each row → 25 runs of 1 → rating=1
    //   mean = (25 + 11*1)/12 = 36/12 = 3.0
    //   variance = ((25-3)^2 + 11*(1-3)^2)/12 = (484 + 11*4)/12 = (484+44)/12 = 528/12 = 44.0
    //
    // Config-B construction (rowCount=4, avatarCount=12):
    //   All avatars active all 4 rows → each rating = 4
    //   mean = 4.0; variance = 0.0; score = 0.0
    //
    // Assert: scorer.scoreWithMatrix(permB,...) = 0.0 < 44.0 = scorer.scoreWithMatrix(permA,...)
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

        // Config-B: mean=4, var=0 (perfectly balanced) → score = 0
        // Config-A: mean=3, var=44 (unbalanced) → score = 44
        assertThat(scoreB)
                .as(
                        "BalancedVarietyScorer: Config-B (balanced, var=0) score=%.2f must be"
                                + " <= Config-A (unbalanced, var=44) score=%.2f",
                        scoreB, scoreA)
                .isLessThanOrEqualTo(scoreA);

        // Also verify VarietyScorer (MEAN-of-products) prefers A over B (reference check)
        double meanScoreA = referenceScorer.scoreWithMatrix(permA, rowCountA, avatarCountA, matrixA);
        double meanScoreB = referenceScorer.scoreWithMatrix(permB, rowCountB, avatarCountA, matrixB);
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
    //
    // Parameterized over 3 phases:
    //   1. 2-avatar, 3-row phase
    //   2. 3-avatar, 5-row phase (from formulationPureVariance fixture)
    //   3. 1-avatar, 4-row alternating phase
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
        double[] ratings = AvatarRunRatings.computeRatings(perm, rowCount, avatarCount, activeMatrix);
        assertThat(ratings).hasSize(2);

        // avatar-0: single run of 3 active → product=3
        assertThat(ratings[0]).as("avatar-0 rating (active all 3 rows)").isEqualTo(3.0);
        // avatar-1: single run of 3 idle → product=3
        assertThat(ratings[1]).as("avatar-1 rating (idle all 3 rows)").isEqualTo(3.0);

        // VarietyScorer score = (3+3)/2 = 3.0
        double varietyScore = referenceScorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
        assertThat(varietyScore).as("VarietyScorer score = mean = 3.0").isEqualTo(3.0);

        // BalancedVarietyScorer score = variance = ((3-3)^2 + (3-3)^2)/2 = 0.0
        double balancedScore = scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
        assertThat(balancedScore).as("BalancedVarietyScorer score = variance = 0.0").isEqualTo(0.0);
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

        double[] ratings = AvatarRunRatings.computeRatings(perm, rowCount, avatarCount, activeMatrix);
        assertThat(ratings[0]).as("avatar-0 rating (alternating, 5 runs of 1)").isEqualTo(1.0);
        assertThat(ratings[1]).as("avatar-1 rating (always active, 1 run of 5)").isEqualTo(5.0);
        assertThat(ratings[2]).as("avatar-2 rating (idle-3active-idle → 1×3×1=3)").isEqualTo(3.0);
    }

    // =========================================================================
    // AC-TEST-DETERMINISM-RED
    // 10 fresh invocations return bit-identical results.
    // =========================================================================

    @Test
    @DisplayName(
            "AC-TEST-DETERMINISM-RED: 10 fresh invocations of scoreWithMatrix produce bit-identical"
                    + " results")
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
            double next = new BalancedVarietyScorer().scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
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
            "AC-TEST-CONFIG-PROPERTY-RESOLUTION-RED: ScorerFactory 'mean' resolves to VarietyScorer")
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
            "AC-TEST-CONFIG-INVALID-FALLS-BACK-RED: invalid scorer value falls back to VarietyScorer"
                    + " without exception")
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
    @DisplayName(
            "AC-ERROR-EMPTY-PHASE-NO-OP-PRESERVED: score with avatarCount=0 returns 0.0")
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
}
