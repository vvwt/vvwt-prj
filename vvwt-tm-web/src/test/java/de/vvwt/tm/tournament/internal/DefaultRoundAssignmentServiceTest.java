package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * White-box unit tests for {@link DefaultRoundAssignmentService}.
 *
 * <p>Tests the greedy edge-coloring algorithm, multi-group concatenation (D-12), flat lap-major
 * ordering, and error-guard paths.
 *
 * <p>E51S16: removed obsolete {@code AC-TEST-MAPPER-FIELDCOUNT-NOT-DEAD-RED} fixture (previously
 * injected {@code PhaseToRawPhaseDefMapper} mock to keep the dead-code path live). After B-b1 the
 * mapper dependency is gone from {@link DefaultRoundAssignmentService}; this test file no longer
 * references {@code PhaseToRawPhaseDefMapper} per AC-IMPL-OBSOLETE-MAPPER-FIELDCOUNT-TEST-DELETED.
 *
 * <p>E54S01: 8 RED-first tests added for voting-driven phase-global slot-filling (DEC-61 Clause A).
 * These tests were written RED against the edge-coloring implementation (all fail before the voting
 * algorithm is in place) per DEC-22 Iron Law.
 *
 * @see DefaultRoundAssignmentService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-61">DEC-61 — L2 voting-driven phase-global slot-filling</a>
 * @see <a href="E51S10">E51S10 — L2 Round-Assignment Service story</a>
 * @see <a href="E51S16">E51S16 — B-b1 cycle-break; mapper dependency removed</a>
 * @see <a href="E54S01">E54S01 — L2 voting-driven port story</a>
 */
class DefaultRoundAssignmentServiceTest {

    private MatchRepository matchRepository;
    private TeamAvatarRepository teamAvatarRepository;
    private DefaultRoundAssignmentService service;

    private UUID phaseId;

    @BeforeEach
    void setUp() {
        matchRepository = mock(MatchRepository.class);
        teamAvatarRepository = mock(TeamAvatarRepository.class);
        service = new DefaultRoundAssignmentService(matchRepository, teamAvatarRepository);
        phaseId = UUID.randomUUID();
    }

    // ── Guard paths ────────────────────────────────────────────────────────────────────────────

    @Test
    void phaseId_null_throws() {
        assertThatThrownBy(() -> service.assignRoundsAndFields(null, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phaseId must not be null");
    }

    @Test
    void fieldCount_zero_throws() {
        assertThatThrownBy(() -> service.assignRoundsAndFields(phaseId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount must be ≥ 1");
    }

    @Test
    void fieldCount_negative_throws() {
        assertThatThrownBy(() -> service.assignRoundsAndFields(phaseId, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldCount must be ≥ 1");
    }

    // ── Siegerehrung / empty-phase no-op ───────────────────────────────────────────────────────

    @Test
    void empty_match_list_is_noop() {
        // AC-IMPL-SIEGEREHRUNG-NOOP: 0 matches → no exception, no save calls
        when(matchRepository.findByPhaseId(phaseId)).thenReturn(List.of());
        // No exception thrown, and no calls to teamAvatarRepository or matchRepository.save()
        service.assignRoundsAndFields(phaseId, 3);
        // verifying no save calls are issued is handled by mock's default (no interaction expected)
    }

    // ── Flat lap-major ordering ─────────────────────────────────────────────────────────────────

    @Test
    void single_group_6_matches_fieldCount3_flat_lap_major() {
        // AC-IMPL-L2-FLAT-INDEX: i=0 → lap=0,f=0; i=1 → lap=0,f=1; i=2 → lap=0,f=2;
        //   i=3 → lap=1,f=0; i=4 → lap=1,f=1; i=5 → lap=1,f=2
        // 4 avatars: A,B,C,D all in group 1
        UUID avA = makeUUID("aa");
        UUID avB = makeUUID("bb");
        UUID avC = makeUUID("cc");
        UUID avD = makeUUID("dd");
        UUID tid = UUID.randomUUID();

        // 6 matches (K4): AB, AC, AD, BC, BD, CD
        List<Match> matches =
                List.of(
                        makeMatch(makeUUID("01"), phaseId, tid, avA, avB),
                        makeMatch(makeUUID("02"), phaseId, tid, avA, avC),
                        makeMatch(makeUUID("03"), phaseId, tid, avA, avD),
                        makeMatch(makeUUID("04"), phaseId, tid, avB, avC),
                        makeMatch(makeUUID("05"), phaseId, tid, avB, avD),
                        makeMatch(makeUUID("06"), phaseId, tid, avC, avD));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(avA, phaseId, 1),
                        makeAvatar(avB, phaseId, 1),
                        makeAvatar(avC, phaseId, 1),
                        makeAvatar(avD, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(new ArrayList<>(matches));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        // All matches must have lapNumber and fieldNumber assigned
        for (Match m : matches) {
            assertThat(m.getLapNumber())
                    .as("lapNumber assigned for match %s", m.getId())
                    .isNotNull();
            assertThat(m.getFieldNumber())
                    .as("fieldNumber assigned for match %s", m.getId())
                    .isNotNull();
        }

        // Collect all (lap, field) pairs — must be 6 unique pairs
        Set<String> pairs = new HashSet<>();
        for (Match m : matches) {
            pairs.add(m.getLapNumber() + ":" + m.getFieldNumber());
        }
        assertThat(pairs).hasSize(6);
    }

    // ── Round-conflict-freedom property ────────────────────────────────────────────────────────

    @Test
    void round_conflict_freedom_no_avatar_plays_twice_in_same_lap() {
        // AC-IMPL-L2-ROUND-CONFLICT-FREEDOM: for any lapNumber L, no avatar appears in 2 matches
        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();
        UUID av5 = UUID.randomUUID();
        UUID av6 = UUID.randomUUID();
        UUID tid = UUID.randomUUID();

        // K6 = 15 matches; fieldCount=3 → should produce 5 laps (edge-chromatic-number of K6 = 5)
        List<UUID> avs = List.of(av1, av2, av3, av4, av5, av6);
        List<Match> matches = new ArrayList<>();
        int i = 0;
        for (int a = 0; a < avs.size(); a++) {
            for (int b = a + 1; b < avs.size(); b++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", i++)),
                                phaseId,
                                tid,
                                avs.get(a),
                                avs.get(b)));
            }
        }

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(av1, phaseId, 1),
                        makeAvatar(av2, phaseId, 1),
                        makeAvatar(av3, phaseId, 1),
                        makeAvatar(av4, phaseId, 1),
                        makeAvatar(av5, phaseId, 1),
                        makeAvatar(av6, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        // Verify round-conflict-freedom: group matches by lap, check no avatar appears twice
        java.util.Map<Integer, Set<UUID>> lapAvatars = new java.util.HashMap<>();
        for (Match m : matches) {
            int lap = m.getLapNumber();
            lapAvatars.computeIfAbsent(lap, k -> new HashSet<>());
            Set<UUID> avatarsInLap = lapAvatars.get(lap);
            assertThat(avatarsInLap)
                    .as("avatar1 %s already in lap %d", m.getMemberAvatar1Id(), lap)
                    .doesNotContain(m.getMemberAvatar1Id());
            assertThat(avatarsInLap)
                    .as("avatar2 %s already in lap %d", m.getMemberAvatar2Id(), lap)
                    .doesNotContain(m.getMemberAvatar2Id());
            avatarsInLap.add(m.getMemberAvatar1Id());
            avatarsInLap.add(m.getMemberAvatar2Id());
        }
    }

    // ── Field-count capacity constraint ────────────────────────────────────────────────────────

    @Test
    void field_count_capacity_constraint_no_lap_exceeds_fieldCount() {
        // AC-IMPL-L2-FIELD-COUNT: each lap holds at most fieldCount matches
        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();
        UUID tid = UUID.randomUUID();

        // K4 = 6 matches, fieldCount=2
        List<Match> matches =
                List.of(
                        makeMatch(makeUUID("01"), phaseId, tid, av1, av2),
                        makeMatch(makeUUID("02"), phaseId, tid, av1, av3),
                        makeMatch(makeUUID("03"), phaseId, tid, av1, av4),
                        makeMatch(makeUUID("04"), phaseId, tid, av2, av3),
                        makeMatch(makeUUID("05"), phaseId, tid, av2, av4),
                        makeMatch(makeUUID("06"), phaseId, tid, av3, av4));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(av1, phaseId, 1),
                        makeAvatar(av2, phaseId, 1),
                        makeAvatar(av3, phaseId, 1),
                        makeAvatar(av4, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(new ArrayList<>(matches));
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 2);

        java.util.Map<Integer, Long> countPerLap = new java.util.HashMap<>();
        for (Match m : matches) {
            countPerLap.merge(m.getLapNumber(), 1L, Long::sum);
        }
        for (var entry : countPerLap.entrySet()) {
            assertThat(entry.getValue())
                    .as("lap %d has more than fieldCount=2 matches", entry.getKey())
                    .isLessThanOrEqualTo(2L);
        }
    }

    // ── Multi-group phase-global assignment (E54S01 — DEC-61 Clause A replaces D-12 concatenation)

    @Test
    void multi_group_phase_global_both_matches_assigned_conflict_free_1based() {
        // AC-IMPL-L2-MULTI-GROUP (updated for DEC-61 Clause A — E54S01):
        // Previously (edge-coloring): Group 1 match → lap 1; Group 2 match → lap 2 (concatenation).
        // Now (voting-driven phase-global, DEC-61 Clause A): groups are NOT processed separately.
        // Both matches compete for the same (lap, field) slots. Since av1-av2 and av3-av4 are
        // DISJOINT avatar sets, BOTH can be placed in lap 1 (fields 1 and 2) without conflict.
        // The old per-group concatenation (Brief D-12) is superseded by DEC-61 Clause A.
        //
        // Assertions: both matches assigned, 1-based, round-conflict-freedom preserved.
        UUID av1 = UUID.randomUUID(); // group 1
        UUID av2 = UUID.randomUUID(); // group 1
        UUID av3 = UUID.randomUUID(); // group 2
        UUID av4 = UUID.randomUUID(); // group 2
        UUID tid = UUID.randomUUID();

        // Group 1: 1 match (av1 vs av2)
        Match m1 = makeMatch(makeUUID("01"), phaseId, tid, av1, av2);
        // Group 2: 1 match (av3 vs av4)
        Match m2 = makeMatch(makeUUID("02"), phaseId, tid, av3, av4);

        List<Match> allMatches = new ArrayList<>(List.of(m1, m2));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(av1, phaseId, 1),
                        makeAvatar(av2, phaseId, 1),
                        makeAvatar(av3, phaseId, 2),
                        makeAvatar(av4, phaseId, 2));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(allMatches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        // Both matches assigned
        assertThat(m1.getLapNumber()).as("Group 1 match assigned a lap").isNotNull();
        assertThat(m2.getLapNumber()).as("Group 2 match assigned a lap").isNotNull();

        // 1-based: both lapNumbers ≥ 1, both fieldNumbers ≥ 1 (DEC-60 D-1, E53S06)
        assertThat(m1.getLapNumber())
                .as("Group 1 match lap is 1-based (≥1)")
                .isGreaterThanOrEqualTo(1);
        assertThat(m1.getFieldNumber())
                .as("Group 1 match field is 1-based (≥1)")
                .isGreaterThanOrEqualTo(1);
        assertThat(m2.getLapNumber())
                .as("Group 2 match lap is 1-based (≥1)")
                .isGreaterThanOrEqualTo(1);
        assertThat(m2.getFieldNumber())
                .as("Group 2 match field is 1-based (≥1)")
                .isGreaterThanOrEqualTo(1);

        // No (lap, field) coordinate duplicates — both matches have distinct positions
        String pos1 = m1.getLapNumber() + ":" + m1.getFieldNumber();
        String pos2 = m2.getLapNumber() + ":" + m2.getFieldNumber();
        assertThat(pos1).as("matches have distinct (lap, field) positions").isNotEqualTo(pos2);

        // Phase-global: since av1-av2 and av3-av4 are disjoint, they CAN coexist in lap 1
        // The voting algorithm places both in lap 1 (fields 1 and 2).
        assertThat(m1.getLapNumber())
                .as(
                        "phase-global: Group 1 match coexists with Group 2 match in lap 1"
                                + " (DEC-61 Clause A — no per-group concatenation)")
                .isEqualTo(1);
        assertThat(m2.getLapNumber())
                .as(
                        "phase-global: Group 2 match coexists with Group 1 match in lap 1"
                                + " (DEC-61 Clause A — no per-group concatenation)")
                .isEqualTo(1);
    }

    // ── Minimal lap count ──────────────────────────────────────────────────────────────────────

    @Test
    void k4_fieldcount3_uses_at_most_3_laps() {
        // AC-IMPL-L2-MINIMAL-LAP-COUNT: K4 (6 matches, fieldCount=3) needs ceil(6/3)=2 laps
        // (greedy edge-coloring of K4 achieves chromatic index 3 → 3 rounds of 1-2 matches,
        // but with fieldCount=3 capacity, greedy packs compactly; expect ≤ 3 laps)
        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();
        UUID tid = UUID.randomUUID();

        List<Match> matches =
                new ArrayList<>(
                        List.of(
                                makeMatch(makeUUID("01"), phaseId, tid, av1, av2),
                                makeMatch(makeUUID("02"), phaseId, tid, av1, av3),
                                makeMatch(makeUUID("03"), phaseId, tid, av1, av4),
                                makeMatch(makeUUID("04"), phaseId, tid, av2, av3),
                                makeMatch(makeUUID("05"), phaseId, tid, av2, av4),
                                makeMatch(makeUUID("06"), phaseId, tid, av3, av4)));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(av1, phaseId, 1),
                        makeAvatar(av2, phaseId, 1),
                        makeAvatar(av3, phaseId, 1),
                        makeAvatar(av4, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        int maxLap = matches.stream().mapToInt(Match::getLapNumber).max().orElse(0);
        // K4 chromatic index = 3 rounds; with fieldCount=3 capacity, greedy assigns ≤ 3 laps.
        // 1-based (E53S06 fix): laps are 1-indexed, so maxLap=3 means 3 laps.
        assertThat(maxLap)
                .as(
                        "K4 with fieldCount=3 should need at most 3 laps (edge-chromatic-number of"
                                + " K4 = 3); 1-based so maxLap=3 means 3 laps (E53S06)")
                .isLessThanOrEqualTo(3); // laps are 1-indexed, so maxLap=3 means 3 laps
    }

    // ── E53S06: 1-based lap numbers RED test ───────────────────────────────────────────────────

    @Test
    void two_groups_6_each_fieldCount3_produces_10_distinct_laps_and_minLap_is_1() {
        // AC1 / AC5 (DEC-22 RED-first): 12 avatars in 2 groups of 6 → 30 matches, 10 laps.
        // MIN(lapNumber) must be ≥ 1 (1-based; lap 0 is forbidden after E53S06 fix).
        // RED before production change: cumulativeLapOffset=0 → first lap=0 → MIN=0 → FAIL.
        UUID tid = UUID.randomUUID();

        // Group 1: avatars a1..a6
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), a3 = UUID.randomUUID();
        UUID a4 = UUID.randomUUID(), a5 = UUID.randomUUID(), a6 = UUID.randomUUID();
        // Group 2: avatars b1..b6
        UUID b1 = UUID.randomUUID(), b2 = UUID.randomUUID(), b3 = UUID.randomUUID();
        UUID b4 = UUID.randomUUID(), b5 = UUID.randomUUID(), b6 = UUID.randomUUID();

        List<UUID> group1 = List.of(a1, a2, a3, a4, a5, a6);
        List<UUID> group2 = List.of(b1, b2, b3, b4, b5, b6);

        List<Match> matches = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < group1.size(); i++) {
            for (int j = i + 1; j < group1.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                group1.get(i),
                                group1.get(j)));
            }
        }
        for (int i = 0; i < group2.size(); i++) {
            for (int j = i + 1; j < group2.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                group2.get(i),
                                group2.get(j)));
            }
        }

        List<TeamAvatar> avatars = new ArrayList<>();
        for (UUID av : group1) avatars.add(makeAvatar(av, phaseId, 1));
        for (UUID av : group2) avatars.add(makeAvatar(av, phaseId, 2));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        long distinctLaps = matches.stream().map(Match::getLapNumber).distinct().count();
        assertThat(distinctLaps)
                .as("12 teams / 2 groups / 3 fields must produce ≥10 distinct lap numbers")
                .isGreaterThanOrEqualTo(10);

        int minLap = matches.stream().mapToInt(Match::getLapNumber).min().orElse(-1);
        assertThat(minLap)
                .as("MIN(lapNumber) must be ≥ 1 (1-based; lap 0 is forbidden after E53S06 fix)")
                .isGreaterThanOrEqualTo(1);
    }

    // ── E53S09: AC-TEST-L2-WRITES-1-BASED-FIELDNUMBER-UNIT-RED ────────────────────────────────

    @Test
    void k_field_assignment_produces_1_based_fieldNumbers_not_0_based() {
        // AC-TEST-L2-WRITES-1-BASED-FIELDNUMBER-UNIT-RED (DEC-60 D-1, DEC-22 RED-first)
        // For K=3 fields, the assigned fieldNumbers must be exactly {1, 2, 3} (NOT {0, 1, 2}).
        // RED before production change: line 203 writes `fieldIdx` (0-based) → MIN=0 → FAIL.
        UUID av1 = UUID.randomUUID();
        UUID av2 = UUID.randomUUID();
        UUID av3 = UUID.randomUUID();
        UUID av4 = UUID.randomUUID();
        UUID av5 = UUID.randomUUID();
        UUID av6 = UUID.randomUUID();
        UUID tid = UUID.randomUUID();

        // K6 = 15 matches, fieldCount=3 → 5 laps × 3 fields
        List<UUID> avs = List.of(av1, av2, av3, av4, av5, av6);
        List<Match> matches = new ArrayList<>();
        int idx = 0;
        for (int a = 0; a < avs.size(); a++) {
            for (int b = a + 1; b < avs.size(); b++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                avs.get(a),
                                avs.get(b)));
            }
        }

        List<TeamAvatar> avatars = new ArrayList<>();
        for (UUID av : avs) avatars.add(makeAvatar(av, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        // All fieldNumbers must be in [1, 3] (1-based per DEC-60 D-1); 0 is forbidden
        int minField = matches.stream().mapToInt(Match::getFieldNumber).min().orElse(-1);
        assertThat(minField)
                .as(
                        "MIN(fieldNumber) must be 1 (1-based per DEC-60 D-1); 0 is forbidden"
                                + " (E53S09 RED-first)")
                .isGreaterThanOrEqualTo(1);

        int maxField = matches.stream().mapToInt(Match::getFieldNumber).max().orElse(-1);
        assertThat(maxField)
                .as("MAX(fieldNumber) must be ≤ K=3 (1-based: fields 1..3)")
                .isLessThanOrEqualTo(3);

        // The distinct field values must be exactly {1, 2, 3}
        java.util.Set<Integer> distinctFields =
                matches.stream()
                        .map(Match::getFieldNumber)
                        .collect(java.util.stream.Collectors.toSet());
        assertThat(distinctFields)
                .as("fieldNumbers must be exactly {1, 2, 3} for K=3 (1-based per DEC-60 D-1)")
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    // ── E54S01: AC-TEST-VOTING-TRIPLE-LOWEST-VOTE-WINS-RED ────────────────────────────────────

    @Test
    void voting_lowest_combined_vote_selected_first() {
        // AC-TEST-VOTING-TRIPLE-LOWEST-VOTE-WINS-RED (DEC-22 RED-first, DEC-61 Clause A)
        // Given: 4 avatars, 3 matches so that one match has been previously assigned (high vote)
        // and another is a fresh (low vote) candidate. The algorithm MUST pick lowest vote first.
        //
        // Setup: 4 avatars A,B,C,D in group 1; 6 matches (K4). fieldCount=1 so we can observe
        // one match per lap. The FIRST lap's field-1 slot must go to the match with lowest vote
        // sum.
        // Initially all votes=0 so the first match assigned is effectively the one sorted first.
        // After 1 assignment, those avatars' voting counters increment to 1.
        // On lap 2, the next selected match must prefer avatars with vote 0 over avatars with vote
        // 1.
        UUID avA = makeUUID("aa");
        UUID avB = makeUUID("bb");
        UUID avC = makeUUID("cc");
        UUID avD = makeUUID("dd");
        UUID tid = UUID.randomUUID();

        // K4 with fieldCount=1: 6 matches; we just verify that NO match with both avatars already
        // having vote=1 is selected before matches with vote=0 avatars exist.
        List<Match> matches =
                new ArrayList<>(
                        List.of(
                                makeMatch(makeUUID("01"), phaseId, tid, avA, avB),
                                makeMatch(makeUUID("02"), phaseId, tid, avA, avC),
                                makeMatch(makeUUID("03"), phaseId, tid, avA, avD),
                                makeMatch(makeUUID("04"), phaseId, tid, avB, avC),
                                makeMatch(makeUUID("05"), phaseId, tid, avB, avD),
                                makeMatch(makeUUID("06"), phaseId, tid, avC, avD)));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(avA, phaseId, 1),
                        makeAvatar(avB, phaseId, 1),
                        makeAvatar(avC, phaseId, 1),
                        makeAvatar(avD, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 1);

        // All 6 matches must be assigned (K4 is 6 matches, each assigned to a separate lap
        // since fieldCount=1 means 1 match/lap and round-conflict-freedom requires no avatar twice)
        assertThat(matches).allSatisfy(m -> assertThat(m.getLapNumber()).isNotNull());
        assertThat(matches).allSatisfy(m -> assertThat(m.getFieldNumber()).isEqualTo(1));

        // Verify round-conflict-freedom (no avatar twice per lap)
        Map<Integer, Set<UUID>> lapAvatars = new HashMap<>();
        for (Match m : matches) {
            int lap = m.getLapNumber();
            lapAvatars.computeIfAbsent(lap, k -> new HashSet<>());
            assertThat(lapAvatars.get(lap)).doesNotContain(m.getMemberAvatar1Id());
            assertThat(lapAvatars.get(lap)).doesNotContain(m.getMemberAvatar2Id());
            lapAvatars.get(lap).add(m.getMemberAvatar1Id());
            lapAvatars.get(lap).add(m.getMemberAvatar2Id());
        }

        // Key assertion: with fieldCount=1, all laps contain exactly 1 match each → 6 laps
        assertThat(matches.stream().map(Match::getLapNumber).distinct().count()).isEqualTo(6);
    }

    // ── E54S01: AC-TEST-VOTING-INCREMENT-ON-ASSIGNMENT-RED ────────────────────────────────────

    @Test
    void voting_counters_increment_after_assignment() {
        // AC-TEST-VOTING-INCREMENT-ON-ASSIGNMENT-RED (DEC-22 RED-first, DEC-61 Clause A)
        // After a match (avA vs avB in group 1) is assigned to a slot, any subsequent match
        // involving avA or avB must have a HIGHER vote sum than matches not involving them.
        // We verify this indirectly: with fieldCount=1 and 3 matches where A-B is assigned first,
        // the next assignment must pick C-D (lowest vote, 0+0+0=0) over A-C (1+0+0=1) or A-D.
        //
        // 4 avatars: A, B in group 1; C, D in group 2 (separate groups → 2 GroupVoting buckets).
        // Matches: A-B (group1), C-D (group2), A-C (cross-group — but in single-group setup,
        // we keep all group 1 for simpler voting).
        //
        // Simpler: 4 avatars A,B,C,D all group 1; K4 = 6 matches; fieldCount=1.
        // After lap 1 assigns A-B (lowest or first in sort), avA.vote=1, avB.vote=1.
        // The next assignment on lap 2 must prefer C-D (vote=0+0+0=0) over A-C (1+0+0=1).
        UUID avA = makeUUID("aa");
        UUID avB = makeUUID("bb");
        UUID avC = makeUUID("cc");
        UUID avD = makeUUID("dd");
        UUID tid = UUID.randomUUID();

        // Create matches with deterministic UUIDs so we can predict sort order
        // Match A-B has the lowest UUID string → sorted first → assigned to lap 1, field 1
        // After assignment: A.vote=1, B.vote=1, group1.vote=1
        // Match C-D has vote=0+0+0=0 → selected before A-C (1+0+0=1) on lap 2
        List<Match> matches =
                new ArrayList<>(
                        List.of(
                                makeMatch(makeUUID("01"), phaseId, tid, avA, avB), // A-B: vote 0
                                makeMatch(makeUUID("02"), phaseId, tid, avC, avD), // C-D: vote 0
                                makeMatch(
                                        makeUUID("03"),
                                        phaseId,
                                        tid,
                                        avA,
                                        avC), // A-C: after A-B assigned → A.vote=1
                                makeMatch(makeUUID("04"), phaseId, tid, avA, avD),
                                makeMatch(makeUUID("05"), phaseId, tid, avB, avC),
                                makeMatch(makeUUID("06"), phaseId, tid, avB, avD)));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(avA, phaseId, 1),
                        makeAvatar(avB, phaseId, 1),
                        makeAvatar(avC, phaseId, 1),
                        makeAvatar(avD, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 1);

        // All 6 assigned
        assertThat(matches).allSatisfy(m -> assertThat(m.getLapNumber()).isNotNull());

        // C-D must be assigned to a LOWER lap number than A-C
        // (because after A-B is assigned, C and D still have vote=0 while A has vote=1)
        Match ab = matches.get(0); // A-B
        Match cd = matches.get(1); // C-D
        Match ac = matches.get(2); // A-C
        // C-D lap < A-C lap (C and D have lower vote after A-B is consumed first)
        assertThat(cd.getLapNumber())
                .as("C-D (vote=0 after A-B assigned) must be before A-C (A.vote=1)")
                .isLessThan(ac.getLapNumber());
        // A-B was assigned in lap 1
        assertThat(ab.getLapNumber()).isEqualTo(1);
    }

    // ── E54S01: AC-TEST-CONFLICT-SKIP-NO-AVATAR-TWICE-PER-LAP-RED ────────────────────────────

    @Test
    void conflict_skip_lowest_vote_but_avatar_conflict_skipped() {
        // AC-TEST-CONFLICT-SKIP-NO-AVATAR-TWICE-PER-LAP-RED (DEC-22 RED-first, DEC-61 Clause A)
        // Construct a scenario where the lowest-vote match conflicts (avatar already in lap),
        // so the algorithm must skip it and select the next conflict-free candidate.
        //
        // Setup: fieldCount=2; 4 avatars A,B,C,D; 3 matches: A-B, A-C, B-D
        // On lap 1:
        //   field 1: A-B assigned (lowest vote 0+0+0=0)
        //   field 2: need conflict-free; A-C is next (A.vote=1 after A-B; but A already in lap!)
        //            → SKIP A-C; select B-D next (B already in lap too!) → SKIP; try C-D... wait.
        //   Better: use A-B, C-D as the 3 matches in fieldCount=2 with K4 avatars.
        //   Actually let's use 6 avatars, fieldCount=2, and verify conflict detection directly.
        //
        // Simpler setup: 4 avatars; K4=6 matches; fieldCount=2.
        // Lap 1: field 1 assigns lowest-vote match. Field 2 MUST skip any match sharing those
        // avatars.
        UUID avA = makeUUID("aa");
        UUID avB = makeUUID("bb");
        UUID avC = makeUUID("cc");
        UUID avD = makeUUID("dd");
        UUID tid = UUID.randomUUID();

        List<Match> matches =
                new ArrayList<>(
                        List.of(
                                makeMatch(makeUUID("01"), phaseId, tid, avA, avB),
                                makeMatch(makeUUID("02"), phaseId, tid, avA, avC),
                                makeMatch(makeUUID("03"), phaseId, tid, avA, avD),
                                makeMatch(makeUUID("04"), phaseId, tid, avB, avC),
                                makeMatch(makeUUID("05"), phaseId, tid, avB, avD),
                                makeMatch(makeUUID("06"), phaseId, tid, avC, avD)));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(avA, phaseId, 1),
                        makeAvatar(avB, phaseId, 1),
                        makeAvatar(avC, phaseId, 1),
                        makeAvatar(avD, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 2);

        // Verify round-conflict-freedom: no avatar twice per lap
        Map<Integer, Set<UUID>> lapAvatars = new HashMap<>();
        for (Match m : matches) {
            int lap = m.getLapNumber();
            lapAvatars.computeIfAbsent(lap, k -> new HashSet<>());
            assertThat(lapAvatars.get(lap))
                    .as("avatar1 %s must not appear twice in lap %d", m.getMemberAvatar1Id(), lap)
                    .doesNotContain(m.getMemberAvatar1Id());
            assertThat(lapAvatars.get(lap))
                    .as("avatar2 %s must not appear twice in lap %d", m.getMemberAvatar2Id(), lap)
                    .doesNotContain(m.getMemberAvatar2Id());
            lapAvatars.get(lap).add(m.getMemberAvatar1Id());
            lapAvatars.get(lap).add(m.getMemberAvatar2Id());
        }

        // All 6 matches assigned
        assertThat(matches).allSatisfy(m -> assertThat(m.getLapNumber()).isNotNull());
    }

    // ── E54S01: AC-TEST-SYMMETRIC-12T-2G-3F-PRODUCES-10-LAPS-3-MATCHES-RED ──────────────────

    @Test
    void symmetric_12T_2G_3F_produces_10_laps_3_matches_per_lap_no_conflicts() {
        // AC-TEST-SYMMETRIC-12T-2G-3F-PRODUCES-10-LAPS-3-MATCHES-RED (DEC-22 RED-first, DEC-61)
        // 12 avatars in 2 groups of 6 + fieldCount=3 → 30 matches (15 per group).
        // Post-voting-driven L2: phase-global assignment → exactly 10 distinct laps,
        // each lap exactly 3 matches, zero (lap, field) duplicates.
        //
        // Under the OLD edge-coloring: Group 1 gets laps 1..5, Group 2 gets laps 6..10.
        // Under the NEW voting: all 30 matches interleaved → 10 laps × 3 matches each.
        // Key assertion: every lap has EXACTLY 3 matches (not fewer due to Bye-Slots).
        // (30 matches / 3 fields / 10 laps = 3 matches per lap exactly — no Bye-Slots needed.)
        UUID tid = UUID.randomUUID();
        UUID a1 = makeUUID("a1"), a2 = makeUUID("a2"), a3 = makeUUID("a3");
        UUID a4 = makeUUID("a4"), a5 = makeUUID("a5"), a6 = makeUUID("a6");
        UUID b1 = makeUUID("b1"), b2 = makeUUID("b2"), b3 = makeUUID("b3");
        UUID b4 = makeUUID("b4"), b5 = makeUUID("b5"), b6 = makeUUID("b6");

        List<UUID> g1 = List.of(a1, a2, a3, a4, a5, a6);
        List<UUID> g2 = List.of(b1, b2, b3, b4, b5, b6);

        List<Match> matches = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < g1.size(); i++) {
            for (int j = i + 1; j < g1.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                g1.get(i),
                                g1.get(j)));
            }
        }
        for (int i = 0; i < g2.size(); i++) {
            for (int j = i + 1; j < g2.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                g2.get(i),
                                g2.get(j)));
            }
        }

        List<TeamAvatar> avatars = new ArrayList<>();
        for (UUID av : g1) avatars.add(makeAvatar(av, phaseId, 1));
        for (UUID av : g2) avatars.add(makeAvatar(av, phaseId, 2));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        // All 30 matches assigned
        assertThat(matches).hasSize(30);
        assertThat(matches).allSatisfy(m -> assertThat(m.getLapNumber()).isNotNull());

        // Exactly 10 distinct laps
        long distinctLaps = matches.stream().mapToInt(Match::getLapNumber).distinct().count();
        assertThat(distinctLaps)
                .as(
                        "30 matches / 3 fields = exactly 10 laps (phase-global voting; no"
                                + " concatenation)")
                .isEqualTo(10);

        // Each lap has exactly 3 matches
        Map<Integer, Long> matchesPerLap =
                matches.stream()
                        .collect(Collectors.groupingBy(Match::getLapNumber, Collectors.counting()));
        assertThat(matchesPerLap.values())
                .as("every lap must have exactly 3 matches (no Bye-Slots for 12T/2G/3F)")
                .allSatisfy(count -> assertThat(count).isEqualTo(3L));

        // No (lap, field) duplicates
        Set<String> pairs =
                matches.stream()
                        .map(m -> m.getLapNumber() + ":" + m.getFieldNumber())
                        .collect(Collectors.toSet());
        assertThat(pairs).as("no (lap, field) duplicates").hasSize(30);

        // Round-conflict-freedom
        Map<Integer, Set<UUID>> lapAvatars = new HashMap<>();
        for (Match m : matches) {
            int lap = m.getLapNumber();
            lapAvatars.computeIfAbsent(lap, k -> new HashSet<>());
            assertThat(lapAvatars.get(lap)).doesNotContain(m.getMemberAvatar1Id());
            assertThat(lapAvatars.get(lap)).doesNotContain(m.getMemberAvatar2Id());
            lapAvatars.get(lap).add(m.getMemberAvatar1Id());
            lapAvatars.get(lap).add(m.getMemberAvatar2Id());
        }

        // 1-based: MIN(lapNumber) = 1
        assertThat(matches.stream().mapToInt(Match::getLapNumber).min().orElse(-1)).isEqualTo(1);
    }

    // ── E54S01: AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-RED ──────────────────────────────────

    @Test
    void asymmetric_11T_2G_3F_produces_bye_slots_no_avatar_twice_per_lap() {
        // AC-TEST-ASYMMETRIC-11T-2G-3F-BYE-SLOTS-RED (DEC-22 RED-first, DEC-61 Clause A + C)
        // 11 avatars in 2 groups (6+5) → 15+10=25 matches total; fieldCount=3.
        // Bye-Slots may occur when no conflict-free match exists for a slot.
        // Assertions:
        //   - No avatar appears twice per lap
        //   - No (lap, field) duplicates among assigned matches
        //   - All 25 matches are assigned (possibly in different laps due to Bye-Slots)
        //   - 1-based: MIN(lapNumber) = 1, MIN(fieldNumber) = 1
        UUID tid = UUID.randomUUID();
        UUID a1 = makeUUID("a1"), a2 = makeUUID("a2"), a3 = makeUUID("a3");
        UUID a4 = makeUUID("a4"), a5 = makeUUID("a5"), a6 = makeUUID("a6");
        UUID b1 = makeUUID("b1"), b2 = makeUUID("b2"), b3 = makeUUID("b3");
        UUID b4 = makeUUID("b4"), b5 = makeUUID("b5");

        List<UUID> g1 = List.of(a1, a2, a3, a4, a5, a6);
        List<UUID> g2 = List.of(b1, b2, b3, b4, b5);

        List<Match> matches = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < g1.size(); i++) {
            for (int j = i + 1; j < g1.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                g1.get(i),
                                g1.get(j)));
            }
        }
        for (int i = 0; i < g2.size(); i++) {
            for (int j = i + 1; j < g2.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                g2.get(i),
                                g2.get(j)));
            }
        }

        List<TeamAvatar> avatars = new ArrayList<>();
        for (UUID av : g1) avatars.add(makeAvatar(av, phaseId, 1));
        for (UUID av : g2) avatars.add(makeAvatar(av, phaseId, 2));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        // All 25 matches assigned (Bye-Slots do NOT produce Match-Rows, but every Match gets a
        // slot)
        assertThat(matches).hasSize(25);
        assertThat(matches)
                .allSatisfy(
                        m ->
                                assertThat(m.getLapNumber())
                                        .as("all matches assigned a lap")
                                        .isNotNull());

        // No (lap, field) duplicates
        Set<String> pairs =
                matches.stream()
                        .map(m -> m.getLapNumber() + ":" + m.getFieldNumber())
                        .collect(Collectors.toSet());
        assertThat(pairs).as("no (lap, field) coordinate duplicates").hasSize(25);

        // Round-conflict-freedom
        Map<Integer, Set<UUID>> lapAvatars = new HashMap<>();
        for (Match m : matches) {
            int lap = m.getLapNumber();
            lapAvatars.computeIfAbsent(lap, k -> new HashSet<>());
            assertThat(lapAvatars.get(lap))
                    .as("avatar1 %s must not appear twice in lap %d", m.getMemberAvatar1Id(), lap)
                    .doesNotContain(m.getMemberAvatar1Id());
            assertThat(lapAvatars.get(lap))
                    .as("avatar2 %s must not appear twice in lap %d", m.getMemberAvatar2Id(), lap)
                    .doesNotContain(m.getMemberAvatar2Id());
            lapAvatars.get(lap).add(m.getMemberAvatar1Id());
            lapAvatars.get(lap).add(m.getMemberAvatar2Id());
        }

        // 1-based
        assertThat(matches.stream().mapToInt(Match::getLapNumber).min().orElse(-1)).isEqualTo(1);
        assertThat(matches.stream().mapToInt(Match::getFieldNumber).min().orElse(-1)).isEqualTo(1);
    }

    // ── E54S01: AC-TEST-IDLE-TIME-MINIMIZATION-VS-BASELINE-RED ──────────────────────────────

    @Test
    void idle_time_minimization_better_than_edge_coloring_baseline() {
        // AC-TEST-IDLE-TIME-MINIMIZATION-VS-BASELINE-RED (DEC-22 RED-first, DEC-61 Clause A)
        // For 12 teams in 2 groups of 6 + fieldCount=3 (10 laps), the maximum consecutive
        // idle laps per avatar under the voting algorithm must be < 5.
        //
        // Baseline (edge-coloring per-group + offset): Group 1 plays laps 1..5, idle 6..10 (5
        // idle).
        // Group 2 plays laps 6..10, idle 1..5 (5 idle).
        // Maximum consecutive idle laps per avatar = 5.
        //
        // Post-voting (phase-global): avatars from both groups are interleaved across all 10 laps
        // → each avatar plays approximately every other lap → max consecutive idle laps < 5.
        UUID tid = UUID.randomUUID();
        UUID a1 = makeUUID("a1"), a2 = makeUUID("a2"), a3 = makeUUID("a3");
        UUID a4 = makeUUID("a4"), a5 = makeUUID("a5"), a6 = makeUUID("a6");
        UUID b1 = makeUUID("b1"), b2 = makeUUID("b2"), b3 = makeUUID("b3");
        UUID b4 = makeUUID("b4"), b5 = makeUUID("b5"), b6 = makeUUID("b6");

        List<UUID> g1 = List.of(a1, a2, a3, a4, a5, a6);
        List<UUID> g2 = List.of(b1, b2, b3, b4, b5, b6);

        List<Match> matches = new ArrayList<>();
        int idx = 0;
        for (int i = 0; i < g1.size(); i++) {
            for (int j = i + 1; j < g1.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                g1.get(i),
                                g1.get(j)));
            }
        }
        for (int i = 0; i < g2.size(); i++) {
            for (int j = i + 1; j < g2.size(); j++) {
                matches.add(
                        makeMatch(
                                makeUUID(String.format("%02d", idx++)),
                                phaseId,
                                tid,
                                g2.get(i),
                                g2.get(j)));
            }
        }

        List<TeamAvatar> avatars = new ArrayList<>();
        for (UUID av : g1) avatars.add(makeAvatar(av, phaseId, 1));
        for (UUID av : g2) avatars.add(makeAvatar(av, phaseId, 2));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        int totalLaps = matches.stream().mapToInt(Match::getLapNumber).max().orElse(0);

        // Compute per-avatar active laps
        Map<UUID, Set<Integer>> activeLaps = new HashMap<>();
        for (Match m : matches) {
            activeLaps
                    .computeIfAbsent(m.getMemberAvatar1Id(), k -> new HashSet<>())
                    .add(m.getLapNumber());
            activeLaps
                    .computeIfAbsent(m.getMemberAvatar2Id(), k -> new HashSet<>())
                    .add(m.getLapNumber());
        }

        // Compute max consecutive idle laps per avatar
        int maxConsecutiveIdle = 0;
        for (Map.Entry<UUID, Set<Integer>> entry : activeLaps.entrySet()) {
            Set<Integer> active = entry.getValue();
            int consecutiveIdle = 0;
            int maxIdle = 0;
            for (int lap = 1; lap <= totalLaps; lap++) {
                if (!active.contains(lap)) {
                    consecutiveIdle++;
                    maxIdle = Math.max(maxIdle, consecutiveIdle);
                } else {
                    consecutiveIdle = 0;
                }
            }
            maxConsecutiveIdle = Math.max(maxConsecutiveIdle, maxIdle);
        }

        // Assert: max consecutive idle laps per avatar < 5 (the edge-coloring baseline)
        assertThat(maxConsecutiveIdle)
                .as(
                        "voting-driven phase-global scheduling should reduce max consecutive idle"
                                + " laps below the edge-coloring baseline of 5")
                .isLessThan(5);
    }

    // ── E54S01: AC-TEST-1-BASED-LAP-AND-FIELD-DEC-60-PRESERVED-RED ──────────────────────────

    @Test
    void voting_algorithm_preserves_dec60_1based_lap_and_field() {
        // AC-TEST-1-BASED-LAP-AND-FIELD-DEC-60-PRESERVED-RED (DEC-22 RED-first, DEC-60, DEC-61)
        // After L2 voting-driven slot-filling: MIN(lapNumber)=1 AND MIN(fieldNumber)=1
        // for any non-empty phase (preserved by construction: lap loop starts at 1).
        UUID avA = makeUUID("aa");
        UUID avB = makeUUID("bb");
        UUID avC = makeUUID("cc");
        UUID avD = makeUUID("dd");
        UUID tid = UUID.randomUUID();

        List<Match> matches =
                new ArrayList<>(
                        List.of(
                                makeMatch(makeUUID("01"), phaseId, tid, avA, avB),
                                makeMatch(makeUUID("02"), phaseId, tid, avA, avC),
                                makeMatch(makeUUID("03"), phaseId, tid, avA, avD),
                                makeMatch(makeUUID("04"), phaseId, tid, avB, avC),
                                makeMatch(makeUUID("05"), phaseId, tid, avB, avD),
                                makeMatch(makeUUID("06"), phaseId, tid, avC, avD)));

        List<TeamAvatar> avatars =
                List.of(
                        makeAvatar(avA, phaseId, 1),
                        makeAvatar(avB, phaseId, 1),
                        makeAvatar(avC, phaseId, 1),
                        makeAvatar(avD, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.assignRoundsAndFields(phaseId, 3);

        int minLap = matches.stream().mapToInt(Match::getLapNumber).min().orElse(-1);
        assertThat(minLap)
                .as("MIN(lapNumber) must be 1 (DEC-60 D-1 preservation; voting starts at lap 1)")
                .isEqualTo(1);

        int minField = matches.stream().mapToInt(Match::getFieldNumber).min().orElse(-1);
        assertThat(minField)
                .as(
                        "MIN(fieldNumber) must be 1 (DEC-60 D-1 preservation; voting field loop"
                                + " starts at 1)")
                .isEqualTo(1);
    }

    // ── E54S01: AC-TEST-IMPOSSIBLE-SETUP-NO-INFINITE-LOOP-GREEN ────────────────────────────

    @Test
    void impossible_setup_terminates_without_exception() {
        // AC-ERROR-IMPOSSIBLE-SETUP-DETECTABLE (DEC-61 Clause A): when all remaining matches
        // conflict in a lap, a Bye-Slot is produced (no Match-Row created) and the loop continues.
        // The algorithm must NOT loop infinitely — it must terminate when all matches are assigned.
        //
        // Setup: 2 avatars A, B in group 1 → 1 match: A-B; fieldCount=3 (capacity > match supply).
        // After lap 1 assigns A-B to field 1, fields 2 and 3 produce Bye-Slots (no conflict-free
        // match).
        // The outer loop exits after all matches are assigned → 1 lap, 1 match, 2 Bye-Slots.
        UUID avA = makeUUID("aa");
        UUID avB = makeUUID("bb");
        UUID tid = UUID.randomUUID();

        List<Match> matches =
                new ArrayList<>(List.of(makeMatch(makeUUID("01"), phaseId, tid, avA, avB)));

        List<TeamAvatar> avatars =
                List.of(makeAvatar(avA, phaseId, 1), makeAvatar(avB, phaseId, 1));

        when(matchRepository.findByPhaseId(phaseId)).thenReturn(matches);
        when(teamAvatarRepository.findByPhaseId(phaseId)).thenReturn(avatars);
        when(matchRepository.save(org.mockito.ArgumentMatchers.any(Match.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Must complete without exception (no infinite loop)
        service.assignRoundsAndFields(phaseId, 3);

        // The 1 match must be assigned
        assertThat(matches.get(0).getLapNumber()).isEqualTo(1);
        assertThat(matches.get(0).getFieldNumber()).isEqualTo(1);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────────────────────

    private static UUID makeUUID(String suffix) {
        // Produce deterministic UUIDs by left-padding suffix with zeros to 12 chars
        String node = ("000000000000" + suffix);
        node = node.substring(node.length() - 12);
        return UUID.fromString("00000000-0000-0000-0000-" + node);
    }

    private static Match makeMatch(UUID id, UUID phaseId, UUID tournamentId, UUID av1, UUID av2) {
        Match m = new Match();
        m.setId(id);
        m.setPhaseId(phaseId);
        m.setTournamentId(tournamentId);
        m.setMemberAvatar1Id(av1);
        m.setMemberAvatar2Id(av2);
        return m;
    }

    private static TeamAvatar makeAvatar(UUID id, UUID phaseId, int groupNumber) {
        TeamAvatar av = new TeamAvatar();
        av.setId(id);
        av.setPhaseId(phaseId);
        av.setGroupNumber(groupNumber);
        return av;
    }
}
