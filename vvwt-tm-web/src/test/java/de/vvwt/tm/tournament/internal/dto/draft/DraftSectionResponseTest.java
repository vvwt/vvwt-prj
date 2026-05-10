package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.draft.GameMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftSectionResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 439.
 *
 * <h2>E51S20 — gameMode String→Enum migration</h2>
 *
 * <p>Constructor calls and assertions updated from {@code String} to {@link GameMode} enum
 * constants. The {@code gameMode} field in both {@link DraftSection} and {@link
 * DraftSectionResponse} is now typed as {@link GameMode}.
 *
 * @see DraftSectionResponse
 * @see GameMode
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S20">E51S20 — gameMode/distributionMode String→Enum migration</a>
 */
class DraftSectionResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: from() factory maps DraftSection correctly. */
    @Test
    void from_withDraftSection_mapsAllFields() {
        DraftSection section =
                new DraftSection(
                        1, "team_number", 2, GameMode.ROUND_ROBIN, 5, 10, 15, 1, List.of());
        DraftSectionResponse response = DraftSectionResponse.from(section);

        assertThat(response.sectionNumber()).isEqualTo(1);
        assertThat(response.sortType()).isEqualTo("team_number");
        assertThat(response.groupCount()).isEqualTo(2);
        assertThat(response.gameMode()).isEqualTo(GameMode.ROUND_ROBIN);
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
                        1, "team_number", 2, GameMode.ROUND_ROBIN, 5, 10, 15, 1, List.of(), null);
        String json = objectMapper.writeValueAsString(original);
        DraftSectionResponse restored = objectMapper.readValue(json, DraftSectionResponse.class);

        assertThat(restored.sectionNumber()).isEqualTo(original.sectionNumber());
        assertThat(restored.sortType()).isEqualTo(original.sortType());
    }
}
