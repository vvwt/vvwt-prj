package de.vvwt.tm.slotopt;

import de.vvwt.tm.domain.Match;
import de.vvwt.tm.domain.repo.MatchRepository;
import de.vvwt.tm.domain.repo.PhaseRepository;
import de.vvwt.worker.codec.LehmerCodec;
import de.vvwt.worker.solver.PacketSolver;
import de.vvwt.worker.types.CanonicalPhaseDef;
import de.vvwt.worker.types.JobDef;
import de.vvwt.worker.types.PacketResult;

// NOTE: JobDef.MAX_N = 17 — PacketSolver only accepts permutation lengths in [1, 17].
// Phases with rowCount > 17 (e.g. 6+ teams: C(6,2)=15 matches is fine, but C(7,2)=21 is not)
// are outside the compute kernel's design scope and must use fallback assignment.

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * In-process slot-optimization client — exhaustive mode (N &le; exhaustiveMaxN) and
 * timeout-based best-effort mode (N &gt; exhaustiveMaxN).
 *
 * <h2>Exhaustive mode (E04S03, N &le; exhaustiveMaxN)</h2>
 * <p>Searches all N! permutations and applies the globally optimal result.
 *
 * <h2>Timeout-based micro-segment mode (E04S04, N &gt; exhaustiveMaxN)</h2>
 * <p>For phases where the permutation space exceeds what can be searched exhaustively in-process
 * (N &gt; {@code tm.slotopt.exhaustive-max-n}, default 10), this mode is used:
 *
 * <ol>
 *   <li>Partitions {@code [0, N!)} into micro-segments of configurable size
 *       ({@code tm.slotopt.segment-size}, default 1,000,000 permutations). For N=12 this
 *       produces ~479 micro-segments.</li>
 *   <li>Submits each micro-segment as a {@link PacketSolver#solvePacket(JobDef, long, long)} task
 *       to a managed {@link ExecutorService} ({@code slotOptExecutor} bean,
 *       {@code tm.slotopt.thread-count} threads, default: available processors).</li>
 *   <li>Stops submitting new micro-segments after the wall-clock timeout expires
 *       ({@code tm.slotopt.timeout-seconds}, default 30).</li>
 *   <li>Waits for currently-running segments to finish naturally (each segment completes in
 *       well under a second at 1M permutations — no interruption needed).</li>
 *   <li>Collects the best {@code (rank, score)} across all completed segments and applies it.</li>
 *   <li>If <em>no</em> segment completes within the timeout, falls back to
 *       {@link FallbackSlotOptimizationClient}'s sequential assignment with a WARN log (AC5).</li>
 * </ol>
 *
 * <h2>Architecture note — N = rowCount</h2>
 * <p>The {@link PacketSolver} operates on permutations of row indices {@code [0, rowCount)}.
 * The permutation length {@code n} passed in {@link JobDef} must therefore equal
 * {@code canonicalPhaseDef.rowCount()}, not {@code avatarCount}.
 *
 * <h2>DEC-4 V1 amendment</h2>
 * <p>Per DEC-4 V1 amendment (2026-04-12), in-process computation is permitted for V1.
 * The dispatcher HTTP integration is deferred to a future Epic.
 *
 * <h2>Bean wiring</h2>
 * <p>This is a {@code @Service} bean of type {@link SlotOptimizationClient}. Its presence
 * disables {@link FallbackSlotOptimizationClient} via {@code @ConditionalOnMissingBean}.
 * The fallback bean is injected here by qualifier for use in the timeout fallback path (AC5, AC11).
 *
 * <h2>Tenant scoping (AC16)</h2>
 * <p>All repository calls delegate to tenant-scoped repositories (DEC-5, E03S05). The
 * TenantContext must be active before calling {@link #optimize(UUID)}.
 *
 * @see SlotOptimizationClient
 * @see FallbackSlotOptimizationClient
 * @see PacketSolver
 * @see SlotOptConfig
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S03.story.md">Story E04S03</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S04.story.md">Story E04S04</a>
 */
@Primary
@Service
public class DirectSlotOptimizationClient implements SlotOptimizationClient {

    private static final Logger LOG = LoggerFactory.getLogger(DirectSlotOptimizationClient.class);

    private final PhaseRepository phaseRepository;
    private final MatchRepository matchRepository;
    private final PhaseToRawPhaseDefMapper mapper;
    private final SlotOptimizationClient fallbackClient;
    private final ExecutorService executor;
    private final int exhaustiveMaxN;
    private final int timeoutSeconds;
    private final long segmentSize;

    /**
     * Constructs the client.
     *
     * @param phaseRepository  tenant-scoped repository for Phase entities (AC9)
     * @param matchRepository  tenant-scoped repository for Match entities (AC10, AC16)
     * @param mapper           forward mapper for Phase &rarr; RawPhaseDef (E04S02)
     * @param fallbackClient   fallback for the timeout path when no segment completes (AC5, AC11)
     * @param executor         managed thread pool for micro-segment tasks (AC3)
     * @param exhaustiveMaxN   maximum N for exhaustive search (default 10,
     *                         {@code tm.slotopt.exhaustive-max-n})
     * @param timeoutSeconds   timeout for micro-segment search in seconds (default 30,
     *                         {@code tm.slotopt.timeout-seconds})
     * @param segmentSize      micro-segment size in permutations (default 1,000,000,
     *                         {@code tm.slotopt.segment-size})
     */
    public DirectSlotOptimizationClient(
            PhaseRepository phaseRepository,
            MatchRepository matchRepository,
            PhaseToRawPhaseDefMapper mapper,
            @Qualifier("fallbackSlotOptimizer") SlotOptimizationClient fallbackClient,
            @Qualifier("slotOptExecutor") ExecutorService executor,
            @Value("${tm.slotopt.exhaustive-max-n:10}") int exhaustiveMaxN,
            @Value("${tm.slotopt.timeout-seconds:30}") int timeoutSeconds,
            @Value("${tm.slotopt.segment-size:1000000}") long segmentSize) {
        if (phaseRepository == null) {
            throw new IllegalArgumentException("phaseRepository must not be null");
        }
        if (matchRepository == null) {
            throw new IllegalArgumentException("matchRepository must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (fallbackClient == null) {
            throw new IllegalArgumentException("fallbackClient must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        if (segmentSize <= 0) {
            throw new IllegalArgumentException("segmentSize must be > 0, got: " + segmentSize);
        }
        this.phaseRepository = phaseRepository;
        this.matchRepository = matchRepository;
        this.mapper = mapper;
        this.fallbackClient = fallbackClient;
        this.executor = executor;
        this.exhaustiveMaxN = exhaustiveMaxN;
        this.timeoutSeconds = timeoutSeconds;
        this.segmentSize = segmentSize;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Selects exhaustive or timeout-based mode based on N (rowCount):
     * <ul>
     *   <li>N &lt; 2: trivial coordinates, no optimization.</li>
     *   <li>N &le; {@code exhaustiveMaxN}: exhaustive search &mdash; guaranteed optimal (E04S03).</li>
     *   <li>N &gt; {@code exhaustiveMaxN}: micro-segment timeout mode (E04S04, AC1).</li>
     * </ul>
     *
     * @param phaseId the phase whose matches should receive slot assignments; must not be null
     * @throws IllegalArgumentException if {@code phaseId} is null or the phase does not exist (AC9)
     * @throws IllegalStateException    if no matches exist for the phase (AC10)
     */
    @Override
    public void optimize(UUID phaseId) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }

        // AC9: verify phase exists
        phaseRepository.findById(phaseId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "DirectSlotOptimizationClient: phase not found: " + phaseId));

        // AC10: verify matches exist
        List<Match> matches = matchRepository.findByPhaseId(phaseId);
        if (matches.isEmpty()) {
            throw new IllegalStateException(
                    "DirectSlotOptimizationClient: no matches found for phase " + phaseId
                    + ". Cannot optimize empty phase.");
        }

        // Forward-map to RawPhaseDef + canonical form
        MappingResult mapping = mapper.map(phaseId);
        CanonicalPhaseDef canonical = mapping.canonical();

        int n = canonical.rowCount();

        // Trivial phase (N < 2): assign sequential coordinates without optimization
        if (n < 2) {
            LOG.warn("DirectSlotOptimizationClient: phase={}, N={} (< 2 rows) — "
                    + "trivial phase, assigning sequential coordinates without optimization",
                    phaseId, n);
            applyTrivialCoordinates(mapping);
            return;
        }

        // AC1: route to correct mode, with N > MAX_N safety guard
        if (n > JobDef.MAX_N) {
            // PacketSolver only accepts permutation lengths in [1, JobDef.MAX_N=17].
            // For phases with rowCount > 17, the compute kernel cannot be used.
            // Fall back to sequential assignment to ensure all matches get valid slot coordinates.
            LOG.warn("DirectSlotOptimizationClient: phase={}, N={} > MAX_N={}. "
                    + "PacketSolver cannot handle this permutation length. "
                    + "Falling back to sequential slot assignment.",
                    phaseId, n, JobDef.MAX_N);
            fallbackClient.optimize(phaseId);
        } else if (n <= exhaustiveMaxN) {
            optimizeExhaustive(phaseId, mapping, canonical, n);
        } else {
            optimizeWithTimeout(phaseId, mapping, canonical, n);
        }
    }

    // -------------------------------------------------------------------------
    // Exhaustive mode (E04S03)
    // -------------------------------------------------------------------------

    /**
     * Exhaustive slot optimization for N &le; exhaustiveMaxN.
     *
     * <p>Evaluates all N! permutations and applies the globally optimal result.
     *
     * @param phaseId   phase identifier (for logging)
     * @param mapping   forward mapping result
     * @param canonical canonical phase definition
     * @param n         rowCount (= number of matches)
     */
    private void optimizeExhaustive(UUID phaseId, MappingResult mapping,
                                     CanonicalPhaseDef canonical, int n) {
        JobDef jobDef = new JobDef(UUID.randomUUID(), n, canonical);
        long totalPermutations = factorial(n);
        long startMs = System.currentTimeMillis();

        PacketResult result = PacketSolver.solvePacket(jobDef, 0L, totalPermutations);

        long wallClockMs = System.currentTimeMillis() - startMs;
        LOG.info("DirectSlotOptimizationClient [exhaustive]: phase={}, N={}, permutations={}, "
                + "wallClockMs={}, bestScore={}",
                phaseId, n, totalPermutations, wallClockMs, result.bestScore());

        int[] rowSeq = LehmerCodec.rankToPermutation(result.bestRank(), n);
        applyOptimizedSlots(mapping, rowSeq);
    }

    // -------------------------------------------------------------------------
    // Timeout-based micro-segment mode (E04S04)
    // -------------------------------------------------------------------------

    /**
     * Timeout-based best-effort slot optimization for N &gt; exhaustiveMaxN (AC1–AC11, AC16).
     *
     * <p>Partitions {@code [0, N!)} into micro-segments of size {@code segmentSize} and submits
     * them to the executor until the wall-clock timeout expires or all segments are submitted.
     * Then collects the best result from all completed segments.
     *
     * <p>If the executor rejects tasks (AC11), or if no segment completes within the timeout
     * (AC5), falls back to {@link FallbackSlotOptimizationClient} sequential assignment.
     *
     * @param phaseId   phase identifier (for logging and fallback delegation)
     * @param mapping   forward mapping result
     * @param canonical canonical phase definition
     * @param n         rowCount; guaranteed &gt; exhaustiveMaxN
     */
    private void optimizeWithTimeout(UUID phaseId, MappingResult mapping,
                                      CanonicalPhaseDef canonical, int n) {
        long totalPermutations = factorial(n);
        long totalMicroSegments = (totalPermutations + segmentSize - 1) / segmentSize;

        int threadPoolSize = (executor instanceof java.util.concurrent.ThreadPoolExecutor tpe)
                ? tpe.getCorePoolSize()
                : -1;

        // AC8: INFO log — startup parameters
        LOG.info("DirectSlotOptimizationClient [timeout-mode]: phase={}, N={}, "
                + "totalPermutations={} ({}!), threads={}, segmentSize={}, "
                + "totalMicroSegments={}, timeoutSeconds={}",
                phaseId, n, totalPermutations, n,
                threadPoolSize, segmentSize, totalMicroSegments, timeoutSeconds);

        JobDef jobDef = new JobDef(UUID.randomUUID(), n, canonical);
        long deadlineMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

        List<Future<PacketResult>> futures = new ArrayList<>();
        long segmentsSubmitted = 0L;

        // Submit micro-segments until timeout expires or all segments are submitted (AC3)
        try {
            for (long segStart = 0; segStart < totalPermutations; segStart += segmentSize) {
                if (System.currentTimeMillis() >= deadlineMs) {
                    break;
                }
                long segEnd = Math.min(segStart + segmentSize, totalPermutations);
                final long capturedStart = segStart;
                final long capturedEnd = segEnd;
                futures.add(executor.submit(
                        () -> PacketSolver.solvePacket(jobDef, capturedStart, capturedEnd)));
                segmentsSubmitted++;
            }
        } catch (RejectedExecutionException ex) {
            // AC11: thread pool failure — fall back to sequential assignment
            LOG.error("DirectSlotOptimizationClient [timeout-mode]: ExecutorService rejected tasks "
                    + "for phase={}. Falling back to sequential assignment. Cause: {}",
                    phaseId, ex.getMessage());
            fallbackClient.optimize(phaseId);
            return;
        }

        // Collect completed results; cancel futures that have not yet started if past deadline
        PacketResult bestResult = null;
        long segmentsCompleted = 0L;
        long permutationsScored = 0L;

        for (Future<PacketResult> future : futures) {
            long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining <= 0) {
                future.cancel(false);
                continue;
            }
            try {
                PacketResult result = future.get(remaining, TimeUnit.MILLISECONDS);
                segmentsCompleted++;
                permutationsScored += segmentSize;
                if (bestResult == null
                        || result.bestScore() < bestResult.bestScore()
                        || (result.bestScore() == bestResult.bestScore()
                                && result.bestRank() < bestResult.bestRank())) {
                    bestResult = result;
                }
            } catch (java.util.concurrent.TimeoutException | InterruptedException
                    | ExecutionException ignored) {
                future.cancel(false);
            }
        }

        long wallClockMs = System.currentTimeMillis()
                - (deadlineMs - TimeUnit.SECONDS.toMillis(timeoutSeconds));
        boolean timeoutHit = segmentsCompleted < segmentsSubmitted;

        // AC8: INFO log — completion summary
        LOG.info("DirectSlotOptimizationClient [timeout-mode]: phase={}, N={}, "
                + "segmentsSubmitted={}, segmentsCompleted={}, permutationsScored~={}, "
                + "wallClockMs={}, bestScore={}, timeout_hit={}",
                phaseId, n, segmentsSubmitted, segmentsCompleted, permutationsScored,
                wallClockMs, bestResult != null ? bestResult.bestScore() : "none", timeoutHit);

        if (bestResult == null) {
            // AC5: no segment completed within timeout — fall back to sequential assignment
            LOG.warn("DirectSlotOptimizationClient [timeout-mode]: Timeout-based optimization "
                    + "produced no result within {}s for N={}. Falling back to sequential assignment.",
                    timeoutSeconds, n);
            fallbackClient.optimize(phaseId);
            return;
        }

        // AC6: apply best result — same lap/field assignment as the exhaustive path
        int[] rowSeq = LehmerCodec.rankToPermutation(bestResult.bestRank(), n);
        applyOptimizedSlots(mapping, rowSeq);
    }

    // -------------------------------------------------------------------------
    // Slot assignment
    // -------------------------------------------------------------------------

    /**
     * Assigns lap/field coordinates from the optimized row sequence using a greedy
     * round-constraint-aware algorithm.
     *
     * <p>For each match in the optimal order, the match is placed in the earliest lap where
     * neither of its avatars has a match already assigned. Field numbers within each lap are
     * sequential (order of arrival).
     *
     * <p>Guarantees:
     * <ul>
     *   <li>Round constraint: no avatar plays twice in the same lap — by construction.</li>
     *   <li>Determinism: for the same row sequence and phase data, the same assignment is
     *       always produced.</li>
     * </ul>
     *
     * @param mapping the forward mapping result (match order, denseIdsByRawRow)
     * @param rowSeq  the optimized row sequence (permutation of [0, rowCount))
     */
    private void applyOptimizedSlots(MappingResult mapping, int[] rowSeq) {
        List<Match> matchOrder = mapping.matchOrder();
        int[][] denseIdsByRawRow = mapping.denseIdsByRawRow();
        int rowCount = matchOrder.size();

        List<Set<Integer>> lapAvatarSets = new ArrayList<>();
        int[] assignedLap = new int[rowCount];
        int[] assignedField = new int[rowCount];
        int[] fieldCountPerLap = new int[rowCount];

        for (int pos = 0; pos < rowCount; pos++) {
            int rowIdx = rowSeq[pos];
            int d1 = denseIdsByRawRow[rowIdx][0];
            int d2 = denseIdsByRawRow[rowIdx][1];

            int targetLap = -1;
            for (int lap = 0; lap < lapAvatarSets.size(); lap++) {
                Set<Integer> used = lapAvatarSets.get(lap);
                if (!used.contains(d1) && !used.contains(d2)) {
                    targetLap = lap;
                    break;
                }
            }
            if (targetLap == -1) {
                targetLap = lapAvatarSets.size();
                lapAvatarSets.add(new HashSet<>());
            }

            Set<Integer> used = lapAvatarSets.get(targetLap);
            used.add(d1);
            used.add(d2);

            assignedLap[rowIdx] = targetLap;
            assignedField[rowIdx] = fieldCountPerLap[targetLap]++;
        }

        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            Match match = matchOrder.get(rowIdx);
            match.setLapNumber(assignedLap[rowIdx]);
            match.setFieldNumber(assignedField[rowIdx]);
            matchRepository.save(match);
        }

        int lapCount = lapAvatarSets.size();
        LOG.info("DirectSlotOptimizationClient: applied optimized slots to {} matches, {} laps",
                rowCount, lapCount);
    }

    /**
     * Assigns trivial coordinates (lap 0, sequential field numbers) to all matches.
     * Used for the trivial-phase case (N &lt; 2).
     *
     * @param mapping the forward mapping result
     */
    private void applyTrivialCoordinates(MappingResult mapping) {
        List<Match> matchOrder = mapping.matchOrder();
        for (int idx = 0; idx < matchOrder.size(); idx++) {
            Match match = matchOrder.get(idx);
            match.setLapNumber(0);
            match.setFieldNumber(idx);
            matchRepository.save(match);
        }
        LOG.info("DirectSlotOptimizationClient: assigned trivial coordinates to {} matches "
                + "in phase (N < 2 case)", matchOrder.size());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Computes N! for N in [0, 20]. All values fit in {@code long} (20! = 2.4e18 &lt; Long.MAX_VALUE).
     *
     * @param n the value whose factorial to compute; must be &ge; 0
     * @return n!
     * @throws IllegalArgumentException if n &lt; 0
     */
    static long factorial(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be >= 0, got: " + n);
        }
        long result = 1L;
        for (int i = 2; i <= n; i++) {
            result *= i;
        }
        return result;
    }
}
