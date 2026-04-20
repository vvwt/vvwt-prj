package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SetResultInput} value object invariants (E21S05, AC-TDD-SetResultInput).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link SetResultInput} at {@code
 * de.vvwt.tm.tournament.SetResultInput} did not exist at commit time, causing a compile error —
 * satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Null matchId rejected by compact canonical constructor
 *   <li>Negative setIndex rejected
 *   <li>Negative team1Points / team2Points rejected
 *   <li>Score values non-negative invariant (score-values &ge; 0, set-number &ge; 1 via setIndex)
 *   <li>{@code legacy(...)} factory round-trips
 * </ul>
 *
 * @see SetResultInput
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 183</a>
 */
@DisplayName("SetResultInput VO invariants — E21S05 AC-TDD-SetResultInput")
class SetResultInputTest {

    @Test
    @DisplayName("Construction with valid values succeeds")
    void validConstructionSucceeds() {
        UUID matchId = UUID.randomUUID();
        SetResultInput input = new SetResultInput(matchId, 0, 25, 20, null, null, null, null);
        assertThat(input.matchId()).isEqualTo(matchId);
        assertThat(input.team1Points()).isEqualTo(25);
        assertThat(input.team2Points()).isEqualTo(20);
    }

    @Test
    @DisplayName("Null matchId throws NullPointerException")
    void nullMatchIdThrows() {
        assertThatThrownBy(() -> new SetResultInput(null, 0, 0, 0, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("matchId");
    }

    @Test
    @DisplayName("Negative setIndex throws IllegalArgumentException")
    void negativeSetIndexThrows() {
        UUID matchId = UUID.randomUUID();
        assertThatThrownBy(() -> new SetResultInput(matchId, -1, 0, 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setIndex");
    }

    @Test
    @DisplayName("Negative team1Points throws IllegalArgumentException")
    void negativeTeam1PointsThrows() {
        UUID matchId = UUID.randomUUID();
        assertThatThrownBy(() -> new SetResultInput(matchId, 0, -1, 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1Points");
    }

    @Test
    @DisplayName("Negative team2Points throws IllegalArgumentException")
    void negativeTeam2PointsThrows() {
        UUID matchId = UUID.randomUUID();
        assertThatThrownBy(() -> new SetResultInput(matchId, 0, 0, -1, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2Points");
    }

    @Test
    @DisplayName("legacy(...) factory sets sourceType and sourceDeviceId to null")
    void legacyFactoryNullsSourceFields() {
        UUID matchId = UUID.randomUUID();
        SetResultInput input = SetResultInput.legacy(matchId, 0, 15, 10, "actor1", "reason");
        assertThat(input.sourceType()).isNull();
        assertThat(input.sourceDeviceId()).isNull();
        assertThat(input.actorId()).isEqualTo("actor1");
        assertThat(input.reason()).isEqualTo("reason");
    }
}
