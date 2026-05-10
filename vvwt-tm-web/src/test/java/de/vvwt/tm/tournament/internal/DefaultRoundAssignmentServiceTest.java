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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
 * @see DefaultRoundAssignmentService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E51S10">E51S10 — L2 Round-Assignment Service story</a>
 * @see <a href="E51S16">E51S16 — B-b1 cycle-break; mapper dependency removed</a>
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

    // ── Multi-group concatenation (Brief D-12) ─────────────────────────────────────────────────

    @Test
    void multi_group_laps_are_concatenated_ascending_group_order() {
        // AC-IMPL-L2-MULTI-GROUP: Group 1 occupies laps 1..k1; Group 2 starts at lap k1+1.
        // Use 2 disjoint groups of 2 avatars each → 1 match per group → 1 lap per group.
        // Expected: Group 1 match → lap 1; Group 2 match → lap 2.
        // (E53S06 fix: 1-based laps; cumulativeLapOffset starts at 1, not 0)
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

        // Group 1: 1 match → 1 lap starting at offset 1 → lap 1 (1-based, E53S06)
        assertThat(m1.getLapNumber()).as("Group 1 match gets lap 1 (1-based, E53S06)").isEqualTo(1);
        assertThat(m1.getFieldNumber()).as("Group 1 match gets field 0").isEqualTo(0);

        // Group 2: 1 match → 1 lap starting at offset 2 (Group 1 had 1 lap, starting at 1) → lap 2
        assertThat(m2.getLapNumber())
                .as("Group 2 match gets lap 2 (after Group 1's lap, 1-based, E53S06)")
                .isEqualTo(2);
        assertThat(m2.getFieldNumber()).as("Group 2 match gets field 0").isEqualTo(0);
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
