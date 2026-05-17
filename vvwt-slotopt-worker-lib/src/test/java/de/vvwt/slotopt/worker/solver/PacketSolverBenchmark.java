// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.solver;

import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PacketResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/**
 * JMH throughput benchmark for {@link PacketSolver} — AC9 of story E01S03.
 *
 * <h2>Benchmark setup</h2>
 *
 * <p>N=14 with a representative phase definition. The packet size is chosen to exercise
 * approximately 15 million permutations per benchmark call (derived from the H-2 gate reference
 * rate of ≥ 3 million perms/sec/core × 5 second target duration in Brief D-10).
 *
 * <h2>Expected throughput (H-2 gate)</h2>
 *
 * <p>The documented baseline for the H-2 gate is <strong>≥ 3 million permutations/sec/core</strong>
 * (Brief D-10). This benchmark measures ops/sec; multiply by {@link #PACKET_SIZE} to derive
 * perms/sec. The E01S12 ship-gate story will enforce this threshold in a dedicated benchmark
 * submodule.
 *
 * <h2>Advisory status</h2>
 *
 * <p>This benchmark is in {@code test} scope and does NOT gate the Maven Surefire build. To run it,
 * use the JMH runner directly:
 *
 * <pre>
 *   mvn test-compile -pl vvwt-worker-lib
 *   java -cp target/test-classes:target/classes:... \
 *        org.openjdk.jmh.Main PacketSolverBenchmark
 * </pre>
 *
 * Or use the JMH Maven plugin / benchmarks runner.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class PacketSolverBenchmark {

    /** N=14 for the benchmark — matches the H-2 gate specification (Brief D-10). */
    private static final int N = 14;

    /**
     * Packet size: 15_000_000 permutations.
     *
     * <p>Rationale: at the H-2 gate threshold of 3M perms/sec, this packet takes ~5 seconds to
     * solve (Brief D-10 reference rate). A single benchmark call measures the throughput of solving
     * this packet, providing a meaningful sustained-load data point.
     *
     * <p>Note: 14! = 87_178_291_200. A packet of 15M perms is a small fraction (< 0.02%) of the
     * full 14! space, which is the realistic production packet size.
     */
    static final long PACKET_SIZE = 15_000_000L;

    private JobDef jobDef;
    private long rankFrom;
    private long rankTo;

    /** Computes n! as a long (n in [0, 17]). */
    private static long factorial(int n) {
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }

    /**
     * Builds the benchmark state: a representative N=14 phase definition with a realistic avatar
     * distribution (7 avatars across 14 rows — alternating active/idle pattern to exercise the
     * variety scorer non-trivially).
     */
    @Setup
    public void setUp() {
        // 14 rows, 7 avatars — each avatar appears in exactly 2 consecutive rows
        // (e.g., avatar 0 in rows 0 and 1, avatar 1 in rows 2 and 3, etc.)
        List<List<Integer>> rows = new ArrayList<>(N);
        for (int row = 0; row < N; row++) {
            int avatarId = row / 2; // 7 avatars for 14 rows
            rows.add(List.of(avatarId));
        }
        CanonicalPhaseDef phaseDef = new CanonicalPhaseDef(N, N / 2, rows);
        jobDef = new JobDef(UUID.randomUUID(), N, phaseDef);

        // Use a representative starting rank well into the space to avoid trivial orderings
        long nFactorial = factorial(N);
        rankFrom = nFactorial / 4; // start at 25th percentile
        rankTo = rankFrom + PACKET_SIZE;
    }

    /**
     * Measures sustained throughput of {@link PacketSolver#solvePacket} over a 15M-perm packet.
     *
     * <p>Throughput in ops/sec × {@link #PACKET_SIZE} = perms/sec. The H-2 gate threshold (≥ 3M
     * perms/sec/core) corresponds to ≥ 0.2 ops/sec for this packet size.
     */
    @Benchmark
    public PacketResult solvePacket() {
        return PacketSolver.solvePacket(jobDef, rankFrom, rankTo);
    }
}
