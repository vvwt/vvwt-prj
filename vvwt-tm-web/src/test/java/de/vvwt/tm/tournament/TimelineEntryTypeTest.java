package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * TDD tests for TimelineEntryType (E21S11).
 *
 * <p>Written RED (against non-existent production class) before implementation. Covers
 * AC-TDD-TimelineEntryType.
 *
 * @see TimelineEntryType
 */
class TimelineEntryTypeTest {

    @Test
    void values_completeness_exactly_four_entries() {
        assertThat(TimelineEntryType.values()).hasSize(4);
    }

    @Test
    void valueOf_matchRound() {
        assertThat(TimelineEntryType.valueOf("MATCH_ROUND"))
                .isEqualTo(TimelineEntryType.MATCH_ROUND);
    }

    @Test
    void valueOf_lapBreak() {
        assertThat(TimelineEntryType.valueOf("LAP_BREAK")).isEqualTo(TimelineEntryType.LAP_BREAK);
    }

    @Test
    void valueOf_intraPhaseBreak() {
        assertThat(TimelineEntryType.valueOf("INTRA_PHASE_BREAK"))
                .isEqualTo(TimelineEntryType.INTRA_PHASE_BREAK);
    }

    @Test
    void valueOf_sectionBreak() {
        assertThat(TimelineEntryType.valueOf("SECTION_BREAK"))
                .isEqualTo(TimelineEntryType.SECTION_BREAK);
    }

    @Test
    void valueOf_unknownName_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> TimelineEntryType.valueOf("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allExpectedValues_present() {
        assertThat(TimelineEntryType.values())
                .containsExactlyInAnyOrder(
                        TimelineEntryType.MATCH_ROUND,
                        TimelineEntryType.LAP_BREAK,
                        TimelineEntryType.INTRA_PHASE_BREAK,
                        TimelineEntryType.SECTION_BREAK);
    }
}
