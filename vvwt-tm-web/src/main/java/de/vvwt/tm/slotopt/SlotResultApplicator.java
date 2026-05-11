package de.vvwt.tm.slotopt;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Result applicator: maps a permutation rank from the slot-optimization compute kernel back to
 * {@code (lapNumber, fieldNumber)} coordinates on each {@link Match} entity.
 *
 * <h2>Algorithm — lap-group permutation (E54S04 fix for sparse/bye-slot phases)</h2>
 *
 * <p>L3's optimizer produces a {@code rank} in {@code [0, lapCount!)} interpreted as a
 * <em>lap-permutation</em> via {@link LehmerCodec#rankToPermutation(long, int)} with {@code n =
 * lapCount}. The permutation π over {@code [0, lapCount-1]} re-orders whole laps; intra-lap
 * field-positions (intra-lap sort order from L2) are preserved.
 *
 * <p>Prior to E54S04, the algorithm used a flat-index scheme ({@code sourceFlatIdx = π[i/fc]*fc +
 * i%fc}) that assumed every lap contains exactly {@code fieldCount} matches (i.e., no bye-slots).
 * For asymmetric phases (e.g., 11T/2G/3F: 5-team group with bye-slots → some laps have 2 matches,
 * not 3), {@code matchCount &lt; lapCount * fieldCount}, and the flat-index can exceed {@code
 * matchCount - 1} → {@link IndexOutOfBoundsException}. This is the root cause of E54S03's failure
 * (Index 25 out of bounds for length 25).
 *
 * <h2>Corrected algorithm (E54S04)</h2>
 *
 * <ol>
 *   <li>Sort the phase matches by L2 position {@code (lapNumber - 1) * fieldCount + (fieldNumber -
 *       1)} ascending to obtain L2 canonical order within each lap.
 *   <li>Group the sorted matches into per-lap buckets {@code lapGroups[0..lapCount-1]}: all matches
 *       with L2 lap 1 → bucket 0, lap 2 → bucket 1, etc. Each bucket contains 1..fieldCount matches
 *       in L2-field order (bye-slot laps may have fewer than {@code fieldCount} matches).
 *   <li>Unrank via {@link LehmerCodec#rankToPermutation(long, int)} with {@code n = lapCount} to
 *       get permutation π over {@code [0, lapCount-1]}.
 *   <li>For each output lap index {@code outLap = 0..lapCount-1}: take the source bucket {@code
 *       lapGroups[π[outLap]]}; assign each match in the bucket {@code lapNumber = outLap + 1}
 *       (1-based) and {@code fieldNumber = 1..bucketSize} (preserving L2 intra-lap order). This
 *       handles bye-slot laps correctly: a 2-match lap remains a 2-match lap after permutation.
 * </ol>
 *
 * <h2>Key invariant (D-7 — preserved under new algorithm)</h2>
 *
 * <p>Because the lap-permutation reorders whole laps without changing intra-lap relative ordering,
 * the intra-lap field assignment is invariant under L3. A match that was at field k within its L2
 * lap will still be at field k within its L3 output lap.
 *
 * <h2>Identity rank (AC-TEST-RANK-AS-LAP-PERMUTATION-RED)</h2>
 *
 * <p>Rank 0 is the identity permutation π(i)=i, producing the same relative (lapNumber,
 * fieldNumber) ordering as L2 assigned — the L2 baseline is preserved. Lap numbers are 1-based in
 * both L2 output and L3 output (E53S06 / DEC-60 D-1).
 *
 * <h2>Empty phase (AC-ERROR-HANDLING-EMPTY-PHASE)</h2>
 *
 * <p>If {@code mapping.matchOrder()} is empty, returns immediately without any writes.
 *
 * <h2>Invalid rank (AC-ERROR-HANDLING-INVALID-RANK)</h2>
 *
 * <p>If {@code rank >= lapCount!}, {@link LehmerCodec#rankToPermutation} throws {@link
 * IllegalArgumentException} — surfaces naturally.
 *
 * <h2>Tenant scoping</h2>
 *
 * <p>All writes use the tenant-scoped {@link MatchRepository#save(Object)}.
 *
 * @see PhaseToRawPhaseDefMapper
 * @see LehmerCodec
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-49">DEC-49 D-3 — N redefined as lapCount</a>
 * @see <a href="DEC-55">DEC-55 D-7 — lap-permutation model</a>
 * @see <a href="E51S11">E51S11 — L3 Refactor</a>
 * @see <a href="E54S04">E54S04 — Fix: IndexOutOfBoundsException for asymmetric bye-slot phases</a>
 */
@Service
public class SlotResultApplicator {

    private static final Logger LOG = LoggerFactory.getLogger(SlotResultApplicator.class);

    private final MatchRepository matchRepository;

    /**
     * Constructs the applicator with the required repository.
     *
     * @param matchRepository tenant-scoped repository for Match persistence
     */
    public SlotResultApplicator(MatchRepository matchRepository) {
        this.matchRepository = matchRepository;
    }

    /**
     * Applies a lap-permutation rank to the matches in the given mapping result, writing {@code
     * lapNumber} and {@code fieldNumber} to every match.
     *
     * <p>The rank is interpreted as a permutation over {@code [0, lapCount-1]} via {@link
     * LehmerCodec#rankToPermutation(long, int)} with {@code n = lapCount}. The permutation π
     * reorders whole laps; intra-lap field-positions stay invariant (D-7).
     *
     * <p>Handles bye-slot (asymmetric) phases where some laps contain fewer than {@code fieldCount}
     * matches — the lap-group permutation approach does not assume uniform lap sizes (E54S04 fix).
     *
     * @param rank the lap-permutation rank from the optimizer; {@code 0} = identity (L2 baseline)
     * @param fieldCount the number of courts/fields per lap; must be {@code >= 1}
     * @param mapping the forward-mapping result from {@link PhaseToRawPhaseDefMapper}
     * @throws IllegalArgumentException if {@code mapping} is {@code null} or {@code fieldCount < 1}
     * @throws IllegalArgumentException if {@code rank >= lapCount!} (from {@link LehmerCodec})
     */
    public void applyResult(long rank, int fieldCount, MappingResult mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("mapping must not be null");
        }
        if (fieldCount < 1) {
            throw new IllegalArgumentException("fieldCount must be >= 1 but was: " + fieldCount);
        }

        List<Match> matchOrder = mapping.matchOrder();
        int rowCount = matchOrder.size();

        // AC-ERROR-HANDLING-EMPTY-PHASE: empty mapping → no-op
        if (rowCount == 0) {
            LOG.debug("SlotResultApplicator: empty mapping (0 matches) — no-op");
            return;
        }

        // DEC-61 Clause B: post-E54S02 canonical.rowCount() = lapCount (not matchCount).
        // Derive lapCount from the canonical form's row count (= number of lap-rows).
        int lapCount = mapping.canonical().rowCount();

        // Step 1: sort matches by L2 position ascending to obtain L2 canonical order.
        // For 1-based lap/field (DEC-60 D-1), use (lapNumber - 1) * fieldCount + (fieldNumber - 1)
        // which is equivalent to lapNumber * fieldCount + fieldNumber (monotone: same sort order).
        List<Match> sortedByL2 = sortByL2Position(matchOrder, fieldCount);

        // Step 2: group sorted matches into per-lap buckets (lap 1 → index 0, lap 2 → index 1, …)
        // E54S04 fix: bucket approach handles bye-slot phases where some laps have < fieldCount
        // matches. The flat-index scheme (π[i/fc]*fc + i%fc) incorrectly assumed matchCount =
        // lapCount * fieldCount and caused IndexOutOfBoundsException for asymmetric phases.
        List<List<Match>> lapGroups = buildLapGroups(sortedByL2, lapCount);

        // Step 3: unrank → lap permutation π over [0, lapCount-1]
        // Throws IAE if rank >= lapCount! (AC-ERROR-HANDLING-INVALID-RANK surfaces naturally)
        int[] pi = LehmerCodec.rankToPermutation(rank, lapCount);

        // Step 4: apply permutation — for output lap index outLap, take source bucket
        // lapGroups[π[outLap]]
        // and assign all matches in that bucket lapNumber = outLap + 1 (1-based per E53S06 / DEC-60
        // D-1),
        // fieldNumber = 1..bucketSize (preserving intra-lap relative order from L2).
        for (int outLap = 0; outLap < lapCount; outLap++) {
            List<Match> sourceBucket = lapGroups.get(pi[outLap]);
            int outputLapNumber = outLap + 1; // 1-based: lap 1..lapCount (E53S06)
            for (int fieldIdx = 0; fieldIdx < sourceBucket.size(); fieldIdx++) {
                Match match = sourceBucket.get(fieldIdx);
                match.setLapNumber(outputLapNumber);
                match.setFieldNumber(fieldIdx + 1); // 1-based: field 1..bucketSize (DEC-60 D-1)
                matchRepository.save(match);
            }
        }

        LOG.info(
                "SlotResultApplicator: applied rank={}, lapCount={}, fieldCount={}, matches={}",
                rank,
                lapCount,
                fieldCount,
                rowCount);
    }

    /**
     * Groups the L2-sorted matches into per-lap buckets.
     *
     * <p>Collects distinct lap numbers in ascending encounter order (the input is already sorted by
     * L2 position, so encounter order = ascending lap order). Assigns bucket index 0 to the
     * smallest encountered lap number, 1 to the next, etc. This is resilient to both 0-based and
     * 1-based lap numbers (though DEC-60 D-1 mandates 1-based in production; 0-based may appear in
     * legacy test fixtures).
     *
     * <p>Matches within each bucket retain the L2 intra-lap sort order from the input list. The
     * returned list has exactly {@code lapCount} entries.
     *
     * <p>E54S04: this method replaces the flat-index scheme to support bye-slot (asymmetric) phases
     * where some laps have fewer than {@code fieldCount} matches.
     *
     * @param sortedByL2 matches sorted by L2 position ascending (output of {@link
     *     #sortByL2Position})
     * @param lapCount the total number of laps (= canonical.rowCount() post-E54S02)
     * @return list of {@code lapCount} buckets, each containing the matches for that lap in L2
     *     intra-lap order
     */
    private static List<List<Match>> buildLapGroups(List<Match> sortedByL2, int lapCount) {
        // Use LinkedHashMap to group by lap number while preserving encounter order.
        // Since input is sorted by L2 position ascending, encounter order = ascending lap order.
        Map<Integer, List<Match>> byLap = new LinkedHashMap<>();
        for (Match m : sortedByL2) {
            int lapNum = safeInt(m.getLapNumber());
            byLap.computeIfAbsent(lapNum, k -> new ArrayList<>()).add(m);
        }
        // Assign sequential bucket indices 0, 1, 2, ... to the distinct lap numbers in order.
        // This handles both 0-based and 1-based lap numbers without assuming a fixed offset.
        List<List<Match>> lapGroups = new ArrayList<>(lapCount);
        for (int i = 0; i < lapCount; i++) {
            lapGroups.add(new ArrayList<>());
        }
        int bucketIdx = 0;
        for (List<Match> bucket : byLap.values()) {
            if (bucketIdx < lapCount) {
                lapGroups.set(bucketIdx, bucket);
            }
            bucketIdx++;
        }
        return lapGroups;
    }

    /**
     * Sorts matches by their L2-assigned flat position {@code lapNumber * fieldCount + fieldNumber}
     * ascending. Matches with {@code null} lap/field (not yet assigned by L2) are treated as
     * position 0.
     *
     * @param matches the matches to sort (not modified in place — a copy is returned)
     * @param fieldCount number of fields per lap
     * @return a new list sorted by L2 flat position ascending
     */
    private static List<Match> sortByL2Position(List<Match> matches, int fieldCount) {
        List<Match> sorted = new ArrayList<>(matches);
        sorted.sort(
                (a, b) -> {
                    int posA = safeInt(a.getLapNumber()) * fieldCount + safeInt(a.getFieldNumber());
                    int posB = safeInt(b.getLapNumber()) * fieldCount + safeInt(b.getFieldNumber());
                    return Integer.compare(posA, posB);
                });
        return sorted;
    }

    private static int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
