package de.vvwt.tm.scoring.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.scoring.ScoringRuleRegistry;
import de.vvwt.tm.scoring.SetValidationRule;
import de.vvwt.tm.scoring.SetValidationRuleRegistry;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.exceptions.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Same-package white-box unit tests for {@link TournamentRuleResolver} per DEC-36.
 *
 * <p>Tests in the same package as the subject ({@code scoring.internal}) MAY reference the
 * implementation class directly. Collaborators ({@link ScoringRuleRegistry}, {@link
 * SetValidationRuleRegistry}) are mocked via their public interface/class types.
 *
 * <p>RED-first per DEC-22 Iron Law: tests written before {@link TournamentRuleResolver} exists.
 *
 * @see TournamentRuleResolver
 */
@ExtendWith(MockitoExtension.class)
class TournamentRuleResolverTest {

    @Mock private ScoringRuleRegistry scoringRuleRegistry;
    @Mock private SetValidationRuleRegistry setValidationRuleRegistry;

    private TournamentRuleResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TournamentRuleResolver(scoringRuleRegistry, setValidationRuleRegistry);
    }

    // -----------------------------------------------------------------------
    // Helper: build a minimal Tournament with the given rule IDs
    // -----------------------------------------------------------------------

    private static Tournament tournamentWith(String scoringRuleId, String setValidationRuleId) {
        Tournament t = new Tournament();
        t.setScoringRuleId(scoringRuleId);
        t.setSetValidationRuleId(setValidationRuleId);
        return t;
    }

    // -----------------------------------------------------------------------
    // AC-RESOLVER-CASCADE-ENTRY + AC-TEST-COVERAGE-MAPPING: happy path
    // -----------------------------------------------------------------------

    @Test
    void resolve_validTournament_returnsNonNullTuple() {
        ScoringRule scoringRule = org.mockito.Mockito.mock(ScoringRule.class);
        SetValidationRule setRule = org.mockito.Mockito.mock(SetValidationRule.class);

        Tournament tournament = tournamentWith("setPoints", "standardVolleyball");
        when(scoringRuleRegistry.get("setPoints")).thenReturn(scoringRule);
        when(setValidationRuleRegistry.get("standardVolleyball")).thenReturn(setRule);

        TournamentRuleResolver.ResolvedRules resolved = resolver.resolve(tournament);

        assertThat(resolved).isNotNull();
        assertThat(resolved.scoringRule()).isSameAs(scoringRule);
        assertThat(resolved.setValidationRule()).isSameAs(setRule);
    }

    // -----------------------------------------------------------------------
    // AC-NULL-GUARDS: resolve(null) throws IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void resolve_null_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // AC-RESOLVER-CASCADE-ENTRY: unknown scoringRuleId propagates as ValidationException
    // -----------------------------------------------------------------------

    @Test
    void resolve_unknownScoringRuleId_throwsValidationException() {
        Tournament tournament = tournamentWith("unknownRule", "standardVolleyball");
        when(scoringRuleRegistry.get("unknownRule"))
                .thenThrow(new ValidationException("unknown scoring rule id: 'unknownRule'"));

        assertThatThrownBy(() -> resolver.resolve(tournament))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unknownRule");
    }

    // -----------------------------------------------------------------------
    // AC-RESOLVER-CASCADE-ENTRY: unknown setValidationRuleId propagates as ValidationException
    // -----------------------------------------------------------------------

    @Test
    void resolve_unknownSetValidationRuleId_throwsValidationException() {
        ScoringRule scoringRule = org.mockito.Mockito.mock(ScoringRule.class);
        Tournament tournament = tournamentWith("setPoints", "unknownValidator");
        when(scoringRuleRegistry.get("setPoints")).thenReturn(scoringRule);
        when(setValidationRuleRegistry.get("unknownValidator"))
                .thenThrow(
                        new ValidationException(
                                "unknown set validation rule id: 'unknownValidator'"));

        assertThatThrownBy(() -> resolver.resolve(tournament))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unknownValidator");
    }

    // -----------------------------------------------------------------------
    // AC-NULL-GUARDS: constructor guard — null scoringRuleRegistry
    // -----------------------------------------------------------------------

    @Test
    void constructor_nullScoringRegistry_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new TournamentRuleResolver(
                                        null, setValidationRuleRegistry))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------------
    // AC-NULL-GUARDS: constructor guard — null setValidationRuleRegistry
    // -----------------------------------------------------------------------

    @Test
    void constructor_nullSetValidationRegistry_throwsIllegalArgumentException() {
        assertThatThrownBy(
                        () ->
                                new TournamentRuleResolver(
                                        scoringRuleRegistry, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
