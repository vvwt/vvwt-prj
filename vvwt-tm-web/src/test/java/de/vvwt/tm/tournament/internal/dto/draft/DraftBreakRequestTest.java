// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal.dto.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftBreakRequest DTO unit test (AC-TDD-DTOs).
 *
 * <p>Inventory: E21S01 line 432.
 *
 * @see DraftBreakRequest
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftBreakRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DTOs: DraftBreakRequest record stores all fields. */
    @Test
    void record_withAllFields_storesAll() {
        DraftBreakRequest request = new DraftBreakRequest(2, 15, "Pause");

        assertThat(request.afterLapNumber()).isEqualTo(2);
        assertThat(request.durationMinutes()).isEqualTo(15);
        assertThat(request.label()).isEqualTo("Pause");
    }

    /** AC-TDD-DTOs: null label is accepted. */
    @Test
    void record_withNullLabel_accepted() {
        DraftBreakRequest request = new DraftBreakRequest(1, 10, null);

        assertThat(request.label()).isNull();
    }

    /** AC-TDD-DTOs: JSON round-trip preserves fields. */
    @Test
    void jsonRoundTrip_withLabel_preserved() throws Exception {
        DraftBreakRequest original = new DraftBreakRequest(3, 20, "Mittagspause");
        String json = objectMapper.writeValueAsString(original);
        DraftBreakRequest restored = objectMapper.readValue(json, DraftBreakRequest.class);

        assertThat(restored.afterLapNumber()).isEqualTo(original.afterLapNumber());
        assertThat(restored.durationMinutes()).isEqualTo(original.durationMinutes());
        assertThat(restored.label()).isEqualTo(original.label());
    }
}
