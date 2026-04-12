package de.vvwt.tm.domain.rules;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchOutcome;
import de.vvwt.tm.domain.Tournament;
import de.vvwt.tm.domain.generator.MatchGeneratorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ScoringRuleRegistry} (AC6, AC11) and
 * {@link TournamentRuleResolver} (AC7, AC11).
 * Also covers AC14 (non-negative points guard) and AC12 (unknown bean ID).
 */
class ScoringRuleRegistryTest {

    private ScoringRuleRegistry registry;
    private TournamentRuleResolver resolver;

    @BeforeEach
    void setUp() {
        List<ScoringRule> rules = List.of(
                new SetPointsRule(),
                new TwoPointMatchRule(),
                new ThreePointMatchRule()
        );
        registry = new ScoringRuleRegistry(rules);

        // TournamentRuleResolver (E03S07 + E03S08 merged) requires both registries.
        // Use a minimal SetValidationRuleRegistry for this unit test scope.
        List<SetValidationRule> setRules = List.of(
                new StandardVolleyballSet(),
                new TimeBoundedSet()
        );
        SetValidationRuleRegistry setRegistry = new SetValidationRuleRegistry(
                setRules.stream().collect(
                        java.util.stream.Collectors.toMap(SetValidationRule::getBeanId, r -> r)));
        // E03S09 adds MatchGeneratorRegistry as a third parameter — use an empty registry here
        // since this test only exercises the ScoringRule resolution path.
        MatchGeneratorRegistry genRegistry = new MatchGeneratorRegistry(Collections.emptyList());
        resolver = new TournamentRuleResolver(setRegistry, registry, genRegistry);
    }

    // -----------------------------------------------------------------------
    // AC6 — registry get() returns correct rule
    // -----------------------------------------------------------------------

    @Test
    void get_setPoints_returnsSetPointsRule() {
        ScoringRule rule = registry.get("setPoints");
        assertThat(rule).isInstanceOf(SetPointsRule.class);
    }

    @Test
    void get_twoPoint_returnsTwoPointMatchRule() {
        ScoringRule rule = registry.get("twoPoint");
        assertThat(rule).isInstanceOf(TwoPointMatchRule.class);
    }

    @Test
    void get_threePoint_returnsThreePointMatchRule() {
        ScoringRule rule = registry.get("threePoint");
        assertThat(rule).isInstanceOf(ThreePointMatchRule.class);
    }

    // -----------------------------------------------------------------------
    // AC11 — unknown bean ID throws IllegalArgumentException with known IDs
    // -----------------------------------------------------------------------

    @Test
    void get_unknownId_throwsWithKnownIds() {
        assertThatThrownBy(() -> registry.get("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown")
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    // Known IDs must appear in the error message
                    assertThat(msg).contains("setPoints");
                    assertThat(msg).contains("twoPoint");
                    assertThat(msg).contains("threePoint");
                });
    }

    @Test
    void get_emptyId_throwsWithKnownIds() {
        assertThatThrownBy(() -> registry.get(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // AC7 — TournamentRuleResolver.resolveScoringRule delegates to registry
    // -----------------------------------------------------------------------

    @Test
    void resolveScoringRule_setPoints_resolves() {
        Tournament tournament = buildTournament("setPoints");
        ScoringRule rule = resolver.resolveScoringRule(tournament);
        assertThat(rule).isInstanceOf(SetPointsRule.class);
    }

    @Test
    void resolveScoringRule_threePoint_resolves() {
        Tournament tournament = buildTournament("threePoint");
        ScoringRule rule = resolver.resolveScoringRule(tournament);
        assertThat(rule).isInstanceOf(ThreePointMatchRule.class);
    }

    @Test
    void resolveScoringRule_unknownId_throwsIllegalArgument() {
        Tournament tournament = buildTournament("noSuchRule");
        assertThatThrownBy(() -> resolver.resolveScoringRule(tournament))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("noSuchRule");
    }

    @Test
    void resolveScoringRule_nullTournament_throwsNullPointer() {
        assertThatThrownBy(() -> resolver.resolveScoringRule(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------------
    // AC14 — ScoringResult constructor rejects negative points
    // -----------------------------------------------------------------------

    @Test
    void scoringResult_negativeTeam1Points_throws() {
        assertThatThrownBy(() -> new ScoringResult(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1Points");
    }

    @Test
    void scoringResult_negativeTeam2Points_throws() {
        assertThatThrownBy(() -> new ScoringResult(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2Points");
    }

    @Test
    void scoringResult_zeroPoints_isValid() {
        ScoringResult result = new ScoringResult(0, 0);
        assertThat(result.team1Points()).isZero();
        assertThat(result.team2Points()).isZero();
    }

    // -----------------------------------------------------------------------
    // AC13 — observability: rules execute without exception for valid inputs
    // (DEBUG logging is verified by absence of exceptions — log output testing
    //  is out-of-scope for unit tests; logback-test configuration handles it)
    // -----------------------------------------------------------------------

    @Test
    void allRules_logAtDebugWithoutException_forValidInput() {
        MatchOutcome outcome = new MatchOutcome(3, 2, 5);
        MatchFormat format = MatchFormat.BEST_OF_5;

        // All three rules must complete without exception (logging is a side-effect)
        assertThat(registry.get("setPoints").calculatePoints(outcome, format)).isNotNull();
        assertThat(registry.get("twoPoint").calculatePoints(outcome, format)).isNotNull();
        assertThat(registry.get("threePoint").calculatePoints(outcome, format)).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static Tournament buildTournament(String scoringRuleId) {
        return new Tournament(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Test Tournament",
                "BEST_OF_3",
                scoringRuleId,
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                null
        );
    }
}
