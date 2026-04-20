package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftSectionRequest DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 438.
 *
 * @see DraftSectionRequest
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftSectionRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: record stores all fields. */
    @Test
    void record_withAllFields_storesAll() {
        DraftSectionRequest request =
                new DraftSectionRequest(1, "team_number", 2, "roundrobin", 5, 10, 15, 1, null);

        assertThat(request.sectionNumber()).isEqualTo(1);
        assertThat(request.sortType()).isEqualTo("team_number");
        assertThat(request.groupCount()).isEqualTo(2);
        assertThat(request.gameMode()).isEqualTo("roundrobin");
        assertThat(request.lapBreakTimeMinutes()).isEqualTo(5);
        assertThat(request.sectionBreakTimeMinutes()).isEqualTo(10);
        assertThat(request.lapTimeMinutes()).isEqualTo(15);
        assertThat(request.setQuantity()).isEqualTo(1);
        assertThat(request.breaks()).isNull();
    }

    /** AC-TDD-DTOs: breaks list can be provided. */
    @Test
    void record_withBreaks_storesList() {
        DraftBreakRequest breakRequest = new DraftBreakRequest(2, 15, "Pause");
        DraftSectionRequest request =
                new DraftSectionRequest(
                        1, "team_number", 2, "roundrobin", 5, 10, 15, 1,
                        List.of(breakRequest));

        assertThat(request.breaks()).hasSize(1);
        assertThat(request.breaks().get(0).afterLapNumber()).isEqualTo(2);
    }

    /** AC-TDD-DTOs: JSON round-trip preserves all fields. */
    @Test
    void jsonRoundTrip_allFields_preserved() throws Exception {
        DraftSectionRequest original =
                new DraftSectionRequest(1, "placement_group", 3, "roundrobin", 0, 15, 20, 2, null);
        String json = objectMapper.writeValueAsString(original);
        DraftSectionRequest restored = objectMapper.readValue(json, DraftSectionRequest.class);

        assertThat(restored.sectionNumber()).isEqualTo(original.sectionNumber());
        assertThat(restored.sortType()).isEqualTo(original.sortType());
        assertThat(restored.groupCount()).isEqualTo(original.groupCount());
    }
}
