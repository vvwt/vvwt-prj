package de.vvwt.tm.slotopt;

import de.vvwt.slotopt.worker.codec.LehmerCodec;
import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Result applicator: maps a permutation rank from the slot-optimization compute kernel back to
 * {@code (lapNumber, fieldNumber)} coordinates on each {@link Match} entity.
 *
 * <h2>Algorithm — Option 3 (flat-index + rank-as-lap-permutation, AC-IMPL-OPTION-3-FLAT-INDEX)</h2>
 *
 * <p>L3's optimizer produces a {@code rank} in {@code [0, lapCount!)} interpreted as a
 * <em>lap-permutation</em> via {@link LehmerCodec#rankToPermutation(long, int)} with {@code n =
 * lapCount}. The permutation π over {@code [0, lapCount-1]} re-orders whole laps; intra-lap
 * field-positions are preserved.
 *
 * <ol>
 *   <li>Sort the phase matches by their L2-assigned position {@code lapNumber * fieldCount +
 *       fieldNumber} ascending to obtain the L2 canonical order. L2 guarantees every slot {@code
 *       (lap, field)} is occupied exactly once for a full phase ({@code rowCount = lapCount *
 *       fieldCount}).
 *   <li>Unrank via {@link LehmerCodec#rankToPermutation(long, int)} with {@code n = lapCount} to
 *       get permutation π over {@code [0, lapCount-1]}.
 *   <li>The output row sequence is {@code [π(0)*fc, π(0)*fc+1, ..., π(lapCount-1)*fc+fc-1]} where
 *       {@code fc = fieldCount}. Each output position {@code i} is filled from the L2-sorted match
 *       at source flat-index {@code π[i/fc]*fc + i%fc}.
 *   <li>Write for output position {@code i}: {@code match.setLapNumber(i / fc + 1)} (1-based;
 *       E53S06 fix — lap numbers start at 1), {@code match.setFieldNumber(i % fc)}.
 * </ol>
 *
 * <h2>Key invariant (D-7, AC-TEST-FIELD-INVARIANT-UNDER-LAP-PERMUTATION-RED)</h2>
 *
 * <p>Because the lap-permutation reorders whole laps without changing intra-lap positions, {@code
 * fieldNumber} is invariant under L3. A match at L2 field=k always gets field=k from L3.
 *
 * <h2>Identity rank (AC-TEST-RANK-AS-LAP-PERMUTATION-RED)</h2>
 *
 * <p>Rank 0 is the identity permutation π(i)=i, producing the same relative (lapNumber,
 * fieldNumber) ordering as L2 assigned — the L2 baseline is preserved. Lap numbers are 1-based in
 * both L2 output and L3 output after E53S06.
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

        int lapCount = rowCount / fieldCount;

        // Step 1: sort matches by L2 position (lapNumber * fieldCount + fieldNumber) ascending
        List<Match> sortedByL2 = sortByL2Position(matchOrder, fieldCount);

        // Step 2: unrank → lap permutation π over [0, lapCount-1]
        // Throws IAE if rank >= lapCount! (AC-ERROR-HANDLING-INVALID-RANK surfaces naturally)
        int[] pi = LehmerCodec.rankToPermutation(rank, lapCount);

        // Step 3 + 4: apply permutation — for output position i, source = π[i/fc]*fc + i%fc
        // outputLapIndex is the 0-based index used for the pi[] lookup and as the flat-list index.
        // outputLap is 1-based (E53S06 fix): lap numbers written to Match are 1..lapCount so that
        // downstream consumers (DefaultLaufzettelAssembler, DefaultTimelineCalculationService)
        // see all rounds and no round is skipped.
        for (int i = 0; i < rowCount; i++) {
            int outputLapIndex = i / fieldCount; // 0-based index for pi[] lookup
            int outputField = i % fieldCount;
            int sourceFlatIdx = pi[outputLapIndex] * fieldCount + outputField;
            Match match = sortedByL2.get(sourceFlatIdx);
            match.setLapNumber(outputLapIndex + 1); // 1-based: lap 1..lapCount (E53S06)
            match.setFieldNumber(outputField);
            matchRepository.save(match);
        }

        LOG.info(
                "SlotResultApplicator: applied rank={}, lapCount={}, fieldCount={}, matches={}",
                rank,
                lapCount,
                fieldCount,
                rowCount);
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
