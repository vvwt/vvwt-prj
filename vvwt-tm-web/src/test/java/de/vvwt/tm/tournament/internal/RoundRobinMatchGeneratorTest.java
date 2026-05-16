package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchGenerator;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoundRobinMatchGenerator} (AC-TDD-RoundRobinMatchGenerator +
 * AC-FAIRNESS-PROPERTY, E21S08).
 *
 * <p>Extends {@link MatchGeneratorAbstractTest} for the base contract and adds RoundRobin-specific
 * tests: fairness property (N*(N-1)/2 matches, each avatar in N-1 matches), odd-N phantom handling,
 * duplicate ID rejection.
 *
 * <p>Source: inventory row 258 — {@code de.vvwt.tm.tournament.internal.RoundRobinMatchGenerator}.
 */
class RoundRobinMatchGeneratorTest extends MatchGeneratorAbstractTest {

    private TournamentRepository tournamentRepository;
    private RoundRobinMatchGenerator generator;

    @BeforeEach
    void setUp() {
        tournamentRepository = mock(TournamentRepository.class);
        Tournament tournament = new Tournament();
        tournament.setMatchFormat(MatchFormat.BEST_OF_3.name());
        when(tournamentRepository.findById(any(UUID.class))).thenReturn(Optional.of(tournament));
        generator = new RoundRobinMatchGenerator(tournamentRepository);
    }

    @Override
    protected MatchGenerator newGenerator() {
        TournamentRepository repo = mock(TournamentRepository.class);
        Tournament t = new Tournament();
        t.setMatchFormat(MatchFormat.BEST_OF_1.name());
        when(repo.findById(any(UUID.class))).thenReturn(Optional.of(t));
        return new RoundRobinMatchGenerator(repo);
    }

    @Override
    protected String expectedKeyId() {
        return "roundRobin";
    }

    @Override
    protected boolean expectedIsLastPhaseGenerator() {
        return false;
    }

    @Override
    protected Phase minimalPhase() {
        Phase p = super.minimalPhase();
        p.setTournamentId(UUID.randomUUID());
        return p;
    }

    // -------------------------------------------------------------------------
    // Fewer than 2 avatars → empty list
    // -------------------------------------------------------------------------

    @Test
    void generate_zeroAvatars_returnsEmptyList() {
        Phase phase = minimalPhase();
        List<Match> result = generator.generate(phase, List.of());
        assertThat(result).isEmpty();
    }

    @Test
    void generate_oneAvatar_returnsEmptyList() {
        Phase phase = minimalPhase();
        List<Match> result = generator.generate(phase, List.of(minimalAvatar(UUID.randomUUID())));
        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Null entry in avatars list → IAE
    // -------------------------------------------------------------------------

    @Test
    void generate_nullEntryInAvatars_throwsIAE() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatarsWithNull = new ArrayList<>();
        avatarsWithNull.add(minimalAvatar(UUID.randomUUID()));
        avatarsWithNull.add(null);

        assertThatThrownBy(() -> generator.generate(phase, avatarsWithNull))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Duplicate avatar ID → IAE
    // -------------------------------------------------------------------------

    @Test
    void generate_duplicateAvatarIds_throwsIAE() {
        Phase phase = minimalPhase();
        UUID duplicateId = UUID.randomUUID();
        List<TeamAvatar> duplicates =
                List.of(minimalAvatar(duplicateId), minimalAvatar(duplicateId));

        assertThatThrownBy(() -> generator.generate(phase, duplicates))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(duplicateId.toString());
    }

    // -------------------------------------------------------------------------
    // Basic: 2 teams → 1 match
    // -------------------------------------------------------------------------

    @Test
    void generate_twoAvatars_producesOneMatch() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars =
                List.of(minimalAvatar(UUID.randomUUID()), minimalAvatar(UUID.randomUUID()));

        List<Match> matches = generator.generate(phase, avatars);

        assertThat(matches).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Basic: 4 teams → 6 matches
    // -------------------------------------------------------------------------

    @Test
    void generate_fourAvatars_producesSixMatches() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildAvatarList(4);

        List<Match> matches = generator.generate(phase, avatars);

        assertThat(matches).hasSize(6);
    }

    // -------------------------------------------------------------------------
    // Odd N: 3 teams → 3 matches (phantom bye)
    // -------------------------------------------------------------------------

    @Test
    void generate_threeAvatars_producesThreeMatches() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildAvatarList(3);

        List<Match> matches = generator.generate(phase, avatars);

        assertThat(matches).hasSize(3);
    }

    // -------------------------------------------------------------------------
    // Each avatar appears in exactly N-1 matches (for N=4)
    // -------------------------------------------------------------------------

    @Test
    void generate_fourAvatars_eachAvatarInThreeMatches() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildAvatarList(4);
        Map<UUID, Integer> appearanceCount = new HashMap<>();
        for (TeamAvatar a : avatars) {
            appearanceCount.put(a.getId(), 0);
        }

        List<Match> matches = generator.generate(phase, avatars);

        for (Match m : matches) {
            appearanceCount.merge(m.getMemberAvatar1Id(), 1, Integer::sum);
            appearanceCount.merge(m.getMemberAvatar2Id(), 1, Integer::sum);
        }

        for (Map.Entry<UUID, Integer> e : appearanceCount.entrySet()) {
            assertThat(e.getValue())
                    .as("Avatar %s should appear in 3 matches", e.getKey())
                    .isEqualTo(3);
        }
    }

    // -------------------------------------------------------------------------
    // AC-FAIRNESS-PROPERTY (jqwik): N ∈ [2, 16] → N*(N-1)/2 matches, each avatar in N-1 matches
    // -------------------------------------------------------------------------

    /**
     * Property-based fairness test (AC-FAIRNESS-PROPERTY, E21S08).
     *
     * <p>For any N teams where 2 ≤ N ≤ 16, the {@link RoundRobinMatchGenerator} must produce
     * exactly {@code N*(N-1)/2} matches, and each team must appear in exactly {@code N-1} matches.
     *
     * <p>Brief O-8 reference: this property anchors the correctness of match generation — a
     * necessary precondition for PhasePreparationService to function correctly.
     */
    @Property(tries = 20)
    void fairnessProperty_matchCountAndAppearanceCount(@ForAll @IntRange(min = 2, max = 16) int n) {
        // Jqwik creates a fresh instance per @Property — re-initialize generator inline
        TournamentRepository repo = mock(TournamentRepository.class);
        Tournament t = new Tournament();
        t.setMatchFormat(MatchFormat.BEST_OF_3.name());
        when(repo.findById(any(UUID.class))).thenReturn(Optional.of(t));
        RoundRobinMatchGenerator gen = new RoundRobinMatchGenerator(repo);

        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildAvatarList(n);

        List<Match> matches = gen.generate(phase, avatars);

        int expectedMatchCount = n * (n - 1) / 2;
        assertThat(matches)
                .as("N=%d should produce %d matches", n, expectedMatchCount)
                .hasSize(expectedMatchCount);

        // Each avatar must appear in exactly N-1 matches
        Map<UUID, Integer> appearances = new HashMap<>();
        for (TeamAvatar a : avatars) {
            appearances.put(a.getId(), 0);
        }
        for (Match m : matches) {
            appearances.merge(m.getMemberAvatar1Id(), 1, Integer::sum);
            appearances.merge(m.getMemberAvatar2Id(), 1, Integer::sum);
        }
        int expectedAppearances = n - 1;
        for (Map.Entry<UUID, Integer> e : appearances.entrySet()) {
            assertThat(e.getValue())
                    .as(
                            "N=%d: avatar %s should appear in %d matches",
                            n, e.getKey(), expectedAppearances)
                    .isEqualTo(expectedAppearances);
        }
    }

    // -------------------------------------------------------------------------
    // set_limit derived from tournament MatchFormat
    // -------------------------------------------------------------------------

    @Test
    void generate_setLimitDerivedFromMatchFormat() {
        TournamentRepository repo = mock(TournamentRepository.class);
        Tournament t = new Tournament();
        t.setMatchFormat(MatchFormat.BEST_OF_5.name());
        when(repo.findById(any(UUID.class))).thenReturn(Optional.of(t));
        RoundRobinMatchGenerator gen = new RoundRobinMatchGenerator(repo);

        Phase phase = minimalPhase();
        List<Match> matches = gen.generate(phase, buildAvatarList(2));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).getSetLimit()).isEqualTo(MatchFormat.BEST_OF_5.getMaxSets());
    }

    // =========================================================================
    // E51S09 — Group-aware partitioning tests (RED-first, DEC-22)
    // =========================================================================

    // -------------------------------------------------------------------------
    // AC-TEST-12-TEAMS-2-GROUPS-PRODUCES-30-MATCHES-RED
    // -------------------------------------------------------------------------

    /**
     * RED-first test (AC-TEST-12-TEAMS-2-GROUPS-PRODUCES-30-MATCHES-RED, E51S09).
     *
     * <p>Given 12 avatars distributed 6/6 across groupNumber=1 and groupNumber=2, {@code
     * generate()} must produce exactly 30 matches (15 per group). Each match's two avatars must
     * share the same {@code groupNumber}.
     *
     * <p>Fails before fix because current code produces 66 cross-group matches.
     */
    @Test
    void generate_twelveAvatarsTwoGroupsSixEach_produces30Matches() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildGroupedAvatarList(new int[] {6, 6});

        List<Match> matches = generator.generate(phase, avatars);

        assertThat(matches)
                .as("12 avatars in 2 groups of 6 must produce 30 matches (15+15)")
                .hasSize(30);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-12-TEAMS-3-GROUPS-PRODUCES-18-MATCHES-RED
    // -------------------------------------------------------------------------

    /**
     * RED-first test (AC-TEST-12-TEAMS-3-GROUPS-PRODUCES-18-MATCHES-RED, E51S09).
     *
     * <p>Given 12 avatars in 3 groups of 4, {@code generate()} must produce 18 matches (3×6).
     */
    @Test
    void generate_twelveAvatarsThreeGroupsFourEach_produces18Matches() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildGroupedAvatarList(new int[] {4, 4, 4});

        List<Match> matches = generator.generate(phase, avatars);

        assertThat(matches)
                .as("12 avatars in 3 groups of 4 must produce 18 matches (6+6+6)")
                .hasSize(18);
    }

    // -------------------------------------------------------------------------
    // AC-TEST-NO-CROSS-GROUP-PAIRINGS-RED
    // -------------------------------------------------------------------------

    /**
     * RED-first test (AC-TEST-NO-CROSS-GROUP-PAIRINGS-RED, E51S09).
     *
     * <p>For any generated match, the two avatars must share the same {@code groupNumber}. Fails
     * before fix because the flat circle-method pairs avatars regardless of group.
     */
    @Test
    void generate_twoGroups_noCrossGroupPairings() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildGroupedAvatarList(new int[] {6, 6});
        // Build a lookup from avatarId → groupNumber
        Map<UUID, Integer> avatarToGroup = new HashMap<>();
        for (TeamAvatar a : avatars) {
            avatarToGroup.put(a.getId(), a.getGroupNumber());
        }

        List<Match> matches = generator.generate(phase, avatars);

        for (Match m : matches) {
            int group1 = avatarToGroup.get(m.getMemberAvatar1Id());
            int group2 = avatarToGroup.get(m.getMemberAvatar2Id());
            assertThat(group1)
                    .as(
                            "Match avatars must share the same groupNumber (got %d vs %d)",
                            group1, group2)
                    .isEqualTo(group2);
        }
    }

    // -------------------------------------------------------------------------
    // AC-TEST-PER-GROUP-ROUND-ROBIN-COMPLETENESS-RED
    // -------------------------------------------------------------------------

    /**
     * RED-first test (AC-TEST-PER-GROUP-ROUND-ROBIN-COMPLETENESS-RED, E51S09).
     *
     * <p>For each group, every distinct unordered pair of avatars in that group appears exactly
     * once in the match-list. Fails before fix because circle-method runs on the flat 12-avatar
     * list and produces inter-group pairs.
     */
    @Test
    void generate_twoGroups_perGroupRoundRobinCompleteness() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildGroupedAvatarList(new int[] {6, 6});
        // Group avatars by groupNumber
        Map<Integer, List<UUID>> byGroup =
                avatars.stream()
                        .collect(
                                Collectors.groupingBy(
                                        TeamAvatar::getGroupNumber,
                                        Collectors.mapping(
                                                TeamAvatar::getId, Collectors.toList())));

        List<Match> matches = generator.generate(phase, avatars);

        for (Map.Entry<Integer, List<UUID>> entry : byGroup.entrySet()) {
            int groupNum = entry.getKey();
            List<UUID> groupAvatarIds = entry.getValue();
            // Build expected pair set for this group
            Set<String> expectedPairs = new HashSet<>();
            for (int i = 0; i < groupAvatarIds.size(); i++) {
                for (int j = i + 1; j < groupAvatarIds.size(); j++) {
                    String id1 = groupAvatarIds.get(i).toString();
                    String id2 = groupAvatarIds.get(j).toString();
                    // Canonical form: smaller string first
                    expectedPairs.add(id1.compareTo(id2) < 0 ? id1 + "|" + id2 : id2 + "|" + id1);
                }
            }
            // Collect actual pairs for this group
            Set<UUID> groupIdSet = new HashSet<>(groupAvatarIds);
            Set<String> actualPairs = new HashSet<>();
            for (Match m : matches) {
                if (groupIdSet.contains(m.getMemberAvatar1Id())
                        && groupIdSet.contains(m.getMemberAvatar2Id())) {
                    String id1 = m.getMemberAvatar1Id().toString();
                    String id2 = m.getMemberAvatar2Id().toString();
                    actualPairs.add(id1.compareTo(id2) < 0 ? id1 + "|" + id2 : id2 + "|" + id1);
                }
            }
            assertThat(actualPairs)
                    .as("Group %d must have all-pairs round-robin completeness", groupNum)
                    .containsExactlyInAnyOrderElementsOf(expectedPairs);
        }
    }

    // -------------------------------------------------------------------------
    // AC-TEST-MATCH-LAP-FIELD-NULL-AFTER-L1-RED
    // -------------------------------------------------------------------------

    /**
     * RED-first test (AC-TEST-MATCH-LAP-FIELD-NULL-AFTER-L1-RED, E51S09).
     *
     * <p>Every persisted Match produced by L1 must have {@code lapNumber=null} and {@code
     * fieldNumber=null}. L1 does NOT assign coordinates (that is L2's job per DEC-55 D-3 step 2).
     */
    @Test
    void generate_twoGroups_allMatchesHaveNullLapAndField() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildGroupedAvatarList(new int[] {4, 4});

        List<Match> matches = generator.generate(phase, avatars);

        for (Match m : matches) {
            assertThat(m.getLapNumber())
                    .as("Match lapNumber must be null after L1 (DEC-55 D-3 step 2)")
                    .isNull();
            assertThat(m.getFieldNumber())
                    .as("Match fieldNumber must be null after L1 (DEC-55 D-3 step 2)")
                    .isNull();
        }
    }

    // =========================================================================
    // Spec-Anchored regression guards (NOT RED — DEC-41)
    // =========================================================================

    // -------------------------------------------------------------------------
    // AC-TEST-SIEGEREHRUNG-UNCHANGED (regression guard)
    // -------------------------------------------------------------------------
    // Siegerehrung tests live in SiegerehrungMatchGeneratorTest — no additional test here.
    // This comment anchors the AC in this test file per story governance.

    // -------------------------------------------------------------------------
    // AC-TEST-SINGLE-GROUP-PHASE-EQUIVALENCE (regression guard)
    // -------------------------------------------------------------------------

    /**
     * Spec-Anchored regression guard (AC-TEST-SINGLE-GROUP-PHASE-EQUIVALENCE, E51S09).
     *
     * <p>For a phase with 12 avatars all in {@code groupNumber=1}, {@code generate()} must produce
     * 66 matches (12×11/2). Backwards-compatible: single-group is the no-partition baseline.
     *
     * <p>This is NOT a RED test — single-group already works; the assertion must pass before and
     * after the fix (DEC-41 Spec-Anchored pattern).
     */
    @Test
    void generate_twelveAvatarsSingleGroup_produces66Matches() {
        Phase phase = minimalPhase();
        // All 12 avatars in groupNumber=1 (default 0 from buildAvatarList → here explicit group 1)
        List<TeamAvatar> avatars = buildGroupedAvatarList(new int[] {12});

        List<Match> matches = generator.generate(phase, avatars);

        assertThat(matches)
                .as("12 avatars all in one group must produce 66 matches (12*11/2)")
                .hasSize(66);
    }

    // =========================================================================
    // Error handling (AC-ERROR-HANDLING-EMPTY-GROUP, AC-ERROR-HANDLING-MIXED-GROUP-COUNTS)
    // =========================================================================

    /**
     * AC-ERROR-HANDLING-EMPTY-GROUP (E51S09).
     *
     * <p>If a group has 0 avatars, generate() produces 0 matches for that group (no exception).
     * Degenerate group is silently skipped.
     */
    @Test
    void generate_oneGroupEmpty_producesMatchesForNonEmptyGroupOnly() {
        Phase phase = minimalPhase();
        // Group 1: 4 avatars → 6 matches. Group 2: 0 avatars → 0 matches.
        // We simulate by building 4 avatars all in group 1.
        // A true empty group scenario: use buildGroupedAvatarList with {4, 0}.
        // buildGroupedAvatarList skips zero-count groups (no avatars assigned) — i.e. group 2 just
        // won't appear in the list. So use a custom build here.
        List<TeamAvatar> avatars = new ArrayList<>();
        UUID phaseId = UUID.randomUUID();
        for (int pos = 1; pos <= 4; pos++) {
            TeamAvatar a = new TeamAvatar();
            a.setId(UUID.randomUUID());
            a.setPhaseId(phaseId);
            a.setGroupNumber(1);
            a.setGroupPosition(pos);
            a.setTeamId(UUID.randomUUID());
            avatars.add(a);
        }
        // Group 2 has 0 avatars — not present in list at all. Partition by groupNumber handles
        // this: group 2 key simply doesn't appear. Degenerate single-avatar group:
        TeamAvatar lone = new TeamAvatar();
        lone.setId(UUID.randomUUID());
        lone.setPhaseId(phaseId);
        lone.setGroupNumber(2);
        lone.setGroupPosition(1);
        lone.setTeamId(UUID.randomUUID());
        avatars.add(lone);

        List<Match> matches = generator.generate(phase, avatars);

        // Group 1: 4 avatars → 6 matches. Group 2: 1 avatar → 0 matches. Total: 6.
        assertThat(matches)
                .as("Group with 1 avatar produces 0 matches; group with 4 produces 6")
                .hasSize(6);
    }

    /**
     * AC-ERROR-HANDLING-MIXED-GROUP-COUNTS (E51S09).
     *
     * <p>Groups with unequal sizes (5/4/3 = 12 avatars in 3 groups). Total matches = 10+6+3 = 19.
     */
    @Test
    void generate_twelveAvatarsThreeGroupsUnequal_produces19Matches() {
        Phase phase = minimalPhase();
        List<TeamAvatar> avatars = buildGroupedAvatarList(new int[] {5, 4, 3});

        List<Match> matches = generator.generate(phase, avatars);

        assertThat(matches).as("5/4/3 groups produce 10+6+3=19 matches").hasSize(19);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<TeamAvatar> buildAvatarList(int n) {
        List<TeamAvatar> list = new ArrayList<>(n);
        UUID phaseId = UUID.randomUUID();
        for (int i = 0; i < n; i++) {
            TeamAvatar a = new TeamAvatar();
            a.setId(UUID.randomUUID());
            a.setPhaseId(phaseId);
            a.setTeamId(UUID.randomUUID());
            list.add(a);
        }
        return list;
    }

    /**
     * Builds a list of avatars distributed across groups. {@code groupSizes[i]} avatars are placed
     * in group {@code i+1}. Each avatar gets a unique ID and a groupPosition starting at 1.
     */
    private List<TeamAvatar> buildGroupedAvatarList(int[] groupSizes) {
        List<TeamAvatar> list = new ArrayList<>();
        UUID phaseId = UUID.randomUUID();
        for (int g = 0; g < groupSizes.length; g++) {
            int groupNumber = g + 1;
            for (int pos = 1; pos <= groupSizes[g]; pos++) {
                TeamAvatar a = new TeamAvatar();
                a.setId(UUID.randomUUID());
                a.setPhaseId(phaseId);
                a.setGroupNumber(groupNumber);
                a.setGroupPosition(pos);
                a.setTeamId(UUID.randomUUID());
                list.add(a);
            }
        }
        return list;
    }
}
