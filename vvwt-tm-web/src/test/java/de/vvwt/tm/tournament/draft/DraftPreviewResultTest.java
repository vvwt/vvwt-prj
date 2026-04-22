package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.internal.dto.draft.DraftTimelineEntryResponse;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftPreviewResult VO unit test (AC-TDD-DraftPreviewResult).
 *
 * <p>Tests record field accessibility, empty-timeline case, and immutability.
 *
 * <p>Inventory: E21S01 line 239.
 *
 * <p>Note: {@code DraftPreviewResult} uses {@link DraftTimelineEntryResponse} (a plain data-carrier
 * DTO owned by E21S07) — not the legacy {@code de.vvwt.tm.domain.timeline.TimelineEntry}. The
 * timeline domain classes ({@code TimelineCalculationService}, {@code TimelineEntry}) arrive in
 * E21S11; this DTO is the shape used now and consumed by S11's timeline computation.
 *
 * @see DraftPreviewResult
 * @see DraftTimelineEntryResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E21S11">E21S11 — Timeline domain classes (future producer)</a>
 */
class DraftPreviewResultTest {

    /** AC-TDD-DraftPreviewResult: record stores sections and empty timeline. */
    @Test
    void record_withSectionsAndEmptyTimeline_storesBoth() {
        DraftPreviewSection section = new DraftPreviewSection(1, 2, 4, 6, 3, 12, 75);
        DraftPreviewResult result = new DraftPreviewResult(List.of(section), List.of());

        assertThat(result.sections()).hasSize(1);
        assertThat(result.sections().get(0).getPhaseNumber()).isEqualTo(1);
        assertThat(result.timeline()).isEmpty();
    }

    /** AC-TDD-DraftPreviewResult: record stores non-empty timeline entries. */
    @Test
    void record_withTimeline_storesEntries() {
        DraftTimelineEntryResponse entry =
                new DraftTimelineEntryResponse(
                        1, 1, "MATCH_ROUND", LocalTime.of(9, 0), LocalTime.of(9, 15), null);
        DraftPreviewResult result = new DraftPreviewResult(List.of(), List.of(entry));

        assertThat(result.timeline()).hasSize(1);
        assertThat(result.timeline().get(0).type()).isEqualTo("MATCH_ROUND");
    }
}
