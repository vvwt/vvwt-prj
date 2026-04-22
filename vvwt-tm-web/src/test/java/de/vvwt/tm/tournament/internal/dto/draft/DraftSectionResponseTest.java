package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftSectionResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 439.
 *
 * @see DraftSectionResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftSectionResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: from() factory maps DraftSection correctly. */
    @Test
    void from_withDraftSection_mapsAllFields() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundrobin", 5, 10, 15, 1, List.of());
        DraftSectionResponse response = DraftSectionResponse.from(section);

        assertThat(response.sectionNumber()).isEqualTo(1);
        assertThat(response.sortType()).isEqualTo("team_number");
        assertThat(response.groupCount()).isEqualTo(2);
        assertThat(response.gameMode()).isEqualTo("roundrobin");
        assertThat(response.lapBreakTimeMinutes()).isEqualTo(5);
        assertThat(response.sectionBreakTimeMinutes()).isEqualTo(10);
        assertThat(response.lapTimeMinutes()).isEqualTo(15);
        assertThat(response.setQuantity()).isEqualTo(1);
        assertThat(response.breaks()).isEmpty();
    }

    /** AC-TDD-DTOs: JSON round-trip preserves all fields. */
    @Test
    void jsonRoundTrip_allFields_preserved() throws Exception {
        DraftSectionResponse original =
                new DraftSectionResponse(
                        1, "team_number", 2, "roundrobin", 5, 10, 15, 1, List.of());
        String json = objectMapper.writeValueAsString(original);
        DraftSectionResponse restored = objectMapper.readValue(json, DraftSectionResponse.class);

        assertThat(restored.sectionNumber()).isEqualTo(original.sectionNumber());
        assertThat(restored.sortType()).isEqualTo(original.sortType());
    }
}
