package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftRequest DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 436.
 *
 * @see DraftRequest
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: record stores sections. */
    @Test
    void record_withSections_storesList() {
        DraftSectionRequest sectionRequest =
                new DraftSectionRequest(
                        1, "team_number", 2, "roundrobin", 5, 10, 15, 1, null, null);
        DraftRequest request = new DraftRequest(List.of(sectionRequest));

        assertThat(request.sections()).hasSize(1);
        assertThat(request.sections().get(0).sectionNumber()).isEqualTo(1);
    }

    /** AC-TDD-DTOs: JSON deserialization from valid JSON succeeds. */
    @Test
    void jsonDeserialization_fromValidJson_succeeds() throws Exception {
        String json =
                "{\"sections\":[{\"sectionNumber\":1,\"sortType\":\"team_number\","
                        + "\"groupCount\":2,\"gameMode\":\"roundrobin\","
                        + "\"lapBreakTimeMinutes\":5,\"sectionBreakTimeMinutes\":10,"
                        + "\"lapTimeMinutes\":15,\"setQuantity\":1,\"breaks\":null}]}";
        DraftRequest request = objectMapper.readValue(json, DraftRequest.class);

        assertThat(request.sections()).hasSize(1);
    }
}
