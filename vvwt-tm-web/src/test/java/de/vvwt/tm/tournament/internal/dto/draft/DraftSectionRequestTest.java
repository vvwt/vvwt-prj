package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.tm.tournament.draft.GameMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftSectionRequest DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 438.
 *
 * <h2>E51S20 — gameMode String→Enum migration</h2>
 *
 * <p>Constructor calls updated from {@code String} to {@link GameMode} enum constants. The {@code
 * gameMode} field is now typed as {@link GameMode} — assertions updated accordingly.
 *
 * @see DraftSectionRequest
 * @see GameMode
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 * @see <a href="E51S20">E51S20 — gameMode/distributionMode String→Enum migration</a>
 */
class DraftSectionRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: record stores all fields. */
    @Test
    void record_withAllFields_storesAll() {
        DraftSectionRequest request =
                new DraftSectionRequest(
                        1, "team_number", 2, GameMode.ROUND_ROBIN, 5, 10, 15, 1, null, null);

        assertThat(request.sectionNumber()).isEqualTo(1);
        assertThat(request.sortType()).isEqualTo("team_number");
        assertThat(request.groupCount()).isEqualTo(2);
        assertThat(request.gameMode()).isEqualTo(GameMode.ROUND_ROBIN);
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
                        1,
                        "team_number",
                        2,
                        GameMode.ROUND_ROBIN,
                        5,
                        10,
                        15,
                        1,
                        List.of(breakRequest),
                        null);

        assertThat(request.breaks()).hasSize(1);
        assertThat(request.breaks().get(0).afterLapNumber()).isEqualTo(2);
    }

    /** AC-TDD-DTOs: JSON round-trip preserves all fields. */
    @Test
    void jsonRoundTrip_allFields_preserved() throws Exception {
        DraftSectionRequest original =
                new DraftSectionRequest(
                        1, "placement_group", 3, GameMode.ROUND_ROBIN, 0, 15, 20, 2, null, null);
        String json = objectMapper.writeValueAsString(original);
        DraftSectionRequest restored = objectMapper.readValue(json, DraftSectionRequest.class);

        assertThat(restored.sectionNumber()).isEqualTo(original.sectionNumber());
        assertThat(restored.sortType()).isEqualTo(original.sortType());
        assertThat(restored.groupCount()).isEqualTo(original.groupCount());
    }
}
