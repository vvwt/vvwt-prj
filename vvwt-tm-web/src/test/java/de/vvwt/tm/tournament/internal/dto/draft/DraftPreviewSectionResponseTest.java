package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.internal.draft.DraftPreviewSection;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftPreviewSectionResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 435.
 *
 * @see DraftPreviewSectionResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftPreviewSectionResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: from() factory maps DraftPreviewSection correctly. */
    @Test
    void from_withDraftPreviewSection_mapsAllFields() {
        DraftPreviewSection section = new DraftPreviewSection(1, 2, 4, 6, 3, 12, 75);
        DraftPreviewSectionResponse response = DraftPreviewSectionResponse.from(section);

        assertThat(response.phaseNumber()).isEqualTo(1);
        assertThat(response.groupCount()).isEqualTo(2);
        assertThat(response.teamsPerGroup()).isEqualTo(4);
        assertThat(response.matchesPerGroup()).isEqualTo(6);
        assertThat(response.totalLaps()).isEqualTo(3);
        assertThat(response.totalMatches()).isEqualTo(12);
        assertThat(response.estimatedTimeMinutes()).isEqualTo(75);
    }

    /** AC-TDD-DTOs: JSON round-trip preserves all fields. */
    @Test
    void jsonRoundTrip_allFields_preserved() throws Exception {
        DraftPreviewSectionResponse original = new DraftPreviewSectionResponse(1, 2, 4, 6, 3, 12, 75);
        String json = objectMapper.writeValueAsString(original);
        DraftPreviewSectionResponse restored =
                objectMapper.readValue(json, DraftPreviewSectionResponse.class);

        assertThat(restored.phaseNumber()).isEqualTo(original.phaseNumber());
        assertThat(restored.totalLaps()).isEqualTo(original.totalLaps());
    }
}
