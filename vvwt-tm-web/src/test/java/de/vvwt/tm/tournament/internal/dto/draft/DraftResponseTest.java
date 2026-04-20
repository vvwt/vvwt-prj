package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.internal.draft.DraftConfig;
import de.vvwt.tm.tournament.internal.draft.DraftSection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 437.
 *
 * @see DraftResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: from() with empty DraftConfig returns empty sections. */
    @Test
    void from_withEmptyDraftConfig_returnsEmptySections() {
        DraftResponse response = DraftResponse.from(DraftConfig.empty());

        assertThat(response.sections()).isEmpty();
    }

    /** AC-TDD-DTOs: from() with one section maps correctly. */
    @Test
    void from_withOneSection_mapsToResponseSection() {
        DraftSection section =
                new DraftSection(1, "team_number", 2, "roundrobin", 5, 10, 15, 1, List.of());
        DraftConfig config = new DraftConfig(List.of(section));
        DraftResponse response = DraftResponse.from(config);

        assertThat(response.sections()).hasSize(1);
        assertThat(response.sections().get(0).sectionNumber()).isEqualTo(1);
    }

    /** AC-TDD-DTOs: JSON round-trip preserves empty sections. */
    @Test
    void jsonRoundTrip_emptySections_preserved() throws Exception {
        DraftResponse original = new DraftResponse(List.of());
        String json = objectMapper.writeValueAsString(original);
        DraftResponse restored = objectMapper.readValue(json, DraftResponse.class);

        assertThat(restored.sections()).isEmpty();
    }
}
