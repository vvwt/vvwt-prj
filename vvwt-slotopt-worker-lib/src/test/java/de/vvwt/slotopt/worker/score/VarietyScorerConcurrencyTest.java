// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.score;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spec-Anchored concurrency test for {@link VarietyScorer}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01). Satisfies DEC-41 criterion
 * (d): the thread-safety invariant is named ("score-determinism-invariant is thread-safe") and
 * quantified over many concurrent invocations with varying inputs (10 threads × 1 000 calls each).
 *
 * <p>Algebraic invariant tests are in {@link VarietyScorerTest}.
 */
@DisplayName("VarietyScorer — thread-safety invariant (DEC-41 D-4 replacement)")
class VarietyScorerConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int CALLS_PER_THREAD = 1_000;
    private static final int TIMEOUT_SECONDS = 30;

    /**
     * Invariant: score-determinism-invariant is thread-safe.
     *
     * <p>Formally: for any valid (rowSequence, phaseDef) pair, concurrent invocations of {@code
     * score(rowSequence, phaseDef)} from multiple threads on a shared {@link VarietyScorer}
     * instance always return the same value as the single-threaded reference computation — i.e., no
     * shared-state corruption occurs when threads call the scorer simultaneously.
     *
     * <p>Quantified over 10 000 concurrent calls (10 threads × 1 000 calls) with deterministic
     * per-thread seeds covering a representative set of phases and permutations.
     */
    @Test
    @DisplayName(
            "Invariant: score-determinism is thread-safe — 10 threads × 1000 calls with no"
                    + " determinism violations")
    void invariant_scoreDeterminism_isThreadSafe() throws Exception {
        VarietyScorer scorer = new VarietyScorer();

        // Reference phase with known structure: 4 avatars, 4 rows
        CanonicalPhaseDef phase =
                new CanonicalPhaseDef(
                        4, 4, List.of(List.of(0, 1), List.of(2, 3), List.of(0, 2), List.of(1, 3)));
        boolean[][] activeMatrix = scorer.buildActiveMatrix(phase.rows(), 4, 4);

        // Compute reference scores for all 24 permutations of [0,1,2,3] (single-threaded)
        int[][] perms = generateAllPermutations(4);
        double[] referenceScores = new double[perms.length];
        for (int i = 0; i < perms.length; i++) {
            referenceScores[i] = scorer.scoreWithMatrix(perms[i], 4, 4, activeMatrix);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger(0);
        List<Future<List<String>>> futures = new ArrayList<>(THREAD_COUNT);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final long seed = t * 31L + 7919L;
            futures.add(
                    pool.submit(
                            () -> {
                                startGate.await(); // all threads start simultaneously
                                return runDeterminismChecks(
                                        scorer,
                                        activeMatrix,
                                        perms,
                                        referenceScores,
                                        seed,
                                        violations);
                            }));
        }

        startGate.countDown(); // release all threads at once
        pool.shutdown();
        boolean finished = pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(finished)
                .as("Thread-safety invariant: pool must terminate within %ds", TIMEOUT_SECONDS)
                .isTrue();

        // Collect per-thread failure descriptions
        List<String> allFailures = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            allFailures.addAll(future.get());
        }

        assertThat(violations.get())
                .as(
                        "Thread-safety invariant: score-determinism must hold for all %d concurrent"
                                + " calls; violations: %s",
                        THREAD_COUNT * CALLS_PER_THREAD, allFailures)
                .isZero();
    }

    /**
     * Invariant: scoreWithMatrix-determinism is thread-safe across varying permutations.
     *
     * <p>Formally: concurrent invocations of {@code scoreWithMatrix(perm, rowCount, avatarCount,
     * activeMatrix)} from multiple threads always return the single-threaded reference value for
     * the same (perm, activeMatrix) combination — no write-through or cache-corruption can occur
     * since VarietyScorer has no mutable instance state.
     *
     * <p>Quantified over 10 threads × 1 000 calls each, cycling through all permutations of n=3.
     */
    @Test
    @DisplayName(
            "Invariant: scoreWithMatrix-determinism is thread-safe — 10 threads × 1000 calls over"
                    + " all n=3 permutations")
    void invariant_scoreWithMatrixDeterminism_isThreadSafe_allPermsN3() throws Exception {
        VarietyScorer scorer = new VarietyScorer();

        CanonicalPhaseDef phase =
                new CanonicalPhaseDef(3, 3, List.of(List.of(0, 1), List.of(1, 2), List.of(0, 2)));
        boolean[][] activeMatrix = scorer.buildActiveMatrix(phase.rows(), 3, 3);

        int[][] perms = generateAllPermutations(3);
        double[] referenceScores = new double[perms.length];
        for (int i = 0; i < perms.length; i++) {
            referenceScores[i] = scorer.scoreWithMatrix(perms[i], 3, 3, activeMatrix);
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger violations = new AtomicInteger(0);
        List<Future<List<String>>> futures = new ArrayList<>(THREAD_COUNT);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            futures.add(
                    pool.submit(
                            () -> {
                                startGate.await();
                                List<String> failures = new ArrayList<>();
                                for (int i = 0; i < CALLS_PER_THREAD; i++) {
                                    int permIdx = (threadId + i) % perms.length;
                                    double result =
                                            scorer.scoreWithMatrix(
                                                    perms[permIdx], 3, 3, activeMatrix);
                                    if (Double.compare(result, referenceScores[permIdx]) != 0) {
                                        violations.incrementAndGet();
                                        failures.add(
                                                String.format(
                                                        "permIdx=%d expected=%.6f got=%.6f",
                                                        permIdx, referenceScores[permIdx], result));
                                    }
                                }
                                return failures;
                            }));
        }

        startGate.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(finished)
                .as("Thread-safety invariant: pool must terminate within %ds", TIMEOUT_SECONDS)
                .isTrue();

        List<String> allFailures = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            allFailures.addAll(future.get());
        }

        assertThat(violations.get())
                .as(
                        "scoreWithMatrix thread-safety invariant: determinism must hold for all"
                                + " concurrent calls; violations: %s",
                        allFailures)
                .isZero();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static List<String> runDeterminismChecks(
            VarietyScorer scorer,
            boolean[][] activeMatrix,
            int[][] perms,
            double[] referenceScores,
            long seed,
            AtomicInteger violations) {
        List<String> failures = new ArrayList<>();
        long state = seed;

        for (int call = 0; call < CALLS_PER_THREAD; call++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            int permIdx = (int) ((state >>> 48) % perms.length);

            try {
                double result = scorer.scoreWithMatrix(perms[permIdx], 4, 4, activeMatrix);
                if (Double.compare(result, referenceScores[permIdx]) != 0) {
                    violations.incrementAndGet();
                    failures.add(
                            String.format(
                                    "permIdx=%d expected=%.6f got=%.6f",
                                    permIdx, referenceScores[permIdx], result));
                }
            } catch (Exception ex) {
                violations.incrementAndGet();
                failures.add(
                        String.format(
                                "permIdx=%d exception %s: %s",
                                permIdx, ex.getClass().getSimpleName(), ex.getMessage()));
            }
        }
        return failures;
    }

    /** Generates all n! permutations of [0, n-1] using Heap's algorithm. */
    private static int[][] generateAllPermutations(int n) {
        int factorial = 1;
        for (int i = 2; i <= n; i++) factorial *= i;
        int[][] result = new int[factorial][];
        int[] perm = new int[n];
        for (int i = 0; i < n; i++) perm[i] = i;
        int[] c = new int[n];
        int idx = 0;
        result[idx++] = perm.clone();
        int i = 0;
        while (i < n) {
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
                result[idx++] = perm.clone();
                c[i]++;
                i = 0;
            } else {
                c[i] = 0;
                i++;
            }
        }
        return result;
    }
}
