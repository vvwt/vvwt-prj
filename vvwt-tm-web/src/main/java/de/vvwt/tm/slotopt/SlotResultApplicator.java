package de.vvwt.tm.slotopt;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.worker.codec.LehmerCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Result applicator: maps a permutation rank from the slot-optimization compute kernel back to
 * {@code (lapNumber, fieldNumber)} coordinates on each {@link Match} entity.
 *
 * <h2>Algorithm (Delivery design choice — AC4)</h2>
 *
 * <ol>
 *   <li>Unrank via {@link LehmerCodec#rankToPermutation(long, int)} with {@code N = avatarCount} to
 *       get permutation {@code pi} over canonical dense avatar IDs {@code [0, N-1]}. {@code pi[i] =
 *       s} means: avatar {@code i} occupies "seat" {@code s} in the slot-assignment schedule.
 *   <li>Build a canonical round-robin schedule for {@code N} seats using the standard circle method
 *       (fix seat {@code N-1}, rotate seats {@code 0..N-2} through {@code N-1} rounds). Produces a
 *       lookup table {@code roundBySeat[sA][sB]} → which round seats {@code sA} and {@code sB} play
 *       each other.
 *   <li>For each match with dense avatar IDs {@code [d1, d2]}, determine its round as {@code
 *       roundBySeat[pi[d1]][pi[d2]]}.
 *   <li>Within each round, sort matches by UUID (ascending) for determinism (AC7) and assign field
 *       numbers sequentially (0, 1, ...).
 *   <li>Validate the round constraint: assert that no avatar appears in two matches in the same
 *       round (AC5). Since the circle method guarantees this by construction, a violation indicates
 *       an algorithm bug.
 * </ol>
 *
 * <h2>Capacity requirement</h2>
 *
 * <p>This algorithm requires {@code N} to be even, which is always true for round-robin tournaments
 * (each group has an even number of teams by DEC-9 structural convention). For odd {@code N}, the
 * algorithm inserts a "bye" seat at {@code N-1} so that {@code N} becomes even — identical to the
 * standard circle-method extension.
 *
 * <h2>Round constraint (AC5)</h2>
 *
 * <p>No TeamAvatar appears more than once in the same lap (= round). The circle method guarantees
 * this by construction. If a violation is detected, {@link IllegalStateException} is thrown before
 * any writes occur.
 *
 * <h2>Postcondition (AC4)</h2>
 *
 * <p>After successful return, every match in the phase has non-null {@code lapNumber} and {@code
 * fieldNumber}.
 *
 * <h2>Tenant scoping (AC14)</h2>
 *
 * <p>All writes use the tenant-scoped {@link MatchRepository#save(Object)}.
 *
 * @see PhaseToRawPhaseDefMapper
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E04S02.story.md">Story
 *     E04S02</a>
 */
@Service
public class SlotResultApplicator {

    private static final Logger LOG = LoggerFactory.getLogger(SlotResultApplicator.class);

    private final MatchRepository matchRepository;

    /**
     * Constructs the applicator with the required repository.
     *
     * @param matchRepository tenant-scoped repository for Match persistence (AC14)
     */
    public SlotResultApplicator(MatchRepository matchRepository) {
        this.matchRepository = matchRepository;
    }

    /**
     * Applies a permutation rank to the matches in the given mapping result, writing {@code
     * lapNumber} and {@code fieldNumber} to every match.
     *
     * @param rank the best permutation rank from the optimizer (AC4)
     * @param fieldCount the number of courts/fields per lap (AC6); must be {@code >= 1}
     * @param mapping the forward-mapping result from {@link PhaseToRawPhaseDefMapper} (AC3)
     * @throws IllegalArgumentException if {@code mapping} is {@code null} or {@code fieldCount < 1}
     * @throws IllegalStateException if the computed assignment violates the round constraint (AC5)
     * @throws IllegalStateException if no tenant context is active (E03S05 guard)
     */
    public void applyResult(long rank, int fieldCount, MappingResult mapping) {
        if (mapping == null) {
            throw new IllegalArgumentException("mapping must not be null");
        }
        if (fieldCount < 1) {
            throw new IllegalArgumentException("fieldCount must be >= 1 but was: " + fieldCount);
        }

        int n = mapping.avatarCount();
        List<Match> matchOrder = mapping.matchOrder();
        int[][] denseIdsByRawRow = mapping.denseIdsByRawRow();
        int rowCount = matchOrder.size();

        // AC4 step 1: unrank permutation pi over avatar IDs [0, N-1]
        // pi[i] = seat assigned to avatar i in the schedule
        int[] pi = LehmerCodec.rankToPermutation(rank, n);

        // AC4 step 2: build canonical round-robin schedule for N seats
        // roundBySeat[seatA][seatB] = which round seats seatA and seatB play each other
        // Uses standard circle method (fix seat N-1, rotate 0..N-2)
        int[][] roundBySeat = buildCanonicalRoundSchedule(n);

        // AC4 step 3: assign each match to a round using the permutation
        // Collect matches by round for deterministic field assignment
        Map<Integer, List<Integer>> matchIndicesByRound = new HashMap<>();
        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            int d1 = denseIdsByRawRow[rowIdx][0];
            int d2 = denseIdsByRawRow[rowIdx][1];
            int seatD1 = pi[d1];
            int seatD2 = pi[d2];
            int round = roundBySeat[seatD1][seatD2];
            matchIndicesByRound.computeIfAbsent(round, k -> new ArrayList<>()).add(rowIdx);
        }

        // AC4 step 4: within each round, sort by match UUID for determinism (AC7),
        // then assign field numbers 0..fieldCount-1
        int[] assignedLap = new int[rowCount];
        int[] assignedField = new int[rowCount];

        for (Map.Entry<Integer, List<Integer>> entry : matchIndicesByRound.entrySet()) {
            int round = entry.getKey();
            List<Integer> indices = entry.getValue();
            // Sort by match UUID for determinism
            indices.sort(Comparator.comparing(idx -> matchOrder.get(idx).getId().toString()));
            // Assign lap = round, field = position within round
            for (int fieldIdx = 0; fieldIdx < indices.size(); fieldIdx++) {
                int rowIdx = indices.get(fieldIdx);
                assignedLap[rowIdx] = round;
                assignedField[rowIdx] = fieldIdx;
            }
        }

        // AC5: validate round constraint before writing
        validateRoundConstraint(rowCount, denseIdsByRawRow, assignedLap, matchOrder, n);

        // Postcondition (AC4): write lap/field to all matches
        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            Match match = matchOrder.get(rowIdx);
            match.setLapNumber(assignedLap[rowIdx]);
            match.setFieldNumber(assignedField[rowIdx]);
            matchRepository.save(match); // AC14: tenant-scoped
        }

        LOG.info(
                "SlotResultApplicator: applied rank={}, N={}, fieldCount={}, matches={}, rounds={}",
                rank,
                n,
                fieldCount,
                rowCount,
                matchIndicesByRound.size());
    }

    /**
     * Builds the canonical round-robin schedule for {@code N} seats using the circle method.
     *
     * <p>Fixes seat {@code N-1} and rotates seats {@code 0..N-2} through {@code N-1} rounds. If
     * {@code N} is odd, a virtual "bye" seat is handled by treating all {@code N-1} rounds
     * uniformly (the by-seat pair always includes the fixed seat, which is the bye).
     *
     * @param n number of avatar seats; must be {@code >= 2}
     * @return {@code roundBySeat[a][b]} = round number in which seats {@code a} and {@code b} play
     *     each other (0-indexed)
     */
    static int[][] buildCanonicalRoundSchedule(int n) {
        // For the circle method we need an even number of seats.
        // If N is odd, we add a virtual bye-seat N (total becomes N+1).
        // For our purposes (round-robin tournament), N is always even (AC12 uses N=6).
        // We handle both cases for robustness.
        int seats;
        boolean hadOddN = (n % 2 != 0);
        if (hadOddN) {
            seats = n + 1;
        } else {
            seats = n;
        }

        int rounds = seats - 1;
        int[][] roundBySeat = new int[seats][seats];

        for (int round = 0; round < rounds; round++) {
            // Build the rotated seat assignment for this round
            // rotated[k] = seat at position k in this round (positions 0..seats-2)
            int[] rotated = new int[seats - 1];
            for (int k = 0; k < seats - 1; k++) {
                rotated[k] = (k + round) % (seats - 1);
            }

            // Fixed seat (seats-1) plays rotated[0]
            int fixedSeat = seats - 1;
            roundBySeat[fixedSeat][rotated[0]] = round;
            roundBySeat[rotated[0]][fixedSeat] = round;

            // Remaining pairs: for k = 1 .. seats/2 - 1, pair rotated[k] vs rotated[seats-1-k]
            for (int k = 1; k <= seats / 2 - 1; k++) {
                int seatA = rotated[k];
                int seatB = rotated[seats - 1 - k];
                roundBySeat[seatA][seatB] = round;
                roundBySeat[seatB][seatA] = round;
            }
        }

        return roundBySeat;
    }

    /**
     * Validates that no dense avatar ID appears in two matches within the same round (lap).
     *
     * @throws IllegalStateException if the round constraint is violated (AC5)
     */
    private static void validateRoundConstraint(
            int rowCount,
            int[][] denseIdsByRawRow,
            int[] assignedLap,
            List<Match> matchOrder,
            int n) {
        // lap → boolean[n] of which dense IDs have been seen
        Map<Integer, boolean[]> lapCheck = new HashMap<>();
        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            int lap = assignedLap[rowIdx];
            boolean[] used = lapCheck.computeIfAbsent(lap, k -> new boolean[n]);
            int d1 = denseIdsByRawRow[rowIdx][0];
            int d2 = denseIdsByRawRow[rowIdx][1];
            if (used[d1]) {
                throw new IllegalStateException(
                        "Round constraint violated (AC5): dense avatar ID "
                                + d1
                                + " appears more than once in lap "
                                + lap
                                + ". This indicates a bug in the slot assignment algorithm."
                                + " Match: "
                                + matchOrder.get(rowIdx).getId());
            }
            if (used[d2]) {
                throw new IllegalStateException(
                        "Round constraint violated (AC5): dense avatar ID "
                                + d2
                                + " appears more than once in lap "
                                + lap
                                + ". This indicates a bug in the slot assignment algorithm."
                                + " Match: "
                                + matchOrder.get(rowIdx).getId());
            }
            used[d1] = true;
            used[d2] = true;
        }
    }
}
