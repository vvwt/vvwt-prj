package de.vvwt.benchmark;

import de.vvwt.slotopt.worker.solver.PacketSolver;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.PacketResult;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * JMH ship-gate benchmark for the Phase 1 H-2 performance requirement (E01S12).
 *
 * <h2>Purpose</h2>
 *
 * <p>This benchmark converts the "Java is fast enough" conditional in Brief D-10 / H-2 into an
 * empirically verified, reproducible, ship-blocking measurement. The conditional (DEC-1 hard
 * requirement for Java workers) is valid only if the worker achieves ≤ 300 ns/perm on reference
 * hardware. This benchmark measures exactly that.
 *
 * <h2>Reference Workload (AC1, AC2)</h2>
 *
 * <p>The benchmark uses the fixed reference workload checked in at {@code
 * src/main/resources/h2-reference-N14.json}. The file contains a deterministic {@link JobDef} with
 * N=14 and a {@link CanonicalPhaseDef} derived from a 14-team round-robin volleyball tournament
 * structure with 4 courts per round (see JSON provenance). The workload is NOT randomly generated
 * at benchmark time — it is a fixed, reproducible input.
 *
 * <h2>JMH Configuration (AC3)</h2>
 *
 * <ul>
 *   <li>Warmup: 5 iterations × 5 seconds — JIT is fully warm before the first measurement.
 *   <li>Measurement: 10 iterations × 5 seconds — 50 seconds of sustained measurement.
 *   <li>Mode: AverageTime — reports nanoseconds per {@code solvePacket} call.
 *   <li>Fork: 1 — single JVM instance, no cross-JVM noise.
 *   <li>Threads: 1 — single-threaded mode per AC3 ("single-threaded mode").
 * </ul>
 *
 * <h2>Reference Hardware Target (AC4)</h2>
 *
 * <p>Target: Intel Xeon E-2236 (or comparable mid-range server CPU at 3.4 GHz base), 4 active
 * cores, JDK 21+, Linux x86_64.
 *
 * <p>The benchmark reports results in two forms:
 *
 * <ol>
 *   <li>Absolute ns/perm on the actual hardware (computed by the gate wrapper script {@code
 *       gate/run-gate.sh} from the JMH JSON output).
 *   <li>Hardware identification (model, frequency, JVM, OS) logged by the gate script so deviations
 *       from the reference target are visible.
 * </ol>
 *
 * <h2>ns/perm Derivation</h2>
 *
 * <p>JMH reports ns/op where one op = one {@code solvePacket} call over {@link #PACKET_SIZE}
 * permutations. The gate wrapper computes:
 *
 * <pre>
 *   nsPerPerm = reportedNsPerOp / PACKET_SIZE
 * </pre>
 *
 * With {@code PACKET_SIZE = 17_000_000}, this is a sub-run of the full 14! space (87_178_291_200
 * ranks), sized to approximately 5 seconds at the H-2 gate threshold (300 ns/perm × 17_000_000 =
 * 5.1 s per JMH iteration), fitting within the 5-second measurement window.
 *
 * <h2>Security (AC10)</h2>
 *
 * <p>This benchmark uses ONLY the production code path of {@link PacketSolver#solvePacket} with the
 * canonical production type model. No test-mode shortcuts, no mocked dependencies, no inlined test
 * data via reflection. The reference workload is loaded from a classpath resource to ensure the
 * same input is used on every run.
 *
 * <h2>Story</h2>
 *
 * <p>Implements story E01S12 (AC1–AC10) in {@code vvwt-benchmark} per DEC-11.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 5, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
public class KernelThroughputBenchmark {

    /**
     * Number of permutation ranks processed per benchmark call.
     *
     * <p>Rationale: at the H-2 gate threshold of 300 ns/perm, a packet of 17 million permutations
     * takes approximately 5.1 seconds to solve, matching the JMH measurement iteration duration.
     * This makes each benchmark call a sustained-load data point representative of real production
     * packet sizes.
     *
     * <p>Note: 14! = 87_178_291_200. A packet of 17M perms is ~0.02% of the full rank space, which
     * is consistent with the real dispatcher packet decomposition strategy.
     */
    public static final long PACKET_SIZE = 17_000_000L;

    /**
     * 14! = 87_178_291_200 — the total number of permutations of 14 elements. Used to validate that
     * PACKET_SIZE does not exceed the rank space.
     */
    public static final long N14_FACTORIAL = 87_178_291_200L;

    /** N for this benchmark — must match the reference workload. */
    private static final int N = 14;

    /**
     * Start rank for the benchmark packet — positioned at the 25th percentile of the rank space.
     */
    private static final long RANK_FROM = N14_FACTORIAL / 4;

    /** End rank (exclusive) — RANK_FROM + PACKET_SIZE. */
    private static final long RANK_TO = RANK_FROM + PACKET_SIZE;

    // Loaded once at @Setup(Level.Trial) — immutable, shared across all iterations.
    private JobDef jobDef;

    /**
     * Loads the reference workload from the checked-in JSON resource file.
     *
     * <p>Invoked once per trial (not per iteration) so that fixture loading time is excluded from
     * the benchmark measurement.
     *
     * @throws IllegalStateException if the resource file is missing or malformed
     */
    @Setup(Level.Trial)
    public void setUp() {
        jobDef = loadReferenceWorkload();
    }

    /**
     * Measures the average time to solve one packet of {@link #PACKET_SIZE} permutations using the
     * production {@link PacketSolver#solvePacket} code path.
     *
     * <p>JMH measures nanoseconds per call to this method. The gate wrapper computes {@code
     * nsPerPerm = result / PACKET_SIZE} to derive the per-permutation throughput.
     *
     * @return the {@link PacketResult} (consumed by JMH to prevent dead-code elimination)
     */
    @Benchmark
    public PacketResult solvePacket() {
        return PacketSolver.solvePacket(jobDef, RANK_FROM, RANK_TO);
    }

    // -------------------------------------------------------------------------
    // Reference workload loader — JDK-only JSON parsing (no external deps)
    // -------------------------------------------------------------------------

    /**
     * Loads and parses the reference workload from {@code h2-reference-N14.json}. Uses only JDK
     * classes — no external JSON library dependency.
     *
     * <p>The JSON structure expected:
     *
     * <pre>
     * {
     *   "jobId": "00000000-0000-0000-0000-000000000014",
     *   "n": 14,
     *   "canonicalPhaseDef": {
     *     "rowCount": 7,
     *     "avatarCount": 14,
     *     "rows": [[0,1,2,...], ...]
     *   }
     * }
     * </pre>
     */
    private static JobDef loadReferenceWorkload() {
        String resourcePath = "/h2-reference-N14.json";
        String json;
        try (InputStream inputStream =
                KernelThroughputBenchmark.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException(
                        "Reference workload not found on classpath: "
                                + resourcePath
                                + ". Ensure vvwt-benchmark/src/main/resources/h2-reference-N14.json"
                                + " is present.");
            }
            json = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ioException) {
            throw new IllegalStateException(
                    "Failed to read reference workload from classpath: " + resourcePath,
                    ioException);
        }

        return parseJobDef(json);
    }

    /**
     * Minimal JSON parser for the reference workload — extracts only the fields needed to construct
     * a {@link JobDef}. Not a general-purpose parser.
     */
    private static JobDef parseJobDef(String json) {
        // Extract jobId
        UUID jobId = UUID.fromString(extractStringField(json, "jobId"));

        // Extract top-level "n" — search only the section before canonicalPhaseDef to
        // avoid matching rowCount or avatarCount integers unintentionally.
        int canonicalBlockStart = json.indexOf("\"canonicalPhaseDef\"");
        String topSection =
                (canonicalBlockStart > 0) ? json.substring(0, canonicalBlockStart) : json;
        int n = extractIntField(topSection, "n");

        // Extract the canonicalPhaseDef block
        String canonicalBlock = extractObjectBlock(json, "canonicalPhaseDef");
        int rowCount = extractIntField(canonicalBlock, "rowCount");
        int avatarCount = extractIntField(canonicalBlock, "avatarCount");
        List<List<Integer>> rows = extractRows(canonicalBlock);

        if (rows.size() != rowCount) {
            throw new IllegalStateException(
                    "h2-reference-N14.json: rowCount="
                            + rowCount
                            + " but parsed "
                            + rows.size()
                            + " rows");
        }

        CanonicalPhaseDef phaseDef = new CanonicalPhaseDef(rowCount, avatarCount, rows);
        return new JobDef(jobId, n, phaseDef);
    }

    /**
     * Extracts the value of a JSON string field by name. Handles the form {@code "fieldName":
     * "value"}.
     *
     * @param json the JSON string to search in
     * @param fieldName the field name (without quotes)
     */
    private static String extractStringField(String json, String fieldName) {
        Pattern pattern =
                Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "h2-reference-N14.json: missing string field '" + fieldName + "'");
        }
        return matcher.group(1);
    }

    /**
     * Extracts the value of a JSON integer field by name. Handles the form {@code "fieldName": 42}.
     *
     * @param json the JSON string (or sub-block) to search in
     * @param fieldName the field name (without quotes)
     */
    private static int extractIntField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "h2-reference-N14.json: missing int field '" + fieldName + "'");
        }
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Extracts a JSON object block for a named field. Finds the opening brace after {@code
     * "fieldName":} and returns the balanced block.
     */
    private static String extractObjectBlock(String json, String fieldName) {
        String marker = "\"" + fieldName + "\"";
        int markerIndex = json.indexOf(marker);
        if (markerIndex < 0) {
            throw new IllegalStateException(
                    "h2-reference-N14.json: missing object field '" + fieldName + "'");
        }
        int braceStart = json.indexOf('{', markerIndex + marker.length());
        if (braceStart < 0) {
            throw new IllegalStateException(
                    "h2-reference-N14.json: no opening brace for field '" + fieldName + "'");
        }
        int depth = 0;
        for (int index = braceStart; index < json.length(); index++) {
            char character = json.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(braceStart, index + 1);
                }
            }
        }
        throw new IllegalStateException(
                "h2-reference-N14.json: unbalanced braces for field '" + fieldName + "'");
    }

    /**
     * Extracts the {@code rows} field as a list of integer lists from a JSON block. Handles the
     * form {@code "rows": [[0,1,2], [3,4,5], ...]}.
     */
    private static List<List<Integer>> extractRows(String json) {
        int rowsMarker = json.indexOf("\"rows\"");
        if (rowsMarker < 0) {
            throw new IllegalStateException("h2-reference-N14.json: missing 'rows' field");
        }
        int outerBracket = json.indexOf('[', rowsMarker);
        if (outerBracket < 0) {
            throw new IllegalStateException("h2-reference-N14.json: no '[' after 'rows'");
        }

        // Find the end of the outer array
        int depth = 0;
        int outerEnd = -1;
        for (int index = outerBracket; index < json.length(); index++) {
            char character = json.charAt(index);
            if (character == '[') {
                depth++;
            } else if (character == ']') {
                depth--;
                if (depth == 0) {
                    outerEnd = index;
                    break;
                }
            }
        }
        if (outerEnd < 0) {
            throw new IllegalStateException("h2-reference-N14.json: unbalanced '[' in 'rows'");
        }

        String rowsContent = json.substring(outerBracket + 1, outerEnd);
        List<List<Integer>> rows = new ArrayList<>();

        // Find each inner array
        int searchFrom = 0;
        while (true) {
            int innerStart = rowsContent.indexOf('[', searchFrom);
            if (innerStart < 0) {
                break;
            }
            int innerEnd = rowsContent.indexOf(']', innerStart);
            if (innerEnd < 0) {
                throw new IllegalStateException("h2-reference-N14.json: unbalanced '[' in a row");
            }
            String innerContent = rowsContent.substring(innerStart + 1, innerEnd).trim();
            List<Integer> row = new ArrayList<>();
            if (!innerContent.isEmpty()) {
                for (String token : innerContent.split(",")) {
                    row.add(Integer.parseInt(token.trim()));
                }
            }
            rows.add(List.copyOf(row));
            searchFrom = innerEnd + 1;
        }

        return rows;
    }
}
