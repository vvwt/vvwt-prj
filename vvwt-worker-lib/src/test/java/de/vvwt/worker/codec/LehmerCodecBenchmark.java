package de.vvwt.worker.codec;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

/**
 * JMH micro-benchmark for {@link LehmerCodec} — AC8.
 *
 * <p>Measures throughput of {@code rankToPermutation} at N=14 (the reference workload per
 * Brief D-10). The documented baseline target is ≥ 5 million ops/sec per core on the
 * reference hardware.
 *
 * <p>This benchmark is advisory only — it does NOT gate the build. It is intended to be run
 * manually or in a dedicated CI profiling job via:
 * <pre>
 *   mvn -pl vvwt-worker-lib test-compile exec:java \
 *     -Dexec.mainClass=de.vvwt.worker.codec.LehmerCodecBenchmark \
 *     -Dexec.classpathScope=test
 * </pre>
 *
 * <p>Or via the JMH runner main method below (for IDE execution).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
public class LehmerCodecBenchmark {

    /**
     * Element count for the benchmark. N=14 is chosen per Brief D-10 reference hardware spec.
     * 14! = 87,178,291,200 — a realistic large job size.
     */
    private static final int BENCH_N = 14;

    /**
     * Pre-computed max rank for N=14 to avoid recomputing in the hot loop.
     */
    private static final long MAX_RANK_14 = LehmerCodec.FACTORIAL[BENCH_N] - 1L;

    /**
     * Rank counter — cycles through the full n=14 space. Using a long field
     * ensures JMH can serialize state per-thread (Scope.Benchmark = single shared instance,
     * but @Threads(1) so no contention).
     */
    private long rank = 0L;

    /**
     * AC8 benchmark: throughput of {@link LehmerCodec#rankToPermutation(long, int)} at N=14.
     *
     * <p>The result is consumed by a {@link Blackhole} to prevent dead-code elimination.
     * The rank cycles through all valid values to exercise the full algorithm uniformly.
     */
    @Benchmark
    public void rankToPermutationN14(Blackhole blackhole) {
        int[] perm = LehmerCodec.rankToPermutation(rank, BENCH_N);
        blackhole.consume(perm);
        // Cycle rank through the full space — avoids biasing toward rank 0
        if (rank >= MAX_RANK_14) {
            rank = 0L;
        } else {
            rank++;
        }
    }

    /**
     * Additional benchmark: round-trip throughput (both directions) at N=14.
     * Not the primary AC8 metric, but useful for full-pipeline profiling.
     */
    @Benchmark
    public void roundTripN14(Blackhole blackhole) {
        int[] perm = LehmerCodec.rankToPermutation(rank, BENCH_N);
        long recoveredRank = LehmerCodec.permutationToRank(perm);
        blackhole.consume(recoveredRank);
        if (rank >= MAX_RANK_14) {
            rank = 0L;
        } else {
            rank++;
        }
    }

    /**
     * Standalone entry point for running the benchmark without Maven exec plugin.
     * Not invoked during {@code mvn test} — this class is compiled into test-classes
     * but the JMH runner is never triggered by Surefire.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(LehmerCodecBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}
