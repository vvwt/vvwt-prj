package de.vvwt.tm.domain.rules;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link TimeBoundedSet} (AC8, AC11).
 *
 * <p>Covers all parametrized cases from AC8 plus null/negative input guards from AC11.
 */
@DisplayName("TimeBoundedSet")
class TimeBoundedSetTest {

    private final TimeBoundedSet rule = new TimeBoundedSet();

    // -----------------------------------------------------------------------
    // AC8 — parametrized cases
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {4}")
    @CsvSource(delimiter = '|', value = {
        // team1|team2|expectedClosed|expectedWinner|description
        "15|14|true |W1|15-14 → closed winner1",
        "14|15|true |W2|14-15 → closed winner2",
        "15|15|false|-  |15-15 → open tied",
        "1 |0 |true |W1|1-0 → closed winner1 (1-point lead sufficient)",
        "0 |0 |false|-  |0-0 → open tied"
    })
    @DisplayName("parametrized AC8 cases")
    void parametrizedCases(int team1Pts, int team2Pts, boolean expectedClosed,
                           String expectedWinner,
                           @SuppressWarnings("unused") String description) {
        // format and setIndex are ignored by TimeBoundedSet — use arbitrary values
        ValidationResult result = rule.isSetClosed(team1Pts, team2Pts, 0, MatchFormat.BEST_OF_3);

        assertThat(result.isClosed())
                .as("closed for (%d, %d)", team1Pts, team2Pts)
                .isEqualTo(expectedClosed);

        if (expectedClosed) {
            assertThat(result.getWinnerHint()).isPresent();
            if ("W1".equals(expectedWinner.trim())) {
                assertThat(result.getWinnerHint().get()).isEqualTo(MatchState.FINISHED_WINNER1);
            } else if ("W2".equals(expectedWinner.trim())) {
                assertThat(result.getWinnerHint().get()).isEqualTo(MatchState.FINISHED_WINNER2);
            }
        } else {
            assertThat(result.getWinnerHint()).isEmpty();
            assertThat(result.getReason()).isEqualTo("tied");
        }
    }

    @Test
    @DisplayName("format variations produce same result — BEST_OF_5 and FIXED_2_SETS both work")
    void formatIndependent() {
        // Same scores, different formats — result must be identical
        ValidationResult bo3 = rule.isSetClosed(15, 14, 0, MatchFormat.BEST_OF_3);
        ValidationResult bo5 = rule.isSetClosed(15, 14, 2, MatchFormat.BEST_OF_5);
        ValidationResult fixed = rule.isSetClosed(15, 14, 1, MatchFormat.FIXED_2_SETS);

        assertThat(bo3.isClosed()).isTrue();
        assertThat(bo5.isClosed()).isTrue();
        assertThat(fixed.isClosed()).isTrue();
        assertThat(bo3.getWinnerHint()).isEqualTo(bo5.getWinnerHint()).isEqualTo(fixed.getWinnerHint());
    }

    @Test
    @DisplayName("setIndex variations produce same result")
    void setIndexIndependent() {
        for (int setIndex = 0; setIndex <= 4; setIndex++) {
            ValidationResult result = rule.isSetClosed(15, 14, setIndex, MatchFormat.BEST_OF_5);
            assertThat(result.isClosed())
                    .as("closed for setIndex=%d", setIndex)
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------------
    // AC11 — null and negative input guards
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("null format → throws IllegalArgumentException")
    void nullFormatThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule.isSetClosed(15, 14, 0, null))
                .withMessageContaining("format must not be null");
    }

    @Test
    @DisplayName("negative team1Pts → throws IllegalArgumentException")
    void negativeTeam1PtsThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule.isSetClosed(-1, 14, 0, MatchFormat.BEST_OF_3))
                .withMessageContaining("team1Pts must be >= 0");
    }

    @Test
    @DisplayName("negative team2Pts → throws IllegalArgumentException")
    void negativeTeam2PtsThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule.isSetClosed(15, -1, 0, MatchFormat.BEST_OF_3))
                .withMessageContaining("team2Pts must be >= 0");
    }

    // -----------------------------------------------------------------------
    // getBeanId (AC1)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getBeanId returns 'timeBounded'")
    void beanId() {
        assertThat(rule.getBeanId()).isEqualTo("timeBounded");
    }
}
