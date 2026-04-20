package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftTimelineEntryResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Plain data-carrier for timeline entries. Does NOT depend on {@code
 * de.vvwt.tm.domain.timeline.TimelineEntry} or future E21S11 timeline domain classes. E21S11's
 * {@code TimelineCalculationService} will produce entries of this shape (or map onto it). This DTO
 * is the shape definition owned by E21S07.
 *
 * <p>Inventory: E21S01 line 440.
 *
 * @see DraftTimelineEntryResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E21S11">E21S11 — Timeline domain classes (future producer)</a>
 */
class DraftTimelineEntryResponseTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    /** AC-TDD-DTOs: record stores all fields. */
    @Test
    void record_withAllFields_storesAll() {
        LocalTime start = LocalTime.of(9, 0);
        LocalTime end = LocalTime.of(9, 15);
        DraftTimelineEntryResponse entry =
                new DraftTimelineEntryResponse(1, 1, "MATCH_ROUND", start, end, null);

        assertThat(entry.phaseNumber()).isEqualTo(1);
        assertThat(entry.lapNumber()).isEqualTo(1);
        assertThat(entry.type()).isEqualTo("MATCH_ROUND");
        assertThat(entry.startTime()).isEqualTo(start);
        assertThat(entry.endTime()).isEqualTo(end);
        assertThat(entry.label()).isNull();
    }

    /** AC-TDD-DTOs: record with non-null label stores it. */
    @Test
    void record_withLabel_storesLabel() {
        DraftTimelineEntryResponse entry =
                new DraftTimelineEntryResponse(
                        1,
                        2,
                        "INTRA_PHASE_BREAK",
                        LocalTime.of(10, 0),
                        LocalTime.of(10, 15),
                        "Mittagspause");

        assertThat(entry.label()).isEqualTo("Mittagspause");
        assertThat(entry.type()).isEqualTo("INTRA_PHASE_BREAK");
    }

    /** AC-TDD-DTOs: JSON round-trip preserves LocalTime fields. */
    @Test
    void jsonRoundTrip_withLocalTimeFields_preserved() throws Exception {
        LocalTime start = LocalTime.of(9, 30);
        LocalTime end = LocalTime.of(9, 45);
        DraftTimelineEntryResponse original =
                new DraftTimelineEntryResponse(1, 1, "MATCH_ROUND", start, end, null);
        String json = objectMapper.writeValueAsString(original);
        DraftTimelineEntryResponse restored =
                objectMapper.readValue(json, DraftTimelineEntryResponse.class);

        assertThat(restored.startTime()).isEqualTo(start);
        assertThat(restored.endTime()).isEqualTo(end);
        assertThat(restored.type()).isEqualTo("MATCH_ROUND");
    }
}
