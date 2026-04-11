package de.vvwt.worker.score;

import de.vvwt.worker.types.CanonicalPhaseDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for {@link VarietyScorer}.
 *
 * <p>AC9: {@code VarietyScorer} is thread-safe by virtue of statelessness.
 * 10 threads × 10000 calls each must produce consistent, race-free results.
 */
class VarietyScorerConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int CALLS_PER_THREAD = 10_000;

    @Test
    @DisplayName("AC9: 10 threads × 10000 calls — no race conditions, consistent results")
    void concurrentScoringProducesConsistentResults() throws Exception {
        // Shared scorer instance — must be safe to call from multiple threads simultaneously
        VarietyScorer scorer = new VarietyScorer();

        // Use a deterministic phase with known score
        CanonicalPhaseDef phase = new CanonicalPhaseDef(4, 4, List.of(
            List.of(0, 1),
            List.of(2, 3),
            List.of(0, 2),
            List.of(1, 3)
        ));
        int[] perm = {0, 1, 2, 3};

        // Pre-build active matrix once — buildActiveMatrix is allocation-only, deterministic
        boolean[][] activeMatrix = scorer.buildActiveMatrix(phase.rows(), 4, 4);

        // Compute expected score deterministically (single-threaded)
        double expectedScore = scorer.scoreWithMatrix(perm, 4, 4, activeMatrix);

        // Spin up threads
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger inconsistencyCount = new AtomicInteger(0);

        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < THREAD_COUNT; t++) {
            futures.add(executor.submit(() -> {
                startGate.await(); // all threads start simultaneously
                for (int i = 0; i < CALLS_PER_THREAD; i++) {
                    double result = scorer.scoreWithMatrix(perm, 4, 4, activeMatrix);
                    if (Double.compare(result, expectedScore) != 0) {
                        inconsistencyCount.incrementAndGet();
                    }
                }
                return null;
            }));
        }

        // Release all threads at once
        startGate.countDown();

        // Wait for all threads to complete (generous timeout: 30 seconds)
        executor.shutdown();
        boolean terminated = executor.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(terminated)
            .as("Executor must terminate within 30 seconds")
            .isTrue();

        // Check for exceptions in futures
        for (Future<Void> future : futures) {
            future.get(); // rethrows any exception from the thread
        }

        // All results must be consistent
        assertThat(inconsistencyCount.get())
            .as("No race-induced inconsistencies expected across %d threads × %d calls",
                THREAD_COUNT, CALLS_PER_THREAD)
            .isZero();
    }

    @Test
    @DisplayName("AC9: concurrent calls with varying permutations — no corrupted state")
    void concurrentScoringWithVariousPermutations() throws Exception {
        VarietyScorer scorer = new VarietyScorer();

        CanonicalPhaseDef phase = new CanonicalPhaseDef(3, 3, List.of(
            List.of(0, 1),
            List.of(1, 2),
            List.of(0, 2)
        ));
        boolean[][] activeMatrix = scorer.buildActiveMatrix(phase.rows(), 3, 3);

        // Pre-compute expected scores for all 6 permutations of [0,1,2]
        int[][] permutations = {
            {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
        };
        double[] expectedScores = new double[permutations.length];
        for (int i = 0; i < permutations.length; i++) {
            expectedScores[i] = scorer.scoreWithMatrix(permutations[i], 3, 3, activeMatrix);
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < CALLS_PER_THREAD; i++) {
                        int permIdx = (threadId + i) % permutations.length;
                        double result = scorer.scoreWithMatrix(
                            permutations[permIdx], 3, 3, activeMatrix);
                        if (Double.compare(result, expectedScores[permIdx]) != 0) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                }
            });
        }

        startGate.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(errors.get())
            .as("No errors expected in concurrent multi-permutation test")
            .isZero();
    }
}
