package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.RoundAssignmentService;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default L2 Round-Assignment implementation using a greedy edge-coloring algorithm.
 *
 * <h2>Algorithm (greedy edge-coloring with lap-major flat ordering)</h2>
 *
 * <ol>
 *   <li>Load all matches for the phase from {@link MatchRepository}.
 *   <li>Load all avatars for the phase from {@link TeamAvatarRepository} to build a groupNumber
 *       index (avatar UUID → groupNumber).
 *   <li>Partition matches by {@code groupNumber} of their avatar1. Matches within the same group
 *       are coloured together; groups are processed in ascending {@code groupNumber} order.
 *   <li>For each group, sort matches by {@link Match#getId()} (ascending UUID string) for
 *       deterministic ordering.
 *   <li>Greedy lap assignment per group: iterate sorted matches. Assign each match to the lowest
 *       lap {@code l} such that: (a) no existing match in lap {@code l} shares an avatar with the
 *       current match, AND (b) lap {@code l} has fewer than {@code fieldCount} matches.
 *   <li>Direct lap+field assignment from greedy buckets: {@code lapNumber = cumulativeLapOffset +
 *       greedy-bucket-index} (1-based), {@code fieldNumber = position-within-bucket + 1} (1-based
 *       per DEC-60 D-1 — E53S09). This guarantees round-conflict-freedom and the field-count
 *       capacity constraint by construction.
 *   <li>Write all lap+field values back via {@link MatchRepository#save(Match)}.
 * </ol>
 *
 * <h2>Multi-group concatenation (Brief D-12)</h2>
 *
 * <p>Groups are processed in ascending {@code groupNumber} order. The cumulative lap offset is
 * advanced by each group's lap count so that Group B's laps begin where Group A's end. Example:
 * Group A (6 teams, fieldCount=3) → 15 matches, 5 laps (1..5); Group B (6 teams, fieldCount=3) → 15
 * matches, laps start at offset 6 → laps 6..10. Lap numbers are 1-based (E53S06).
 *
 * <h2>Complexity</h2>
 *
 * <p>Greedy edge-coloring on K_N runs in O(N² × N) per group. For tournament sizes ≤ 32 teams per
 * group (≤ 496 edges, ≤ 31 laps), runtime is sub-millisecond.
 *
 * <h2>Spielart-agnostic guarantee (AC-IMPL-L2-SPIELART-AGNOSTIC)</h2>
 *
 * <p>This class does NOT import or reference {@code RoundRobinMatchGenerator}, {@code
 * SiegerehrungMatchGenerator}, or any {@code Spielart} / {@code gameMode} string. It operates
 * exclusively on {@link Match} objects (avatar-pair tuples) and repositories.
 *
 * <h2>B-b1 cycle-break (E51S16)</h2>
 *
 * <p>The previous injection of {@code PhaseToRawPhaseDefMapper} (from the {@code slotopt} module)
 * created a {@code tournament → slotopt} compile-time edge, causing the Modulith cycle {@code
 * slotopt → tournament → slotopt}. E51S16 removes this edge by replacing the mapper dependency with
 * a direct {@code @Value("${tm.slotopt.fallback.field-count:3}")} injection, which supplies the
 * same default value via Spring's PropertyResolver without crossing the module boundary. Per DEC-55
 * D-3 and DEC-21 {@code tournament @ApplicationModule(allowedDependencies = {"tenant"})}.
 *
 * @see RoundAssignmentService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith; tournament allowedDependencies = tenant only</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-35">DEC-35 — Spring Modulith: impl in .internal</a>
 * @see <a href="DEC-37">DEC-37 Clause B — runs within caller's TX (no own @Transactional)</a>
 * @see <a href="DEC-55">DEC-55 D-3 — L2 writes lap+field after L1</a>
 * @see <a href="E51S10">E51S10 — L2 Round-Assignment Service story</a>
 * @see <a href="E51S16">E51S16 — B-b1 Modulith-cycle elimination</a>
 */
@Service
class DefaultRoundAssignmentService implements RoundAssignmentService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRoundAssignmentService.class);

    private final MatchRepository matchRepository;
    private final TeamAvatarRepository teamAvatarRepository;

    /**
     * Coherence anchor: the configured fallback field-count default.
     *
     * <p>This field is NOT used at runtime for round-assignment logic — {@link
     * #assignRoundsAndFields(UUID, int)} receives the already-resolved {@code fieldCount} from
     * {@code MatchGenJobExecutor}. Its presence here serves as a drift guard: if this literal ever
     * diverges from the literal in {@code MatchGenJobExecutor} or {@code PhaseToRawPhaseDefMapper},
     * {@code FieldCountDefaultCoherenceTest} fails immediately
     * (AC-IMPL-DEFAULT-FIELDCOUNT-COHERENCE, AC-TEST-DEFAULT-FIELDCOUNT-COHERENCE-RED).
     *
     * <p>The literal MUST be EXACTLY {@code ${tm.slotopt.fallback.field-count:3}} —
     * character-identical to {@code MatchGenJobExecutor} and {@code PhaseToRawPhaseDefMapper}.
     */
    @Value("${tm.slotopt.fallback.field-count:3}")
    private int fallbackFieldCountCoherenceAnchor;

    DefaultRoundAssignmentService(
            MatchRepository matchRepository, TeamAvatarRepository teamAvatarRepository) {
        this.matchRepository = matchRepository;
        this.teamAvatarRepository = teamAvatarRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Worked example (fieldCount=3, 4 teams in 1 group → 6 matches):
     *
     * <pre>
     * Avatars: A(g=1,p=1), B(g=1,p=2), C(g=1,p=3), D(g=1,p=4)
     * Matches (sorted by UUID): A-B, A-C, A-D, B-C, B-D, C-D
     * Greedy lap assignment (fieldCount=3):
     *   A-B → lap 0 (empty; cap 0/3; A,B free)
     *   A-C → lap 0 (A in lap 0 already) → lap 1 (empty; cap 0/3; A,C free)
     *   A-D → lap 0 (A in lap 0) → lap 1 (A in lap 1) → lap 2 (empty; A,D free)
     *   B-C → lap 0 (B in lap 0) → lap 1 (C in lap 1) → lap 2 (cap 1/3; B,C free) → lap 2
     *   B-D → lap 0 (B in lap 0) → lap 1 (empty for B,D at cap 1/3) → lap 1
     *   C-D → lap 0 (cap 1/3; C,D free) → lap 0
     * lapBuckets: [0:[A-B,C-D], 1:[A-C,B-D], 2:[A-D,B-C]]
     * Final assignment (lapNumber=cumulativeLapOffset+bucket-index (1-based), fieldNumber=pos-in-bucket+1 (1-based per DEC-60 D-1)):
     *   A-B: lap=1, f=0; C-D: lap=1, f=1
     *   A-C: lap=2, f=0; B-D: lap=2, f=1
     *   A-D: lap=3, f=0; B-C: lap=3, f=1
     * (partial laps OK: fieldCount=3 capacity but only 2 matches per lap for K4)
     * Lap numbers are 1-based: first group starts at lap 1 (E53S06 fix).
     * </pre>
     *
     * @param phaseId must not be {@code null}
     * @param fieldCount must be ≥ 1
     */
    @Override
    public void assignRoundsAndFields(UUID phaseId, int fieldCount) {
        if (phaseId == null) {
            throw new IllegalArgumentException("phaseId must not be null");
        }
        if (fieldCount < 1) {
            throw new IllegalArgumentException(
                    "fieldCount must be ≥ 1 after fallback resolution; resolved="
                            + fieldCount
                            + ". Set tournament.fieldCount ≥ 1 or"
                            + " tm.slotopt.fallback.field-count ≥ 1 in application config.");
        }

        List<Match> matches = matchRepository.findByPhaseId(phaseId);

        if (matches.isEmpty()) {
            LOG.info(
                    "DefaultRoundAssignmentService: phase={} has 0 matches — no-op"
                            + " (siegerehrung or empty phase)",
                    phaseId);
            return;
        }

        // ── Step 1: Build avatar-to-groupNumber index ──────────────────────────────────────────
        List<TeamAvatar> avatars = teamAvatarRepository.findByPhaseId(phaseId);
        Map<UUID, Integer> avatarGroupNumber = new HashMap<>(avatars.size() * 2);
        for (TeamAvatar avatar : avatars) {
            avatarGroupNumber.put(avatar.getId(), avatar.getGroupNumber());
        }

        // ── Step 2: Partition matches by groupNumber of avatar1 ────────────────────────────────
        // After E51S09, intra-group round-robin guarantees both avatars share the same groupNumber.
        // Default to group 1 if avatar not found (defensive; should not occur post-E51S09).
        TreeMap<Integer, List<Match>> matchesByGroup = new TreeMap<>();
        for (Match match : matches) {
            int group = avatarGroupNumber.getOrDefault(match.getMemberAvatar1Id(), 1);
            matchesByGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(match);
        }

        // ── Step 3: Greedy edge-coloring per group with cumulative lap offset (D-12) ─────────────
        // cumulativeLapOffset advances by the number of laps produced per group so that Group B
        // begins where Group A ended (Brief D-12 concatenation convention).
        // 1-based: first group starts at lap 1, not lap 0, so that downstream consumers
        // (DefaultLaufzettelAssembler iterates lap=1..maxLap, DefaultTimelineCalculationService
        // generates lapNumber=1..lapCount) see all laps and no round is skipped (E53S06 fix).
        int cumulativeLapOffset = 1; // 1-based: first group starts at lap 1

        for (Map.Entry<Integer, List<Match>> entry : matchesByGroup.entrySet()) {
            int groupNumber = entry.getKey();
            List<Match> groupMatches = entry.getValue();

            // Deterministic ordering within group: sort by UUID string ascending
            groupMatches.sort(Comparator.comparing(m -> m.getId().toString()));

            // Greedy lap assignment within this group
            List<List<Match>> lapBuckets = greedyAssignLaps(groupMatches, fieldCount);

            // Assign lapNumber = cumulativeLapOffset + greedy-bucket-index,
            // fieldNumber = position within that bucket + 1 (1-based per DEC-60 D-1, E53S09).
            // This guarantees round-conflict-freedom and field-count constraint by construction.
            for (int lapIdx = 0; lapIdx < lapBuckets.size(); lapIdx++) {
                List<Match> lapMatches = lapBuckets.get(lapIdx);
                for (int fieldIdx = 0; fieldIdx < lapMatches.size(); fieldIdx++) {
                    Match match = lapMatches.get(fieldIdx);
                    match.setLapNumber(cumulativeLapOffset + lapIdx);
                    match.setFieldNumber(fieldIdx + 1); // 1-based per DEC-60 D-1 (E53S09)
                }
            }

            LOG.debug(
                    "DefaultRoundAssignmentService: phase={}, group={}, matches={}, laps={},"
                            + " lapOffset={}",
                    phaseId,
                    groupNumber,
                    groupMatches.size(),
                    lapBuckets.size(),
                    cumulativeLapOffset);

            cumulativeLapOffset += lapBuckets.size();
        }

        // ── Step 4: Persist all lap+field assignments ──────────────────────────────────────────
        // Runs within the caller's REQUIRES_NEW TX (MatchGenJobExecutor) — atomicity guaranteed.
        for (Match match : matches) {
            matchRepository.save(match);
        }

        LOG.info(
                "DefaultRoundAssignmentService: phase={}, totalMatches={}, fieldCount={}",
                phaseId,
                matches.size(),
                fieldCount);
    }

    /**
     * Greedy lap assignment for a sorted list of matches within a single group.
     *
     * <p>Assigns each match to the lowest lap {@code l} such that: (a) no existing match in lap
     * {@code l} shares an avatar with the current match (round-conflict-freedom), AND (b) lap
     * {@code l} has fewer than {@code fieldCount} matches (capacity constraint).
     *
     * <p>If no eligible lap exists, a new lap is opened.
     *
     * @param sortedMatches matches sorted deterministically (by UUID ascending)
     * @param fieldCount maximum matches per lap (capacity bound)
     * @return list of laps; each element is the list of matches assigned to that lap
     */
    private List<List<Match>> greedyAssignLaps(List<Match> sortedMatches, int fieldCount) {
        List<List<Match>> lapBuckets = new ArrayList<>();
        // Track avatar UUIDs already used in each lap for conflict detection
        List<Set<UUID>> avatarSetsPerLap = new ArrayList<>();

        for (Match match : sortedMatches) {
            UUID av1 = match.getMemberAvatar1Id();
            UUID av2 = match.getMemberAvatar2Id();

            int assignedLap = -1;
            for (int l = 0; l < lapBuckets.size(); l++) {
                Set<UUID> used = avatarSetsPerLap.get(l);
                List<Match> bucket = lapBuckets.get(l);
                // Eligible: no avatar conflict AND bucket not full
                if (!used.contains(av1) && !used.contains(av2) && bucket.size() < fieldCount) {
                    assignedLap = l;
                    break;
                }
            }

            if (assignedLap == -1) {
                assignedLap = lapBuckets.size();
                lapBuckets.add(new ArrayList<>());
                avatarSetsPerLap.add(new HashSet<>());
            }

            lapBuckets.get(assignedLap).add(match);
            avatarSetsPerLap.get(assignedLap).add(av1);
            avatarSetsPerLap.get(assignedLap).add(av2);
        }

        return lapBuckets;
    }
}
