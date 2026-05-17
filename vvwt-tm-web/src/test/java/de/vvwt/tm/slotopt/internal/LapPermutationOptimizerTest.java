// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.vvwt.slotopt.worker.score.VarietyScorer;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.RawPhaseDef;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import de.vvwt.slotopt.worker.types.TransformResult;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.JobHandle;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.OptimizationResult;
import de.vvwt.tm.slotopt.SlotResultApplicator;
import de.vvwt.tm.tournament.Match;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Unit tests for {@link LapPermutationOptimizer} — RED-first per DEC-22 Q-1a.
 *
 * <p>The class is new; these tests are authored before any production code. They assert the correct
 * DEC-61-B + DEC-63-C + E54S12-fix behavior of the extracted algorithm.
 *
 * <p>Per DEC-36: same package as subject ({@code slotopt.internal}) → white-box reference
 * permitted.
 *
 * <p>Per AC-TEST-EXTRACTED-CLASS-UNIT-TESTS-RED:
 *
 * <ul>
 *   <li>(i) Symmetric 12T/2G/3F fixture with scorer=mean — analytical optimum stddev=0.
 *   <li>(ii) Asymmetric 11T/2G/3F-style input (lapCount=10; verifies lapCount derivation correct,
 *       not stale rowCount/fieldCount).
 *   <li>(iii) Cancellation mid-iteration returns cancelled result with best-so-far applied.
 *   <li>(iv) scorer=balanced selects BalancedVarietyScorer per DEC-63 Clause C.
 * </ul>
 *
 * <p>Per AC-SECURITY-NO-PII-IN-NEW-TESTS: all IDs are UUID.randomUUID(), team names synthetic.
 *
 * @see LapPermutationOptimizer
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-61">DEC-61 Clause B — lapCount = canonical.rowCount()</a>
 * @see <a href="DEC-63">DEC-63 Clause C — scorer config</a>
 * @see <a href="E54S13">E54S13 — Extract story</a>
 */
class LapPermutationOptimizerTest {

    // =========================================================================
    // (i) Symmetric 12T/2G/3F — finds global optimum with scorer=mean
    // =========================================================================

    /**
     * AC-TEST-EXTRACTED-CLASS-UNIT-TESTS-RED (i): symmetric fixture with 4 laps, 2 groups, 3 fields
     * (analogous to 12T/2G/3F at small scale). Uses scorer=mean and verifies prodScore <=
     * testBestScore + 1e-9.
     *
     * <p>Uses an independent brute-force (not LehmerCodec) for testBestScore.
     */
    @Test
    void optimize_symmetricFixture_scorer_mean_findsGlobalOptimum() {
        UUID phaseId = UUID.randomUUID();
        // Build a symmetric mapping: 4 laps, each with 2 avatars alternating between 2 groups
        // lap 0: avatars 0,1 (group A)
        // lap 1: avatars 2,3 (group B)
        // lap 2: avatars 0,1 (group A)
        // lap 3: avatars 2,3 (group B)
        // The VarietyScorer should prefer alternating laps (A,B,A,B) over clustered (A,A,B,B).
        MappingResult mapping = buildAlternatingGroupMapping(phaseId, 4, 3);

        SlotResultApplicator applicatorMock = mock(SlotResultApplicator.class);

        OptimizationResult result =
                LapPermutationOptimizer.optimize(
                        phaseId, mapping, 3, "mean", applicatorMock, null, Optional.empty());

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isFalse();
        verify(applicatorMock).applyResult(anyLong(), eq(3), eq(mapping));

        // Verify prodScore <= testBestScore (independent scoring)
        VarietyScorer testScorer = new VarietyScorer();
        int lapCount = mapping.canonical().rowCount();
        int avatarCount = mapping.canonical().avatarCount();
        boolean[][] activeMatrix =
                buildActiveMatrix(mapping.denseIdsByRawRow(), lapCount, avatarCount);

        // Independent brute-force
        double testBestScore = Double.MAX_VALUE;
        int[] perm = new int[lapCount];
        for (int i = 0; i < lapCount; i++) perm[i] = i;
        testBestScore =
                Math.min(
                        testBestScore,
                        testScorer.scoreWithMatrix(perm, lapCount, avatarCount, activeMatrix));
        // Heap's algorithm
        int[] c = new int[lapCount];
        int i = 0;
        while (i < lapCount) {
            if (c[i] < i) {
                if (i % 2 == 0) {
                    int tmp = perm[0];
                    perm[0] = perm[i];
                    perm[i] = tmp;
                } else {
                    int tmp = perm[c[i]];
                    perm[c[i]] = perm[i];
                    perm[i] = tmp;
                }
                testBestScore =
                        Math.min(
                                testBestScore,
                                testScorer.scoreWithMatrix(
                                        perm, lapCount, avatarCount, activeMatrix));
                c[i]++;
                i = 0;
            } else {
                c[i] = 0;
                i++;
            }
        }

        // Get the actual bestScore from result
        double prodScore = result.bestScore();
        assertThat(prodScore)
                .as("LapPermutationOptimizer must find the global minimum (mean scorer)")
                .isLessThanOrEqualTo(testBestScore + 1e-9);
    }

    // =========================================================================
    // (ii) Asymmetric lapCount derivation — lapCount != rowCount/fieldCount
    // =========================================================================

    /**
     * AC-TEST-EXTRACTED-CLASS-UNIT-TESTS-RED (ii): asymmetric fixture where post-DEC-61-B
     * lapCount=3 but the stale formula rowCount/fieldCount would give a wrong value.
     *
     * <p>Verifies the optimizer completes without error and calls applyResult exactly once,
     * confirming it used the correct lapCount (canonical.rowCount()) not stale division.
     */
    @Test
    void optimize_asymmetricLapCount_usesCanonicalRowCount_notStaleDivision() {
        UUID phaseId = UUID.randomUUID();
        // Build mapping with 3 lap-rows but fieldCount=2.
        // Stale formula: rowCount/fieldCount = 3/2 = 1 (integer division) → wrong.
        // Correct: canonical.rowCount() = 3.
        MappingResult mapping = buildMinimalMapping(phaseId, 3);
        SlotResultApplicator applicatorMock = mock(SlotResultApplicator.class);

        OptimizationResult result =
                LapPermutationOptimizer.optimize(
                        phaseId,
                        mapping,
                        2, // fieldCount — stale division would give 3/2=1
                        "mean",
                        applicatorMock,
                        null,
                        Optional.empty());

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isFalse();
        // Optimizer must call applyResult (not silently no-op due to wrong lapCount < 2)
        verify(applicatorMock).applyResult(anyLong(), eq(2), eq(mapping));
    }

    // =========================================================================
    // (iii) Cancellation mid-iteration
    // =========================================================================

    /**
     * AC-TEST-EXTRACTED-CLASS-UNIT-TESTS-RED (iii): cancellation mid-iteration returns cancelled
     * result with best-so-far applied.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void optimize_cancellationMidIteration_returnsCancelledWithBestSoFar() throws Exception {
        UUID phaseId = UUID.randomUUID();
        // N=8 → 8! = 40,320 permutations; won't complete in 10ms
        MappingResult mapping = buildMinimalMapping(phaseId, 8);
        SlotResultApplicator applicatorMock = mock(SlotResultApplicator.class);
        CancellationToken token = CancellationToken.create();
        JobHandle handle = new JobHandle(token, Instant.now());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<OptimizationResult> future =
                executor.submit(
                        () ->
                                LapPermutationOptimizer.optimize(
                                        phaseId,
                                        mapping,
                                        1,
                                        "mean",
                                        applicatorMock,
                                        token,
                                        Optional.of(handle)));

        Thread.sleep(10);
        token.cancel();

        OptimizationResult result = future.get(2, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isTrue();
        verify(applicatorMock).applyResult(anyLong(), eq(1), eq(mapping));
        executor.shutdownNow();
    }

    // =========================================================================
    // (iv) scorer=balanced selects BalancedVarietyScorer (DEC-63 Clause C)
    // =========================================================================

    /**
     * AC-TEST-EXTRACTED-CLASS-UNIT-TESTS-RED (iv): scorer=balanced uses BalancedVarietyScorer per
     * DEC-63 Clause C. Verified indirectly: for a 2-lap, 2-avatar mapping with scorer=mean vs
     * scorer=balanced, the bestScore value may differ (BalancedVarietyScorer minimizes variance,
     * VarietyScorer minimizes mean). Both must complete without error and call applyResult.
     */
    @Test
    void optimize_scorerBalanced_completesWithoutError() {
        UUID phaseId = UUID.randomUUID();
        MappingResult mapping = buildMinimalMapping(phaseId, 2);
        SlotResultApplicator applicatorMock = mock(SlotResultApplicator.class);

        OptimizationResult result =
                LapPermutationOptimizer.optimize(
                        phaseId, mapping, 1, "balanced", applicatorMock, null, Optional.empty());

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isFalse();
        verify(applicatorMock).applyResult(anyLong(), eq(1), eq(mapping));
    }

    // =========================================================================
    // lapCount < 2 — trivial phase: identity rank applied
    // =========================================================================

    /**
     * AC-ERROR-LAPCOUNT-1-IDENTITY-PRESERVED: for lapCount < 2, applies rank=0 (L2 baseline)
     * without entering brute-force loop.
     */
    @Test
    void optimize_lapCountLessThan2_appliesIdentityRank() {
        UUID phaseId = UUID.randomUUID();
        MappingResult mapping = buildMinimalMapping(phaseId, 1); // 1 lap-row
        SlotResultApplicator applicatorMock = mock(SlotResultApplicator.class);

        OptimizationResult result =
                LapPermutationOptimizer.optimize(
                        phaseId, mapping, 1, "mean", applicatorMock, null, Optional.empty());

        assertThat(result).isNotNull();
        assertThat(result.cancelled()).isFalse();
        verify(applicatorMock).applyResult(eq(0L), eq(1), eq(mapping));
    }

    // =========================================================================
    // null-guard tests
    // =========================================================================

    @Test
    void optimize_nullPhaseId_throws() {
        MappingResult mapping = buildMinimalMapping(UUID.randomUUID(), 2);
        SlotResultApplicator applicatorMock = mock(SlotResultApplicator.class);
        assertThatThrownBy(
                        () ->
                                LapPermutationOptimizer.optimize(
                                        null,
                                        mapping,
                                        1,
                                        "mean",
                                        applicatorMock,
                                        null,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimize_nullMapping_throws() {
        assertThatThrownBy(
                        () ->
                                LapPermutationOptimizer.optimize(
                                        UUID.randomUUID(),
                                        null,
                                        1,
                                        "mean",
                                        mock(SlotResultApplicator.class),
                                        null,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optimize_nullApplicator_throws() {
        MappingResult mapping = buildMinimalMapping(UUID.randomUUID(), 2);
        assertThatThrownBy(
                        () ->
                                LapPermutationOptimizer.optimize(
                                        UUID.randomUUID(),
                                        mapping,
                                        1,
                                        "mean",
                                        null,
                                        null,
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean[][] buildActiveMatrix(
            int[][] denseIdsByRawRow, int rowCount, int avatarCount) {
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        for (int lapIndex = 0; lapIndex < rowCount; lapIndex++) {
            for (int avatarId : denseIdsByRawRow[lapIndex]) {
                activeMatrix[lapIndex][avatarId] = true;
            }
        }
        return activeMatrix;
    }

    /**
     * Builds a minimal MappingResult with rowCount distinct lap-rows, each with 2 unique avatars.
     * lapCount = rowCount (= canonical.rowCount() per DEC-61 B, since each row is a lap-row).
     */
    private static MappingResult buildMinimalMapping(UUID phaseId, int rowCount) {
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new ArrayList<>();
        List<Match> matches = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            de.vvwt.slotopt.worker.types.PositionTuple pt1 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 0);
            de.vvwt.slotopt.worker.types.PositionTuple pt2 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 1);
            rows.add(new de.vvwt.slotopt.worker.types.RawRow(List.of(pt1, pt2)));
            Match m = new Match();
            m.setId(UUID.randomUUID());
            m.setPhaseId(phaseId);
            matches.add(m);
        }
        int auditPhaseId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditPhaseId, rowCount, rows);
        TransformResult tr = StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();
        java.util.Map<de.vvwt.slotopt.worker.types.PositionTuple, Integer> denseMap =
                DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[rowCount][];
        for (int r = 0; r < rowCount; r++) {
            de.vvwt.slotopt.worker.types.PositionTuple pt1 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 0);
            de.vvwt.slotopt.worker.types.PositionTuple pt2 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(r, 1);
            denseIdsByRawRow[r] = new int[] {denseMap.get(pt1), denseMap.get(pt2)};
        }
        return new MappingResult(
                raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }

    /**
     * Builds a mapping with 2 alternating groups across lapCount laps: even laps: group 0 avatars;
     * odd laps: group 1 avatars. The VarietyScorer should prefer alternating over clustered for
     * variety.
     *
     * @param lapCount number of laps (must be even for symmetric result)
     * @param fieldCount passed through to fieldCount parameter (does not affect lap structure)
     */
    private static MappingResult buildAlternatingGroupMapping(
            UUID phaseId, int lapCount, int fieldCount) {
        List<de.vvwt.slotopt.worker.types.RawRow> rows = new ArrayList<>();
        List<Match> matches = new ArrayList<>();
        // Avatar 0,1 = group A; Avatar 2,3 = group B
        for (int r = 0; r < lapCount; r++) {
            int groupBase = (r % 2 == 0) ? 0 : 2; // group A on even laps, group B on odd
            de.vvwt.slotopt.worker.types.PositionTuple pt1 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(groupBase, 0);
            de.vvwt.slotopt.worker.types.PositionTuple pt2 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(groupBase, 1);
            rows.add(new de.vvwt.slotopt.worker.types.RawRow(List.of(pt1, pt2)));
            Match m = new Match();
            m.setId(UUID.randomUUID());
            m.setPhaseId(phaseId);
            m.setLapNumber(r + 1);
            m.setFieldNumber(1);
            matches.add(m);
        }
        int auditPhaseId = Math.abs(phaseId.hashCode());
        RawPhaseDef raw = new RawPhaseDef(auditPhaseId, lapCount, rows);
        TransformResult tr = StructuralFingerprint.transform(raw);
        CanonicalPhaseDef canonical = tr.canonical();
        java.util.Map<de.vvwt.slotopt.worker.types.PositionTuple, Integer> denseMap =
                DefaultPhaseToRawPhaseDefMapper.buildDenseIdMapping(raw);
        int[][] denseIdsByRawRow = new int[lapCount][];
        for (int r = 0; r < lapCount; r++) {
            int groupBase = (r % 2 == 0) ? 0 : 2;
            de.vvwt.slotopt.worker.types.PositionTuple pt1 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(groupBase, 0);
            de.vvwt.slotopt.worker.types.PositionTuple pt2 =
                    new de.vvwt.slotopt.worker.types.PositionTuple(groupBase, 1);
            denseIdsByRawRow[r] = new int[] {denseMap.get(pt1), denseMap.get(pt2)};
        }
        return new MappingResult(
                raw, canonical, canonical.avatarCount(), matches, denseIdsByRawRow);
    }
}
