package de.vvwt.worker.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrency test for {@link LehmerCodec} — AC5.
 *
 * <p>Verifies that 1000 concurrent calls from 10 threads with random inputs produce no
 * inconsistencies: every result is consistent with the serial round-trip property.
 *
 * <p>LehmerCodec is designed to be unconditionally thread-safe (no shared mutable state other than
 * the one-time log sentinel). This test provides empirical verification.
 */
@DisplayName("LehmerCodec — concurrency (AC5)")
class LehmerCodecConcurrencyTest {

    private static final int THREAD_COUNT = 10;
    private static final int CALLS_PER_THREAD = 100; // total 1000 calls
    private static final int TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("AC5: 1000 concurrent calls from 10 threads produce no inconsistencies")
    void concurrentCallsProduceNoInconsistencies() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        List<Future<List<String>>> futures = new ArrayList<>(THREAD_COUNT);

        for (int t = 0; t < THREAD_COUNT; t++) {
            final long seed = t * 31L + 7919L;
            futures.add(pool.submit(() -> runThread(seed)));
        }

        pool.shutdown();
        boolean finished = pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertThat(finished).as("Thread pool did not finish within %ds", TIMEOUT_SECONDS).isTrue();

        List<String> allFailures = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            try {
                allFailures.addAll(future.get());
            } catch (ExecutionException ex) {
                fail("Thread threw unexpected exception: " + ex.getCause());
            }
        }

        if (!allFailures.isEmpty()) {
            fail("Concurrency inconsistencies detected:\n" + String.join("\n", allFailures));
        }
    }

    /**
     * Runs {@link #CALLS_PER_THREAD} random (n, rank) pairs through rankToPermutation and verifies
     * the round-trip in the same call. Collects failure descriptions; does not throw.
     */
    private static List<String> runThread(long seed) {
        Random rng = new Random(seed);
        List<String> failures = new ArrayList<>();

        for (int call = 0; call < CALLS_PER_THREAD; call++) {
            int n = rng.nextInt(17) + 1; // [1, 17]
            long maxRank = LehmerCodec.FACTORIAL[n] - 1L;
            long rank = (long) (rng.nextDouble() * (maxRank + 1));
            if (rank > maxRank) {
                rank = maxRank;
            }

            try {
                // rankToPermutation
                int[] perm = LehmerCodec.rankToPermutation(rank, n);

                // Structural validity
                if (perm == null) {
                    failures.add(
                            String.format(
                                    "n=%d rank=%d: rankToPermutation returned null", n, rank));
                    continue;
                }
                if (perm.length != n) {
                    failures.add(
                            String.format(
                                    "n=%d rank=%d: expected length %d, got %d",
                                    n, rank, n, perm.length));
                    continue;
                }
                boolean[] seen = new boolean[n];
                boolean valid = true;
                for (int elem : perm) {
                    if (elem < 0 || elem >= n || seen[elem]) {
                        valid = false;
                        break;
                    }
                    seen[elem] = true;
                }
                if (!valid) {
                    failures.add(
                            String.format(
                                    "n=%d rank=%d: result is not a valid permutation: %s",
                                    n, rank, Arrays.toString(perm)));
                    continue;
                }

                // Round-trip
                long recovered = LehmerCodec.permutationToRank(perm);
                if (recovered != rank) {
                    failures.add(
                            String.format(
                                    "n=%d rank=%d: round-trip failed, got %d back (perm=%s)",
                                    n, rank, recovered, Arrays.toString(perm)));
                }

            } catch (Exception ex) {
                failures.add(
                        String.format(
                                "n=%d rank=%d: unexpected exception %s: %s",
                                n, rank, ex.getClass().getSimpleName(), ex.getMessage()));
            }
        }

        return failures;
    }
}
