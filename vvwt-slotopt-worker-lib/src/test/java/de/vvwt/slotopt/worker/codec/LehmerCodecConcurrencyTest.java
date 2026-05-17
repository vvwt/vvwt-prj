// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
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
 * Spec-Anchored concurrency test for {@link LehmerCodec}.
 *
 * <p>Replaces the Snapshot-Driven corpus (DEC-41 D-4 audit: E35S01). Satisfies DEC-41 criterion
 * (d): the thread-safety invariant is named ("bijection-round-trip-invariant is thread-safe") and
 * quantified over many concurrent invocations with varying inputs (10 threads × 100 calls each).
 *
 * <p>Property-based tests are in {@link LehmerCodecPropertyTest}. Algebraic invariant tests are in
 * {@link LehmerCodecTest}.
 */
@DisplayName("LehmerCodec — thread-safety invariant (DEC-41 D-4 replacement)")
class LehmerCodecConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int CALLS_PER_THREAD = 100;
    private static final int TIMEOUT_SECONDS = 30;

    /**
     * Invariant: bijection-round-trip-invariant is thread-safe.
     *
     * <p>Formally: for any valid (n, rank) pair, concurrent invocations of {@code
     * rankToPermutation(rank, n)} followed by {@code permutationToRank(perm)} from multiple threads
     * yield results satisfying the round-trip bijection invariant — i.e., no shared-state
     * corruption occurs when threads call the codec simultaneously.
     *
     * <p>Quantified over 1 000 concurrent calls (10 threads × 100 calls) with deterministic
     * per-thread seeds covering n ∈ [1, 17] and representative rank values.
     */
    @Test
    @DisplayName(
            "Invariant: bijection-round-trip is thread-safe — 10 threads × 100 calls with no"
                    + " round-trip violations")
    void invariant_bijectionRoundTrip_isThreadSafe() throws Exception {
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
                                return runBijectionChecks(seed, violations);
                            }));
        }

        startGate.countDown(); // release all threads at once
        pool.shutdown();
        boolean finished = pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(finished)
                .as("Thread-safety invariant: pool must terminate within %ds", TIMEOUT_SECONDS)
                .isTrue();

        // Collect per-thread failure descriptions for diagnostic reporting
        List<String> allFailures = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            allFailures.addAll(future.get());
        }

        assertThat(violations.get())
                .as(
                        "Thread-safety invariant: bijection round-trip must hold for all %d"
                                + " concurrent calls; violations: %s",
                        THREAD_COUNT * CALLS_PER_THREAD, allFailures)
                .isZero();
    }

    /**
     * Runs {@link #CALLS_PER_THREAD} round-trip checks with deterministic per-seed inputs. Returns
     * a list of violation descriptions; increments {@code violations} counter atomically.
     */
    private static List<String> runBijectionChecks(long seed, AtomicInteger violations) {
        // Deterministic seed-driven input generation: no Random (avoids flakiness)
        List<String> failures = new ArrayList<>();
        long state = seed;

        for (int call = 0; call < CALLS_PER_THREAD; call++) {
            // LCG step to generate a deterministic (n, rank) pair
            state = state * 6364136223846793005L + 1442695040888963407L;
            int n = (int) ((state >>> 48) % 17) + 1; // n in [1, 17]
            long maxRank = LehmerCodec.FACTORIAL[n] - 1L;
            state = state * 6364136223846793005L + 1442695040888963407L;
            long rank = (state >>> 1) % (maxRank + 1);

            try {
                int[] perm = LehmerCodec.rankToPermutation(rank, n);
                long recovered = LehmerCodec.permutationToRank(perm);
                if (recovered != rank) {
                    violations.incrementAndGet();
                    failures.add(
                            String.format(
                                    "n=%d rank=%d: round-trip returned %d (perm=%s)",
                                    n, rank, recovered, Arrays.toString(perm)));
                }
            } catch (Exception ex) {
                violations.incrementAndGet();
                failures.add(
                        String.format(
                                "n=%d rank=%d: exception %s: %s",
                                n, rank, ex.getClass().getSimpleName(), ex.getMessage()));
            }
        }
        return failures;
    }
}
