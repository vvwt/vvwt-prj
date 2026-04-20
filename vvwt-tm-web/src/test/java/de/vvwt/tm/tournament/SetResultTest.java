package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SetResult} entity invariants (E21S05, AC-TDD-SetResult).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link SetResult} at {@code de.vvwt.tm.tournament.SetResult}
 * did not exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Default construction (Spring Data JDBC requirement)
 *   <li>Non-negative score invariant via constructor
 *   <li>{@code setSetState} / {@code getSetState} round-trip
 *   <li>Composite key fields (matchId + setIndex) accessible
 * </ul>
 *
 * @see SetResult
 * @see SetState
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 182</a>
 */
@DisplayName("SetResult entity invariants — E21S05 AC-TDD-SetResult")
class SetResultTest {

    @Test
    @DisplayName("Default constructor succeeds (Spring Data JDBC requirement)")
    void defaultConstructorSucceeds() {
        SetResult sr = new SetResult();
        assertThat(sr).isNotNull();
    }

    @Test
    @DisplayName("Full constructor validates non-negative score invariant")
    void fullConstructorRejectsNegativeScores() {
        UUID matchId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                new SetResult(
                                        matchId,
                                        0,
                                        tenantId,
                                        phaseId,
                                        -1,
                                        0,
                                        SetState.OPEN.getLegacyCode(),
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1Points");
    }

    @Test
    @DisplayName("Full constructor rejects null matchId")
    void fullConstructorRejectsNullMatchId() {
        UUID tenantId = UUID.randomUUID();
        UUID phaseId = UUID.randomUUID();

        assertThatThrownBy(
                        () ->
                                new SetResult(
                                        null,
                                        0,
                                        tenantId,
                                        phaseId,
                                        0,
                                        0,
                                        SetState.OPEN.getLegacyCode(),
                                        null,
                                        null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("matchId");
    }

    @Test
    @DisplayName("setSetState + getSetState round-trips via legacy code")
    void setStateRoundTrip() {
        SetResult sr = new SetResult();
        sr.setSetState(SetState.WINNER1);
        assertThat(sr.getSetState()).isEqualTo(SetState.WINNER1);
    }

    @Test
    @DisplayName("Composite key fields matchId and setIndex are accessible")
    void compositeKeyFieldsAccessible() {
        UUID matchId = UUID.randomUUID();
        SetResult sr = new SetResult();
        sr.setMatchId(matchId);
        sr.setSetIndex(2);
        assertThat(sr.getMatchId()).isEqualTo(matchId);
        assertThat(sr.getSetIndex()).isEqualTo(2);
    }
}
