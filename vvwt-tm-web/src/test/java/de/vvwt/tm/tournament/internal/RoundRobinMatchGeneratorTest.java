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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    // getBeanId
    // -------------------------------------------------------------------------

    @Test
    void getBeanId_returnsRoundRobin() {
        assertThat(generator.getBeanId()).isEqualTo("roundRobin");
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<TeamAvatar> buildAvatarList(int n) {
        List<TeamAvatar> list = new ArrayList<>(n);
        UUID phaseId = UUID.randomUUID();
        for (int i = 0; i < n; i++) {
            TeamAvatar a = new TeamAvatar();
            a.setId(UUID.randomUUID());
            a.setTenantId(UUID.randomUUID());
            a.setPhaseId(phaseId);
            a.setTeamId(UUID.randomUUID());
            list.add(a);
        }
        return list;
    }
}
