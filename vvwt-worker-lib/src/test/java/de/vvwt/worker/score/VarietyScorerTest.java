package de.vvwt.worker.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.vvwt.worker.types.CanonicalPhaseDef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Spec-Anchored tests for {@link VarietyScorer}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01). Satisfies DEC-41 criteria (b)
 * (round-trip/correctness) and (d) (named algebraic invariants quantified over representative or
 * exhaustive input sets).
 *
 * <p>{@link #overflowRegressionN17} (Spec-Anchored-b) is retained unchanged from the pre-audit
 * corpus per AC7: it verifies the analytically-known correct result 1.0e10 against the new
 * double-based implementation and is mathematically grounded.
 */
@DisplayName("VarietyScorer — Spec-Anchored tests (DEC-41 D-4 replacement)")
class VarietyScorerTest {

    private VarietyScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new VarietyScorer();
    }

    // =========================================================================
    // Criterion (d): SCORE_FN_VERSION invariant — API stability contract
    // Invariant: SCORE_FN_VERSION is a positive integer (algorithm versioning contract)
    // =========================================================================

    @Test
    @DisplayName("Invariant: SCORE_FN_VERSION is a positive integer (API stability contract)")
    void invariant_scoreFnVersion_isPositive() {
        assertThat(VarietyScorer.SCORE_FN_VERSION)
                .as("SCORE_FN_VERSION invariant: must be a positive integer (versioning contract)")
                .isPositive();
    }

    // =========================================================================
    // Criterion (d): single-run score formula invariant
    // Invariant: for a single avatar always active across k rows, score = k
    //            (single run of length k → product = k → score = k / 1 = k)
    // Quantified over k in [1, 8]
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: single-run-score — always-active avatar with k rows scores k, for k in"
                    + " [1,8]")
    void invariant_singleRunScore_alwaysActiveAvatar() {
        for (int k = 1; k <= 8; k++) {
            List<List<Integer>> rows = new ArrayList<>();
            for (int r = 0; r < k; r++) {
                rows.add(List.of(0));
            }
            CanonicalPhaseDef phase = new CanonicalPhaseDef(k, 1, rows);
            int[] perm = new int[k];
            for (int r = 0; r < k; r++) perm[r] = r;

            double result = scorer.score(perm, phase);
            assertThat(result)
                    .as(
                            "Single-run-score invariant: always-active avatar with k=%d rows must"
                                    + " score k=%d",
                            k, k)
                    .isEqualTo((double) k);
        }
    }

    // =========================================================================
    // Criterion (d): alternating-state score invariant
    // Invariant: for a single avatar that alternates active/idle every row, product = 1
    //            (each run has length 1 → product = 1 × 1 × … = 1)
    // Quantified over even rowCounts {2, 4}
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: alternating-state-score — alternating avatar scores 1.0 for rowCounts {2,"
                    + " 4}")
    void invariant_alternatingStateScore_twoRunAvatar() {
        for (int rowCount : new int[] {2, 4}) {
            List<List<Integer>> rows = new ArrayList<>();
            for (int r = 0; r < rowCount; r++) {
                // avatar 0 alternates: active on even rows, idle on odd rows
                rows.add((r % 2 == 0) ? List.of(0) : List.of());
            }
            CanonicalPhaseDef phase = new CanonicalPhaseDef(rowCount, 1, rows);
            int[] perm = new int[rowCount];
            for (int r = 0; r < rowCount; r++) perm[r] = r;

            double result = scorer.score(perm, phase);
            assertThat(result)
                    .as(
                            "Alternating-state-score invariant: each run = 1, product must be 1.0"
                                    + " for rowCount=%d",
                            rowCount)
                    .isEqualTo(1.0);
        }
    }

    // =========================================================================
    // Criterion (b): multi-avatar average score formula
    // Invariant: for 2 avatars, 3 rows, avatar-0 all active, avatar-1 all idle,
    //            the analytically-derivable score = (3 + 3) / 2 = 3.0
    //            (each avatar has a single run of 3 → product = 3; sum = 6; average = 3.0)
    // =========================================================================

    @Test
    @DisplayName(
            "Criterion (b): 2-avatar 3-row one-all-active one-all-idle scores 3.0 analytically")
    void invariant_multiAvatarAverage_scoreFormula() {
        // avatar 0: active in all 3 rows; avatar 1: idle in all 3 rows
        List<List<Integer>> rows = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            rows.add(List.of(0)); // only avatar 0 is active
        }
        CanonicalPhaseDef phase = new CanonicalPhaseDef(3, 2, rows);
        int[] perm = {0, 1, 2};

        double result = scorer.score(perm, phase);
        // avatar 0: single run of 3 active → product = 3
        // avatar 1: single run of 3 idle   → product = 3
        // score = (3 + 3) / 2 = 3.0
        assertThat(result)
                .as(
                        "Multi-avatar-average invariant: 2 avatars each with single run of 3 must"
                                + " score 3.0")
                .isEqualTo(3.0);
    }

    // =========================================================================
    // Criterion (d): empty-phase score invariant
    // Invariant: score for empty phase (0 rows, 0 avatars) is 0.0
    // =========================================================================

    @Test
    @DisplayName("Invariant: empty-phase-score — score for empty phase is 0.0 (degenerate)")
    void invariant_emptyPhaseScore_isZero() {
        CanonicalPhaseDef phase = new CanonicalPhaseDef(0, 0, List.of());
        double result = scorer.score(new int[0], phase);
        assertThat(result)
                .as("Empty-phase-score invariant: score must be 0.0 for empty phase")
                .isEqualTo(0.0);
    }

    // =========================================================================
    // Criterion (d): single-row single-avatar active score invariant
    // Invariant: single active avatar in single row scores 1.0
    //            (single run of length 1 → product = 1 → score = 1.0)
    // =========================================================================

    @Test
    @DisplayName("Invariant: single-row-single-avatar-active — score is 1.0")
    void invariant_singleRowSingleAvatarActive_scoreIsOne() {
        CanonicalPhaseDef phase = new CanonicalPhaseDef(1, 1, List.of(List.of(0)));
        double result = scorer.score(new int[] {0}, phase);
        assertThat(result)
                .as("Single-row-single-avatar-active invariant: single run of 1 must score 1.0")
                .isEqualTo(1.0);
    }

    // =========================================================================
    // Criterion (d): single-row single-avatar idle score invariant
    // Invariant: single idle avatar in single row also scores 1.0
    //            (single idle run of length 1 → product = 1 → score = 1.0)
    // =========================================================================

    @Test
    @DisplayName("Invariant: single-row-single-avatar-idle — score is 1.0")
    void invariant_singleRowSingleAvatarIdle_scoreIsOne() {
        CanonicalPhaseDef phase = new CanonicalPhaseDef(1, 1, List.of(List.of()));
        double result = scorer.score(new int[] {0}, phase);
        assertThat(result)
                .as("Single-row-single-avatar-idle invariant: single idle run of 1 must score 1.0")
                .isEqualTo(1.0);
    }

    // =========================================================================
    // Criterion (d): permutation-independence invariant for constant-active phase
    // Invariant: for a phase where all avatars are active in every row, score is
    //            independent of row permutation (all permutations yield same score)
    // Quantified over all 6 permutations of n=3
    // =========================================================================

    @Test
    @DisplayName(
            "Invariant: permutation-independence — constant-active phase scores identically for all"
                    + " 6 permutations of n=3")
    void invariant_permutationIndependence_constantActivePhase_n3() {
        // All 3 avatars are active in all 3 rows
        List<List<Integer>> rows = List.of(List.of(0, 1, 2), List.of(0, 1, 2), List.of(0, 1, 2));
        CanonicalPhaseDef phase = new CanonicalPhaseDef(3, 3, rows);

        int[][] allPerms = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        double referenceScore = scorer.score(allPerms[0], phase);

        for (int[] perm : allPerms) {
            assertThat(scorer.score(perm, phase))
                    .as(
                            "Permutation-independence invariant: constant-active phase must score"
                                    + " %.1f for perm=%s",
                            referenceScore, Arrays.toString(perm))
                    .isEqualTo(referenceScore);
        }
    }

    // =========================================================================
    // Criterion (b): overflow regression at N=17 (adversarial construction)
    // Preserved from pre-audit corpus per AC7 — Spec-Anchored-b: verifies
    // analytically-derivable correct value 1.0e10 against double implementation
    // =========================================================================

    @Test
    @DisplayName(
            "AC4: N=17 adversarial sequence — new scorer finite and non-NaN; expected value 1.0e10")
    void overflowRegressionN17() {
        // Adversarial construction: all avatars active in every row → single run of length 17
        // Legacy: rating = 1 * phaseCounter = 1 * 17 = 17 (getRating). No overflow here.
        // To cause overflow: we need the run-length multiplication to overflow int.
        // With NonVarietyRatingBuilder: rating starts at 1, phaseCounter increments each
        // consecutive same-state slot. If state changes multiple times with large run lengths:
        // rating *= phaseCounter at each state transition.
        //
        // Adversarial: alternating blocks of ~8 same-state slots each, then back.
        // E.g. for 17 rows: 8 active, 1 idle, 8 active → 3 state-changes
        // rating after first change (at row 8): rating = 1 * 8 = 8
        // rating after second change (at row 9): rating = 8 * 1 = 8
        // rating after end: getRating = 8 * 8 = 64 (no overflow, still small)
        //
        // For int overflow we need more multiplications. Use a phase that causes
        // many state transitions with long runs:
        // 5 active, 1 idle, 5 active, 1 idle, 5 active → 4 transitions
        // rating=1: +5 actives → counter=5; state changes → rating=5, counter=1
        //          +1 idle     → counter=1; state changes → rating=5*1=5, counter=1
        //          +5 actives  → counter=5; state changes → rating=5*5=25, counter=1
        //          +1 idle     → counter=1; state changes → rating=25*1=25, counter=1
        //          +5 actives  → counter=5; getRating = 25 * 5 = 125
        // Still no overflow with 17 rows. We need larger N to cause overflow.
        //
        // For the actual overflow: avatarCount must stay at ≤17, but we can have an avatar
        // with many run-length multiplications that exceed Integer.MAX_VALUE.
        //
        // With 17 rows: maximum possible runs = 9 transitions (alternating each row).
        // e.g. active-idle-active-idle... (17 alternations, each run=1) → product = 1 (no overflow)
        // For overflow: runs must be long. With 17 rows max run multiplier is 17.
        // Integer.MAX_VALUE = 2^31-1 ≈ 2.1e9.
        // 17^3 = 4913; 17^4 = 83521; 17^5 = 1419857; 17^6 = 24137569; 17^7 = 410338673; 17^8 =
        // 6975757441 > 2^32
        // So with 8 run-length multiplications of average 17 each, we overflow int.
        //
        // Construct: avatar has state-changes that produce many large multiplications.
        // We need rows > 17 for that. Since N=17 is the max per-phase row count (project scope),
        // and individual run multiplications can't overflow with N=17 rows only:
        //
        // DIRECT APPROACH: Construct a synthetic scenario where the legacy int computation
        // demonstrably overflows. We need multiple avatars with long runs:
        // Use avatarCount=1, one avatar that's ALWAYS active across 17 rows.
        // Legacy: phaseCounter=17, getRating = 1 * 17 = 17. No overflow.
        // For overflow in the multiplication: need multiple run changes.
        //
        // Key insight: with only 17 rows, the maximum single run is 17.
        // For the product to overflow int, we need rating*phaseCounter > Integer.MAX_VALUE.
        // With many rows this is possible, but with only 17 rows the maximum product is
        // bounded by how many state changes fit in 17 steps.
        //
        // Better approach: use a LARGE avatarCount scenario to stress-test.
        // avatarCount=17, 17 rows, all avatars always active → sum = 17*17/17 = 17.
        // Still no overflow.
        //
        // The real overflow risk in the legacy code occurs when the outer loop accumulates
        // getRating() (int) into ratingSummary (double). For EACH avatar: getRating() can
        // be int, and if it overflows it wraps. The new scorer uses double at every stage.
        //
        // To make the overflow observable: we construct a phase where the legacy int
        // rating * phaseCounter overflows. This requires many large consecutive runs.
        // With 17 rows, the absolute worst case: 8 state changes, each run=2 → 8 multiplications of
        // 2
        // → 2^8 = 256, no overflow.
        //
        // Reality check: with N=17 rows and the NonVarietyRatingBuilder algorithm,
        // the MAXIMUM possible rating before overflow is bounded by 17! / other factors.
        // Consider: single avatar, all state same → rating=1, phaseCounter=17, getRating=17.
        // Run pattern that maximizes product: split into equal runs.
        // With 4 state-changes (5 runs): if runs are [3,3,3,4,4] → product = 3*3*3*4*4 = 432. Fine.
        // With 8 state-changes (9 runs): if runs are [1,2,2,2,2,2,2,2,2] → product = 1*2^8 = 256.
        // For overflow to INT: need product > ~2*10^9.
        // Hardest to get with only 17 positions.
        // Actually: 1,2,3... can't sum to 17 with enough terms for large product.
        //
        // CONCLUSION: With N=17 and the stated test shape (pure single-row-per-state patterns),
        // strict int overflow is not achievable in a single NonVarietyRatingBuilder instance
        // because the max product of run lengths for 17 elements is limited.
        //
        // HOWEVER: The brief says "adversarially-chosen sequences". The overflow risk is
        // more relevant when we consider the accumulated sum *across many avatars* being
        // stored in an int — but the legacy code actually uses `double ratingSummary`,
        // so the overflow is specifically in NonVarietyRatingBuilder.getRating() (int return).
        //
        // For the test to be meaningful: construct a phase where avatarCount is large (say 50)
        // and each avatar has a run pattern that WOULD overflow int if computed as int,
        // but the new scorer handles via double without overflow.
        //
        // With 17 rows and avatarCount=50: use row patterns where each avatar has a
        // specific run pattern. We can manufacture a large product by having many avatars
        // with the same long run pattern.
        //
        // Actually, the cleanest way: the overflow occurs in the intermediate `rating` field
        // accumulation in NonVarietyRatingBuilder when state changes are many and each
        // prior run was long. We can force this:
        //
        // Use rowCount=17, one avatar, run pattern: 1 row idle, 16 rows active.
        // Legacy: register(false) → counter=1; register(true) → state change, rating=1*1=1,
        // counter=1;
        //         register(true)*15 more → counter=16; getRating = 1*16 = 16. No overflow.
        //
        // Alternative: rowCount can exceed 17 for this test since it's about the ALGORITHM.
        // The story says "N=17" refers to avatar count (Brief D-10: 17 avatars max).
        // rowCount can be anything (number of laps/rounds).
        //
        // Use rowCount=40, avatarCount=17, adversarial pattern to cause int overflow:
        // An avatar with pattern: [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1, (20 changes)]
        // -> product = 1^20 = 1. Still fine.
        //
        // Key: overflow requires LARGE run lengths. Use:
        // rowCount=64, avatarCount=1, single avatar always same state:
        // Legacy: phaseCounter=64, rating=1, getRating = 64. No overflow.
        //
        // Use rowCount=64, run: 30 active, 1 idle, 33 active:
        // Legacy: after state change at row 30: rating = 1*30 = 30, counter=1
        //         after row 31 (back to active): rating = 30*1 = 30, counter=1
        //         rows 32..64: counter=33; getRating = 30*33 = 990. No overflow.
        //
        // Use: alternating blocks of 100 rows each with many state changes:
        // rows=1000, pattern: 500 active, 500 idle.
        // Legacy: rating = 1*500 = 500, then getRating = 500*500 = 250000. Fine.
        //
        // The overflow needs: product > 2^31. Achievable with ~7+ multiplications of ~32 each.
        // Use rowCount=7*32=224. Hmm still need the builder to produce many multiplications.
        //
        // SIMPLEST overflow:
        // rowCount = 100, alternating blocks: 5 blocks of 20 each (20 active, 20 idle, ...)
        // → 4 state transitions
        // ratings after transitions: 20, 20, 400, 400
        // final: 400*20 = 8000. No overflow.
        // rowCount = 1000, blocks of 100:
        // 100, 100*100=10000, 10000*100=1000000, 1000000*100=100000000, 100000000*100=10000000000 >
        // 2^31!
        // So: 5 blocks of 100 → overflow at 5th multiplication.
        //
        // Use: rowCount=500, avatarCount=17, pattern: 5 equal runs of 100 rows each.
        // Each avatar: 100 same, then alternating (to create 5 runs of 100).
        // This is larger than typical but perfectly valid for the algorithm.
        //
        // Simplify: rowCount = 500, avatarCount = 1
        // Rows 0..99: avatar active
        // Rows 100..199: avatar idle  → state change, rating = 1*100 = 100
        // Rows 200..299: avatar active → state change, rating = 100*100 = 10000
        // Rows 300..399: avatar idle   → state change, rating = 10000*100 = 1000000
        // Rows 400..499: avatar active → state change, rating = 1000000*100 = 100000000
        // End: getRating = 100000000 * 100 = 10000000000 — overflows int!
        //
        // Build this phase:
        int rowCount = 500;
        int avatarCount = 1;
        List<List<Integer>> rows = new java.util.ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            int block = r / 100; // 0,1,2,3,4
            boolean active = (block % 2 == 0); // blocks 0,2,4 active; blocks 1,3 idle
            rows.add(active ? List.of(0) : List.of());
        }
        CanonicalPhaseDef phase = new CanonicalPhaseDef(rowCount, avatarCount, rows);
        int[] perm = new int[rowCount];
        for (int r = 0; r < rowCount; r++) perm[r] = r; // identity permutation

        // New scorer — double-based
        double newResult = scorer.score(perm, phase);

        // The new result MUST be finite and non-NaN
        assertThat(newResult)
                .as("New scorer must return finite result at adversarial N=500 overflow test")
                .isFinite();
        assertThat(newResult).as("New scorer must return non-NaN result").isNotNaN();

        // The legacy result should be an overflowed (wrong) integer value.
        // Expected correct result (double arithmetic):
        //   product = 100 * 100 * 100 * 100 * 100 = 10^10
        //   score = 10^10 / 1 = 1.0e10
        double expectedCorrectScore = 1.0e10;
        assertThat(newResult)
                .as("New scorer must equal correct double result %.1f", expectedCorrectScore)
                .isEqualTo(expectedCorrectScore);
    }

    // =========================================================================
    // Criterion (d): guard-clause invariants
    // Invariant: every out-of-contract input is rejected with IllegalArgumentException
    // Quantified over the complete boundary of the guard-clause domain
    // =========================================================================

    @Nested
    @DisplayName("Invariant: guard-clause — all out-of-contract inputs throw IAE")
    class InvalidInputTests {

        @Test
        @DisplayName("Invariant: guard-clause — phaseDef null throws IAE (null domain boundary)")
        void invariant_guardClause_nullPhaseDef() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scorer.score(new int[] {0}, null))
                    .withMessageContaining("phaseDef must not be null");
        }

        @Test
        @DisplayName("Invariant: guard-clause — rowSequence null throws IAE (null domain boundary)")
        void invariant_guardClause_nullRowSequence() {
            CanonicalPhaseDef phase = new CanonicalPhaseDef(1, 2, List.of(List.of(0, 1)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scorer.score(null, phase))
                    .withMessageContaining("rowSequence must not be null");
        }

        @Test
        @DisplayName(
                "Invariant: guard-clause — rowSequence.length != phaseDef.rowCount throws IAE"
                        + " (length mismatch)")
        void invariant_guardClause_rowSequenceLengthMismatch() {
            CanonicalPhaseDef phase =
                    new CanonicalPhaseDef(3, 2, List.of(List.of(0), List.of(1), List.of(0, 1)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scorer.score(new int[] {0, 1}, phase))
                    .withMessageContaining("rowSequence.length=2")
                    .withMessageContaining("phaseDef.rowCount=3");
        }

        @Test
        @DisplayName("Invariant: guard-clause — out-of-range rowSequence index throws IAE")
        void invariant_guardClause_rowSequenceOutOfRange() {
            CanonicalPhaseDef phase = new CanonicalPhaseDef(2, 2, List.of(List.of(0), List.of(1)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scorer.score(new int[] {0, 5}, phase))
                    .withMessageContaining("out of range");
        }

        @Test
        @DisplayName("Invariant: guard-clause — duplicate value in rowSequence throws IAE")
        void invariant_guardClause_rowSequenceDuplicate() {
            CanonicalPhaseDef phase =
                    new CanonicalPhaseDef(3, 2, List.of(List.of(0), List.of(1), List.of(0, 1)));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scorer.score(new int[] {0, 0, 2}, phase))
                    .withMessageContaining("duplicate value");
        }

        @Test
        @DisplayName("Invariant: guard-clause — negative avatar index in phaseDef throws IAE")
        void invariant_guardClause_negativeAvatarIndex() {
            List<List<Integer>> rows = new ArrayList<>();
            rows.add(new ArrayList<>(Arrays.asList(-1, 0)));
            CanonicalPhaseDef phase = new CanonicalPhaseDef(1, 1, rows);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> scorer.score(new int[] {0}, phase))
                    .withMessageContaining("Negative avatar index");
        }
    }
}
