package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RED — DraftPreviewSection VO unit test (AC-TDD-DraftPreviewSection).
 *
 * <p>Tests field accessibility and immutability (final class, all-args constructor).
 *
 * <p>Inventory: E21S01 line 240.
 *
 * @see DraftPreviewSection
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftPreviewSectionTest {

    /** AC-TDD-DraftPreviewSection: all-args constructor stores fields correctly. */
    @Test
    void constructor_withAllFields_storesAll() {
        DraftPreviewSection section = new DraftPreviewSection(1, 2, 4, 6, 3, 12, 75);

        assertThat(section.getPhaseNumber()).isEqualTo(1);
        assertThat(section.getGroupCount()).isEqualTo(2);
        assertThat(section.getTeamsPerGroup()).isEqualTo(4);
        assertThat(section.getMatchesPerGroup()).isEqualTo(6);
        assertThat(section.getTotalLaps()).isEqualTo(3);
        assertThat(section.getTotalMatches()).isEqualTo(12);
        assertThat(section.getEstimatedTimeMinutes()).isEqualTo(75);
    }

    /** AC-TDD-DraftPreviewSection: zero values are accepted (edge case: empty phase). */
    @Test
    void constructor_withZeroValues_accepted() {
        DraftPreviewSection section = new DraftPreviewSection(1, 1, 1, 0, 0, 0, 0);

        assertThat(section.getTotalMatches()).isEqualTo(0);
        assertThat(section.getTotalLaps()).isEqualTo(0);
    }
}
