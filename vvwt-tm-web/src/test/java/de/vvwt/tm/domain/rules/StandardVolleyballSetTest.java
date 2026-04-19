package de.vvwt.tm.domain.rules;

import static org.assertj.core.api.Assertions.*;

import de.vvwt.tm.domain.MatchFormat;
import de.vvwt.tm.domain.MatchState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link StandardVolleyballSet} (AC7, AC11).
 *
 * <p>Covers all parametrized cases from AC7 plus null/negative input guards from AC11.
 */
@DisplayName("StandardVolleyballSet")
class StandardVolleyballSetTest {

    private final StandardVolleyballSet rule = new StandardVolleyballSet();

    // -----------------------------------------------------------------------
    // AC7 — parametrized happy/boundary/error cases
    // -----------------------------------------------------------------------

    /**
     * Cases: (team1Pts, team2Pts, setIndex, format, expectedClosed, expectedWinner)
     *
     * <p>setIndex is 0-based. BEST_OF_3 deciding set index = 2, BEST_OF_5 = 4.
     *
     * <p>expectedWinner: W1 = WINNER1, W2 = WINNER2, - = open (no winner)
     */
    @ParameterizedTest(name = "[{index}] {6}")
    @CsvSource(
            delimiter = '|',
            value = {
                // team1|team2|setIdx|format         |closed|winner|description
                "25    |23   |0     |BEST_OF_3      |true  |W1    |25-23 BEST_OF_3 set1 → closed"
                        + " winner1",
                "25    |24   |0     |BEST_OF_3      |false |-     |25-24 BEST_OF_3 set1 → open"
                        + " no-2-lead",
                "23    |25   |0     |BEST_OF_3      |true  |W2    |23-25 BEST_OF_3 set1 → closed"
                        + " winner2",
                "26    |24   |0     |BEST_OF_3      |true  |W1    |26-24 BEST_OF_3 set1 → closed"
                        + " winner1",
                "24    |24   |0     |BEST_OF_3      |false |-     |24-24 BEST_OF_3 set1 → open",
                "15    |13   |2     |BEST_OF_3      |true  |W1    |15-13 BEST_OF_3 set3 deciding →"
                        + " closed winner1",
                "15    |14   |2     |BEST_OF_3      |false |-     |15-14 BEST_OF_3 set3 deciding →"
                        + " open no-2-lead",
                "16    |14   |2     |BEST_OF_3      |true  |W1    |16-14 BEST_OF_3 set3 deciding →"
                        + " closed",
                "14    |12   |2     |BEST_OF_3      |false |-     |14-12 BEST_OF_3 set3 deciding →"
                        + " open target-not-reached",
                "15    |13   |4     |BEST_OF_5      |true  |W1    |15-13 BEST_OF_5 set5"
                        + " deciding(index4) → closed winner1"
            })
    @DisplayName("parametrized AC7 cases")
    void parametrizedCases(
            int team1Pts,
            int team2Pts,
            int setIndex,
            String formatName,
            boolean expectedClosed,
            String expectedWinner,
            @SuppressWarnings("unused") String description) {
        MatchFormat format = MatchFormat.valueOf(formatName.trim());
        ValidationResult result = rule.isSetClosed(team1Pts, team2Pts, setIndex, format);

        assertThat(result.isClosed())
                .as("closed for (%d, %d) set=%d format=%s", team1Pts, team2Pts, setIndex, format)
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
            assertThat(result.getReason()).isNotBlank();
        }
    }

    @Test
    @DisplayName("25-24 BEST_OF_3 set1 → reason is 'no 2-point lead'")
    void noTwoPointLeadReason() {
        ValidationResult result = rule.isSetClosed(25, 24, 0, MatchFormat.BEST_OF_3);
        assertThat(result.isClosed()).isFalse();
        assertThat(result.getReason()).isEqualTo("no 2-point lead");
    }

    @Test
    @DisplayName("14-12 BEST_OF_3 set3 (deciding) → reason is 'target not reached'")
    void targetNotReachedReason() {
        ValidationResult result = rule.isSetClosed(14, 12, 2, MatchFormat.BEST_OF_3);
        assertThat(result.isClosed()).isFalse();
        assertThat(result.getReason()).isEqualTo("target not reached");
    }

    // -----------------------------------------------------------------------
    // FIXED_2_SETS guard (AC3 null-guard for decidingSet)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("FIXED_2_SETS → throws IllegalArgumentException (format has no deciding set)")
    void fixedTwoSetsThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule.isSetClosed(15, 13, 0, MatchFormat.FIXED_2_SETS))
                .withMessageContaining(
                        "StandardVolleyballSet is not compatible with format FIXED_2_SETS");
    }

    // -----------------------------------------------------------------------
    // AC11 — null and negative input guards
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("null format → throws IllegalArgumentException")
    void nullFormatThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule.isSetClosed(25, 23, 0, null))
                .withMessageContaining("format must not be null");
    }

    @Test
    @DisplayName("negative team1Pts → throws IllegalArgumentException")
    void negativeTeam1PtsThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule.isSetClosed(-1, 23, 0, MatchFormat.BEST_OF_3))
                .withMessageContaining("team1Pts must be >= 0");
    }

    @Test
    @DisplayName("negative team2Pts → throws IllegalArgumentException")
    void negativeTeam2PtsThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rule.isSetClosed(25, -1, 0, MatchFormat.BEST_OF_3))
                .withMessageContaining("team2Pts must be >= 0");
    }

    // -----------------------------------------------------------------------
    // getBeanId (AC1)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("getBeanId returns 'standardVolleyball'")
    void beanId() {
        assertThat(rule.getBeanId()).isEqualTo("standardVolleyball");
    }

    // -----------------------------------------------------------------------
    // BEST_OF_1 — deciding set is set index 0, target 15
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("BEST_OF_1 15-13 set0 → closed (deciding set target 15)")
    void bestOf1DecidingTarget() {
        ValidationResult result = rule.isSetClosed(15, 13, 0, MatchFormat.BEST_OF_1);
        assertThat(result.isClosed()).isTrue();
        assertThat(result.getWinnerHint().get()).isEqualTo(MatchState.FINISHED_WINNER1);
    }
}
