package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.internal.draft.DraftBreak;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftBreakResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 433.
 *
 * @see DraftBreakResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftBreakResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: from() factory maps DraftBreak correctly. */
    @Test
    void from_withDraftBreak_mapsAllFields() {
        DraftBreak domainBreak = new DraftBreak(2, 15, "Pause");
        DraftBreakResponse response = DraftBreakResponse.from(domainBreak);

        assertThat(response.afterLapNumber()).isEqualTo(2);
        assertThat(response.durationMinutes()).isEqualTo(15);
        assertThat(response.label()).isEqualTo("Pause");
    }

    /** AC-TDD-DTOs: from() with null label maps null. */
    @Test
    void from_withNullLabel_mapsNull() {
        DraftBreak domainBreak = new DraftBreak(1, 10, null);
        DraftBreakResponse response = DraftBreakResponse.from(domainBreak);

        assertThat(response.label()).isNull();
    }

    /** AC-TDD-DTOs: JSON round-trip preserves all fields. */
    @Test
    void jsonRoundTrip_withAllFields_preserved() throws Exception {
        DraftBreakResponse original = new DraftBreakResponse(1, 10, "Break");
        String json = objectMapper.writeValueAsString(original);
        DraftBreakResponse restored = objectMapper.readValue(json, DraftBreakResponse.class);

        assertThat(restored.afterLapNumber()).isEqualTo(original.afterLapNumber());
        assertThat(restored.durationMinutes()).isEqualTo(original.durationMinutes());
        assertThat(restored.label()).isEqualTo(original.label());
    }
}
