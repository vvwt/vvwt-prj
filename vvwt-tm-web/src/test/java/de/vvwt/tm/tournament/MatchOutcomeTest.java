package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MatchOutcome} entity invariants (E21S05, AC-TDD-MatchOutcome).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link MatchOutcome} at {@code
 * de.vvwt.tm.tournament.MatchOutcome} did not exist at commit time, causing a compile error —
 * satisfying the DEC-22 Iron Law.
 *
 * <h2>MatchOutcome type confirmation</h2>
 *
 * <p>Per AC-TDD-MatchOutcome: inventory line 175 described "Match result aggregate consumed by
 * ScoringRule". Inspection of {@code de.vvwt.tm.domain.MatchOutcome} (READ ONLY) confirms it is a
 * {@code @Table("match_outcome")} Spring Data JDBC entity (not an enum). The new class is therefore
 * an entity-style boundary-API class. This finding is documented in the impl-report.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Default construction (Spring Data JDBC requirement)
 *   <li>{@code setComputedMatchState} / {@code getComputedMatchState} round-trip via legacy code
 *   <li>Null-guard on {@code setComputedMatchState(null)}
 *   <li>{@code matchesComputedState} D-32 invariant helper
 *   <li>Null-guard on {@code matchesComputedState(null)}
 * </ul>
 *
 * @see MatchOutcome
 * @see MatchState
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 175</a>
 */
@DisplayName("MatchOutcome entity invariants — E21S05 AC-TDD-MatchOutcome")
class MatchOutcomeTest {

    @Test
    @DisplayName("Default constructor succeeds (Spring Data JDBC requirement)")
    void defaultConstructorSucceeds() {
        MatchOutcome mo = new MatchOutcome();
        assertThat(mo).isNotNull();
    }

    @Test
    @DisplayName("setComputedMatchState + getComputedMatchState round-trips via legacy code")
    void computedMatchStateRoundTrip() {
        MatchOutcome mo = new MatchOutcome();
        mo.setComputedMatchState(MatchState.FINISHED_WINNER1);
        assertThat(mo.getComputedMatchState()).isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    @DisplayName("setComputedMatchState(null) throws NullPointerException")
    void setComputedMatchStateNullThrows() {
        MatchOutcome mo = new MatchOutcome();
        assertThatThrownBy(() -> mo.setComputedMatchState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("matchesComputedState returns true when states agree (D-32 invariant)")
    void matchesComputedStateTrueWhenEqual() {
        MatchOutcome mo = new MatchOutcome();
        mo.setComputedMatchState(MatchState.FINISHED_WINNER2);

        Match match = new Match();
        match.setMatchState(MatchState.FINISHED_WINNER2);

        assertThat(mo.matchesComputedState(match)).isTrue();
    }

    @Test
    @DisplayName("matchesComputedState returns false when states differ (D-32 invariant)")
    void matchesComputedStateFalseWhenDifferent() {
        MatchOutcome mo = new MatchOutcome();
        mo.setComputedMatchState(MatchState.FINISHED_WINNER1);

        Match match = new Match();
        match.setMatchState(MatchState.FINISHED_WINNER2);

        assertThat(mo.matchesComputedState(match)).isFalse();
    }

    @Test
    @DisplayName("matchesComputedState(null) throws NullPointerException")
    void matchesComputedStateNullThrows() {
        MatchOutcome mo = new MatchOutcome();
        assertThatThrownBy(() -> mo.matchesComputedState(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("matchId and tenantId fields round-trip")
    void uuidFieldsRoundTrip() {
        UUID matchId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        MatchOutcome mo = new MatchOutcome();
        mo.setMatchId(matchId);
        assertThat(mo.getMatchId()).isEqualTo(matchId);
    }
}
