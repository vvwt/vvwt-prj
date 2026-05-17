// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.score;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * JMH micro-benchmark for {@link VarietyScorer} — AC8 advisory baseline.
 *
 * <p>Target: ≥ 3 million score computations per second per core at N=14 avatars (Brief D-10 /
 * E01S02 AC8). This is an advisory gate — it does not fail the Maven build. The hard ship-gate
 * benchmark is E01S12.
 *
 * <p>Run standalone via:
 *
 * <pre>
 *   mvn -pl vvwt-worker-lib test -Dtest=VarietyScorerBenchmark#main
 * </pre>
 *
 * or via the JMH runner entry point below.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1, warmups = 0)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class VarietyScorerBenchmark {

    private VarietyScorer scorer;
    private boolean[][] activeMatrix;
    private int[] perm;
    private int rowCount;
    private int avatarCount;

    /**
     * N=14 setup: 14 avatars, 14 rows, 2 avatars active per row (round-robin). This represents a
     * realistic dense phase used in slot optimization.
     */
    @Setup
    public void setUp() {
        scorer = new VarietyScorer();
        rowCount = 14;
        avatarCount = 14;

        List<List<Integer>> rows = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            int a1 = (r * 2) % avatarCount;
            int a2 = (r * 2 + 1) % avatarCount;
            rows.add(List.of(a1, a2));
        }
        CanonicalPhaseDef phase = new CanonicalPhaseDef(rowCount, avatarCount, rows);
        activeMatrix = scorer.buildActiveMatrix(phase.rows(), rowCount, avatarCount);

        // Fixed permutation for reproducible benchmark
        perm = new int[rowCount];
        for (int i = 0; i < rowCount; i++) {
            perm[i] = (i * 3 + 7) % rowCount; // non-trivial fixed permutation
        }
        // Ensure it's actually a valid permutation (in case of collisions with the formula)
        // Simple Fisher-Yates-derived deterministic permutation:
        perm = new int[rowCount];
        for (int i = 0; i < rowCount; i++) perm[i] = i;
        // Manual swap: [13,12,11,10,9,8,7,6,5,4,3,2,1,0] (reverse)
        for (int i = 0; i < rowCount / 2; i++) {
            int tmp = perm[i];
            perm[i] = perm[rowCount - 1 - i];
            perm[rowCount - 1 - i] = tmp;
        }
    }

    /**
     * Hot-path benchmark: score a single permutation. This is the innermost loop of the
     * PacketSolver (E01S03).
     *
     * <p>Advisory target: ≥ 3 million ops/sec on reference hardware (Brief D-10).
     */
    @Benchmark
    public double scoreN14() {
        return scorer.scoreWithMatrix(perm, rowCount, avatarCount, activeMatrix);
    }

    /**
     * Standalone runner — invoke directly to see benchmark results without full Maven lifecycle.
     */
    public static void main(String[] args) throws RunnerException {
        Options opt =
                new OptionsBuilder()
                        .include(VarietyScorerBenchmark.class.getSimpleName())
                        .forks(1)
                        .warmupIterations(3)
                        .measurementIterations(5)
                        .build();
        new Runner(opt).run();
    }
}
