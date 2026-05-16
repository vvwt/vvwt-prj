// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.draft;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * RED — DraftBreak VO unit test (AC-TDD-DraftBreak).
 *
 * <p>Tests field accessibility, JSON (de)serialization round-trip, and null label handling.
 *
 * <p>Inventory: E21S01 line 237.
 *
 * @see DraftBreak
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction</a>
 */
class DraftBreakTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** AC-TDD-DraftBreak: constructor stores all fields correctly. */
    @Test
    void constructor_withValidFields_storesAll() {
        DraftBreak draftBreak = new DraftBreak(2, 15, "Mittagspause");

        assertThat(draftBreak.getAfterLapNumber()).isEqualTo(2);
        assertThat(draftBreak.getDurationMinutes()).isEqualTo(15);
        assertThat(draftBreak.getLabel()).isEqualTo("Mittagspause");
    }

    /** AC-TDD-DraftBreak: null label is allowed. */
    @Test
    void constructor_withNullLabel_storesNull() {
        DraftBreak draftBreak = new DraftBreak(1, 10, null);

        assertThat(draftBreak.getLabel()).isNull();
    }

    /** AC-TDD-DraftBreak: JSON round-trip preserves all fields including label. */
    @Test
    void jsonRoundTrip_withLabel_preserved() throws Exception {
        DraftBreak original = new DraftBreak(3, 20, "Pause");
        String json = objectMapper.writeValueAsString(original);
        DraftBreak restored = objectMapper.readValue(json, DraftBreak.class);

        assertThat(restored.getAfterLapNumber()).isEqualTo(original.getAfterLapNumber());
        assertThat(restored.getDurationMinutes()).isEqualTo(original.getDurationMinutes());
        assertThat(restored.getLabel()).isEqualTo(original.getLabel());
    }

    /** AC-TDD-DraftBreak: JSON round-trip with null label succeeds. */
    @Test
    void jsonRoundTrip_withNullLabel_preserved() throws Exception {
        DraftBreak original = new DraftBreak(1, 5, null);
        String json = objectMapper.writeValueAsString(original);
        DraftBreak restored = objectMapper.readValue(json, DraftBreak.class);

        assertThat(restored.getAfterLapNumber()).isEqualTo(1);
        assertThat(restored.getDurationMinutes()).isEqualTo(5);
        assertThat(restored.getLabel()).isNull();
    }
}
