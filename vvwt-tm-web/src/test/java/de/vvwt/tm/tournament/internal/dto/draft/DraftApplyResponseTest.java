package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftApplyResponse DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 431.
 *
 * @see DraftApplyResponse
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftApplyResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: DraftApplyResponse record stores phaseIds. */
    @Test
    void record_withPhaseIds_storesList() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        DraftApplyResponse response = new DraftApplyResponse(List.of(id1, id2));

        assertThat(response.phaseIds()).containsExactly(id1, id2);
    }

    /** AC-TDD-DTOs: JSON round-trip preserves phaseIds. */
    @Test
    void jsonRoundTrip_withPhaseIds_preserved() throws Exception {
        UUID id = UUID.randomUUID();
        DraftApplyResponse original = new DraftApplyResponse(List.of(id));
        String json = objectMapper.writeValueAsString(original);
        DraftApplyResponse restored = objectMapper.readValue(json, DraftApplyResponse.class);

        assertThat(restored.phaseIds()).hasSize(1);
        assertThat(restored.phaseIds().get(0)).isEqualTo(id);
    }
}
